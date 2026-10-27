<#
.SYNOPSIS
    One-time setup: builds and installs LumiDeck, turns on Android's desktop mode, and
    makes LumiDeck the default home app.

.DESCRIPTION
    Needs USB debugging on. The settings changed here are the same ones under
    Developer options ("Enable freeform windows", "Force activities to be resizable",
    "Force desktop mode"). They take effect after a reboot.

.EXAMPLE
    .\scripts\setup-device.ps1 -Reboot
#>
param(
    [string]$Serial,
    [switch]$SkipInstall,
    [switch]$Reboot
)

. "$PSScriptRoot\common.ps1"

$serial = Select-LumiDevice $Serial
function Invoke-Adb { adb -s $serial @args; if ($LASTEXITCODE) { throw "adb $args failed" } }

$sdk = [int](Invoke-Adb shell getprop ro.build.version.sdk)
if ($sdk -lt 29) { throw "LumiDeck needs Android 10 or newer (this device is API $sdk)." }

if (-not $SkipInstall) {
    Use-AndroidStudioJdk
    Write-Host 'Building LumiDeck...'
    # Release, not debug: Compose runs several times slower in a debug build.
    & "$LumiRoot\android\gradlew.bat" -p "$LumiRoot\android" assembleRelease --quiet
    if ($LASTEXITCODE) { throw 'Build failed.' }
    Write-Host 'Installing...'
    Invoke-Adb install -r "$LumiRoot\android\app\build\outputs\apk\release\app-release.apk" | Out-Null
    # Compile ahead of time now, instead of waiting for the device's idle-time compiler.
    Invoke-Adb shell cmd package compile -m speed -f $LumiPackage | Out-Null
}

Write-Host 'Turning on desktop mode for external displays...'
Invoke-Adb shell settings put global enable_freeform_support 1
Invoke-Adb shell settings put global force_resizable_activities 1
Invoke-Adb shell settings put global force_desktop_mode_on_external_displays 1

Write-Host 'Letting the taskbar stay above app windows...'
# "Display over other apps" in Settings.
Invoke-Adb shell appops set $LumiPackage SYSTEM_ALERT_WINDOW allow

Write-Host 'Making LumiDeck the default home app...'
# Home is a "role" since Android 10; this is what Settings > Default apps > Home app sets.
Invoke-Adb shell cmd role add-role-holder android.app.role.HOME $LumiPackage | Out-Null

# Open the setup screen so the user can pick which launcher the device's own screen uses.
Invoke-Adb shell am start -n "$LumiPackage/.HomeActivity" -a android.intent.action.MAIN -c android.intent.category.LAUNCHER | Out-Null
Write-Host 'On the device: pick your normal launcher under "Launcher on this screen".'

if (-not $Reboot) {
    Write-Host 'Starting the window helper (running apps in the taskbar)...'
    Start-WindowHelper $serial
}

if ($Reboot) {
    Write-Host 'Rebooting...'
    Invoke-Adb reboot
} else {
    Write-Host 'Done. Reboot the device once so desktop mode takes effect (or rerun with -Reboot).'
}
