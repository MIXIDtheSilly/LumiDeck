//! LumiDeck for PCs: shows the device's LumiDeck desktop in a window, with its sound, and
//! sends it the mouse and keyboard.
//!
//! The device side is LumiDeck's stream server (android/…/server/StreamServer.kt), which
//! this starts over ADB. See protocol.rs for what goes between them, through ADB or
//! straight over USB (usb.rs).

mod adb;
mod audio;
mod clipboard;
mod decoder;
mod keyboard;
mod protocol;
mod render;
mod usb;
mod usb_setup;
mod video;

use std::hash::{BuildHasher, Hasher};
use std::io::{Read, Write};
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use winit::application::ApplicationHandler;
use winit::dpi::{PhysicalPosition, PhysicalSize};
use winit::event::{ElementState, MouseButton, MouseScrollDelta, WindowEvent};
use winit::event_loop::{ActiveEventLoop, DeviceEvents, EventLoop, EventLoopProxy};
use winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use winit::window::{CursorGrabMode, CursorIcon, CustomCursor, Fullscreen, Icon, Window, WindowId};

use crate::adb::Adb;
use crate::keyboard::Shortcut;
use crate::protocol::{Button, Control};
use crate::render::Fit;
use crate::video::{Header, WindowSize};

const USAGE: &str = "\
LumiDeck: your Android device's desktop in a window.

Usage: lumideck [options]

  --serial <id>         The device to use, when several are plugged in
  --resolution <WxH>    The desktop's size in pixels (default 1920x1080)
  --dpi <n>             Its density: 160 is one pixel per dp (default 160)
  --fullscreen          Start fullscreen (Right Ctrl+F toggles it)
  --codec <hevc|h264>   Video codec (default hevc, which the device may not have)
  --bitrate <Mbit/s>    Video bit rate (default 15)
  --no-audio            Leave the sound on the device
  --link <auto|usb|adb> Straight over USB, or through ADB (default auto: USB once it's
                        set up)
  --setup-usb           Set the PC up for the USB link, once (needs an administrator)
  --usb-ready           Exit with 0 if the PC is set up for the USB link, 1 if not
  --stats               Print how fast video goes, every few seconds
  --dump-video <file>   Save the video stream as it arrives, to look into problems
";

#[derive(Clone, Copy, PartialEq, Eq)]
enum Link {
    Auto,
    Usb,
    Adb,
}

struct Args {
    serial: Option<String>,
    width: u32,
    height: u32,
    dpi: u32,
    fullscreen: bool,
    codec: String,
    bitrate: u32,
    audio: bool,
    link: Link,
    setup_usb: bool,
    usb_ready: bool,
    stats: bool,
    dump: Option<std::path::PathBuf>,
}

impl Args {
    fn parse() -> Result<Args> {
        let mut args = Args {
            serial: None,
            width: 1920,
            height: 1080,
            dpi: 160,
            fullscreen: false,
            codec: "hevc".into(),
            bitrate: 15,
            audio: true,
            link: Link::Auto,
            setup_usb: false,
            usb_ready: false,
            stats: false,
            dump: None,
        };
        let mut it = std::env::args().skip(1);
        while let Some(arg) = it.next() {
            let mut value = || it.next().with_context(|| format!("{arg} needs a value"));
            match arg.as_str() {
                "--serial" | "-s" => args.serial = Some(value()?),
                "--resolution" => {
                    let v = value()?;
                    let (w, h) = v.split_once('x').with_context(|| format!("Resolution {v} isn't WIDTHxHEIGHT"))?;
                    args.width = w.parse()?;
                    args.height = h.parse()?;
                }
                "--dpi" => args.dpi = value()?.parse()?,
                "--fullscreen" => args.fullscreen = true,
                "--codec" => args.codec = value()?,
                "--bitrate" => args.bitrate = value()?.parse()?,
                "--no-audio" => args.audio = false,
                "--link" => {
                    args.link = match value()?.as_str() {
                        "auto" => Link::Auto,
                        "usb" => Link::Usb,
                        "adb" => Link::Adb,
                        other => bail!("--link is auto, usb or adb, not {other}"),
                    }
                }
                "--setup-usb" => args.setup_usb = true,
                "--usb-ready" => args.usb_ready = true,
                "--stats" => args.stats = true,
                "--dump-video" => args.dump = Some(value()?.into()),
                "--help" | "-h" => {
                    print!("{USAGE}");
                    std::process::exit(0);
                }
                other => bail!("Unknown option {other}\n\n{USAGE}"),
            }
        }
        // Even sizes, which video encoders need.
        args.width &= !1;
        args.height &= !1;
        Ok(args)
    }
}

