<#
.SYNOPSIS
    LumiDeck for Windows: opens the device's LumiDeck desktop in a window.

.DESCRIPTION
    Runs the LumiDeck client (desktop\client), building it first if needed. The client
    starts LumiDeck's stream server on the device, which creates a new display; Android
    starts the LumiDeck desktop on it, and the client shows it here with its sound and
    sends it the keyboard and mouse. Closing the window moves any open apps back to the
    device's own screen.

    The client goes straight over USB once this PC is set up for it, which -SetupUsb does
    once (as an administrator: Windows asks). Until then, and with -Link adb, it goes
    through ADB.

    -Tray opens LumiDeck's window instead, with the device and the settings, and keeps
    LumiDeck in the Windows tray: it can open the desktop whenever the device is plugged
    in, and start with Windows. Options given here override the window's settings.

    -Scrcpy uses scrcpy instead, as LumiDeck did before it had its own client.

.EXAMPLE
    .\desktop\lumideck.ps1
    .\desktop\lumideck.ps1 -Resolution 2560x1440 -Dpi 200 -Fullscreen
    .\desktop\lumideck.ps1 -SetupUsb
    .\desktop\lumideck.ps1 -Tray
#>
param(
    [string]$Serial,
    [string]$Resolution = '1920x1080',
    # 160 dpi = 1 px per dp, which gives desktop-sized UI at 1080p.
    [int]$Dpi = 160,
    [switch]$Fullscreen,
    [switch]$NoAudio,
    # auto (USB once set up), usb or adb.
    [ValidateSet('auto', 'usb', 'adb')]
    [string]$Link = 'auto',
    # Set this PC up for the USB link, once.
    [switch]$SetupUsb,
    # Print how fast the video goes.
    [switch]$Stats,
    # Open LumiDeck's window, and stay in the tray.
    [switch]$Tray,
    [switch]$Scrcpy,
    # Turn the device's own screen off while the desktop is open (scrcpy only, for now).
    [switch]$ScreenOff
)

. "$PSScriptRoot\..\scripts\common.ps1"

if (-not $Scrcpy) {
    $client = Join-Path $PSScriptRoot 'client'
    $exe = Join-Path $client 'target\release\lumideck.exe'
    # Brings the client up to date (quick when it already is).
    if (Get-Command cargo -ErrorAction SilentlyContinue) {
        if (-not (Test-Path (Join-Path $client 'ffmpeg'))) { & (Join-Path $client 'get-ffmpeg.ps1') }
        cargo build --release --quiet --manifest-path (Join-Path $client 'Cargo.toml')
        if ($LASTEXITCODE -ne 0) { throw 'The client failed to build.' }
    } elseif (-not (Test-Path $exe)) {
        throw 'Building the client needs Rust. Install it with: winget install --id Rustlang.Rustup -e'
    }
    if ($SetupUsb) {
        $setupArgs = @('--setup-usb')
        if ($Serial) { $setupArgs += '--serial', $Serial }
        # Windows only lets an administrator change drivers. The console stays open to show
        # how it went.
        $command = "& '$exe' $($setupArgs -join ' '); Read-Host 'Press Enter to close'"
        $setup = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList '-NoProfile', '-Command', $command
        exit $setup.ExitCode
    }
    if ($ScreenOff) { Write-Warning '-ScreenOff needs -Scrcpy for now.' }
    $clientArgs = @('--resolution', $Resolution, '--dpi', $Dpi, '--link', $Link)
    if ($Serial) { $clientArgs += '--serial', $Serial }
    if ($Fullscreen) { $clientArgs += '--fullscreen' }
    if ($NoAudio) { $clientArgs += '--no-audio' }
    if ($Stats) { $clientArgs += '--stats' }
    if ($Tray) {
        # Only what was asked for here: the window has its own settings.
        $trayArgs = @()
        if ($PSBoundParameters.ContainsKey('Resolution')) { $trayArgs += '--resolution', $Resolution }
        if ($PSBoundParameters.ContainsKey('Dpi')) { $trayArgs += '--dpi', $Dpi }
        if ($PSBoundParameters.ContainsKey('Link')) { $trayArgs += '--link', $Link }
        if ($Fullscreen) { $trayArgs += '--fullscreen' }
        if ($NoAudio) { $trayArgs += '--no-audio' }
        if ($Stats) { $trayArgs += '--stats' }
        $trayExe = Join-Path $client 'target\release\lumideck-tray.exe'
        # The app (desktop\app, Tauri) builds next to the client: its .cargo\config.toml says
        # so, which cargo only reads from inside its folder. A running one just shows its window.
        if ((Get-Command cargo -ErrorAction SilentlyContinue) -and -not (Get-Process lumideck-tray -ErrorAction SilentlyContinue)) {
            Push-Location (Join-Path $PSScriptRoot 'app')
            try { cargo build --release --quiet } finally { Pop-Location }
            if ($LASTEXITCODE -ne 0) { throw 'The app failed to build.' }
        } elseif (-not (Test-Path $trayExe)) {
            throw 'Building the app needs Rust. Install it with: winget install --id Rustlang.Rustup -e'
        }
        if ($trayArgs) { Start-Process $trayExe -ArgumentList $trayArgs } else { Start-Process $trayExe }
        exit 0
    }
    & $exe @clientArgs
    exit $LASTEXITCODE
}

