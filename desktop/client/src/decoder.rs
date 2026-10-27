//! Safe wrapper around the ffmpeg glue in decoder.c.

use std::ffi::{CStr, c_char, c_int, c_void};
use std::ptr;

use anyhow::{Result, bail};
use windows::Win32::Graphics::Direct3D11::{ID3D11Device, ID3D11Texture2D};
use windows::core::Interface;

#[repr(C)]
struct RawFrame {
    texture: *mut c_void,
    index: c_int,
    width: c_int,
    height: c_int,
    full_range: c_int,
    bt709: c_int,
    handle: *mut c_void,
}

unsafe extern "C" {
    fn ld_decoder_new(hevc: c_int, device: *mut c_void, error: *mut c_char, error_size: c_int) -> *mut c_void;
    fn ld_decoder_free(decoder: *mut c_void);
    fn ld_decoder_send(decoder: *mut c_void, data: *const u8, size: c_int) -> c_int;
    fn ld_decoder_receive(decoder: *mut c_void, out: *mut RawFrame) -> c_int;
    fn ld_frame_free(handle: *mut c_void);
    fn ld_error_string(code: c_int, buffer: *mut c_char, size: c_int);
}

/// Decodes into textures on the given D3D11 device.
pub struct Decoder(*mut c_void);

impl Decoder {
    pub fn new(hevc: bool, device: &ID3D11Device) -> Result<Self> {
        let mut error = [0 as c_char; 256];
        let raw = unsafe { ld_decoder_new(hevc as c_int, device.as_raw(), error.as_mut_ptr(), error.len() as c_int) };
        if raw.is_null() {
            bail!("{}", unsafe { CStr::from_ptr(error.as_ptr()) }.to_string_lossy());
        }
        Ok(Decoder(raw))
    }

    pub fn send(&mut self, data: &[u8]) -> Result<()> {
        let r = unsafe { ld_decoder_send(self.0, data.as_ptr(), data.len() as c_int) };
        if r < 0 {
            bail!("Couldn't decode: {}", error_string(r));
        }
        Ok(())
    }

    pub fn receive(&mut self) -> Result<Option<Frame>> {
        let mut raw = RawFrame {
            texture: ptr::null_mut(),
            index: 0,
            width: 0,
            height: 0,
            full_range: 0,
            bt709: 0,
            handle: ptr::null_mut(),
        };
        match unsafe { ld_decoder_receive(self.0, &mut raw) } {
            0 => Ok(None),
            r if r < 0 => bail!("Couldn't decode: {}", error_string(r)),
            _ => Ok(Some(Frame(raw))),
        }
    }
}

impl Drop for Decoder {
    fn drop(&mut self) {
        unsafe { ld_decoder_free(self.0) }
    }
}

/// A decoded frame: one slice of a texture array, held until dropped.
pub struct Frame(RawFrame);

impl Frame {
    pub fn texture(&self) -> &ID3D11Texture2D {
        // The frame holds a reference for as long as it lives.
        unsafe { ID3D11Texture2D::from_raw_borrowed(&self.0.texture).unwrap() }
    }

    pub fn index(&self) -> u32 {
        self.0.index as u32
    }

    pub fn size(&self) -> (u32, u32) {
        (self.0.width as u32, self.0.height as u32)
    }

    pub fn full_range(&self) -> bool {
        self.0.full_range != 0
    }

    pub fn bt709(&self) -> bool {
        self.0.bt709 != 0
    }
}

impl Drop for Frame {
    fn drop(&mut self) {
        unsafe { ld_frame_free(self.0.handle) }
    }
}

fn error_string(code: c_int) -> String {
    let mut buffer = [0 as c_char; 128];
    unsafe {
        ld_error_string(code, buffer.as_mut_ptr(), buffer.len() as c_int);
        CStr::from_ptr(buffer.as_ptr()).to_string_lossy().into_owned()
    }
}