enum UserEvent {
    Shortcut(Shortcut),
    /// An app on the device took the mouse for itself, or let it go (see protocol::POINTER_CAPTURE).
    PointerCapture(bool),
    /// The session ended, with why if it wasn't the user.
    Ended(Option<String>),
}

fn main() -> ExitCode {
    match run() {
        Ok(()) => ExitCode::SUCCESS,
        Err(e) => {
            eprintln!("{e:#}");
            ExitCode::FAILURE
        }
    }
}

fn run() -> Result<()> {
    let args = Args::parse()?;
    if args.usb_ready {
        std::process::exit(if usb_setup::done() { 0 } else { 1 });
    }
    // Waits as short as a millisecond, for spacing out the frames (see video::due).
    unsafe { windows::Win32::Media::timeBeginPeriod(1) };
    let adb = Adb::connect(args.serial.clone())?;
    if args.setup_usb {
        return usb_setup::run(&adb);
    }
    adb.prepare()?;

    // Android draws its own navigation bar along the bottom of the desktop and forces it
    // on while app windows are open. The display is one bar taller and the device leaves
    // the bar out of the video; the desktop keeps that strip clear, so nothing else is
    // lost. The bar is 56dp on displays this large.
    let nav_bar = (56 * args.dpi).div_ceil(160) & !1;
    let options = vec![
        format!("width={}", args.width),
        format!("height={}", args.height),
        format!("nav_bar={nav_bar}"),
        format!("dpi={}", args.dpi),
        format!("codec={}", args.codec),
        format!("bitrate={}", args.bitrate * 1_000_000),
        format!("audio={}", args.audio),
        format!("stats={}", args.stats),
    ];
    // Over USB only once the PC is set up for it, as the first time stops ADB's server for
    // a moment (usb::switch).
    let usb = match args.link {
        Link::Usb => Some(start_usb(&adb, &options, args.audio)?),
        Link::Auto if usb_setup::done() => start_usb(&adb, &options, args.audio)
            .inspect_err(|e| eprintln!("No USB link, going through ADB: {e:#}"))
            .ok(),
        _ => None,
    };
    let (server, mut video, sound, controls_out) = match usb {
        Some(streams) => streams,
        None => {
            let mut server = adb.start_stream_server(&options, false)?;
            let video = server.connect()?;
            let sound: Option<Box<dyn Read + Send>> =
                if args.audio { Some(Box::new(server.connect()?)) } else { None };
            let controls: Box<dyn Write + Send> = Box::new(video.try_clone()?);
            (server, Box::new(video) as Box<dyn Read + Send>, sound, controls)
        }
    };
    adb.start_helper()?;
    let header = Header::read(&mut video)?;

    let (controls, control_queue) = mpsc::channel();
    spawn_control_writer(controls_out, control_queue);
    let (video_events, video_queue) = mpsc::channel();
    let stopped = video_events.clone();
    server.on_stop(move |why| {
        let _ = stopped.send(video::Event::Closed(std::io::Error::other(why)));
    });
    let event_loop = EventLoop::<UserEvent>::with_user_event().build()?;
    // winit reads the keyboard as raw input while the window has focus, and Windows then
    // stops calling the keyboard hook (keyboard.rs) for keys meant for it: no keys at all.
    event_loop.listen_device_events(DeviceEvents::Never);

    let clipboard = clipboard::spawn(controls.clone())
        .inspect_err(|e| eprintln!("No clipboard: {e:#}"))
        .ok();
    let proxy = event_loop.create_proxy();
    video::spawn_reader(
        video,
        video_events.clone(),
        move |text| {
            if let Some(clipboard) = &clipboard {
                clipboard.set(text);
            }
        },
        move |captured| {
            let _ = proxy.send_event(UserEvent::PointerCapture(captured));
        },
    );
    if let Some(sound) = sound {
        audio::spawn(sound);
    }

    let mut app = App {
        args,
        header: Some(header),
        video_queue: Some(video_queue),
        video_events,
        controls,
        proxy: event_loop.create_proxy(),
        window: None,
        size: Arc::new(WindowSize::default()),
        cursor: (0.0, 0.0),
        captured: false,
        grabbed: false,
        blank_cursor: None,
        result: Ok(()),
    };
    event_loop.run_app(&mut app)?;
    drop(server);
    app.result
}

