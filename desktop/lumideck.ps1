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

$env:SCRCPY_ICON_DIR = Join-Path $PSScriptRoot 'icons'

$scrcpyArgs = @(
    "--serial=$serial"
    "--new-display=$Resolution/$Dpi"
    '--window-title=LumiDeck'
    # Act as a real (HID) keyboard: no on-screen keyboard popping up, Meta key works.
    '--keyboard=uhid'
    # Send right/middle clicks to Android instead of mapping them to Back/Home.
    '--mouse-bind=++++:bhsn'
    # Keep Alt, AltGr and Super free for Android; scrcpy's own shortcuts use Right Ctrl.
    '--shortcut-mod=rctrl'
    '--no-vd-destroy-content'
    '--stay-awake'
)
if ($Fullscreen) { $scrcpyArgs += '--fullscreen' }
if ($ScreenOff) { $scrcpyArgs += '--turn-screen-off' }

scrcpy @scrcpyArgs
