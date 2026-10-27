# Shared helpers for LumiDeck scripts. Dot-source this file: . "$PSScriptRoot\common.ps1"

$ErrorActionPreference = 'Stop'

$LumiRoot = Split-Path -Parent $PSScriptRoot
$LumiPackage = 'app.lumideck'

# winget adds tools to PATH, but only for new shells; pick them up in this one too.
$env:Path = [Environment]::GetEnvironmentVariable('Path', 'User') + ';' + [Environment]::GetEnvironmentVariable('Path', 'Machine') + ';' + $env:Path

function Assert-Tool([string]$Name, [string]$WingetId) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "$Name not found. Install it with: winget install --id $WingetId -e"
    }
}

function Select-LumiDevice([string]$Serial) {
    Assert-Tool adb 'Google.PlatformTools'
    if ($Serial) { return $Serial }
    if ($env:ANDROID_SERIAL) { return $env:ANDROID_SERIAL }

    $devices = @(adb devices -l | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice\s' } | ForEach-Object {
        [pscustomobject]@{
            Serial = ($_ -split '\s+')[0]
            Model  = if ($_ -match 'model:(\S+)') { $Matches[1] } else { '?' }
        }
    })
    if ($devices.Count -eq 0) {
        throw 'No device found. Plug it in over USB and turn on USB debugging (Settings > Developer options).'
    }
    if ($devices.Count -gt 1) {
        $list = ($devices | ForEach-Object { "  $($_.Serial)  ($($_.Model))" }) -join "`n"
        throw "Several devices are connected. Pick one with -Serial:`n$list"
    }
    Write-Host "Using $($devices[0].Model) ($($devices[0].Serial))"
    return $devices[0].Serial
}

# Starts the window helper, which lets the taskbar list, focus, minimise, maximise and close
# app windows (android/app/src/main/java/app/lumideck/helper/WindowHelper.kt). It runs from
# LumiDeck's APK as ADB's shell user and keeps running after the PC disconnects, until the
# device reboots. Starting it again replaces the running one.
function Start-WindowHelper([string]$Serial) {
    adb -s $Serial shell "CLASSPATH=`$(pm path $LumiPackage | head -n 1 | cut -d: -f2) nohup app_process / $LumiPackage.helper.WindowHelper >/dev/null 2>&1 &"
}

# Gradle and the SDK tools want a JDK they know; Android Studio ships one.
function Use-AndroidStudioJdk {
    $jbr = Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'
    if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
}
