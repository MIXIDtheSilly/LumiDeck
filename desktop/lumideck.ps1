<#
.SYNOPSIS
    LumiDeck for Windows (v0): opens the device's LumiDeck desktop in a window.

.DESCRIPTION
    scrcpy creates a virtual display on the device; Android starts the LumiDeck desktop
    on it, and scrcpy streams it here with keyboard, mouse and audio. Closing the window
    moves any open apps back to the device's own screen.

.EXAMPLE
    .\desktop\lumideck.ps1
    .\desktop\lumideck.ps1 -Resolution 2560x1440 -Dpi 200 -Fullscreen
#>
param(
    [string]$Serial,
    [string]$Resolution = '1920x1080',
    # 160 dpi = 1 px per dp, which gives desktop-sized UI at 1080p.
    [int]$Dpi = 160,
    [switch]$Fullscreen,
    # Turn the device's own screen off while the desktop is open.
    [switch]$ScreenOff
)

. "$PSScriptRoot\..\scripts\common.ps1"

Assert-Tool scrcpy 'Genymobile.scrcpy'
$serial = Select-LumiDevice $Serial

# Some firmware (seen on the Anbernic RG556) keeps LumiDeck as the HOME role holder after
# a reboot but stops resolving it as home, so the desktop would come up as the stock
# launcher. Re-adding the role resyncs it. Skipped if the user picked another home app.
$homeRole = adb -s $serial shell dumpsys role | Select-String -Pattern 'android.app.role.HOME' -Context 0, 1
if ($homeRole -and $homeRole.Context.PostContext -match "holders=$LumiPackage") {
    adb -s $serial shell cmd role add-role-holder android.app.role.HOME $LumiPackage | Out-Null
}

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
