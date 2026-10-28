//! LumiDeck in the Windows tray: opens the device's desktop (lumideck.exe, next to this
//! program) as soon as the device is plugged in, and again from the tray icon.
//!
//! A device counts once it shows ADB's USB interface, which it does with USB debugging on.
//! After the desktop is closed, LumiDeck waits for the device to be plugged in again, or
//! for a click on the icon. Its options (`--resolution` and the rest) go on to every
//! desktop it opens. The tray's menu can also start it with Windows.

#![windows_subsystem = "windows"]

use std::cell::RefCell;
use std::collections::HashMap;
use std::io::{BufRead, BufReader};
use std::os::windows::process::CommandExt;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, POINT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_DWORD, REG_OPTION_NON_VOLATILE, REG_SZ, RRF_RT_REG_DWORD, RRF_RT_REG_SZ,
    RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegGetValueW, RegSetValueExW,
};
use windows::Win32::UI::Shell::{
    NIF_ICON, NIF_INFO, NIF_MESSAGE, NIF_TIP, NIIF_WARNING, NIM_ADD, NIM_DELETE, NIM_MODIFY, NOTIFYICONDATAW,
    Shell_NotifyIconW,
};
use windows::Win32::UI::WindowsAndMessaging::{
    AppendMenuW, CreateIconFromResourceEx, CreatePopupMenu, CreateWindowExW, DefWindowProcW, DestroyMenu,
    DispatchMessageW, EnumWindows, GetCursorPos, GetMessageW, GetSystemMetrics, GetWindowThreadProcessId, HICON,
    HWND_MESSAGE, IsWindowVisible, LR_DEFAULTCOLOR, MF_CHECKED, MF_SEPARATOR, MF_STRING, MSG, PostQuitMessage,
    RegisterClassW, RegisterWindowMessageW, SM_CXSMICON, SW_RESTORE, SetForegroundWindow, SetTimer, ShowWindow,
    TPM_BOTTOMALIGN, TPM_RETURNCMD, TPM_RIGHTBUTTON, TrackPopupMenu, WINDOW_EX_STYLE, WINDOW_STYLE, WM_APP,
    WM_COMMAND, WM_CONTEXTMENU, WM_LBUTTONUP, WM_RBUTTONUP, WM_TIMER, WNDCLASSW,
};
use windows::core::{HSTRING, PCWSTR, w};

/// What the tray icon sends its window.
const WM_TRAY: u32 = WM_APP + 1;
const POLL_TIMER: usize = 1;
const POLL_MS: u32 = 2000;
/// How long a device must be gone to count as plugged in again when it's back. Its USB
/// drops out for a moment when the desktop switches it over to the direct link.
const GONE_FOR: Duration = Duration::from_secs(6);

// The menu's items.
const OPEN: usize = 1;
const CLOSE: usize = 2;
const AUTO_CONNECT: usize = 3;
const START_WITH_WINDOWS: usize = 4;
const QUIT: usize = 5;

const SETTINGS_KEY: &str = "Software\\LumiDeck";
const RUN_KEY: &str = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
const RUN_VALUE: &str = "LumiDeck";

/// A desktop that's open, in its own process.
struct Session {
    child: Child,
    serial: String,
    /// The last lines it printed as errors, to show if it fails.
    errors: Arc<Mutex<Vec<String>>>,
}

struct Tray {
    window: HWND,
    icon: HICON,
    session: Option<Session>,
    /// Devices that are plugged in, by serial, and when each was last seen.
    seen: HashMap<String, Instant>,
    auto_connect: bool,
    /// Explorer's message that the taskbar was made again, after which the icon must be too.
    taskbar_created: u32,
}

thread_local! {
    static TRAY: RefCell<Option<Tray>> = const { RefCell::new(None) };
}

