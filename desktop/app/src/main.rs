//! LumiDeck for Windows: a window with the device and its settings (ui/), and an icon in
//! the tray that stays when the window is closed. It opens the device's desktop
//! (lumideck.exe, next to this program) from the window or the tray, and as soon as the
//! device is plugged in.
//!
//! A device counts once it shows ADB's USB interface, which it does with USB debugging on.
//! After the desktop is closed, LumiDeck waits for the device to be plugged in again, or
//! for a click. Any options it's given (`--link` and the rest) go on to every desktop it
//! opens, after the window's settings. `--hidden` starts it in the tray only, as it starts
//! with Windows.
//!
//! The page sends commands such as `open` or `set dpi 200` (the `page` command), and gets
//! what it shows back as a `state` event. Everything else happens on the main thread too,
//! which is where Tauri runs the tray, the menu and synchronous commands.

#![windows_subsystem = "windows"]

use std::collections::HashMap;
use std::env;
use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::json;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};
use tauri::webview::Color;
use tauri::{AppHandle, Emitter, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder, Wry};
use windows::Win32::Foundation::{CloseHandle, HANDLE, HWND, LPARAM};
use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_DWORD, REG_OPTION_NON_VOLATILE, REG_SZ, RRF_RT_REG_DWORD,
    RRF_RT_REG_SZ, RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegGetValueW, RegSetValueExW,
};
use windows::Win32::System::Threading::{INFINITE, WaitForSingleObject};
use windows::Win32::UI::Shell::{SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW, ShellExecuteExW};
use windows::Win32::UI::WindowsAndMessaging::{
    EnumWindows, GetWindowThreadProcessId, IsWindowVisible, SW_RESTORE, SW_SHOWNORMAL, SetForegroundWindow, ShowWindow,
};
use windows::core::{HSTRING, PCWSTR, w};

/// The window's label.
const HOME: &str = "home";
const POLL: Duration = Duration::from_secs(2);
/// How long a device must be gone to count as plugged in again when it's back. Its USB
/// drops out for a moment when the desktop switches it over to the direct link.
const GONE_FOR: Duration = Duration::from_secs(6);
/// How often the window checks the device's battery.
const BATTERY_EVERY: Duration = Duration::from_secs(30);

const SETTINGS_KEY: &str = "Software\\LumiDeck";
const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const RUN_VALUE: &str = "LumiDeck";
const HIDDEN: &str = "--hidden";

/// A desktop that's open, in its own process.
struct Session {
    child: Child,
    serial: String,
    /// The last lines it printed as errors, to show if it fails.
    errors: Arc<Mutex<Vec<String>>>,
}

/// The window's settings, kept in the registry.
struct Settings {
    resolution: String,
    dpi: u32,
    fullscreen: bool,
    audio: bool,
    auto_connect: bool,
}

impl Settings {
    fn load() -> Settings {
        let flag = |name, default| read_dword(SETTINGS_KEY, name).map_or(default, |v| v != 0);
        Settings {
            resolution: read_string(SETTINGS_KEY, "Resolution")
                .filter(|r| valid_resolution(r))
                .unwrap_or_else(|| "1920x1080".into()),
            dpi: read_dword(SETTINGS_KEY, "Dpi").filter(|d| valid_dpi(*d)).unwrap_or(160),
            fullscreen: flag("Fullscreen", false),
            audio: flag("Audio", true),
            auto_connect: flag("AutoConnect", true),
        }
    }

    /// lumideck.exe's options for them.
    fn args(&self) -> Vec<String> {
        let mut args = vec!["--resolution".into(), self.resolution.clone(), "--dpi".into(), self.dpi.to_string()];
        if self.fullscreen {
            args.push("--fullscreen".into());
        }
        if !self.audio {
            args.push("--no-audio".into());
        }
        args
    }
}

