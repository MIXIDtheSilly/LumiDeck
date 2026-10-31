<#
.SYNOPSIS
    Builds LumiDeck's Windows installer: LumiDeck-<version>-setup.exe.

.DESCRIPTION
    The installer puts the LumiDeck app (desktop\app), the desktop window (desktop\client)
    with its ffmpeg DLLs, adb, and the Android app for the device (lumideck.apk) in one
    folder, for the current user only, so Windows doesn't ask for an administrator. The
    app sets the device up from that APK in one click.

    Needs Rust, Node.js (for Tauri's command line, run through npx), Android Studio's JDK
    for the APK, and adb from winget (Google.PlatformTools), which is what gets bundled.
    Close LumiDeck first: a running lumideck.exe can't be replaced.

.EXAMPLE
    .\desktop\build-installer.ps1
#>
param(
    # Use the APK already built instead of building it again.
    [switch]$SkipApk
)

. "$PSScriptRoot\..\scripts\common.ps1"
$ProgressPreference = 'SilentlyContinue'

$client = Join-Path $PSScriptRoot 'client'
$app = Join-Path $PSScriptRoot 'app'
# Where both build (the app's .cargo\config.toml points it there too), unless
# CARGO_TARGET_DIR says otherwise.
$target = if ($env:CARGO_TARGET_DIR) { $env:CARGO_TARGET_DIR } else { Join-Path $client 'target' }
$release = Join-Path $target 'release'
$apk = Join-Path $LumiRoot 'android\app\build\outputs\apk\release\app-release.apk'

if (-not $SkipApk) {
    Use-AndroidStudioJdk
    Write-Host 'Building the Android app...'
    & "$LumiRoot\android\gradlew.bat" -p "$LumiRoot\android" assembleRelease --quiet
    if ($LASTEXITCODE) { throw 'The Android app failed to build.' }
}
if (-not (Test-Path $apk)) { throw "No APK at $apk. Run without -SkipApk." }

Write-Host 'Building the desktop window...'
if (-not (Test-Path (Join-Path $client 'ffmpeg'))) { & (Join-Path $client 'get-ffmpeg.ps1') }
cargo build --release --quiet --manifest-path (Join-Path $client 'Cargo.toml')
if ($LASTEXITCODE) { throw 'The desktop window failed to build.' }

# What goes next to lumideck-tray.exe (tauri.conf.json's bundle.resources).
$bundle = Join-Path $app 'bundle'
if (Test-Path $bundle) { Remove-Item -Recurse -Force $bundle }
New-Item -ItemType Directory $bundle | Out-Null
Copy-Item (Join-Path $release 'lumideck.exe') $bundle
Get-ChildItem $release -Filter *.dll | Copy-Item -Destination $bundle
Copy-Item $apk (Join-Path $bundle 'lumideck.apk')

# adb from Google's platform tools, with the two DLLs it needs and its licence.
$tools = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages') -Directory -Filter 'Google.PlatformTools*' -ErrorAction SilentlyContinue |
    ForEach-Object { Join-Path $_.FullName 'platform-tools' } | Where-Object { Test-Path (Join-Path $_ 'adb.exe') } | Select-Object -First 1
if (-not $tools) { throw 'adb not found. Install it with: winget install --id Google.PlatformTools -e' }
foreach ($file in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll', 'NOTICE.txt') {
    $source = Join-Path $tools $file
    if (Test-Path $source) { Copy-Item $source $bundle }
}
$version = (& (Join-Path $tools 'adb.exe') version | Select-Object -First 2) -join ', '
Write-Host "Bundling $version"

Write-Host 'Building the installer...'
Push-Location $app
# It reports progress on stderr, which Windows PowerShell would take for a failure.
$ErrorActionPreference = 'Continue'
try {
    # Tauri's command line, the version matching the tauri crate (Cargo.toml).
    npx --yes '@tauri-apps/cli@2.12.4' build --bundles nsis 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE) { throw 'The installer failed to build.' }
} finally {
    Pop-Location
    $ErrorActionPreference = 'Stop'
}

$setup = Get-ChildItem (Join-Path $release 'bundle\nsis') -Filter '*-setup.exe' | Sort-Object LastWriteTime | Select-Object -Last 1
Write-Host "Installer: $($setup.FullName)"
