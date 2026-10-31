//! Files dropped on the window go to the device's Download folder, through ADB, one at a
//! time on their own thread. How it's going shows in the window's title.

use std::path::PathBuf;
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::thread;
use std::time::Duration;

use crate::adb::Adb;

/// Where dropped files go on the device.
const FOLDER: &str = "/sdcard/Download/";
/// How long the title says how it went.
const SHOW_FOR: Duration = Duration::from_secs(4);

/// Starts the thread that copies dropped files. `status` gets what the title should say
/// after "LumiDeck", or None when it's done saying it.
pub fn spawn(adb: Adb, status: impl Fn(Option<String>) + Send + 'static) -> Sender<PathBuf> {
    let (files, queue) = mpsc::channel::<PathBuf>();
    thread::Builder::new()
        .name("files".into())
        .spawn(move || {
            let mut copied = 0;
            let mut failed = Vec::new();
            loop {
                let file = match queue.recv_timeout(SHOW_FOR) {
                    Ok(file) => file,
                    Err(RecvTimeoutError::Timeout) => {
                        if copied > 0 || !failed.is_empty() {
                            copied = 0;
                            failed.clear();
                            status(None);
                        }
                        continue;
                    }
                    Err(RecvTimeoutError::Disconnected) => return,
                };
                let name = file.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
                status(Some(format!("Copying {name} to the device…")));
                let pushed = adb.command().arg("push").arg(&file).arg(FOLDER).output();
                match pushed {
                    Ok(output) if output.status.success() => copied += 1,
                    Ok(output) => {
                        eprintln!(
                            "Couldn't copy {}: {}",
                            file.display(),
                            String::from_utf8_lossy(&output.stderr).trim()
                        );
                        failed.push(name);
                    }
                    Err(e) => {
                        eprintln!("Couldn't copy {}: {e}", file.display());
                        failed.push(name);
                    }
                }
                status(Some(match (copied, failed.as_slice()) {
                    (_, [one]) => format!("Couldn't copy {one}"),
                    (_, [_, ..]) => format!("Couldn't copy {} files", failed.len()),
                    (1, []) => "Copied to Download on the device".to_string(),
                    (n, []) => format!("Copied {n} files to Download on the device"),
                }));
            }
        })
        .unwrap();
    files
}