/// The server, with its video, sound and where the controls go.
type Streams = (adb::Server, Box<dyn Read + Send>, Option<Box<dyn Read + Send>>, Box<dyn Write + Send>);

/// Starts the server straight over USB, switching the device's USB over if it isn't.
fn start_usb(adb: &Adb, options: &[String], audio: bool) -> Result<Streams> {
    usb::switch(adb)?;
    // Tells this session's data from what an earlier one left in the pipe.
    let session = format!("{:016x}", std::collections::hash_map::RandomState::new().build_hasher().finish());
    let mut options = options.to_vec();
    options.extend(["link=usb".to_string(), format!("session={session}")]);
    let server = adb.start_stream_server(&options, true)?;
    let link = usb::connect(&adb.serial, &session)?;
    println!("Connected straight over USB");
    let sound: Option<Box<dyn Read + Send>> = if audio { Some(Box::new(link.audio)) } else { None };
    Ok((server, Box::new(link.video), sound, Box::new(link.controls)))
}

/// How long the controls may go quiet before a [Control::Ping].
const PING_EVERY: Duration = Duration::from_secs(1);

/// Sends the controls on their own thread, so the window never waits on the connection.
/// Pointer moves queued behind one another go as just the last, and a game's mouse
/// movements ([Control::MouseRelative]) as their sum.
fn spawn_control_writer(mut stream: impl Write + Send + 'static, queue: Receiver<Control>) {
    thread::Builder::new()
        .name("controls".into())
        .spawn(move || {
            let mut buffer = Vec::new();
            loop {
                let first = match queue.recv_timeout(PING_EVERY) {
                    Ok(control) => control,
                    Err(RecvTimeoutError::Timeout) => Control::Ping,
                    Err(RecvTimeoutError::Disconnected) => return,
                };
                let batch: Vec<Control> = std::iter::once(first).chain(queue.try_iter()).collect();
                buffer.clear();
                let mut moved = (0, 0);
                for (i, control) in batch.iter().enumerate() {
                    if let Control::MouseRelative { dx, dy } = *control {
                        moved = (moved.0 + dx, moved.1 + dy);
                        if !matches!(batch.get(i + 1), Some(Control::MouseRelative { .. })) {
                            Control::MouseRelative { dx: moved.0, dy: moved.1 }.encode(&mut buffer);
                            moved = (0, 0);
                        }
                        continue;
                    }
                    let superseded = matches!(control, Control::MouseMove { .. })
                        && matches!(batch.get(i + 1), Some(Control::MouseMove { .. }));
                    if !superseded {
                        control.encode(&mut buffer);
                    }
                }
                if stream.write_all(&buffer).is_err() {
                    return;
                }
            }
        })
        .unwrap();
}

struct App {
    args: Args,
    header: Option<Header>,
    video_queue: Option<Receiver<video::Event>>,
    video_events: Sender<video::Event>,
    controls: Sender<Control>,
    proxy: EventLoopProxy<UserEvent>,
    window: Option<Window>,
    size: Arc<WindowSize>,
    cursor: (f64, f64),
    /// An app on the device has the mouse (a game turning its camera with it).
    captured: bool,
    /// The PC's mouse is held for it: hidden, kept in the window, and its movements sent on.
    grabbed: bool,
    /// A pointer with nothing to see, for while the mouse is held.
    blank_cursor: Option<CustomCursor>,
    result: Result<()>,
}

impl App {
    /// The desktop's size, without the navigation bar.
    fn content(&self) -> (u32, u32) {
        (self.args.width, self.args.height)
    }

