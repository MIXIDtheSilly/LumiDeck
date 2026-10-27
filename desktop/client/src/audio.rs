//! Plays the device's sound. It arrives as 48 kHz stereo PCM; a short buffer smooths out
//! USB's timing, and is trimmed if it grows, so the sound never lags the picture by much.

use std::collections::VecDeque;
use std::io::Read;
use std::net::TcpStream;
use std::sync::{Arc, Mutex};
use std::thread;

use anyhow::{Context, Result, anyhow};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SizedSample};

/// Sound is held back this long before playing starts (and again after running dry).
const START_MS: usize = 40;
/// More than this queued and it's cut back to [START_MS].
const MAX_MS: usize = 120;

struct Queue {
    frames: VecDeque<[f32; 2]>,
    rate: usize,
    playing: bool,
}

pub fn spawn(stream: TcpStream) {
    thread::Builder::new()
        .name("sound".into())
        .spawn(move || {
            if let Err(e) = run(stream) {
                eprintln!("No sound: {e:#}");
            }
        })
        .unwrap();
}

fn run(mut socket: TcpStream) -> Result<()> {
    let mut header = [0u8; 8];
    socket.read_exact(&mut header)?;
    let rate = u32::from_be_bytes(header[..4].try_into().unwrap()) as usize;
    let channels = u32::from_be_bytes(header[4..].try_into().unwrap());
    if channels != 2 {
        return Err(anyhow!("{channels} sound channels"));
    }

    let queue = Arc::new(Mutex::new(Queue { frames: VecDeque::new(), rate, playing: false }));
    let device = cpal::default_host().default_output_device().context("No sound output")?;
    let config = device.default_output_config()?;
    let format = config.sample_format();
    let config: cpal::StreamConfig = config.into();
    let output = match format {
        cpal::SampleFormat::F32 => build::<f32>(&device, &config, queue.clone())?,
        cpal::SampleFormat::I16 => build::<i16>(&device, &config, queue.clone())?,
        cpal::SampleFormat::I32 => build::<i32>(&device, &config, queue.clone())?,
        cpal::SampleFormat::U16 => build::<u16>(&device, &config, queue.clone())?,
        other => return Err(anyhow!("Unsupported sound format {other}")),
    };
    output.play()?;

    let mut chunk = Vec::new();
    loop {
        let mut size = [0u8; 4];
        if socket.read_exact(&mut size).is_err() {
            return Ok(());
        }
        chunk.resize(u32::from_be_bytes(size) as usize, 0);
        socket.read_exact(&mut chunk)?;
        let mut queue = queue.lock().unwrap();
        for frame in chunk.as_chunks::<4>().0 {
            let left = i16::from_le_bytes([frame[0], frame[1]]) as f32 / 32768.0;
            let right = i16::from_le_bytes([frame[2], frame[3]]) as f32 / 32768.0;
            queue.frames.push_back([left, right]);
        }
        let max = queue.rate * MAX_MS / 1000;
        if queue.frames.len() > max {
            let keep = queue.rate * START_MS / 1000;
            let drop = queue.frames.len() - keep;
            queue.frames.drain(..drop);
        }
    }
}

/// An output stream at the device's own rate and channel count, resampling as it goes.
fn build<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: &cpal::StreamConfig,
    queue: Arc<Mutex<Queue>>,
) -> Result<cpal::Stream> {
    let channels = config.channels as usize;
    let out_rate = config.sample_rate.0 as f64;
    // Where playback is between the last frame taken and the next.
    let mut position = 0.0f64;
    let mut previous = [0.0f32; 2];
    let mut next = [0.0f32; 2];
    let stream = device.build_output_stream(
        config,
        move |data: &mut [T], _| {
            let mut queue = queue.lock().unwrap();
            let step = queue.rate as f64 / out_rate;
            if !queue.playing && queue.frames.len() >= queue.rate * START_MS / 1000 {
                queue.playing = true;
            }
            for frame in data.chunks_mut(channels) {
                let sample = if queue.playing {
                    position += step;
                    while position >= 1.0 {
                        position -= 1.0;
                        previous = next;
                        match queue.frames.pop_front() {
                            Some(f) => next = f,
                            None => {
                                // Ran dry: wait for the buffer to fill again.
                                queue.playing = false;
                                next = [0.0; 2];
                                break;
                            }
                        }
                    }
                    let t = position as f32;
                    [previous[0] + (next[0] - previous[0]) * t, previous[1] + (next[1] - previous[1]) * t]
                } else {
                    [0.0; 2]
                };
                for (i, out) in frame.iter_mut().enumerate() {
                    // Left and right on the first two speakers; nothing on the rest.
                    let value = if i < 2 { sample[i] } else { 0.0 };
                    *out = T::from_sample(value);
                }
            }
        },
        |e| eprintln!("Sound: {e}"),
        None,
    )?;
    Ok(stream)
}
