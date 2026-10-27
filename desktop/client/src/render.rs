//! Shows decoded frames in the window. The GPU's video processor converts each frame from
//! the decoder's YUV to the window's RGB, crops and scales it in one pass, and the swap
//! chain flips it onto the screen without waiting for the display's refresh.

use std::collections::HashMap;
use std::mem::ManuallyDrop;

use anyhow::{Context, Result};
use windows::Win32::Foundation::{HMODULE, HWND, RECT};
use windows::Win32::Graphics::Direct3D::{D3D_DRIVER_TYPE_HARDWARE, D3D_FEATURE_LEVEL_11_0};
use windows::Win32::Graphics::Direct3D11::*;
use windows::Win32::Graphics::Dxgi::Common::*;
use windows::Win32::Graphics::Dxgi::*;
use windows::core::{BOOL, Interface};

use crate::decoder::Frame;

/// Where content of the given size goes in a window, as large as fits, centred.
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct Fit {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

impl Fit {
    pub fn new(content: (u32, u32), window: (u32, u32)) -> Fit {
        let (cw, ch) = (content.0.max(1) as f64, content.1.max(1) as f64);
        let (ww, wh) = (window.0 as f64, window.1 as f64);
        let scale = (ww / cw).min(wh / ch);
        let (w, h) = ((cw * scale).round(), (ch * scale).round());
        Fit {
            x: ((ww - w) / 2.0).round() as i32,
            y: ((wh - h) / 2.0).round() as i32,
            width: w as i32,
            height: h as i32,
        }
    }

    /// The content pixel under a window pixel, kept inside the content.
    pub fn to_content(self, content: (u32, u32), x: f64, y: f64) -> (i32, i32) {
        let cx = (x - self.x as f64) * content.0 as f64 / self.width.max(1) as f64;
        let cy = (y - self.y as f64) * content.1 as f64 / self.height.max(1) as f64;
        ((cx.floor() as i32).clamp(0, content.0 as i32 - 1), (cy.floor() as i32).clamp(0, content.1 as i32 - 1))
    }
}

/// Bars around the picture when the window's shape doesn't match it: the desktop's dark blue.
const BACKGROUND: D3D11_VIDEO_COLOR_RGBA = D3D11_VIDEO_COLOR_RGBA { R: 0.063, G: 0.082, B: 0.098, A: 1.0 };

pub struct Renderer {
    pub device: ID3D11Device,
    video_device: ID3D11VideoDevice,
    video_context: ID3D11VideoContext,
    swap_chain: IDXGISwapChain1,
    tearing: bool,
    size: (u32, u32),
    processor: Option<Processor>,
    output: Option<ID3D11VideoProcessorOutputView>,
}

/// The video processor and the views made with it, for one input and output size.
struct Processor {
    input_size: (u32, u32),
    output_size: (u32, u32),
    enumerator: ID3D11VideoProcessorEnumerator,
    processor: ID3D11VideoProcessor,
    /// By texture and slice: the decoder reuses a small set of them.
    inputs: HashMap<(usize, u32), ID3D11VideoProcessorInputView>,
}

impl Renderer {
    pub fn new(hwnd: HWND, size: (u32, u32)) -> Result<Renderer> {
        let mut device = None;
        let mut context = None;
        unsafe {
            D3D11CreateDevice(
                None,
                D3D_DRIVER_TYPE_HARDWARE,
                HMODULE::default(),
                D3D11_CREATE_DEVICE_BGRA_SUPPORT | D3D11_CREATE_DEVICE_VIDEO_SUPPORT,
                Some(&[D3D_FEATURE_LEVEL_11_0]),
                D3D11_SDK_VERSION,
                Some(&mut device),
                None,
                Some(&mut context),
            )
            .context("Couldn't start Direct3D 11")?;
        }
        let device: ID3D11Device = device.unwrap();
        let context: ID3D11DeviceContext = context.unwrap();
        let video_device: ID3D11VideoDevice = device.cast().context("The GPU has no video support")?;
        let video_context: ID3D11VideoContext = context.cast()?;

        let factory: IDXGIFactory2 = unsafe { device.cast::<IDXGIDevice>()?.GetAdapter()?.GetParent()? };
        let tearing = tearing_supported(&factory);
        let desc = DXGI_SWAP_CHAIN_DESC1 {
            Width: size.0.max(1),
            Height: size.1.max(1),
            Format: DXGI_FORMAT_B8G8R8A8_UNORM,
            SampleDesc: DXGI_SAMPLE_DESC { Count: 1, Quality: 0 },
            BufferUsage: DXGI_USAGE_RENDER_TARGET_OUTPUT,
            BufferCount: 2,
            Scaling: DXGI_SCALING_NONE,
            SwapEffect: DXGI_SWAP_EFFECT_FLIP_DISCARD,
            AlphaMode: DXGI_ALPHA_MODE_IGNORE,
            Flags: swap_chain_flags(tearing),
            ..Default::default()
        };
        let swap_chain = unsafe { factory.CreateSwapChainForHwnd(&device, hwnd, &desc, None, None)? };
        // The client toggles fullscreen itself, as a borderless window.
        unsafe { factory.MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER)? };

        Ok(Renderer {
            device,
            video_device,
            video_context,
            swap_chain,
            tearing,
            size: (desc.Width, desc.Height),
            processor: None,
            output: None,
        })
    }

    /// Draws `crop` (the top-left part of the frame that shows) to fit the window, and shows it.
    pub fn draw(&mut self, frame: Option<&Frame>, crop: (u32, u32), window: (u32, u32)) -> Result<()> {
        if window.0 == 0 || window.1 == 0 {
            // Minimised.
            return Ok(());
        }
        if window != self.size {
            self.output = None;
            unsafe {
                self.swap_chain.ResizeBuffers(
                    0,
                    window.0,
                    window.1,
                    DXGI_FORMAT_UNKNOWN,
                    DXGI_SWAP_CHAIN_FLAG(swap_chain_flags(self.tearing) as i32),
                )?;
            }
            self.size = window;
        }
        let input_size = frame.map(Frame::size).unwrap_or(crop);
        if self.processor.as_ref().is_none_or(|p| p.input_size != input_size || p.output_size != window) {
            self.output = None;
            self.processor = Some(self.processor(input_size, window)?);
        }
        let processor = self.processor.as_mut().unwrap();
        if self.output.is_none() {
            let back_buffer: ID3D11Texture2D = unsafe { self.swap_chain.GetBuffer(0)? };
            let desc = D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC {
                ViewDimension: D3D11_VPOV_DIMENSION_TEXTURE2D,
                Anonymous: D3D11_VIDEO_PROCESSOR_OUTPUT_VIEW_DESC_0 { Texture2D: D3D11_TEX2D_VPOV { MipSlice: 0 } },
            };
            let mut view = None;
            unsafe {
                self.video_device.CreateVideoProcessorOutputView(
                    &back_buffer,
                    &processor.enumerator,
                    &desc,
                    Some(&mut view),
                )?
            };
            self.output = view;
        }
        let output = self.output.as_ref().unwrap();
        let vc = &self.video_context;
        let vp = &processor.processor;
        let fit = Fit::new(crop, window);
        unsafe {
            vc.VideoProcessorSetOutputTargetRect(vp, true, Some(&rect(0, 0, window.0 as i32, window.1 as i32)));
            vc.VideoProcessorSetOutputBackgroundColor(
                vp,
                false,
                &D3D11_VIDEO_COLOR { Anonymous: D3D11_VIDEO_COLOR_0 { RGBA: BACKGROUND } },
            );
            vc.VideoProcessorSetOutputColorSpace(vp, &color_space(false, true, true));
        }
        // Nothing to show before the first frame.
        let Some(frame) = frame else { return Ok(()) };

        let key = (frame.texture().as_raw() as usize, frame.index());
        if !processor.inputs.contains_key(&key) {
            let desc = D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC {
                FourCC: 0,
                ViewDimension: D3D11_VPIV_DIMENSION_TEXTURE2D,
                Anonymous: D3D11_VIDEO_PROCESSOR_INPUT_VIEW_DESC_0 {
                    Texture2D: D3D11_TEX2D_VPIV { MipSlice: 0, ArraySlice: frame.index() },
                },
            };
            let mut view = None;
            unsafe {
                self.video_device.CreateVideoProcessorInputView(
                    frame.texture(),
                    &processor.enumerator,
                    &desc,
                    Some(&mut view),
                )?
            };
            processor.inputs.insert(key, view.unwrap());
        }
        let input = processor.inputs[&key].clone();
        unsafe {
            vc.VideoProcessorSetStreamFrameFormat(vp, 0, D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE);
            vc.VideoProcessorSetStreamColorSpace(vp, 0, &color_space(true, frame.bt709(), frame.full_range()));
            // No sharpening or noise reduction from the driver: the desktop's text should stay as it is.
            vc.VideoProcessorSetStreamAutoProcessingMode(vp, 0, false);
            vc.VideoProcessorSetStreamSourceRect(vp, 0, true, Some(&rect(0, 0, crop.0 as i32, crop.1 as i32)));
            vc.VideoProcessorSetStreamDestRect(
                vp,
                0,
                true,
                Some(&rect(fit.x, fit.y, fit.x + fit.width, fit.y + fit.height)),
            );
        }
        let mut stream = D3D11_VIDEO_PROCESSOR_STREAM {
            Enable: BOOL::from(true),
            pInputSurface: ManuallyDrop::new(Some(input)),
            ..Default::default()
        };
        let result = unsafe { vc.VideoProcessorBlt(vp, output, 0, std::slice::from_ref(&stream)) };
        unsafe { ManuallyDrop::drop(&mut stream.pInputSurface) };
        result?;
        self.present()
    }

    fn present(&self) -> Result<()> {
        let flags = if self.tearing { DXGI_PRESENT_ALLOW_TEARING } else { DXGI_PRESENT(0) };
        unsafe { self.swap_chain.Present(0, flags).ok()? };
        Ok(())
    }

    fn processor(&self, input_size: (u32, u32), output_size: (u32, u32)) -> Result<Processor> {
        let desc = D3D11_VIDEO_PROCESSOR_CONTENT_DESC {
            InputFrameFormat: D3D11_VIDEO_FRAME_FORMAT_PROGRESSIVE,
            InputFrameRate: DXGI_RATIONAL { Numerator: 60, Denominator: 1 },
            InputWidth: input_size.0,
            InputHeight: input_size.1,
            OutputFrameRate: DXGI_RATIONAL { Numerator: 60, Denominator: 1 },
            OutputWidth: output_size.0,
            OutputHeight: output_size.1,
            Usage: D3D11_VIDEO_USAGE_OPTIMAL_QUALITY,
        };
        let enumerator = unsafe { self.video_device.CreateVideoProcessorEnumerator(&desc)? };
        let processor = unsafe { self.video_device.CreateVideoProcessor(&enumerator, 0)? };
        Ok(Processor { input_size, output_size, enumerator, processor, inputs: HashMap::new() })
    }
}

