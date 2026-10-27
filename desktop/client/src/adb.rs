//! Everything done through ADB: finding the device, getting it ready, and starting the
//! server, which runs from LumiDeck's APK so there's nothing to copy over.

use std::env;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::TcpStream;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::thread;
use std::time::{Duration, Instant};

use anyhow::{Context, Result, anyhow, bail};

use crate::protocol::{HELLO, SOCKET};

const PACKAGE: &str = "app.lumideck";

pub struct Adb {
    exe: PathBuf,
    pub serial: String,
}

impl Adb {
    /// The device to use: `serial`, or `ANDROID_SERIAL`, or the only one plugged in.
    pub fn connect(serial: Option<String>) -> Result<Adb> {
        let exe = find_adb()?;
        let serial = match serial.or_else(|| env::var("ANDROID_SERIAL").ok()) {
            Some(serial) => serial,
            None => {
                let output = run(Command::new(&exe).args(["devices", "-l"]))?;
                let devices: Vec<(String, String)> = output
                    .lines()
                    .skip(1)
                    .filter(|line| line.split_whitespace().nth(1) == Some("device"))
                    .map(|line| {
                        let serial = line.split_whitespace().next().unwrap().to_string();
                        let model = line
                            .split_whitespace()
                            .find_map(|word| word.strip_prefix("model:"))
                            .unwrap_or("?")
                            .to_string();
                        (serial, model)
                    })
                    .collect();
                match devices.as_slice() {
                    [] => bail!(
                        "No device found. Plug it in over USB and turn on USB debugging (Settings > Developer options)."
                    ),
                    [(serial, model)] => {
                        println!("Using {model} ({serial})");
                        serial.clone()
                    }
                    _ => {
                        let list: Vec<String> = devices.iter().map(|(s, m)| format!("  {s}  ({m})")).collect();
                        bail!("Several devices are connected. Pick one with --serial:\n{}", list.join("\n"));
                    }
                }
            }
        };
        Ok(Adb { exe, serial })
    }

    pub fn command(&self) -> Command {
        let mut command = Command::new(&self.exe);
        command.args(["-s", &self.serial]);
        command
    }

    pub fn shell(&self, script: &str) -> Result<String> {
        run(self.command().args(["shell", script]))
    }

    /// What lumideck.ps1 used to do before each session.
    pub fn prepare(&self) -> Result<()> {
        let apk = self.apk()?;
        // Some firmware (seen on the Anbernic RG556) keeps LumiDeck as the home role holder
        // after a reboot but stops resolving it as home, so the desktop would come up as the
        // stock launcher. Adding the role again fixes it; skipped if the user picked another.
        let roles = self.shell("dumpsys role")?;
        let mut lines = roles.lines();
        while let Some(line) = lines.next() {
            if line.contains("android.app.role.HOME") {
                if lines.next().is_some_and(|holders| holders.contains(&format!("holders={PACKAGE}"))) {
                    self.shell(&format!("cmd role add-role-holder android.app.role.HOME {PACKAGE}"))?;
                }
                break;
            }
        }
        // Lets the taskbar and title bars stay above app windows ("Display over other apps").
        self.shell(&format!("appops set {PACKAGE} SYSTEM_ALERT_WINDOW allow"))?;
        // The window helper, for the taskbar's running apps and the title bars. It keeps
        // running after the PC goes; starting it again replaces the old one.
        self.shell(&format!("CLASSPATH={apk} nohup app_process / {PACKAGE}.helper.WindowHelper >/dev/null 2>&1 &"))?;
        Ok(())
    }

    fn apk(&self) -> Result<String> {
        let output = self.shell(&format!("pm path {PACKAGE}"))?;
        output
            .lines()
            .find_map(|line| line.trim().strip_prefix("package:"))
            .map(str::to_string)
            .ok_or_else(|| anyhow!("LumiDeck isn't installed on the device. Run scripts\\setup-device.ps1 first."))
    }

