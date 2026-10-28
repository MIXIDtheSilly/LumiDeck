//! Keeps the PC's clipboard and the device's the same, for text. When the device's
//! changes, its text comes with the video (see protocol.rs) and goes on the PC's
//! ([Clipboard::set]). When the PC's changes, its text goes to the device as a
//! [Control::Clipboard], and once at the start, so what was copied before shows up there
//! too. Neither side sends back what it just got. The device's side is
//! android/…/server/ClipboardSync.kt.
//!
//! A hidden window on a thread of its own hears about clipboard changes and owns the text
//! it puts there, as Windows wants.

use std::cell::RefCell;
use std::sync::mpsc::{self, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use anyhow::{Result, anyhow};
use windows::Win32::Foundation::{GlobalFree, HANDLE, HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::DataExchange::{
    AddClipboardFormatListener, CloseClipboard, EmptyClipboard, GetClipboardData, GetClipboardOwner,
    IsClipboardFormatAvailable, OpenClipboard, SetClipboardData,
};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::System::Memory::{GMEM_MOVEABLE, GlobalAlloc, GlobalLock, GlobalUnlock};
use windows::Win32::System::Ole::CF_UNICODETEXT;
use windows::Win32::UI::WindowsAndMessaging::{
    CreateWindowExW, DefWindowProcW, DispatchMessageW, GetMessageW, HWND_MESSAGE, KillTimer, MSG, PostMessageW,
    RegisterClassW, SetTimer, WINDOW_EX_STYLE, WINDOW_STYLE, WM_APP, WM_CLIPBOARDUPDATE, WM_TIMER, WNDCLASSW,
};
use windows::core::w;

use crate::protocol::Control;

/// The most text that goes either way, so a huge copy doesn't hold up the stream.
const MAX_TEXT: usize = 1 << 20;

/// Tells the window that text came from the device.
const WM_FROM_DEVICE: u32 = WM_APP + 1;

/// How long after the PC's clipboard changes its text is read. Some programs (.NET ones,
/// for one) put it there in steps, and reading in between makes their copy fail.
const SETTLE_MS: u32 = 100;
const SETTLE_TIMER: usize = 1;

/// Where the device's clipboard text goes.
#[derive(Clone)]
pub struct Clipboard {
    /// The hidden window, as a number so it can go between threads.
    window: isize,
    from_device: Arc<Mutex<Option<String>>>,
}

impl Clipboard {
    pub fn set(&self, text: String) {
        *self.from_device.lock().unwrap() = Some(text);
        let _ = unsafe { PostMessageW(Some(HWND(self.window as *mut _)), WM_FROM_DEVICE, WPARAM(0), LPARAM(0)) };
    }
}

/// The hidden window's, on its thread.
struct State {
    window: HWND,
    controls: Sender<Control>,
    from_device: Arc<Mutex<Option<String>>>,
}

thread_local! {
    static STATE: RefCell<Option<State>> = const { RefCell::new(None) };
}

pub fn spawn(controls: Sender<Control>) -> Result<Clipboard> {
    let from_device = Arc::new(Mutex::new(None));
    let shared = from_device.clone();
    let (ready, started) = mpsc::channel();
    thread::Builder::new().name("clipboard".into()).spawn(move || {
        let window = match unsafe { create_window() } {
            Ok(window) => window,
            Err(e) => {
                let _ = ready.send(Err(e));
                return;
            }
        };
        let _ = ready.send(Ok(window.0 as isize));
        let state = State { window, controls, from_device: shared };
        state.pc_changed();
        STATE.with(|s| *s.borrow_mut() = Some(state));
        let mut message = MSG::default();
        while unsafe { GetMessageW(&mut message, None, 0, 0) }.0 > 0 {
            unsafe { DispatchMessageW(&message) };
        }
    })?;
    let window = started.recv()??;
    Ok(Clipboard { window, from_device })
}

unsafe fn create_window() -> Result<HWND> {
    unsafe {
        let instance = GetModuleHandleW(None)?;
        let class = WNDCLASSW {
            lpfnWndProc: Some(window_proc),
            hInstance: instance.into(),
            lpszClassName: w!("LumiDeckClipboard"),
            ..Default::default()
        };
        if RegisterClassW(&class) == 0 {
            return Err(anyhow!("Couldn't watch the clipboard: {}", windows::core::Error::from_thread()));
        }
        let window = CreateWindowExW(
            WINDOW_EX_STYLE(0),
            w!("LumiDeckClipboard"),
            w!(""),
            WINDOW_STYLE(0),
            0,
            0,
            0,
            0,
            Some(HWND_MESSAGE),
            None,
            Some(instance.into()),
            None,
        )?;
        AddClipboardFormatListener(window)?;
        Ok(window)
    }
}

unsafe extern "system" fn window_proc(window: HWND, message: u32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    match message {
        WM_CLIPBOARDUPDATE => {
            unsafe { SetTimer(Some(window), SETTLE_TIMER, SETTLE_MS, None) };
        }
        WM_TIMER if wparam.0 == SETTLE_TIMER => {
            let _ = unsafe { KillTimer(Some(window), SETTLE_TIMER) };
            STATE.with(|s| s.borrow().as_ref().map(State::pc_changed));
        }
        WM_FROM_DEVICE => {
            STATE.with(|s| s.borrow().as_ref().map(State::device_changed));
        }
        _ => return unsafe { DefWindowProcW(window, message, wparam, lparam) },
    };
    LRESULT(0)
}

impl State {
    fn pc_changed(&self) {
        // Text this window put there came from the device.
        if unsafe { GetClipboardOwner() }.is_ok_and(|owner| owner == self.window) {
            return;
        }
        if let Some(text) = read(self.window)
            && text.len() <= MAX_TEXT
        {
            // Android's lines end in just a line feed.
            let _ = self.controls.send(Control::Clipboard(text.replace("\r\n", "\n")));
        }
    }

    fn device_changed(&self) {
        let Some(text) = self.from_device.lock().unwrap().take() else { return };
        if text.len() > MAX_TEXT {
            return;
        }
        let text = text.replace("\r\n", "\n").replace('\n', "\r\n");
        if read(self.window).as_ref() == Some(&text) {
            return;
        }
        if let Err(e) = write(self.window, &text) {
            eprintln!("Couldn't copy the device's text: {e}");
        }
    }
}

/// Opens the clipboard, which another program may have open for a moment.
fn open(window: HWND) -> Result<()> {
    for _ in 0..20 {
        if unsafe { OpenClipboard(Some(window)) }.is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(5));
    }
    Err(anyhow!("another program has the clipboard"))
}

/// The clipboard's text, if it has any.
fn read(window: HWND) -> Option<String> {
    unsafe {
        IsClipboardFormatAvailable(CF_UNICODETEXT.0 as u32).ok()?;
        open(window).ok()?;
        let text = GetClipboardData(CF_UNICODETEXT.0 as u32).ok().and_then(|data| {
            let memory = windows::Win32::Foundation::HGLOBAL(data.0);
            let start = GlobalLock(memory) as *const u16;
            if start.is_null() {
                return None;
            }
            let mut len = 0;
            while *start.add(len) != 0 {
                len += 1;
            }
            let text = String::from_utf16_lossy(std::slice::from_raw_parts(start, len));
            let _ = GlobalUnlock(memory);
            Some(text)
        });
        let _ = CloseClipboard();
        text
    }
}

fn write(window: HWND, text: &str) -> Result<()> {
    let wide: Vec<u16> = text.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        open(window)?;
        let result = (|| -> Result<()> {
            EmptyClipboard()?;
            let memory = GlobalAlloc(GMEM_MOVEABLE, wide.len() * 2)?;
            let start = GlobalLock(memory) as *mut u16;
            if start.is_null() {
                let _ = GlobalFree(Some(memory));
                return Err(anyhow!("out of memory"));
            }
            std::ptr::copy_nonoverlapping(wide.as_ptr(), start, wide.len());
            let _ = GlobalUnlock(memory);
            // The clipboard owns it from here, unless this fails.
            if let Err(e) = SetClipboardData(CF_UNICODETEXT.0 as u32, Some(HANDLE(memory.0))) {
                let _ = GlobalFree(Some(memory));
                return Err(e.into());
            }
            Ok(())
        })();
        let _ = CloseClipboard();
        result
    }
}
