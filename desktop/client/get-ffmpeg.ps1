<#
.SYNOPSIS
    Downloads the ffmpeg build the client compiles against and ships (LGPL, shared).

.DESCRIPTION
    Unpacks into desktop\client\ffmpeg, where build.rs finds it. Set FFMPEG_DIR instead to
    use another shared ffmpeg build (one with include, lib and bin folders).
#>
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$url = 'https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-n8.1-latest-win64-lgpl-shared-8.1.zip'
$dir = Join-Path $PSScriptRoot 'ffmpeg'
$zip = Join-Path $env:TEMP 'lumideck-ffmpeg.zip'

if (Test-Path $dir) { Remove-Item -Recurse -Force $dir }
Write-Host "Downloading ffmpeg..."
Invoke-WebRequest $url -OutFile $zip
Expand-Archive $zip -DestinationPath $dir
Remove-Item $zip
Write-Host "ffmpeg is in $dir"
