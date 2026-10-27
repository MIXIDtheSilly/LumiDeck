//! The video: one thread reads packets off the socket, another decodes and draws them.
//! Every packet is decoded (each frame builds on the last). Frames that arrive together
//! are spaced out by their time stamps, a frame at most, so each one gets on screen.

use std::io::{self, Read, Write};
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
    /// Where to save the video as it arrives, for looking into problems.
    pub dump: Option<std::path::PathBuf>,
}

/// How often to ask again for a full frame while waiting for one.
const KEY_FRAME_RETRY: Duration = Duration::from_millis(500);

/// The longest a frame is held back to keep the frames evenly spaced: two frames' time.
/// After a longer gap (the screen was still), a frame goes up as soon as it's decoded.
const MAX_HOLD_GAP: Duration = Duration::from_millis(34);

/// How early a held frame may go up, so the spacing never makes frames later and later.
const HOLD_SLACK: Duration = Duration::from_millis(2);

/// The frame time stamp in a packet's flags, in µs.
const PTS_MASK: u64 = (1 << 62) - 1;

/// When the frame with time stamp `pts` should go up, keeping its spacing from the one
/// shown last (which went up at `shown.0` and had time stamp `shown.1`).
///
/// The device's encoder often hands over two frames at once. Drawn on arrival, the
/// first would be replaced before the screen ever showed it, and motion would look like
/// 30 frames a second. Holding the second back for its share of the time shows both.
fn due(shown: Option<(Instant, u64)>, pts: u64, now: Instant) -> Instant {
    match shown {
        Some((at, shown_pts)) if pts > shown_pts => {
            let gap = Duration::from_micros(pts - shown_pts);
            if gap > MAX_HOLD_GAP { now } else { (at + gap).checked_sub(HOLD_SLACK).unwrap_or(now) }
        }
        _ => now,
    }
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
    // The frame on screen, with when it went up and its time stamp, and the one due next.
    let mut last: Option<Frame> = None;
    let mut shown: Option<(Instant, u64)> = None;
    let mut held: Option<(Frame, u64)> = None;
    // After a decoding error, packets are skipped until the next full frame.
    let mut broken = false;
    // Packets in a row that gave no frame. Each should give one, so a few mean ffmpeg lost
    // track of the stream: it only logs a missing reference frame, it doesn't fail.
    let mut fruitless = 0;
    let mut asked = Instant::now() - KEY_FRAME_RETRY;
    let mut dump = match &options.dump {
        Some(path) => Some(std::io::BufWriter::new(std::fs::File::create(path)?)),
        None => None,
    };
    let mut stats = Stats::new(options.stats);

    loop {
        let mut redraw = false;
        // Wait for something, or for the held frame's turn, then take everything else that's queued.
        let wait = match &held {
            Some((_, pts)) => due(shown, *pts, Instant::now()).saturating_duration_since(Instant::now()),
            None => Duration::from_secs(1),
        };
        let first = match events.recv_timeout(wait) {
            Ok(event) => Some(event),
            Err(RecvTimeoutError::Timeout) => None,
            Err(RecvTimeoutError::Disconnected) => return Ok(()),
        };
        for event in first.into_iter().chain(events.try_iter()) {
            match event {
                Event::Packet { flags, data } => {
                    if let Some(dump) = &mut dump {
                        dump.write_all(&data)?;
                    }
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
                    // Every full frame gets the stream's parameters in front, so a fresh
                    // decoder can start from it.
                    let result = if key && !config.is_empty() {
                        let mut joined = config.clone();
                        joined.extend_from_slice(&data);
                        decoder.send(&joined)
                    } else {
                        decoder.send(&data)
                    };
                    let mut failed = result.as_ref().err().map(ToString::to_string);
                    let mut got = false;
                    while failed.is_none() {
                        match decoder.receive() {
                            Ok(Some(frame)) => {
                                got = true;
                                stats.decoded(started.elapsed());
                                // A frame still held when the next one is decoded is late
                                // already: up it goes, and the new one waits its turn after it.
                                if let Some((frame, pts)) = held.take() {
                                    let drawing = Instant::now();
                                    renderer.draw(Some(&frame), crop, size.get())?;
                                    stats.drawn(drawing.elapsed());
                                    last = Some(frame);
                                    shown = Some((Instant::now(), pts));
                                }
                                held = Some((frame, flags & PTS_MASK));
                            }
                            Ok(None) => break,
                            Err(e) => failed = Some(e.to_string()),
                        }
                    }
                    fruitless = if got { 0 } else { fruitless + 1 };
                    if failed.is_none() && fruitless >= 3 {
                        failed = Some("Frames stopped coming out of the decoder".into());
                    }
                    if let Some(reason) = failed {
                        // Start over from the next full frame, with a fresh decoder.
                        eprintln!("{reason}; asking the device for a full frame");
                        broken = true;
                        fruitless = 0;
                        decoder = Decoder::new(header.hevc, &renderer.device)?;
                        if asked.elapsed() >= KEY_FRAME_RETRY {
                            let _ = controls.send(Control::KeyFrame);
                            asked = Instant::now();
                        }
                    }
                }
                Event::Redraw => redraw = true,
                Event::Closed(e) => return Err(e.into()),
            }
        }
        let now = Instant::now();
        if let Some((_, pts)) = &held
            && due(shown, *pts, now) <= now
        {
            let (frame, pts) = held.take().unwrap();
            renderer.draw(Some(&frame), crop, size.get())?;
            stats.drawn(now.elapsed());
            last = Some(frame);
            shown = Some((Instant::now(), pts));
        } else if redraw {
            renderer.draw(last.as_ref(), crop, size.get())?;
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
    /// Time between frames going up, in ms.
    gaps: Vec<f64>,
    last_drawn: Option<Instant>,
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
            gaps: Vec::new(),
            last_drawn: None,
        }
    }

    fn decoded(&mut self, time: Duration) {
        self.decoded += 1;
        self.decode_time += time;
    }

    fn drawn(&mut self, time: Duration) {
        self.drawn += 1;
        self.draw_time += time;
        let now = Instant::now();
        if let Some(last) = self.last_drawn.replace(now) {
            self.gaps.push((now - last).as_secs_f64() * 1000.0);
        }
        let elapsed = self.since.elapsed();
        if !self.on || elapsed < Duration::from_secs(5) {
            return;
        }
        let ms = |d: Duration, n: u32| d.as_secs_f64() * 1000.0 / n.max(1) as f64;
        self.gaps.sort_by(f64::total_cmp);
        let gap = |p: usize| self.gaps.get(self.gaps.len() * p / 100).copied().unwrap_or(0.0);
        println!(
            "PC: {:.0} frames/s decoded in {:.1} ms, {:.0} drawn in {:.1} ms, {:.1} ms apart (95% within {:.1})",
            self.decoded as f64 / elapsed.as_secs_f64(),
            ms(self.decode_time, self.decoded),
            self.drawn as f64 / elapsed.as_secs_f64(),
            ms(self.draw_time, self.drawn),
            gap(50),
            gap(95),
        );
        *self = Stats { last_drawn: self.last_drawn, ..Stats::new(true) };
    }
}
