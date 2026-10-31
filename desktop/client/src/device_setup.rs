//! Setting the device up for LumiDeck, which the LumiDeck app does in one click: install
//! the app on it (lumideck.apk, next to this program), turn on Android's desktop mode,
//! make LumiDeck the home app and allow what only ADB can. What
//! scripts/setup-device.ps1 does for a build from source.

use std::path::PathBuf;

use anyhow::{Context, Result, bail};
use windows::Win32::Security::Cryptography::{BCRYPT_SHA256_ALG_HANDLE, BCryptHash};

use crate::adb::{Adb, PACKAGE};

/// Android's developer options for desktop mode: freeform windows, every app resizable,
/// and the desktop on external displays. They take effect after a restart.
const DESKTOP_MODE: [&str; 3] =
    ["enable_freeform_support", "force_resizable_activities", "force_desktop_mode_on_external_displays"];
/// Set (until the device restarts) when setup changed something that needs a restart.
const RESTART_PROP: &str = "debug.lumideck.restart";

/// How far along the device is, as `--device-status` prints it.
#[derive(Debug, PartialEq, Eq)]
pub enum Status {
    /// Older than Android 10.
    Unsupported,
    /// LumiDeck isn't on it.
    Install,
    /// It has a different LumiDeck from the one next to this program.
    Update,
    /// Desktop mode is off, or LumiDeck isn't the home app.
    Setup,
    /// Set up, but it needs restarting once first.
    Restart,
    Ready,
}

impl Status {
    pub fn name(&self) -> &'static str {
        match self {
            Status::Unsupported => "unsupported",
            Status::Install => "install",
            Status::Update => "update",
            Status::Setup => "setup",
            Status::Restart => "restart",
            Status::Ready => "ready",
        }
    }
}

pub fn status(adb: &Adb) -> Result<Status> {
    if sdk(adb)? < 29 {
        return Ok(Status::Unsupported);
    }
    let Some(installed) = adb.installed_apk()? else { return Ok(Status::Install) };
    if let Some(bundled) = bundled_apk()
        && device_hash(adb, &installed)? != file_hash(&bundled)?
    {
        return Ok(Status::Update);
    }
    if !desktop_mode_on(adb)? || adb.home_holder()?.as_deref() != Some(PACKAGE) {
        return Ok(Status::Setup);
    }
    if adb.shell(&format!("getprop {RESTART_PROP}"))?.trim() == "1" {
        return Ok(Status::Restart);
    }
    Ok(Status::Ready)
}

/// Does whatever setup the device still needs, saying what as it goes.
pub fn run(adb: &Adb) -> Result<()> {
    let sdk = sdk(adb)?;
    if sdk < 29 {
        bail!("LumiDeck needs Android 10 or newer (this device has API {sdk}).");
    }
    let installed = adb.installed_apk()?;
    let first = installed.is_none();
    match (bundled_apk(), &installed) {
        (Some(apk), Some(on_device)) if device_hash(adb, on_device)? == file_hash(&apk)? => {}
        (Some(apk), _) => {
            println!("{} LumiDeck on the device…", if first { "Installing" } else { "Updating" });
            let output = adb.command().arg("install").arg("-r").arg(&apk).output().context("Couldn't run adb")?;
            let said = String::from_utf8_lossy(&output.stdout);
            if !output.status.success() || !said.contains("Success") {
                let err = String::from_utf8_lossy(&output.stderr);
                bail!("Installing LumiDeck failed: {}", format!("{said} {err}").trim());
            }
            // Ahead of time, so the desktop is quick from the start: the device would
            // otherwise compile it only while idle, and Compose is slow until then.
            println!("Getting it ready to run fast…");
            adb.shell(&format!("cmd package compile -m speed -f {PACKAGE}"))?;
        }
        (None, None) => bail!("lumideck.apk isn't next to lumideck.exe, so there's no LumiDeck to install."),
        (None, Some(_)) => {}
    }

    println!("Turning on desktop mode…");
    let mut restart = false;
    for setting in DESKTOP_MODE {
        if adb.shell(&format!("settings get global {setting}"))?.trim() != "1" {
            adb.shell(&format!("settings put global {setting} 1"))?;
            restart = true;
        }
    }
    restart |= adb.prepare_device()?;
    println!("Making LumiDeck the home app…");
    adb.shell(&format!("cmd role add-role-holder android.app.role.HOME {PACKAGE}"))?;
    if restart {
        adb.shell(&format!("setprop {RESTART_PROP} 1"))?;
    }
    if first {
        // Where the user picks the launcher for the device's own screen.
        adb.shell(&format!(
            "am start -n {PACKAGE}/.HomeActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER"
        ))?;
        println!("On the device, pick the launcher its own screen should keep using.");
    }
    if restart {
        println!("Done. Restart the device once so desktop mode takes effect.");
    } else {
        println!("Done.");
    }
    Ok(())
}

fn sdk(adb: &Adb) -> Result<u32> {
    adb.shell("getprop ro.build.version.sdk")?.trim().parse().context("The device didn't say its Android version")
}

fn desktop_mode_on(adb: &Adb) -> Result<bool> {
    let script: Vec<String> = DESKTOP_MODE.iter().map(|s| format!("settings get global {s}")).collect();
    Ok(adb.shell(&script.join("; "))?.lines().all(|line| line.trim() == "1"))
}

/// The LumiDeck that comes with this program, to install.
fn bundled_apk() -> Option<PathBuf> {
    let apk = std::env::current_exe().ok()?.with_file_name("lumideck.apk");
    apk.is_file().then_some(apk)
}

fn device_hash(adb: &Adb, path: &str) -> Result<String> {
    let output = adb.shell(&format!("sha256sum '{path}'"))?;
    Ok(output.split_whitespace().next().context("The device didn't hash LumiDeck")?.to_lowercase())
}

fn file_hash(path: &PathBuf) -> Result<String> {
    let data = std::fs::read(path).with_context(|| format!("Couldn't read {}", path.display()))?;
    let mut hash = [0u8; 32];
    let status = unsafe { BCryptHash(BCRYPT_SHA256_ALG_HANDLE, None, &data, &mut hash) };
    if status.is_err() {
        bail!("Couldn't hash {}", path.display());
    }
    Ok(hash.iter().map(|b| format!("{b:02x}")).collect())
}