fn main() {
    unsafe {
        let Ok(instance) = GetModuleHandleW(None) else { return };
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance.into(),
            lpszClassName: w!("LumiDeckTray"),
            ..Default::default()
        };
        RegisterClassW(&class);
        let Ok(window) = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            w!("LumiDeckTray"),
            w!("LumiDeck"),
            WINDOW_STYLE(0),
            0,
            0,
            0,
            0,
            Some(HWND_MESSAGE),
            None,
            Some(instance.into()),
            None,
        ) else {
            return;
        };
        let size = GetSystemMetrics(SM_CXSMICON);
        let icon = CreateIconFromResourceEx(include_bytes!("../../../icons/scrcpy.png"), true, 0x0003_0000, size, size, LR_DEFAULTCOLOR)
            .unwrap_or_default();
        let tray = Tray {
            window,
            icon,
            session: None,
            seen: HashMap::new(),
            auto_connect: read_dword(SETTINGS_KEY, "AutoConnect").unwrap_or(1) != 0,
            taskbar_created: RegisterWindowMessageW(w!("TaskbarCreated")),
        };
        tray.add_icon();
        TRAY.with(|t| *t.borrow_mut() = Some(tray));
        SetTimer(Some(window), POLL_TIMER, POLL_MS, None);
        with_tray(Tray::poll);

        let mut message = MSG::default();
        while GetMessageW(&mut message, None, 0, 0).0 > 0 {
            DispatchMessageW(&message);
        }
        with_tray(|tray| {
            let data = tray.icon_data();
            let _ = Shell_NotifyIconW(NIM_DELETE, &data);
        });
    }
}

/// Runs `action` on the tray, unless it's busy already: its menu, while open, still
/// passes on the timer's messages.
fn with_tray(action: impl FnOnce(&mut Tray)) {
    TRAY.with(|t| {
        if let Ok(mut tray) = t.try_borrow_mut()
            && let Some(tray) = tray.as_mut()
        {
            action(tray);
        }
    });
}

unsafe extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_TIMER if wparam.0 == POLL_TIMER => with_tray(Tray::poll),
        WM_TRAY => match (lparam.0 & 0xFFFF) as u32 {
            WM_LBUTTONUP => with_tray(Tray::open),
            WM_RBUTTONUP | WM_CONTEXTMENU => with_tray(Tray::menu),
            _ => {}
        },
        WM_COMMAND => {}
        _ => {
            let recreate = TRAY.with(|t| t.borrow().as_ref().is_some_and(|tray| message == tray.taskbar_created));
            if recreate {
                with_tray(|tray| tray.add_icon());
            }
            return unsafe { DefWindowProcW(window, message, wparam, lparam) };
        }
    }
    LRESULT(0)
}

impl Tray {
    fn icon_data(&self) -> NOTIFYICONDATAW {
        let mut data = NOTIFYICONDATAW {
            cbSize: size_of::<NOTIFYICONDATAW>() as u32,
            hWnd: self.window,
            uID: 1,
            uFlags: NIF_ICON | NIF_MESSAGE | NIF_TIP,
            uCallbackMessage: WM_TRAY,
            hIcon: self.icon,
            ..Default::default()
        };
        let tip = match &self.session {
            Some(_) => "LumiDeck: desktop open",
            None if self.seen.is_empty() => "LumiDeck: plug in your device",
            None => "LumiDeck: click to open the desktop",
        };
        copy_wide(&mut data.szTip, tip);
        data
    }

    fn add_icon(&self) {
        let data = self.icon_data();
        unsafe {
            let _ = Shell_NotifyIconW(NIM_ADD, &data);
        }
    }

    fn update_icon(&self) {
        let data = self.icon_data();
        unsafe {
            let _ = Shell_NotifyIconW(NIM_MODIFY, &data);
        }
    }

    fn notify(&self, title: &str, text: &str) {
        let mut data = self.icon_data();
        data.uFlags |= NIF_INFO;
        data.dwInfoFlags = NIIF_WARNING;
        copy_wide(&mut data.szInfoTitle, title);
        copy_wide(&mut data.szInfo, text);
        unsafe {
            let _ = Shell_NotifyIconW(NIM_MODIFY, &data);
        }
    }

    /// Checks on the open desktop, and opens one for a device that was just plugged in.
    fn poll(&mut self) {
        let now = Instant::now();
        let plugged = adb_devices();
        let mut fresh = Vec::new();
        for serial in plugged.iter() {
            let last = self.seen.insert(serial.clone(), now);
            if last.is_none_or(|last| now - last > GONE_FOR) {
                fresh.push(serial.clone());
            }
        }
        self.seen.retain(|_, last| now - *last <= GONE_FOR);

        if let Some(session) = &mut self.session
            && let Ok(Some(status)) = session.child.try_wait()
        {
            let session = self.session.take().unwrap();
            // Unplugging ends it too, which needs no telling.
            if !status.success() && plugged.contains(&session.serial) {
                let errors = session.errors.lock().unwrap();
                let why = errors.last().cloned().unwrap_or_else(|| "It stopped.".into());
                self.notify("LumiDeck couldn't open the desktop", &why);
            }
        }
        if self.session.is_none()
            && self.auto_connect
            && let Some(serial) = fresh.first()
        {
            self.start(serial.clone());
        }
        self.update_icon();
    }