    /// Starts the server with `options` and returns it with a free port forwarded to it.
    pub fn start_server(&self, options: &[String]) -> Result<Server> {
        let apk = self.apk()?;
        // A server left over from a session that ended badly would hold the socket.
        // The brackets keep pkill from matching its own command line.
        self.shell("pkill -f 'app.lumideck.server.[S]treamServer'; true")?;
        let port: u16 = run(self.command().args(["forward", "tcp:0", SOCKET]))?
            .trim()
            .parse()
            .context("adb forward gave no port")?;
        let mut child = self
            .command()
            .args(["shell", &format!("CLASSPATH={apk}"), "app_process", "/", "app.lumideck.server.StreamServer"])
            .args(options)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .context("Couldn't start the server")?;
        echo(child.stdout.take().unwrap());
        echo(child.stderr.take().unwrap());
        Ok(Server { adb: self.command(), port, child })
    }
}

pub struct Server {
    adb: Command,
    port: u16,
    child: Child,
}

impl Server {
    /// Opens the next connection to the server, waiting for it to start.
    pub fn connect(&mut self) -> Result<TcpStream> {
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            if let Ok(Some(status)) = self.child.try_wait() {
                bail!("The server stopped ({status})");
            }
            if let Ok(mut stream) = TcpStream::connect(("127.0.0.1", self.port)) {
                // adb takes the connection even before the server listens, then drops it.
                let mut hello = [0u8; HELLO.len()];
                if stream.read_exact(&mut hello).is_ok() {
                    if &hello != HELLO {
                        bail!("The server on the device is a different version. Update LumiDeck on the device.");
                    }
                    stream.set_nodelay(true)?;
                    return Ok(stream);
                }
            }
            if Instant::now() > deadline {
                bail!("The server didn't start");
            }
            thread::sleep(Duration::from_millis(100));
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.adb.args(["forward", "--remove", &format!("tcp:{}", self.port)]).output();
    }
}

/// Prints the server's messages in the console.
fn echo(stream: impl Read + Send + 'static) {
    thread::spawn(move || {
        for line in BufReader::new(stream).lines().map_while(Result::ok) {
            let mut out = std::io::stdout().lock();
            let _ = writeln!(out, "Device: {line}");
        }
    });
}

fn run(command: &mut Command) -> Result<String> {
    let output = command.output().context("Couldn't run adb")?;
    if !output.status.success() {
        let args: Vec<_> = command.get_args().map(|a| a.to_string_lossy()).collect();
        let mut message = String::from_utf8_lossy(&output.stderr).trim().to_string();
        if message.is_empty() {
            message = String::from_utf8_lossy(&output.stdout).trim().to_string();
        }
        bail!("adb {} failed ({}): {message}", args.join(" "), output.status);
    }
    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// adb from the PATH, the Android SDK, or scrcpy's folder (as winget installs it).
fn find_adb() -> Result<PathBuf> {
    if let Ok(path) = env::var("PATH") {
        for dir in env::split_paths(&path) {
            let exe = dir.join("adb.exe");
            if exe.is_file() {
                return Ok(exe);
            }
        }
    }
    let local = env::var("LOCALAPPDATA").map(PathBuf::from).unwrap_or_default();
    let mut candidates = vec![local.join("Android").join("Sdk").join("platform-tools").join("adb.exe")];
    if let Ok(sdk) = env::var("ANDROID_HOME") {
        candidates.push(PathBuf::from(sdk).join("platform-tools").join("adb.exe"));
    }
    let winget = local.join("Microsoft").join("WinGet").join("Packages");
    if let Ok(entries) = std::fs::read_dir(&winget) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("Google.PlatformTools") || name.starts_with("Genymobile.scrcpy") {
                for inner in std::fs::read_dir(entry.path()).into_iter().flatten().flatten() {
                    candidates.push(inner.path().join("adb.exe"));
                }
                candidates.push(entry.path().join("adb.exe"));
            }
        }
    }
    candidates
        .into_iter()
        .find(|p| p.is_file())
        .ok_or_else(|| anyhow!("adb not found. Install it with: winget install --id Google.PlatformTools -e"))
}