fn valid_resolution(r: &str) -> bool {
    r.split_once('x').is_some_and(|(w, h)| {
        let (Ok(w), Ok(h)) = (w.parse::<u32>(), h.parse::<u32>()) else { return false };
        (320..=7680).contains(&w) && (240..=4320).contains(&h)
    })
}

fn valid_dpi(dpi: u32) -> bool {
    (80..=640).contains(&dpi)
}

/// What threads find out for the window.
#[derive(Default)]
struct Found {
    /// The device's serial, battery level and whether it's charging.
    battery: Option<(String, u8, bool)>,
    /// The devices' model names, by serial: USB only has the chip maker's.
    models: HashMap<String, String>,
    /// Whether this PC is set up for the USB link, once checked.
    usb_ready: Option<bool>,
    usb_busy: bool,
}

struct Lumi {
    app: AppHandle,
    tray: TrayIcon,
    open_item: MenuItem<Wry>,
    close_item: MenuItem<Wry>,
    session: Option<Session>,
    /// Devices that are plugged in, by serial, and when each was last seen.
    seen: HashMap<String, Instant>,
    /// The devices' names, by serial.
    names: HashMap<String, String>,
    settings: Settings,
    /// Why the last desktop failed, until the next one.
    error: Option<String>,
    /// Set when something failed with the window closed, to open it and show why.
    wants_home: bool,
    found: Arc<Mutex<Found>>,
    /// When the battery was last asked for, and for which device.
    battery_asked: Option<(String, Instant)>,
    /// What the window and the tray show, to only send changes.
    shown: String,
    tip: &'static str,
}

fn main() {
    let hidden = env::args().any(|a| a == HIDDEN);
    let built = tauri::Builder::default()
        // Started again: show this one's window instead.
        .plugin(tauri_plugin_single_instance::init(|app, args, _| {
            if !args.iter().any(|a| a == HIDDEN) {
                show_home(app);
            }
        }))
        .invoke_handler(tauri::generate_handler![page])
        .setup(move |app| {
            app.manage(Mutex::new(Lumi::new(app.handle())?));
            let handle = app.handle().clone();
            thread::spawn(move || {
                loop {
                    let app = handle.clone();
                    let _ = handle.run_on_main_thread(move || {
                        run(&app, Lumi::poll);
                    });
                    thread::sleep(POLL);
                }
            });
            if !hidden {
                show_home(app.handle());
            }
            Ok(())
        })
        .build(tauri::generate_context!());
    let Ok(app) = built else { return };
    app.run(|_, event| {
        // Closing the window leaves LumiDeck in the tray.
        if let RunEvent::ExitRequested { code: None, api, .. } = event {
            api.prevent_exit();
        }
    });
}

/// Runs `action` on LumiDeck, unless it's busy already, and then opens the window if a
/// failure needs showing.
fn run(app: &AppHandle, action: impl FnOnce(&mut Lumi)) -> bool {
    let done = with_lumi(app, |lumi| {
        action(lumi);
        std::mem::take(&mut lumi.wants_home)
    });
    if done == Some(true) {
        show_home(app);
    }
    done.is_some()
}

fn with_lumi<T>(app: &AppHandle, action: impl FnOnce(&mut Lumi) -> T) -> Option<T> {
    let lumi = app.try_state::<Mutex<Lumi>>()?;
    let mut lumi = lumi.try_lock().ok()?;
    Some(action(&mut lumi))
}

/// Updates the window from the main thread, for work done on another.
fn refresh_later(app: &AppHandle) {
    let handle = app.clone();
    let _ = app.run_on_main_thread(move || {
        with_lumi(&handle, |lumi| lumi.refresh(false));
    });
}

/// What the page asks for.
#[tauri::command]
fn page(app: AppHandle, message: String) {
    if message == "setupUsb" {
        setup_usb(&app);
    } else if !run(&app, |lumi| lumi.command(&message)) {
        // Busy: try again once it isn't.
        let handle = app.clone();
        let _ = app.run_on_main_thread(move || page(handle, message));
    }
}

