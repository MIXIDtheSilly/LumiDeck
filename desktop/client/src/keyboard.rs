//! The keyboard, read through a low-level Windows hook while the window is in front.
//!
//! Keys go to the device by where they are on the keyboard (USB HID codes), not by what
//! they type: the device's keyboard is a real one to Android, which applies the layout
//! picked for it. The hook also keeps the Windows key from opening the Start menu, and
//! sees AltGr's fake Ctrl for what it is.
//!
//! Right Ctrl stays on the PC: it's the client's own shortcut key (Right Ctrl+F for
//! fullscreen).

use std::sync::Mutex;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicIsize, Ordering};
use std::sync::mpsc::Sender;

use windows::Win32::Foundation::{HWND, LPARAM, LRESULT, WPARAM};
use windows::Win32::System::LibraryLoader::GetModuleHandleW;
use windows::Win32::UI::Input::KeyboardAndMouse::{VK_LWIN, VK_NUMLOCK, VK_PAUSE, VK_RCONTROL, VK_RWIN};
use windows::Win32::UI::WindowsAndMessaging::{
    CallNextHookEx, GetForegroundWindow, HHOOK, KBDLLHOOKSTRUCT, LLKHF_EXTENDED, LLKHF_UP, SetWindowsHookExW,
    WH_KEYBOARD_LL,
};

use crate::protocol::Control;

/// What the client does itself rather than send on.
#[derive(Debug)]
pub enum Shortcut {
    ToggleFullscreen,
}

struct Hook {
    controls: Sender<Control>,
    shortcuts: Box<dyn Fn(Shortcut) + Send + Sync>,
}

static HOOK: OnceLock<Hook> = OnceLock::new();
static WINDOW: AtomicIsize = AtomicIsize::new(0);
/// Keys sent down, so they can be sent up when the window loses focus.
static DOWN: Mutex<[bool; 256]> = Mutex::new([false; 256]);
static RIGHT_CTRL: Mutex<bool> = Mutex::new(false);

/// Starts listening. Call on the thread that runs the window's event loop.
pub fn install(window: isize, controls: Sender<Control>, shortcuts: impl Fn(Shortcut) + Send + Sync + 'static) {
    WINDOW.store(window, Ordering::Relaxed);
    if HOOK.set(Hook { controls, shortcuts: Box::new(shortcuts) }).is_err() {
        return;
    }
    unsafe {
        let module = GetModuleHandleW(None).unwrap_or_default();
        if let Err(e) = SetWindowsHookExW(WH_KEYBOARD_LL, Some(hook), Some(module.into()), 0) {
            eprintln!("No keyboard: {e}");
        }
    }
}

/// Sends every key that's down up again: the window lost focus, so their releases go elsewhere.
pub fn release_all() {
    let Some(hook) = HOOK.get() else { return };
    let mut down = DOWN.lock().unwrap();
    if down.iter().any(|&d| d) {
        *down = [false; 256];
        let _ = hook.controls.send(Control::ReleaseKeys);
    }
    *RIGHT_CTRL.lock().unwrap() = false;
}

unsafe extern "system" fn hook(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
    if code >= 0 && unsafe { GetForegroundWindow() } == HWND(WINDOW.load(Ordering::Relaxed) as *mut _) {
        let event = unsafe { &*(lparam.0 as *const KBDLLHOOKSTRUCT) };
        if handle(event) {
            // Kept from Windows.
            return LRESULT(1);
        }
    }
    unsafe { CallNextHookEx(Some(HHOOK::default()), code, wparam, lparam) }
}

/// Returns whether Windows should never see the key.
fn handle(event: &KBDLLHOOKSTRUCT) -> bool {
    let Some(hook) = HOOK.get() else { return false };
    let down = event.flags.0 & LLKHF_UP.0 == 0;
    let vk = event.vkCode as u16;
    let win = vk == VK_LWIN.0 || vk == VK_RWIN.0;

    if vk == VK_RCONTROL.0 {
        *RIGHT_CTRL.lock().unwrap() = down;
        return false;
    }
    if *RIGHT_CTRL.lock().unwrap() {
        if down && vk == b'F' as u16 {
            (hook.shortcuts)(Shortcut::ToggleFullscreen);
        }
        return true;
    }
    // AltGr presses a fake left Ctrl first, with this scan code.
    if event.scanCode == 0x21D {
        return false;
    }
    let extended = event.flags.0 & LLKHF_EXTENDED.0 != 0;
    let usage = if vk == VK_PAUSE.0 {
        0x48
    } else if vk == VK_NUMLOCK.0 {
        0x53
    } else {
        hid_usage(event.scanCode, extended)
    };
    if usage != 0 {
        let mut pressed = DOWN.lock().unwrap();
        // Windows repeats held keys; Android does its own repeating.
        if pressed[usage as usize] != down {
            pressed[usage as usize] = down;
            let _ = hook.controls.send(Control::Key { usage, down });
        }
    }
    win
}

