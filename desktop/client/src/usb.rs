//! The connection to the device straight over USB, without ADB in between: Android's
//! accessory mode (AOA). The device side is android/…/server/UsbLink.kt.
//!
//! The PC asks the device to switch its USB over ([switch]), and it comes back as Google's
//! accessory device, with ADB still alongside, until it's unplugged. The stream server opens
//! the accessory and everything goes through its one pipe each way. To the PC, each message
//! goes after its channel ([VIDEO] or [AUDIO], a byte) and its size (a big-endian u32), and
//! each channel carries what its own adb connection would (see protocol.rs). Empty messages
//! on another channel only end USB transfers. The video
//! channel starts with [HELLO] and the session the client gave the server, so whatever an
//! earlier session left in the pipe is skipped. To the device go the controls.
//!
//! Windows needs WinUSB on the accessory interface, set up once (see usb_setup.rs).

use std::io::{self, Read, Write};
use std::sync::mpsc::{self, Receiver, Sender};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};
use futures_lite::future::block_on;
use nusb::transfer::{ControlIn, ControlOut, ControlType, Direction, EndpointType, Recipient, RequestBuffer};
use nusb::{DeviceInfo, Interface};

use crate::adb::Adb;
use crate::protocol::{Control, HELLO};

pub const VIDEO: u8 = 0;
pub const AUDIO: u8 = 1;

/// Google's vendor ID, which accessories come back with.
pub const GOOGLE: u16 = 0x18d1;
/// The accessory alone, and with ADB.
pub const ACCESSORY: [u16; 2] = [0x2d00, 0x2d01];
/// The accessory's interface: the first.
pub const INTERFACE: u8 = 0;

/// What the PC calls itself to the device. The server checks it (UsbLink.kt).
const MANUFACTURER: &str = "LumiDeck";
const MODEL: &str = "LumiDeck stream";

// The AOA requests.
const GET_PROTOCOL: u8 = 51;
const SEND_STRING: u8 = 52;
const START: u8 = 53;

/// Transfers waiting for the device at once, and how much each takes. A transfer ends at
/// the end of each message, so they only need to be big enough for the largest.
const TRANSFERS: usize = 4;
const TRANSFER_SIZE: usize = 1 << 20;

fn find(serial: &str, accessory: bool) -> Result<Option<DeviceInfo>> {
    Ok(nusb::list_devices()?.find(|device| {
        device.serial_number() == Some(serial)
            && (device.vendor_id() == GOOGLE && ACCESSORY.contains(&device.product_id())) == accessory
    }))
}

/// Whether the device's USB is switched over already.
pub fn switched(serial: &str) -> Result<bool> {
    Ok(find(serial, true)?.is_some())
}

/// Switches the device's USB over to accessory mode, which lasts until it's unplugged.
pub fn switch(adb: &Adb) -> Result<()> {
    if switched(&adb.serial)? {
        return Ok(());
    }
    println!("Switching the device's USB over to LumiDeck");
    // Windows lets one program at a time talk to a USB device, and ADB's server does.
    adb.kill_server()?;
    let result = ask_to_switch(&adb.serial);
    adb.start_server()?;
    result?;
    let deadline = Instant::now() + Duration::from_secs(10);
    while !switched(&adb.serial)? {
        if Instant::now() > deadline {
            bail!("The device didn't come back from switching its USB over");
        }
        thread::sleep(Duration::from_millis(200));
    }
    adb.wait_for_device()
}

fn ask_to_switch(serial: &str) -> Result<()> {
    let info = find(serial, false)?.context("The device isn't on USB")?;
    let device = info.open()?;
    // Any interface WinUSB drives will do, as the requests go to the device itself.
    let interface = info
        .interfaces()
        .find_map(|i| device.claim_interface(i.interface_number()).ok())
        .context("Couldn't reach the device over USB")?;
    let version = block_on(interface.control_in(ControlIn {
        control_type: ControlType::Vendor,
        recipient: Recipient::Device,
        request: GET_PROTOCOL,
        value: 0,
        index: 0,
        length: 2,
    }))
    .into_result()?;
    if version.len() < 2 || version == [0, 0] {
        bail!("The device can't be a USB accessory");
    }
    let send = |request, index, data: &[u8]| {
        block_on(interface.control_out(ControlOut {
            control_type: ControlType::Vendor,
            recipient: Recipient::Device,
            request,
            value: 0,
            index,
            data,
        }))
        .into_result()
    };
    // Manufacturer, model, description, version, web page, serial. With no web page
    // Android shows nothing when no app takes the accessory.
    let strings = [MANUFACTURER, MODEL, "LumiDeck for PCs", "1", "", "lumideck"];
    for (index, string) in strings.iter().enumerate() {
        send(SEND_STRING, index as u16, format!("{string}\0").as_bytes())?;
    }
    send(START, 0, &[])?;
    Ok(())
}

/// The streams from the server, and the controls to it.
pub struct Link {
    pub video: Channel,
    pub audio: Channel,
    pub controls: Controls,
}