/// Opens the window, or brings it to the front. Not while holding LumiDeck: WebView2
/// runs the main thread's messages while it starts.
fn show_home(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(HOME) {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        return;
    }
    let data = env::var("LOCALAPPDATA").map(PathBuf::from).unwrap_or_default().join("LumiDeck").join("WebView2");
    let built = WebviewWindowBuilder::new(app, HOME, WebviewUrl::default())
        .title("LumiDeck")
        .inner_size(980.0, 580.0)
        .min_inner_size(720.0, 480.0)
        .center()
        // The page draws its own title bar.
        .decorations(false)
        .shadow(true)
        // Until the page draws, so it doesn't flash white.
        .background_color(Color(0xED, 0xF3, 0xF8, 0xFF))
        .data_directory(data)
        .build();
    if built.is_ok() {
        with_lumi(app, Lumi::check_usb_setup);
    }
}

impl Lumi {
    fn new(app: &AppHandle) -> tauri::Result<Lumi> {
        let home = MenuItem::with_id(app, "home", "Open LumiDeck", true, None::<&str>)?;
        let open_item = MenuItem::with_id(app, "open", "Open the desktop", true, None::<&str>)?;
        let close_item = MenuItem::with_id(app, "close", "Close the desktop", false, None::<&str>)?;
        let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
        let separator = PredefinedMenuItem::separator(app)?;
        let menu = Menu::with_items(app, &[&home, &open_item, &close_item, &separator, &quit])?;
        let mut tray = TrayIconBuilder::with_id("lumideck")
            .tooltip("LumiDeck")
            .menu(&menu)
            .show_menu_on_left_click(false)
            .on_menu_event(|app, event| match event.id.as_ref() {
                "home" => show_home(app),
                "open" => {
                    run(app, Lumi::open);
                }
                "close" => {
                    run(app, Lumi::close);
                }
                "quit" => app.exit(0),
                _ => {}
            })
            .on_tray_icon_event(|tray, event| {
                if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } =
                    event
                {
                    show_home(tray.app_handle());
                }
            });
        if let Some(icon) = app.default_window_icon() {
            tray = tray.icon(icon.clone());
        }
        Ok(Lumi {
            app: app.clone(),
            tray: tray.build(app)?,
            open_item,
            close_item,
            session: None,
            seen: HashMap::new(),
            names: HashMap::new(),
            settings: Settings::load(),
            error: None,
            wants_home: false,
            found: Arc::default(),
            battery_asked: None,
            shown: String::new(),
            tip: "",
        })
    }

    fn is_open(&self) -> bool {
        self.app.get_webview_window(HOME).is_some()
    }

    /// Shows a failure in the window, opening it if it's closed.
    fn fail(&mut self, why: String) {
        self.wants_home = !self.is_open();
        self.error = Some(why);
    }

    /// Checks on the open desktop, and opens one for a device that was just plugged in.
    fn poll(&mut self) {
        let now = Instant::now();
        let plugged = adb_devices();
        let mut fresh = Vec::new();
        for (serial, name) in plugged.iter() {
            let last = self.seen.insert(serial.clone(), now);
            if last.is_none_or(|last| now - last > GONE_FOR) {
                fresh.push(serial.clone());
            }
            self.names.insert(serial.clone(), name.clone());
        }
        self.seen.retain(|_, last| now - *last <= GONE_FOR);

        if let Some(session) = &mut self.session
            && let Ok(Some(status)) = session.child.try_wait()
        {
            let session = self.session.take().unwrap();
            // Unplugging ends it too, which needs no telling.
            if !status.success() && plugged.iter().any(|(serial, _)| *serial == session.serial) {
                let why = session.errors.lock().unwrap().last().cloned().unwrap_or_else(|| "It stopped.".into());
                self.fail(why);
            }
        }
        if self.session.is_none()
            && self.settings.auto_connect
            && let Some(serial) = fresh.first()
        {
            self.start(serial.clone());
        }
        self.refresh(false);
    }

    /// The device to show: the open desktop's, or else the first plugged in.
    fn device(&self) -> Option<String> {
        match &self.session {
            Some(session) => Some(session.serial.clone()),
            None => self.seen.keys().min().cloned(),
        }
    }

    /// Opens the desktop, or brings it to the front if it's open.
    fn open(&mut self) {
        if let Some(session) = &self.session {
            bring_to_front(session.child.id());
            return;
        }
        match self.device() {
            Some(serial) => self.start(serial),
            // The window says to plug it in.
            None => self.wants_home = true,
        }
    }

    fn start(&mut self, serial: String) {
        let exe = sibling("lumideck.exe");
        let mut command = Command::new(&exe);
        command.arg("--serial").arg(&serial).args(self.settings.args()).args(passed_args());
        command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
        // No console window: the desktop's own window is all there is to see.
        command.creation_flags(CREATE_NO_WINDOW);
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                self.fail(format!("{}: {e}", exe.display()));
                return;
            }
        };
        let errors = Arc::new(Mutex::new(Vec::new()));
        if let Some(stderr) = child.stderr.take() {
            let errors = errors.clone();
            thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    if !line.trim().is_empty() {
                        let mut errors = errors.lock().unwrap();
                        errors.push(line);
                        let excess = errors.len().saturating_sub(5);
                        errors.drain(..excess);
                    }
                }
            });
        }
        self.error = None;
        self.session = Some(Session { child, serial, errors });
        self.refresh(false);
    }

    fn close(&mut self) {
        if let Some(mut session) = self.session.take() {
            let _ = session.child.kill();
            let _ = session.child.wait();
        }
        self.refresh(false);
    }

    /// Does what the window's page asks.
    fn command(&mut self, text: &str) {
        let mut words = text.split(' ');
        match (words.next(), words.next(), words.next()) {
            (Some("ready"), ..) => self.refresh(true),
            (Some("open" | "show"), ..) => self.open(),
            (Some("close"), ..) => self.close(),
            (Some("set"), Some(key), Some(value)) => {
                let on = value == "1";
                match key {
                    "resolution" if valid_resolution(value) => {
                        self.settings.resolution = value.into();
                        write_string(SETTINGS_KEY, "Resolution", value);
                    }
                    "dpi" => {
                        if let Ok(dpi) = value.parse()
                            && valid_dpi(dpi)
                        {
                            self.settings.dpi = dpi;
                            write_dword(SETTINGS_KEY, "Dpi", dpi);
                        }
                    }
                    "fullscreen" => {
                        self.settings.fullscreen = on;
                        write_dword(SETTINGS_KEY, "Fullscreen", on as u32);
                    }
                    "audio" => {
                        self.settings.audio = on;
                        write_dword(SETTINGS_KEY, "Audio", on as u32);
                    }
                    "autoConnect" => {
                        self.settings.auto_connect = on;
                        write_dword(SETTINGS_KEY, "AutoConnect", on as u32);
                    }
                    "startWithWindows" => set_start_with_windows(on),
                    _ => {}
                }
                self.refresh(true);
            }
            _ => {}
        }
    }

    /// Brings the tray up to date, and sends the window what it shows if it changed or
    /// `force`. Asks for the battery when it's due.
    fn refresh(&mut self, force: bool) {
        let tip = match &self.session {
            Some(_) => "LumiDeck: desktop open",
            None if self.seen.is_empty() => "LumiDeck: plug in your device",
            None => "LumiDeck: ready",
        };
        if tip != self.tip {
            self.tip = tip;
            let open = self.session.is_some();
            let _ = self.tray.set_tooltip(Some(tip));
            let _ = self.open_item.set_text(if open { "Show the desktop" } else { "Open the desktop" });
            let _ = self.close_item.set_enabled(open);
        }
        if !self.is_open() {
            return;
        }

        let device = self.device();
        if let Some(serial) = &device {
            let due =
                self.battery_asked.as_ref().is_none_or(|(asked, at)| asked != serial || at.elapsed() >= BATTERY_EVERY);
            if due {
                self.battery_asked = Some((serial.clone(), Instant::now()));
                self.ask_battery(serial.clone());
            }
        }

        let found = self.found.lock().unwrap();
        let battery = found.battery.as_ref().filter(|(serial, ..)| Some(serial) == device.as_ref());
        let usb = match (found.usb_busy, found.usb_ready) {
            (true, _) => "busy",
            (_, Some(true)) => "ready",
            (_, Some(false)) => "none",
            (_, None) => "unknown",
        };
        let device = device.map(|serial| {
            let name = found.models.get(&serial).or(self.names.get(&serial)).cloned().unwrap_or_else(|| serial.clone());
            json!({ "name": name, "serial": serial })
        });
        let state = json!({
            "version": env!("CARGO_PKG_VERSION"),
            "device": device,
            "session": self.session.is_some(),
            "battery": battery.map(|(_, level, _)| level),
            "charging": battery.is_some_and(|(_, _, charging)| *charging),
            "error": self.error,
            "usb": usb,
            "settings": {
                "resolution": self.settings.resolution,
                "dpi": self.settings.dpi,
                "fullscreen": self.settings.fullscreen,
                "audio": self.settings.audio,
                "autoConnect": self.settings.auto_connect,
                "startWithWindows": starts_with_windows(),
            },
        });
        drop(found);
        let text = state.to_string();
        if force || text != self.shown {
            let _ = self.app.emit_to(HOME, "state", state);
            self.shown = text;
        }
    }

    /// Reads the device's battery and model through ADB, on another thread.
    fn ask_battery(&self, serial: String) {
        let found = self.found.clone();
        let app = self.app.clone();
        thread::spawn(move || {
            let Some(adb) = find_adb() else { return };
            let Ok(output) = Command::new(adb)
                .args(["-s", &serial, "shell", "echo model: $(getprop ro.product.model); dumpsys battery"])
                .creation_flags(CREATE_NO_WINDOW)
                .output()
            else {
                return;
            };
            let text = String::from_utf8_lossy(&output.stdout);
            let field = |name: &str| {
                text.lines()
                    .find_map(|line| line.trim().strip_prefix(name)?.strip_prefix(':').map(|v| v.trim().to_string()))
            };
            let Some(level) = field("level").and_then(|v| v.parse().ok()) else { return };
            // BatteryManager.BATTERY_STATUS_CHARGING
            let charging = field("status").as_deref() == Some("2");
            let mut found = found.lock().unwrap();
            if let Some(model) = field("model").filter(|m| !m.is_empty()) {
                found.models.insert(serial.clone(), model);
            }
            found.battery = Some((serial, level, charging));
            drop(found);
            refresh_later(&app);
        });
    }

    /// Finds out whether this PC is set up for the USB link, on another thread.
    fn check_usb_setup(&mut self) {
        let found = self.found.clone();
        let app = self.app.clone();
        thread::spawn(move || {
            let ready = usb_ready();
            found.lock().unwrap().usb_ready = ready;
            refresh_later(&app);
        });
    }
}