    fn fit(&self) -> Fit {
        Fit::new(self.content(), self.size.get())
    }

    fn device_point(&self) -> (i32, i32) {
        self.fit().to_content(self.content(), self.cursor.0, self.cursor.1)
    }

    fn send(&self, control: Control) {
        let _ = self.controls.send(control);
    }

    /// Holds the mouse for the app that has the device's. Windows can only keep it in the
    /// window, not still, so it goes back to the middle after every move, and how far it
    /// went goes to the device.
    fn grab(&mut self) {
        let (Some(window), Some(blank)) = (&self.window, &self.blank_cursor) else { return };
        if window.set_cursor_grab(CursorGrabMode::Confined).is_err() {
            return;
        }
        // Not hidden: winit keeps a hidden one to a single pixel, to be read as raw input,
        // which is off (see run). An invisible one keeps the whole window to move in.
        window.set_cursor(blank.clone());
        self.grabbed = true;
        self.recenter();
    }

    /// Lets go of the mouse: Right Ctrl, the window losing focus, or the app letting go.
    fn release(&mut self) {
        if !self.grabbed {
            return;
        }
        self.grabbed = false;
        if let Some(window) = &self.window {
            let _ = window.set_cursor_grab(CursorGrabMode::None);
            window.set_cursor(CursorIcon::Default);
        }
    }

    fn middle(&self) -> PhysicalPosition<f64> {
        let (width, height) = self.size.get();
        PhysicalPosition::new((width / 2) as f64, (height / 2) as f64)
    }

    fn recenter(&mut self) {
        let middle = self.middle();
        if let Some(window) = &self.window {
            let _ = window.set_cursor_position(middle);
        }
        self.cursor = (middle.x, middle.y);
    }

    fn create_window(&mut self, event_loop: &ActiveEventLoop) -> Result<()> {
        let content = self.content();
        // As large as the desktop, or as fits on the screen.
        let mut size = PhysicalSize::new(content.0, content.1);
        if let Some(monitor) = event_loop.primary_monitor() {
            let screen = monitor.size();
            let scale =
                (screen.width as f64 * 0.9 / content.0 as f64).min(screen.height as f64 * 0.85 / content.1 as f64);
            if scale < 1.0 {
                size = PhysicalSize::new((content.0 as f64 * scale) as u32, (content.1 as f64 * scale) as u32);
            }
        }
        let mut attributes =
            Window::default_attributes().with_title("LumiDeck").with_inner_size(size).with_window_icon(icon());
        if self.args.fullscreen {
            attributes = attributes.with_fullscreen(Some(Fullscreen::Borderless(None)));
        }
        let window = event_loop.create_window(attributes)?;
        self.blank_cursor = CustomCursor::from_rgba(vec![0; 4], 1, 1, 0, 0)
            .map(|source| event_loop.create_custom_cursor(source))
            .ok();
        let inner = window.inner_size();
        self.size.set(inner.width, inner.height);
        let hwnd = match window.window_handle()?.as_raw() {
            RawWindowHandle::Win32(handle) => handle.hwnd.get(),
            _ => bail!("Not a Windows window"),
        };

        let proxy = self.proxy.clone();
        keyboard::install(hwnd, self.controls.clone(), move |shortcut| {
            let _ = proxy.send_event(UserEvent::Shortcut(shortcut));
        });

        let options = video::Options {
            hwnd,
            header: self.header.take().unwrap(),
            crop: content,
            stats: self.args.stats,
            dump: self.args.dump.clone(),
        };
        let queue = self.video_queue.take().unwrap();
        let size = self.size.clone();
        let controls = self.controls.clone();
        let proxy = self.proxy.clone();
        thread::Builder::new().name("video".into()).spawn(move || {
            let result = video::run(options, queue, size, controls);
            let _ = proxy.send_event(UserEvent::Ended(result.err().map(|e| format!("{e:#}"))));
        })?;
        self.window = Some(window);
        Ok(())
    }
}