fn rect(left: i32, top: i32, right: i32, bottom: i32) -> RECT {
    RECT { left, top, right, bottom }
}

/// The video processor's colour space bits: RGB_Range (bit 1, 0 = full), YCbCr_Matrix
/// (bit 2, 1 = BT.709) and Nominal_Range (bits 4-5: 1 = studio, 2 = full).
fn color_space(yuv: bool, bt709: bool, full_range: bool) -> D3D11_VIDEO_PROCESSOR_COLOR_SPACE {
    let bits = if yuv { ((bt709 as u32) << 2) | (if full_range { 2 } else { 1 } << 4) } else { 0 };
    D3D11_VIDEO_PROCESSOR_COLOR_SPACE { _bitfield: bits }
}

fn tearing_supported(factory: &IDXGIFactory2) -> bool {
    let Ok(factory) = factory.cast::<IDXGIFactory5>() else { return false };
    let mut allowed = BOOL::default();
    let ok = unsafe {
        factory.CheckFeatureSupport(
            DXGI_FEATURE_PRESENT_ALLOW_TEARING,
            &mut allowed as *mut BOOL as *mut _,
            size_of::<BOOL>() as u32,
        )
    };
    ok.is_ok() && allowed.as_bool()
}

fn swap_chain_flags(tearing: bool) -> u32 {
    if tearing { DXGI_SWAP_CHAIN_FLAG_ALLOW_TEARING.0 as u32 } else { 0 }
}