/// Sets this PC up for the USB link, as an administrator: Windows asks first. Not while
/// holding LumiDeck, as Windows runs the main thread's messages while it asks.
fn setup_usb(app: &AppHandle) {
    let ready = with_lumi(app, |lumi| {
        let serial = lumi.device()?;
        let busy = lumi.session.is_some() || lumi.found.lock().unwrap().usb_busy;
        (!busy).then(|| (serial, lumi.found.clone()))
    });
    let Some(Some((serial, found))) = ready else { return };

    // Through PowerShell, whose console stays open if it fails, to show why.
    let quote = |text: &str| format!("'{}'", text.replace('\'', "''"));
    let exe = sibling("lumideck.exe");
    let parameters = HSTRING::from(format!(
        "-NoProfile -Command \"& {} --setup-usb --serial {}; if ($LASTEXITCODE) {{ Read-Host 'Press Enter to close' }}\"",
        quote(&exe.to_string_lossy()),
        quote(&serial),
    ));
    let mut info = SHELLEXECUTEINFOW {
        cbSize: size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        lpVerb: w!("runas"),
        lpFile: w!("powershell.exe"),
        lpParameters: PCWSTR(parameters.as_ptr()),
        nShow: SW_SHOWNORMAL.0,
        ..Default::default()
    };
    // Fails when the user says no.
    if unsafe { ShellExecuteExW(&mut info) }.is_err() || info.hProcess.is_invalid() {
        return;
    }
    found.lock().unwrap().usb_busy = true;
    refresh_later(app);
    let process = info.hProcess.0 as isize;
    let app = app.clone();
    thread::spawn(move || {
        unsafe {
            let process = HANDLE(process as _);
            WaitForSingleObject(process, INFINITE);
            let _ = CloseHandle(process);
        }
        let ready = usb_ready();
        let mut found = found.lock().unwrap();
        found.usb_busy = false;
        found.usb_ready = ready;
        drop(found);
        refresh_later(&app);
    });
}

