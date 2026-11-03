<#
.SYNOPSIS
    Installs the latest Laser Measure release from GitHub on a phone connected over USB.

.DESCRIPTION
    For phones that can't install updates themselves, such as with Android's Advanced
    Protection turned on. The script:
      1. finds adb and the connected phone
      2. gets the latest release from github.com/computir/hoto-laser-measure
      3. checks the download against the SHA-256 checksum GitHub publishes for it
      4. checks the APK is signed with the project's release key (when apksigner is available)
      5. installs it over the existing app with "adb install -r", which keeps your history and pairing

    The phone needs USB debugging turned on (Settings > System > Developer options).

.PARAMETER Adb
    Path to adb.exe. By default it's found through local.properties, ANDROID_HOME, ANDROID_SDK_ROOT,
    the Android Studio SDK folder or PATH.

.PARAMETER Force
    Reinstall even if the phone already has the latest version.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\update-phone.ps1
#>
param(
    [string]$Adb,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$Repo = 'computir/hoto-laser-measure'
$Package = 'com.hotomeasure.app'
$AssetName = 'LaserMeasure.apk'
# SHA-256 of the release signing certificate (published in the v1.7 release notes).
$ReleaseCert = '202c0f74ee83c026a2776aa17b41b70588fe78f4dd3339aa58d6d4883ed142e6'

function Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "ERROR: $text" -ForegroundColor Red; exit 1 }

# --- Find the Android SDK and adb ---
$sdkCandidates = @()
$localProps = Join-Path $PSScriptRoot '..\local.properties'
if (Test-Path $localProps) {
    $line = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdkCandidates += ($line -replace '^sdk\.dir=', '' -replace '\\:', ':' -replace '\\\\', '\') }
}
$sdkCandidates += @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object { $_ }
$sdk = $sdkCandidates | Where-Object { Test-Path (Join-Path $_ 'platform-tools\adb.exe') } | Select-Object -First 1

if (-not $Adb) {
    if ($sdk) { $Adb = Join-Path $sdk 'platform-tools\adb.exe' }
    else {
        $cmd = Get-Command adb -ErrorAction SilentlyContinue
        if ($cmd) { $Adb = $cmd.Source } else { Fail 'adb not found. Install Android platform-tools or pass -Adb <path to adb.exe>.' }
    }
}

# --- Phone ---
Step 'Looking for the phone'
$devices = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\S' }
if (-not $devices) { Fail 'No phone found. Plug it in, unlock it and make sure USB debugging is on.' }
if ($devices -match 'unauthorized') { Fail 'The phone has not allowed this computer yet. Unlock it and tap Allow on the USB debugging prompt.' }
if (@($devices).Count -gt 1) { Fail 'More than one device is connected. Unplug the others and try again.' }

$installed = (& $Adb shell dumpsys package $Package) | Select-String 'versionName=' | Select-Object -First 1
$installedVersion = if ($installed) { ($installed.ToString().Trim() -replace '^versionName=', '') } else { $null }
if ($installedVersion) { Write-Host "    Installed: $installedVersion" } else { Write-Host '    Laser Measure is not installed yet.' }

# --- Latest release ---
Step 'Checking GitHub for the latest release'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'LaserMeasure-update-script' }
$latestVersion = $release.tag_name -replace '^v', ''
$asset = $release.assets | Where-Object { $_.name -eq $AssetName } | Select-Object -First 1
if (-not $asset) { Fail "Release $($release.tag_name) has no $AssetName." }
Write-Host "    Latest:    $latestVersion"

function VersionParts($v) { ($v -split '[.-]') | ForEach-Object { [int]("0" + ($_ -replace '\D', '')) } }
function IsNewer($a, $b) {
    $x = @(VersionParts $a); $y = @(VersionParts $b)
    for ($i = 0; $i -lt [Math]::Max($x.Count, $y.Count); $i++) {
        $p = if ($i -lt $x.Count) { $x[$i] } else { 0 }
        $q = if ($i -lt $y.Count) { $y[$i] } else { 0 }
        if ($p -ne $q) { return $p -gt $q }
    }
    return $false
}
if ($installedVersion -and -not (IsNewer $latestVersion $installedVersion) -and -not $Force) {
    Write-Host 'The phone already has the latest version. (Use -Force to reinstall.)' -ForegroundColor Green
    exit 0
}

# --- Download and verify ---
Step "Downloading $AssetName $latestVersion"
$dir = Join-Path ([IO.Path]::GetTempPath()) "laser-measure-update"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
$apk = Join-Path $dir $AssetName
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $apk -UseBasicParsing -Headers @{ 'User-Agent' = 'LaserMeasure-update-script' }

Step 'Verifying the download'
$actual = (Get-FileHash -Algorithm SHA256 -Path $apk).Hash.ToLower()
if ($asset.digest -and $asset.digest -like 'sha256:*') {
    $expected = ($asset.digest -replace '^sha256:', '').ToLower()
    if ($actual -ne $expected) { Remove-Item $apk; Fail "Checksum mismatch: the download is damaged or was altered. Nothing was installed." }
    Write-Host "    Checksum matches GitHub's ($actual)"
} else {
    Write-Host "    GitHub lists no checksum for this file; SHA-256 is $actual" -ForegroundColor Yellow
}

$apksigner = $null
if ($sdk) { $apksigner = Get-ChildItem (Join-Path $sdk 'build-tools') -Filter apksigner.bat -Recurse -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1 }
if ($apksigner) {
    # apksigner needs a recent Java; prefer the JDK next to the SDK if there is one.
    $jdk = Get-ChildItem (Join-Path (Split-Path $sdk) 'jdk') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($jdk) { $env:JAVA_HOME = $jdk.FullName; $env:Path = (Join-Path $jdk.FullName 'bin') + ';' + $env:Path }
    $certs = & $apksigner.FullName verify --print-certs $apk 2>&1
    if ($LASTEXITCODE -ne 0) { Remove-Item $apk; Fail "The APK's signature is invalid. Nothing was installed." }
    $digest = ($certs | Select-String 'Signer #1 certificate SHA-256 digest: (\w+)').Matches.Groups[1].Value
    if ($digest -ne $ReleaseCert) { Remove-Item $apk; Fail "The APK isn't signed with the project's release key ($digest). Nothing was installed." }
    Write-Host '    Signed with the project release key'
} else {
    Write-Host '    apksigner not found; skipping the key check (Android still refuses an update signed with a different key).' -ForegroundColor Yellow
}

# --- Install ---
Step 'Installing on the phone'
$out = & $Adb install -r $apk 2>&1
if ($LASTEXITCODE -ne 0 -or -not ($out -match 'Success')) {
    if ($out -match 'UPDATE_INCOMPATIBLE') { Fail "The phone has a copy signed with a different key (e.g. 1.6 or your own build). Uninstall Laser Measure first. This deletes its history, so use History > Share all to save it." }
    Fail "Install failed: $out"
}
& $Adb shell am start -n "$Package/.MainActivity" | Out-Null
Remove-Item $apk -ErrorAction SilentlyContinue
Write-Host "Done: Laser Measure $latestVersion is installed and open on the phone." -ForegroundColor Green