Assert-Tool scrcpy 'Genymobile.scrcpy'
$serial = Select-LumiDevice $Serial

# Some firmware (seen on the Anbernic RG556) keeps LumiDeck as the HOME role holder after
# a reboot but stops resolving it as home, so the desktop would come up as the stock
# launcher. Re-adding the role resyncs it. Skipped if the user picked another home app.
$homeRole = adb -s $serial shell dumpsys role | Select-String -Pattern 'android.app.role.HOME' -Context 0, 1
if ($homeRole -and $homeRole.Context.PostContext -match "holders=$LumiPackage") {
    adb -s $serial shell cmd role add-role-holder android.app.role.HOME $LumiPackage | Out-Null
}

# Also done by setup-device.ps1; repeated here for installs made before they were needed.
Grant-LumiPermissions $serial

# Running apps in the taskbar.
Start-WindowHelper $serial

$env:SCRCPY_ICON_DIR = Join-Path $PSScriptRoot 'icons'

# Android draws its own navigation bar along the bottom of the desktop and forces it on
# while app windows are open. Make the display one bar taller and crop the bar off the
# video; the desktop keeps that strip clear, so nothing else is lost. The bar is 56dp on
# displays this large.
$width, $height = $Resolution -split 'x' | ForEach-Object { [int]$_ }
$navBar = [math]::Ceiling(56 * $Dpi / 160)

$scrcpyArgs = @(
    "--serial=$serial"
    "--new-display=${width}x$($height + $navBar)/$Dpi"
    "--crop=${width}:${height}:0:0"
    '--window-title=LumiDeck'
    # Act as a real (HID) keyboard: no on-screen keyboard popping up, Meta key works.
    '--keyboard=uhid'
    # Send right/middle clicks to Android instead of mapping them to Back/Home.
    '--mouse-bind=++++:bhsn'
    # Keep Alt, AltGr and Super free for Android; scrcpy's own shortcuts use Right Ctrl.
    '--shortcut-mod=rctrl'
    # No on-screen keyboard or Gboard toolbar on the desktop; typing still works.
    '--display-ime-policy=hide'
    # Uncompressed audio: USB has the bandwidth, and it saves the device encoding Opus.
    '--audio-codec=raw'
    '--no-vd-destroy-content'
    '--stay-awake'
)
if ($Fullscreen) { $scrcpyArgs += '--fullscreen' }
if ($ScreenOff) { $scrcpyArgs += '--turn-screen-off' }

scrcpy @scrcpyArgs
