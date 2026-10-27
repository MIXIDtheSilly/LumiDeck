//! LumiDeck for PCs: shows the device's LumiDeck desktop in a window, with its sound, and
//! sends it the mouse and keyboard.
//!
//! The device side is LumiDeck's stream server (android/…/server/StreamServer.kt), which
//! this starts over ADB. See protocol.rs for what goes between them.

mod adb;
mod audio;
mod decoder;
mod keyboard;
mod protocol;
mod render;
mod video;

use std::io::Write;
use std::net::TcpStream;
use std::process::ExitCode;
use std::sync::Arc;
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;

use anyhow::{Context, Result, bail};
use winit::application::ApplicationHandler;
use winit::dpi::PhysicalSize;
use winit::event::{ElementState, MouseButton, MouseScrollDelta, WindowEvent};
use winit::event_loop::{ActiveEventLoop, EventLoop, EventLoopProxy};
use winit::raw_window_handle::{HasWindowHandle, RawWindowHandle};
use winit::window::{Fullscreen, Icon, Window, WindowId};

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
  --bitrate <Mbit/s>    Video bit rate (default 16)
  --no-audio            Leave the sound on the device
  --stats               Print how fast video goes, every few seconds
";

struct Args {
    serial: Option<String>,
    width: u32,
    height: u32,
    dpi: u32,
    fullscreen: bool,
    codec: String,
    bitrate: u32,
    audio: bool,
    stats: bool,
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
            bitrate: 16,
            audio: true,
            stats: false,
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
                "--stats" => args.stats = true,
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
    let adb = Adb::connect(args.serial.clone())?;
    adb.prepare()?;

    // Android draws its own navigation bar along the bottom of the desktop and forces it
    // on while app windows are open. The display is one bar taller and the bar is cropped
    // off on this side; the desktop keeps that strip clear, so nothing else is lost. The
    // bar is 56dp on displays this large.
    let nav_bar = (56 * args.dpi).div_ceil(160) & !1;
    let options = vec![
        format!("width={}", args.width),
        format!("height={}", args.height + nav_bar),
        format!("dpi={}", args.dpi),
        format!("codec={}", args.codec),
        format!("bitrate={}", args.bitrate * 1_000_000),
        format!("audio={}", args.audio),
        format!("stats={}", args.stats),
    ];
    let mut server = adb.start_server(&options)?;
    let mut video = server.connect()?;
    let sound = if args.audio { Some(server.connect()?) } else { None };
    let header = Header::read(&mut video)?;

    let (controls, control_queue) = mpsc::channel();
    spawn_control_writer(video.try_clone()?, control_queue);
    let (video_events, video_queue) = mpsc::channel();
    video::spawn_reader(video, video_events.clone());
    if let Some(sound) = sound {
        audio::spawn(sound);
    }

    let event_loop = EventLoop::<UserEvent>::with_user_event().build()?;
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
        result: Ok(()),
    };
    event_loop.run_app(&mut app)?;
    drop(server);
    app.result
}

/// Sends the controls on their own thread, so the window never waits on the socket.
/// Pointer moves queued behind one another go as just the last.
fn spawn_control_writer(mut stream: TcpStream, queue: Receiver<Control>) {
    thread::Builder::new()
        .name("controls".into())
        .spawn(move || {
            let mut buffer = Vec::new();
            while let Ok(first) = queue.recv() {
                let batch: Vec<Control> = std::iter::once(first).chain(queue.try_iter()).collect();
                buffer.clear();
                for (i, control) in batch.iter().enumerate() {
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

        let options =
            video::Options { hwnd, header: self.header.take().unwrap(), crop: content, stats: self.args.stats };
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
            WindowEvent::Focused(false) => keyboard::release_all(),
            WindowEvent::CursorMoved { position, .. } => {
                self.cursor = (position.x, position.y);
                let (x, y) = self.device_point();
                self.send(Control::MouseMove { x, y });
            }
            WindowEvent::CursorLeft { .. } => self.send(Control::MouseLeave),
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
