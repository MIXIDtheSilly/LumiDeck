//! Where the window was, so it opens there again: kept in the registry, next to the
//! LumiDeck app's settings (HKCU\Software\LumiDeck).

use windows::Win32::System::Registry::{
    HKEY, HKEY_CURRENT_USER, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE, REG_SZ, RRF_RT_REG_SZ, RegCloseKey,
    RegCreateKeyExW, RegGetValueW, RegSetValueExW,
};
use windows::core::{HSTRING, PCWSTR};

const KEY: &str = "Software\\LumiDeck";
const VALUE: &str = "WindowPlacement";

/// The window's outer position and inner size in pixels, as it was last shown in a window
/// (not fullscreen), and whether it was maximised.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Placement {
    pub x: i32,
    pub y: i32,
    pub width: u32,
    pub height: u32,
    pub maximized: bool,
}

pub fn load() -> Option<Placement> {
    let text = read()?;
    let mut numbers = text.split(' ').map(str::parse::<i64>);
    let mut next = || numbers.next()?.ok();
    let placement = Placement {
        x: next()?.try_into().ok()?,
        y: next()?.try_into().ok()?,
        width: next()?.try_into().ok()?,
        height: next()?.try_into().ok()?,
        maximized: next()? != 0,
    };
    (placement.width >= 200 && placement.height >= 150).then_some(placement)
}

pub fn save(placement: Placement) {
    let Placement { x, y, width, height, maximized } = placement;
    let text = format!("{x} {y} {width} {height} {}", maximized as u8);
    let bytes: Vec<u8> = text.encode_utf16().chain(std::iter::once(0)).flat_map(u16::to_le_bytes).collect();
    unsafe {
        let mut key = HKEY::default();
        let created = RegCreateKeyExW(
            HKEY_CURRENT_USER,
            &HSTRING::from(KEY),
            None,
            PCWSTR::null(),
            REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE,
            None,
            &mut key,
            None,
        );
        if created.is_ok() {
            let _ = RegSetValueExW(key, &HSTRING::from(VALUE), None, REG_SZ, Some(&bytes));
            let _ = RegCloseKey(key);
        }
    }
}

fn read() -> Option<String> {
    let mut buffer = [0u16; 128];
    let mut size = (buffer.len() * 2) as u32;
    let result = unsafe {
        RegGetValueW(
            HKEY_CURRENT_USER,
            &HSTRING::from(KEY),
            &HSTRING::from(VALUE),
            RRF_RT_REG_SZ,
            None,
            Some(buffer.as_mut_ptr().cast()),
            Some(&mut size),
        )
    };
    result.is_ok().then(|| String::from_utf16_lossy(&buffer[..(size as usize / 2).saturating_sub(1)]))
}