const CREATE_NO_WINDOW: u32 = 0x0800_0000;

/// Whether this PC is set up for the USB link, as lumideck.exe finds it.
fn usb_ready() -> Option<bool> {
    let status =
        Command::new(sibling("lumideck.exe")).arg("--usb-ready").creation_flags(CREATE_NO_WINDOW).status().ok()?;
    Some(status.success())
}

/// The options LumiDeck was started with, for lumideck.exe.
fn passed_args() -> impl Iterator<Item = String> {
    env::args().skip(1).filter(|a| a != HIDDEN)
}

/// The devices plugged in with USB debugging on, by serial, with their names: those with
/// ADB's USB interface (vendor class, subclass 0x42, protocol 1).
fn adb_devices() -> Vec<(String, String)> {
    let Ok(devices) = nusb::list_devices() else { return Vec::new() };
    devices
        .filter(|d| d.interfaces().any(|i| i.class() == 0xFF && i.subclass() == 0x42 && i.protocol() == 1))
        .filter_map(|d| {
            let serial = d.serial_number()?.to_string();
            let name = d.product_string().filter(|n| !n.trim().is_empty()).unwrap_or(&serial).trim().to_string();
            Some((serial, name))
        })
        .collect()
}

/// adb.exe, where the client looks for it too (client/src/adb.rs).
fn find_adb() -> Option<PathBuf> {
    let on_path = env::var_os("PATH").into_iter().flat_map(|path| env::split_paths(&path).collect::<Vec<_>>());
    let local = env::var("LOCALAPPDATA").map(PathBuf::from).unwrap_or_default();
    let sdk = env::var("ANDROID_HOME").map(PathBuf::from).into_iter();
    let winget = std::fs::read_dir(local.join("Microsoft").join("WinGet").join("Packages"))
        .into_iter()
        .flatten()
        .flatten()
        .filter(|e| e.file_name().to_string_lossy().starts_with("Google.PlatformTools"))
        .flat_map(|e| {
            let inner = std::fs::read_dir(e.path()).into_iter().flatten().flatten().map(|i| i.path());
            inner.chain(std::iter::once(e.path())).collect::<Vec<_>>()
        });
    on_path
        .chain([local.join("Android").join("Sdk").join("platform-tools")])
        .chain(sdk.map(|s| s.join("platform-tools")))
        .chain(winget)
        .map(|dir| dir.join("adb.exe"))
        .find(|exe| exe.is_file())
}