impl ApplicationHandler<UserEvent> for App {
    fn resumed(&mut self, event_loop: &ActiveEventLoop) {
        if self.window.is_none()
            && let Err(e) = self.create_window(event_loop)
        {
            self.result = Err(e);
            event_loop.exit();
        }
    }

    fn window_event(&mut self, event_loop: &ActiveEventLoop, _id: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => event_loop.exit(),
            WindowEvent::Resized(size) => {
                self.size.set(size.width, size.height);
                let _ = self.video_events.send(video::Event::Redraw);
            }
            WindowEvent::RedrawRequested => {
                let _ = self.video_events.send(video::Event::Redraw);
            }
            WindowEvent::Focused(false) => {
                keyboard::release_all();
                self.release();
            }
            WindowEvent::CursorMoved { position, .. } => {
                if self.grabbed {
                    let middle = self.middle();
                    let (dx, dy) = ((position.x - middle.x).round() as i32, (position.y - middle.y).round() as i32);
                    // Going back to the middle comes through here too, as no move.
                    if dx != 0 || dy != 0 {
                        self.send(Control::MouseRelative { dx, dy });
                        self.recenter();
                    }
                    return;
                }
                self.cursor = (position.x, position.y);
                let (x, y) = self.device_point();
                self.send(Control::MouseMove { x, y });
            }
            WindowEvent::CursorLeft { .. } => self.send(Control::MouseLeave),
            // A click in the window takes the mouse back for the app that has the device's,
            // as in a game on the PC.
            WindowEvent::MouseInput { state: ElementState::Pressed, button: MouseButton::Left, .. }
                if self.captured && !self.grabbed =>
            {
                self.grab()
            }
            WindowEvent::MouseInput { state, button, .. } => {
                let button = match button {
                    MouseButton::Left => Button::Primary,
                    MouseButton::Right => Button::Secondary,
                    MouseButton::Middle => Button::Middle,
                    MouseButton::Back => Button::Back,
                    MouseButton::Forward => Button::Forward,
                    MouseButton::Other(_) => return,
                };
                let (x, y) = self.device_point();
                self.send(Control::MouseButton { button, down: state == ElementState::Pressed, x, y });
            }
            WindowEvent::MouseWheel { delta, .. } => {
                let (horizontal, vertical) = match delta {
                    MouseScrollDelta::LineDelta(x, y) => (x, y),
                    // Touchpads: about 50 pixels to a wheel notch.
                    MouseScrollDelta::PixelDelta(p) => (p.x as f32 / 50.0, p.y as f32 / 50.0),
                };
                let (x, y) = self.device_point();
                self.send(Control::Scroll { x, y, horizontal, vertical });
            }
            _ => {}
        }
    }

    fn user_event(&mut self, event_loop: &ActiveEventLoop, event: UserEvent) {
        match event {
            UserEvent::Shortcut(Shortcut::ToggleFullscreen) => {
                if let Some(window) = &self.window {
                    let fullscreen = window.fullscreen().is_some();
                    window.set_fullscreen(if fullscreen { None } else { Some(Fullscreen::Borderless(None)) });
                }
            }
            UserEvent::Shortcut(Shortcut::ReleaseMouse) => self.release(),
            UserEvent::PointerCapture(captured) => {
                self.captured = captured;
                if !captured {
                    self.release();
                } else if self.window.as_ref().is_some_and(Window::has_focus) {
                    self.grab();
                }
            }
            UserEvent::Ended(error) => {
                if let Some(error) = error {
                    self.result = Err(anyhow::anyhow!("The device disconnected: {error}"));
                }
                event_loop.exit();
            }
        }
    }
}

/// The LumiDeck logo, for the title bar and the taskbar.
fn icon() -> Option<Icon> {
    let decoder = png::Decoder::new(std::io::Cursor::new(include_bytes!("../../icons/scrcpy.png")));
    let mut reader = decoder.read_info().ok()?;
    let mut pixels = vec![0; reader.output_buffer_size()?];
    let info = reader.next_frame(&mut pixels).ok()?;
    if info.color_type != png::ColorType::Rgba || info.bit_depth != png::BitDepth::Eight {
        return None;
    }
    pixels.truncate(info.buffer_size());
    Icon::from_rgba(pixels, info.width, info.height).ok()
}
