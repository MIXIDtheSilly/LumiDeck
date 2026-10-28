//! What the client and the server on the device (android/…/server) send each other.
//!
//! The client starts the server over ADB and reaches it through `adb forward` on the
//! abstract socket [SOCKET]. It opens two connections, in order:
//!
//! 1. **Video and controls.** The server sends [HELLO], then the video header
//!    (codec, width, height: three big-endian u32s), then one packet per encoded frame:
//!    a u64 of flags ([FLAG_CONFIG], [FLAG_KEY_FRAME], [FLAG_COPY]) and the frame's
//!    time in µs, a u32 size, and the data. A packet with all three flags ([CLIPBOARD]) is
//!    the device's clipboard text instead, in UTF-8. The other way, the client sends the
//!    [Control] messages.
//! 2. **Sound** (only when asked for). [HELLO], the sample rate and channel count (two
//!    u32s), then chunks of 16-bit little-endian PCM, each after its u32 size.
//!
//! Numbers are big-endian, apart from the PCM samples. adb accepts a connection even
//! before the server listens, and then closes it: hence the hello.

pub const SOCKET: &str = "localabstract:lumideck";
pub const HELLO: &[u8; 10] = b"LumiDeck\x00\x03";

pub const CODEC_H264: u32 = 0x6832_3634;
pub const CODEC_HEVC: u32 = 0x6832_3635;
pub const FLAG_CONFIG: u64 = 1 << 63;
pub const FLAG_KEY_FRAME: u64 = 1 << 62;
/// A repeat of the frame before it, which the device's encoder needed to let that frame
/// out: decoded, since later frames build on it, but not shown.
pub const FLAG_COPY: u64 = 1 << 61;
/// Not a frame: the device's clipboard changed (see clipboard.rs).
pub const CLIPBOARD: u64 = FLAG_CONFIG | FLAG_KEY_FRAME | FLAG_COPY;

/// Mouse buttons, as the server numbers them.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Button {
    Primary = 1,
    Secondary = 2,
    Middle = 3,
    Back = 4,
    Forward = 5,
}

/// The client's messages to the server, with display coordinates in pixels.
#[derive(Clone, Debug)]
pub enum Control {
    MouseMove {
        x: i32,
        y: i32,
    },
    MouseButton {
        button: Button,
        down: bool,
        x: i32,
        y: i32,
    },
    Scroll {
        x: i32,
        y: i32,
        horizontal: f32,
        vertical: f32,
    },
    /// The pointer left the window.
    MouseLeave,
    /// A key went down or up, by its USB (HID) usage code.
    Key {
        usage: u8,
        down: bool,
    },
    /// Every key up, as when the window loses focus.
    ReleaseKeys,
    /// Asks for a full frame, to decode from after an error.
    KeyFrame,
    /// Nothing: sent when there's been nothing else for a while, so the device knows the PC
    /// is still there. Over USB nothing else would tell it.
    Ping,
    /// The PC's clipboard text, for the device's.
    Clipboard(String),
}

impl Control {
    pub fn encode(&self, out: &mut Vec<u8>) {
        match *self {
            Control::Clipboard(ref text) => {
                out.push(9);
                out.extend_from_slice(&(text.len() as u32).to_be_bytes());
                out.extend_from_slice(text.as_bytes());
            }
            Control::MouseMove { x, y } => {
                out.push(1);
                out.extend_from_slice(&x.to_be_bytes());
                out.extend_from_slice(&y.to_be_bytes());
            }
            Control::MouseButton { button, down, x, y } => {
                out.push(2);
                out.push(button as u8);
                out.push(down as u8);
                out.extend_from_slice(&x.to_be_bytes());
                out.extend_from_slice(&y.to_be_bytes());
            }
            Control::Scroll { x, y, horizontal, vertical } => {
                out.push(3);
                out.extend_from_slice(&x.to_be_bytes());
                out.extend_from_slice(&y.to_be_bytes());
                out.extend_from_slice(&horizontal.to_be_bytes());
                out.extend_from_slice(&vertical.to_be_bytes());
            }
            Control::MouseLeave => out.push(4),
            Control::Key { usage, down } => {
                out.push(5);
                out.push(usage);
                out.push(down as u8);
            }
            Control::ReleaseKeys => out.push(6),
            Control::KeyFrame => out.push(7),
            Control::Ping => out.push(8),
        }
    }
}