    /// Opens the desktop, or brings it to the front if it's open.
    fn open(&mut self) {
        if let Some(session) = &self.session {
            bring_to_front(session.child.id());
            return;
        }
        let plugged = adb_devices();
        match plugged.first() {
            Some(serial) => self.start(serial.clone()),
            None => self.notify("No device", "Plug the device in over USB, with USB debugging on."),
        }
    }

    fn start(&mut self, serial: String) {
        let exe = sibling("lumideck.exe");
        let mut command = Command::new(&exe);
        command.arg("--serial").arg(&serial).args(std::env::args().skip(1));
        command.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::piped());
        // No console window: the desktop's own window is all there is to see.
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
        let mut child = match command.spawn() {
            Ok(child) => child,
            Err(e) => {
                self.notify("LumiDeck couldn't open the desktop", &format!("{}: {e}", exe.display()));
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
        self.session = Some(Session { child, serial, errors });
        self.update_icon();
    }

    fn close(&mut self) {
        if let Some(mut session) = self.session.take() {
            let _ = session.child.kill();
            let _ = session.child.wait();
        }
        self.update_icon();
    }

    fn menu(&mut self) {
        unsafe {
            let Ok(menu) = CreatePopupMenu() else { return };
            let check = |on: bool| if on { MF_STRING | MF_CHECKED } else { MF_STRING };
            let open = if self.session.is_some() { w!("Show the desktop") } else { w!("Open the desktop") };
            let _ = AppendMenuW(menu, MF_STRING, OPEN, open);
            if self.session.is_some() {
                let _ = AppendMenuW(menu, MF_STRING, CLOSE, w!("Close the desktop"));
            }
            let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
            let _ = AppendMenuW(menu, check(self.auto_connect), AUTO_CONNECT, w!("Open when the device is plugged in"));
            let _ = AppendMenuW(menu, check(starts_with_windows()), START_WITH_WINDOWS, w!("Start with Windows"));
            let _ = AppendMenuW(menu, MF_SEPARATOR, 0, PCWSTR::null());
            let _ = AppendMenuW(menu, MF_STRING, QUIT, w!("Quit"));
            let mut point = POINT::default();
            let _ = GetCursorPos(&mut point);
            // Without this the menu stays open when clicking elsewhere.
            let _ = SetForegroundWindow(self.window);
            let choice =
                TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_BOTTOMALIGN, point.x, point.y, None, self.window, None);
            let _ = DestroyMenu(menu);
            match choice.0 as usize {
                OPEN => self.open(),
                CLOSE => self.close(),
                AUTO_CONNECT => {
                    self.auto_connect = !self.auto_connect;
                    write_dword(SETTINGS_KEY, "AutoConnect", self.auto_connect as u32);
                }
                START_WITH_WINDOWS => set_start_with_windows(!starts_with_windows()),
                QUIT => PostQuitMessage(0),
                _ => {}
            }
        }
    }
}

/// The serials of the devices plugged in with USB debugging on: those with ADB's USB
/// interface (vendor class, subclass 0x42, protocol 1).
fn adb_devices() -> Vec<String> {
    let Ok(devices) = nusb::list_devices() else { return Vec::new() };
    devices
        .filter(|d| d.interfaces().any(|i| i.class() == 0xFF && i.subclass() == 0x42 && i.protocol() == 1))
        .filter_map(|d| d.serial_number().map(str::to_string))
        .collect()
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
    let exe = std::env::current_exe().unwrap_or_default();
    exe.with_file_name(name)
}

fn starts_with_windows() -> bool {
    read_string(RUN_KEY, RUN_VALUE).is_some()
}

fn set_start_with_windows(on: bool) {
    unsafe {
        let Some(key) = open_key(RUN_KEY) else { return };
        if on {
            let exe = std::env::current_exe().unwrap_or_default();
            let mut command = format!("\"{}\"", exe.display());
            for arg in std::env::args().skip(1) {
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

/// `text` as a NUL-terminated UTF-16 string's bytes, as the registry stores it.
fn wide_bytes(text: &str) -> Vec<u8> {
    text.encode_utf16().chain(std::iter::once(0)).flat_map(u16::to_le_bytes).collect()
}

/// Copies `text` into a fixed-size UTF-16 field, cut short if it must be, NUL-terminated.
fn copy_wide(field: &mut [u16], text: &str) {
    let wide: Vec<u16> = text.encode_utf16().take(field.len() - 1).collect();
    field[..wide.len()].copy_from_slice(&wide);
    field[wide.len()] = 0;
}