/// The USB HID usage of a key, by its PC (set 1) scan code; 0 for keys the device doesn't get.
fn hid_usage(scan: u32, extended: bool) -> u8 {
    if extended {
        return match scan {
            0x1C => 0x58, // keypad Enter
            0x1D => 0xE4, // right Ctrl
            0x35 => 0x54, // keypad /
            0x37 => 0x46, // Print Screen
            0x38 => 0xE6, // right Alt (AltGr)
            0x47 => 0x4A, // Home
            0x48 => 0x52, // Up
            0x49 => 0x4B, // Page Up
            0x4B => 0x50, // Left
            0x4D => 0x4F, // Right
            0x4F => 0x4D, // End
            0x50 => 0x51, // Down
            0x51 => 0x4E, // Page Down
            0x52 => 0x49, // Insert
            0x53 => 0x4C, // Delete
            0x5B => 0xE3, // left Windows
            0x5C => 0xE7, // right Windows
            0x5D => 0x65, // Menu
            _ => 0,
        };
    }
    const BASE: [u8; 0x59] = [
        0x00, 0x29, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, // _ Esc 1-6
        0x24, 0x25, 0x26, 0x27, 0x2D, 0x2E, 0x2A, 0x2B, // 7-0 - = Backspace Tab
        0x14, 0x1A, 0x08, 0x15, 0x17, 0x1C, 0x18, 0x0C, // Q W E R T Y U I
        0x12, 0x13, 0x2F, 0x30, 0x28, 0xE0, 0x04, 0x16, // O P [ ] Enter LCtrl A S
        0x07, 0x09, 0x0A, 0x0B, 0x0D, 0x0E, 0x0F, 0x33, // D F G H J K L ;
        0x34, 0x35, 0xE1, 0x31, 0x1D, 0x1B, 0x06, 0x19, // ' ` LShift \ Z X C V
        0x05, 0x11, 0x10, 0x36, 0x37, 0x38, 0xE5, 0x55, // B N M , . / RShift keypad*
        0xE2, 0x2C, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, // LAlt Space CapsLock F1-F5
        0x3F, 0x40, 0x41, 0x42, 0x43, 0x53, 0x47, 0x5F, // F6-F10 NumLock ScrollLock keypad7
        0x60, 0x61, 0x56, 0x5C, 0x5D, 0x5E, 0x57, 0x59, // keypad 8 9 - 4 5 6 + 1
        0x5A, 0x5B, 0x62, 0x63, 0x00, 0x00, 0x64, 0x44, // keypad 2 3 0 . _ _ ISO\ F11
        0x45, // F12
    ];
    match scan {
        0..=0x58 => BASE[scan as usize],
        0x70 => 0x88, // Katakana/Hiragana
        0x73 => 0x87, // Ro
        0x79 => 0x8A, // Henkan
        0x7B => 0x8B, // Muhenkan
        0x7D => 0x89, // Yen
        _ => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::hid_usage;

    #[test]
    fn maps_common_keys() {
        assert_eq!(hid_usage(0x1E, false), 0x04); // A
        assert_eq!(hid_usage(0x2C, false), 0x1D); // Z
        assert_eq!(hid_usage(0x0B, false), 0x27); // 0
        assert_eq!(hid_usage(0x39, false), 0x2C); // Space
        assert_eq!(hid_usage(0x58, false), 0x45); // F12
        assert_eq!(hid_usage(0x56, false), 0x64); // ISO backslash
        assert_eq!(hid_usage(0x48, true), 0x52); // Up
        assert_eq!(hid_usage(0x38, true), 0xE6); // AltGr
    }
}