/// Brings the visible windows of the process `pid` to the front.
fn bring_to_front(pid: u32) {
    unsafe extern "system" fn each(window: HWND, pid: LPARAM) -> windows::core::BOOL {
        unsafe {
            let mut owner = 0;
            GetWindowThreadProcessId(window, Some(&mut owner));
            if owner == pid.0 as u32 && IsWindowVisible(window).as_bool() {
                let _ = ShowWindow(window, SW_RESTORE);
                let _ = SetForegroundWindow(window);
            }
        }
        true.into()
    }
    unsafe {
        let _ = EnumWindows(Some(each), LPARAM(pid as isize));
    }
}

fn sibling(name: &str) -> PathBuf {
    let exe = env::current_exe().unwrap_or_default();
    exe.with_file_name(name)
}

fn starts_with_windows() -> bool {
    read_string(RUN_KEY, RUN_VALUE).is_some()
}

fn set_start_with_windows(on: bool) {
    unsafe {
        let Some(key) = open_key(RUN_KEY) else { return };
        if on {
            let exe = env::current_exe().unwrap_or_default();
            let mut command = format!("\"{}\" {HIDDEN}", exe.display());
            for arg in passed_args() {
                command.push_str(&format!(" \"{arg}\""));
            }
            let _ = RegSetValueExW(key, &HSTRING::from(RUN_VALUE), None, REG_SZ, Some(&wide_bytes(&command)));
        } else {
            let _ = RegDeleteValueW(key, &HSTRING::from(RUN_VALUE));
        }
        let _ = RegCloseKey(key);
    }
}

