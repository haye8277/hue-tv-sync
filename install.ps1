# Installs Hue TV Sync on the Shield over the network and grants the two permissions
# that make it one-click: skip the screen-recording prompt, and see what's playing.
#
# Usage:  .\install.ps1 -ShieldIp 192.168.1.50
param(
    [Parameter(Mandatory = $true)][string]$ShieldIp,
    [string]$Apk
)

# $PSScriptRoot is empty inside param() defaults on Windows PowerShell 5.1.
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk' }

$ErrorActionPreference = 'Stop'
$pkg = 'com.kneejar.tvhue'
$device = "${ShieldIp}:5555"

$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = "$env:USERPROFILE\Android\sdk\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        throw "adb not found. Install Android platform-tools and add it to PATH."
    }
}
if (-not (Test-Path $Apk)) {
    throw "APK not found at $Apk. Build it first (.\gradlew.bat assembleDebug)."
}

function Invoke-Adb {
    & $adb @args
    if ($LASTEXITCODE -ne 0) { throw "adb $($args -join ' ') failed" }
}

Write-Host "Connecting to $device (accept the prompt on the TV the first time)..."
# adb connect exits 0 even when it fails, so read what it says.
$said = & $adb connect $device | Out-String
if ($said -notmatch 'connected to') {
    throw "Couldn't reach the Shield at ${device}: $($said.Trim()) Is Network debugging on (Settings > Device Preferences > Developer options)?"
}
Invoke-Adb -s $device wait-for-device

Write-Host "Installing..."
Invoke-Adb -s $device install -r $Apk

Write-Host "Granting permissions..."
Invoke-Adb -s $device shell cmd notification allow_listener "$pkg/$pkg.mood.MediaListenerService"
Invoke-Adb -s $device shell appops set $pkg PROJECT_MEDIA allow

Write-Host "Done. Open 'Hue TV Sync' from the Shield home screen."