/// Opens the accessory and waits for the server started with `session` to say hello.
pub fn connect(serial: &str, session: &str) -> Result<Link> {
    let info = find(serial, true)?.context("The device's USB isn't switched over")?;
    let device = info.open()?;
    let interface = device.claim_interface(INTERFACE).map_err(|e| {
        anyhow!("Couldn't open LumiDeck's USB connection ({e}). Set it up first: desktop\\lumideck.ps1 -SetupUsb")
    })?;
    let setting = interface.descriptors().next().context("The accessory has no interface")?;
    let endpoint = |direction| {
        setting
            .endpoints()
            .find(|e| e.transfer_type() == EndpointType::Bulk && e.direction() == direction)
            .map(|e| e.address())
            .context("The accessory has no pipe")
    };
    let (inbound, outbound) = (endpoint(Direction::In)?, endpoint(Direction::Out)?);

    let (video, video_queue) = mpsc::channel();
    let (audio, audio_queue) = mpsc::channel();
    let (hello, said_hello) = mpsc::channel();
    let reader = Reader { video, audio, hello: Some(hello), session: session.as_bytes().to_vec() };
    let input = interface.clone();
    thread::Builder::new().name("usb in".into()).spawn(move || reader.run(input, inbound))?;
    match said_hello.recv_timeout(Duration::from_secs(15)) {
        Ok(result) => result?,
        Err(_) => bail!("The server didn't start"),
    }
    Ok(Link {
        video: Channel::new(video_queue),
        audio: Channel::new(audio_queue),
        controls: Controls { interface, endpoint: outbound },
    })
}

struct Reader {
    video: Sender<io::Result<Vec<u8>>>,
    audio: Sender<io::Result<Vec<u8>>>,
    /// Until the server says hello: told whether it did.
    hello: Option<Sender<Result<()>>>,
    session: Vec<u8>,
}

impl Reader {
    fn run(mut self, interface: Interface, endpoint: u8) {
        let mut queue = interface.bulk_in_queue(endpoint);
        for _ in 0..TRANSFERS {
            queue.submit(RequestBuffer::new(TRANSFER_SIZE));
        }
        // What came in and hasn't gone out on a channel yet, from `start`.
        let mut pending = Vec::new();
        let mut start = 0;
        let error = loop {
            let completion = block_on(queue.next_complete());
            if let Err(e) = completion.status {
                break io::Error::other(format!("USB: {e}"));
            }
            pending.extend_from_slice(&completion.data);
            queue.submit(RequestBuffer::reuse(completion.data, TRANSFER_SIZE));
            if self.hello.is_some() {
                match self.find_hello(&pending) {
                    Ok(Some(end)) => {
                        start = end;
                        let _ = self.hello.take().unwrap().send(Ok(()));
                    }
                    Ok(None) => {
                        // Keeps the end, in case the hello is cut in two.
                        let keep = pending.len().saturating_sub(64);
                        pending.drain(..keep);
                        continue;
                    }
                    Err(e) => {
                        let _ = self.hello.take().unwrap().send(Err(e));
                        return;
                    }
                }
            }
            while let Some((channel, size)) = header(&pending[start..]) {
                let end = start + 5 + size;
                if pending.len() < end {
                    break;
                }
                let message = pending[start + 5..end].to_vec();
                match channel {
                    VIDEO => {
                        if self.video.send(Ok(message)).is_err() {
                            return;
                        }
                    }
                    // The sound may not be wanted.
                    AUDIO => drop(self.audio.send(Ok(message))),
                    _ => {}
                }
                start = end;
            }
            pending.drain(..start);
            start = 0;
        };
        if let Some(hello) = self.hello.take() {
            let _ = hello.send(Err(error.into()));
            return;
        }
        let _ = self.audio.send(Err(io::Error::new(error.kind(), error.to_string())));
        let _ = self.video.send(Err(error));
    }

    /// Where the messages start after this session's hello, if it's in `data`.
    fn find_hello(&self, data: &[u8]) -> Result<Option<usize>> {
        // The hello's channel and size, then all of HELLO but the version.
        let size = (HELLO.len() + self.session.len()) as u32;
        let mut prefix = vec![VIDEO];
        prefix.extend_from_slice(&size.to_be_bytes());
        prefix.extend_from_slice(&HELLO[..HELLO.len() - 1]);
        let whole = prefix.len() + 1 + self.session.len();
        for at in 0..data.len().saturating_sub(whole - 1) {
            if data[at..].starts_with(&prefix) && data[at + prefix.len() + 1..].starts_with(&self.session) {
                if data[at + prefix.len()] != HELLO[HELLO.len() - 1] {
                    bail!("The server on the device is a different version. Update LumiDeck on the device.");
                }
                return Ok(Some(at + whole));
            }
        }
        Ok(None)
    }
}

/// A message's channel and size, if its header is all there.
fn header(data: &[u8]) -> Option<(u8, usize)> {
    let header = data.get(..5)?;
    Some((header[0], u32::from_be_bytes(header[1..].try_into().unwrap()) as usize))
}

/// One of the server's streams, as it would come over its own connection.
pub struct Channel {
    queue: Receiver<io::Result<Vec<u8>>>,
    data: Vec<u8>,
    at: usize,
}

impl Channel {
    fn new(queue: Receiver<io::Result<Vec<u8>>>) -> Channel {
        Channel { queue, data: Vec::new(), at: 0 }
    }
}

impl Read for Channel {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if self.at == self.data.len() {
            match self.queue.recv() {
                Ok(data) => {
                    self.data = data?;
                    self.at = 0;
                }
                Err(_) => return Ok(0),
            }
        }
        let n = buf.len().min(self.data.len() - self.at);
        buf[..n].copy_from_slice(&self.data[self.at..self.at + n]);
        self.at += n;
        Ok(n)
    }
}

/// Where the controls go.
pub struct Controls {
    interface: Interface,
    endpoint: u8,
}

impl Write for Controls {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        let mut data = buf.to_vec();
        // A transfer that fills its last packet (512 bytes or 1024) might wait on the device
        // for the next one: a ping makes it shorter.
        if data.len().is_multiple_of(512) {
            Control::Ping.encode(&mut data);
        }
        block_on(self.interface.bulk_out(self.endpoint, data)).into_result().map_err(io::Error::other)?;
        Ok(buf.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