/// A key under HKEY_CURRENT_USER, made if it isn't there, for writing.
unsafe fn open_key(path: &str) -> Option<HKEY> {
    let mut key = HKEY::default();
    let result = unsafe {
        RegCreateKeyExW(
            HKEY_CURRENT_USER,
            &HSTRING::from(path),
            None,
            PCWSTR::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            None,
            &mut key,
            None,
        )
    };
    result.is_ok().then_some(key)
}

fn read_string(path: &str, name: &str) -> Option<String> {
    let mut buffer = [0u16; 1024];
    let mut size = (buffer.len() * 2) as u32;
    let result = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            &HSTRING::from(path),
            &HSTRING::from(name),
            RRF_RT_REG_SZ,
            None,
            Some(buffer.as_mut_ptr().cast()),
            Some(&mut size),
        )
    };
    result.is_ok().then(|| String::from_utf16_lossy(&buffer[..(size as usize / 2).saturating_sub(1)]))
}

fn read_dword(path: &str, name: &str) -> Option<u32> {
    let mut value = 0u32;
    let mut size = 4u32;
    let result = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            &HSTRING::from(path),
            &HSTRING::from(name),
            RRF_RT_REG_DWORD,
            None,
            Some((&mut value as *mut u32).cast()),
            Some(&mut size),
        )
    };
    result.is_ok().then_some(value)
}

fn write_dword(path: &str, name: &str, value: u32) {
    unsafe {
        let Some(key) = open_key(path) else { return };
        let _ = RegSetValueExW(key, &HSTRING::from(name), None, REG_DWORD, Some(&value.to_le_bytes()));
        let _ = RegCloseKey(key);
    }
}

fn write_string(path: &str, name: &str, value: &str) {
    unsafe {
        let Some(key) = open_key(path) else { return };
        let _ = RegSetValueExW(key, &HSTRING::from(name), None, REG_SZ, Some(&wide_bytes(value)));
        let _ = RegCloseKey(key);
    }
}

/// `text` as a NUL-terminated UTF-16 string's bytes, as the registry stores it.
fn wide_bytes(text: &str) -> Vec<u8> {
    text.encode_utf16().chain(std::iter::once(0)).flat_map(u16::to_le_bytes).collect()
}
