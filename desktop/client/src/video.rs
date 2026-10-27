//! The video: one thread reads packets off the socket, another decodes and draws them.
//! When packets arrive faster than they can be drawn, every one is still decoded (each
//! frame builds on the last) but only the newest is drawn.

use std::io::{self, Read};
use std::net::TcpStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError, Sender};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Result, bail};
use windows::Win32::Foundation::HWND;

use crate::decoder::{Decoder, Frame};
use crate::protocol::{CODEC_H264, CODEC_HEVC, Control, FLAG_CONFIG, FLAG_KEY_FRAME};
use crate::render::Renderer;

pub struct Header {
    pub hevc: bool,
    pub width: u32,
    pub height: u32,
}

impl Header {
    pub fn read(stream: &mut TcpStream) -> Result<Header> {
        let mut bytes = [0u8; 12];
        stream.read_exact(&mut bytes)?;
        let word = |i: usize| u32::from_be_bytes(bytes[i..i + 4].try_into().unwrap());
        let hevc = match word(0) {
            CODEC_HEVC => true,
            CODEC_H264 => false,
            other => bail!("Unknown video codec {other:#x}"),
        };
        Ok(Header { hevc, width: word(4), height: word(8) })
    }
}

pub enum Event {
    Packet {
        flags: u64,
        data: Vec<u8>,
    },
    /// The window changed size, or needs drawing again.
    Redraw,
    Closed(io::Error),
}

/// The window's size in pixels, shared with the window's thread: width in the high half.
#[derive(Default)]
pub struct WindowSize(AtomicU64);

impl WindowSize {
    pub fn set(&self, width: u32, height: u32) {
        self.0.store(((width as u64) << 32) | height as u64, Ordering::Relaxed);
    }

    pub fn get(&self) -> (u32, u32) {
        let v = self.0.load(Ordering::Relaxed);
        ((v >> 32) as u32, v as u32)
    }
}

pub fn spawn_reader(mut stream: TcpStream, events: Sender<Event>) {
    thread::Builder::new()
        .name("video in".into())
        .spawn(move || {
            let error = loop {
                let mut header = [0u8; 12];
                if let Err(e) = stream.read_exact(&mut header) {
                    break e;
                }
                let flags = u64::from_be_bytes(header[..8].try_into().unwrap());
                let size = u32::from_be_bytes(header[8..].try_into().unwrap()) as usize;
                let mut data = vec![0u8; size];
                if let Err(e) = stream.read_exact(&mut data) {
                    break e;
                }
                if events.send(Event::Packet { flags, data }).is_err() {
                    return;
                }
            };
            let _ = events.send(Event::Closed(error));
        })
        .unwrap();
}

pub struct Options {
    pub hwnd: isize,
    pub header: Header,
    /// The part of the frame that shows: the device draws its navigation bar below it.
    pub crop: (u32, u32),
    pub stats: bool,
}

/// Draws until the server goes away. Returns why.
pub fn run(options: Options, events: Receiver<Event>, size: Arc<WindowSize>, controls: Sender<Control>) -> Result<()> {
    let hwnd = HWND(options.hwnd as *mut _);
    let header = &options.header;
    // Never more than the device sends.
    let crop = (options.crop.0.min(header.width), options.crop.1.min(header.height));
    let mut renderer = Renderer::new(hwnd, size.get())?;
    let mut decoder = Decoder::new(header.hevc, &renderer.device)?;
    let mut config: Vec<u8> = Vec::new();
    let mut last: Option<Frame> = None;
    // After a decoding error, packets are skipped until the next full frame.
    let mut broken = false;
    let mut stats = Stats::new(options.stats);

    loop {
        let mut redraw = false;
        let mut new_frame = false;
        // Wait for something, then take everything else that's queued.
        let first = match events.recv_timeout(Duration::from_secs(1)) {
            Ok(event) => event,
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => return Ok(()),
        };
        for event in std::iter::once(first).chain(events.try_iter()) {
            match event {
                Event::Packet { flags, data } => {
                    if flags & FLAG_CONFIG != 0 {
                        config = data;
                        continue;
                    }
                    let key = flags & FLAG_KEY_FRAME != 0;
                    if broken && !key {
                        continue;
                    }
                    broken = false;
                    let started = Instant::now();
                    let result = if config.is_empty() {
                        decoder.send(&data)
                    } else {
                        let mut joined = std::mem::take(&mut config);
                        joined.extend_from_slice(&data);
                        decoder.send(&joined)
                    };
                    if let Err(e) = result {
                        eprintln!("{e}; waiting for a full frame");
                        broken = true;
                        let _ = controls.send(Control::KeyFrame);
                        continue;
                    }
                    while let Some(frame) = decoder.receive()? {
                        last = Some(frame);
                        new_frame = true;
                        stats.decoded(started.elapsed());
                    }
                }
                Event::Redraw => redraw = true,
                Event::Closed(e) => return Err(e.into()),
            }
        }
        if new_frame || redraw {
            let started = Instant::now();
            renderer.draw(last.as_ref(), crop, size.get())?;
            if new_frame {
                stats.drawn(started.elapsed());
            }
        }
    }
}

/// Prints how decoding and drawing keep up, every few seconds, when asked to.
struct Stats {
    on: bool,
    since: Instant,
    decoded: u32,
    drawn: u32,
    decode_time: Duration,
    draw_time: Duration,
}

impl Stats {
    fn new(on: bool) -> Stats {
        Stats {
            on,
            since: Instant::now(),
            decoded: 0,
            drawn: 0,
            decode_time: Duration::ZERO,
            draw_time: Duration::ZERO,
        }
    }

    fn decoded(&mut self, time: Duration) {
        self.decoded += 1;
        self.decode_time += time;
    }

    fn drawn(&mut self, time: Duration) {
        self.drawn += 1;
        self.draw_time += time;
        let elapsed = self.since.elapsed();
        if !self.on || elapsed < Duration::from_secs(5) {
            return;
        }
        let ms = |d: Duration, n: u32| d.as_secs_f64() * 1000.0 / n.max(1) as f64;
        println!(
            "PC: {:.0} frames/s decoded in {:.1} ms, {:.0} drawn in {:.1} ms",
            self.decoded as f64 / elapsed.as_secs_f64(),
            ms(self.decode_time, self.decoded),
            self.drawn as f64 / elapsed.as_secs_f64(),
            ms(self.draw_time, self.drawn),
        );
        *self = Stats::new(true);
    }
}
