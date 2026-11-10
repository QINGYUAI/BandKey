[CmdletBinding()]
param(
    [string]$Version,
    [switch]$SkipWindows,
    [switch]$SkipAndroid
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
Set-Location $root

$issPath = Join-Path $root 'installer\BandKey.iss'
$issText = Get-Content -Raw -LiteralPath $issPath
if (-not $Version) {
    $match = [regex]::Match($issText, '(?m)^\s*#define\s+AppVersion\s+"([^"]+)"')
    if (-not $match.Success) {
        throw 'Cannot read AppVersion from installer\BandKey.iss'
    }
    $Version = $match.Groups[1].Value
}

if ($Version -notmatch '^\d+\.\d+\.\d+([-.][0-9A-Za-z.-]+)?$') {
    throw "Invalid version: $Version"
}

$serviceProject = Join-Path $root 'windows\BandKeyService\BandKeyService.csproj'
$trayProject = Join-Path $root 'windows\BandKeyTray\BandKeyTray.csproj'
$gradleFile = Join-Path $root 'android\app\build.gradle.kts'

$serviceVersion = [regex]::Match(
    (Get-Content -Raw -LiteralPath $serviceProject),
    '<Version>([^<]+)</Version>').Groups[1].Value
$trayVersion = [regex]::Match(
    (Get-Content -Raw -LiteralPath $trayProject),
    '<Version>([^<]+)</Version>').Groups[1].Value
$gradleVersion = [regex]::Match(
    (Get-Content -Raw -LiteralPath $gradleFile),
    'val\s+bandKeyVersionName\s*=\s*"([^"]+)"').Groups[1].Value

foreach ($pair in @(
        @('Windows Service', $serviceVersion, $serviceProject),
        @('Windows Tray', $trayVersion, $trayProject),
        @('Android', $gradleVersion, $gradleFile))) {
    if ($pair[1] -ne $Version) {
        throw "Version mismatch: $($pair[0]) uses '$($pair[1])', expected '$Version' ($($pair[2]))"
    }
}

$dotnet = Join-Path $env:USERPROFILE '.dotnet\dotnet.exe'
if (-not (Test-Path -LiteralPath $dotnet)) {
    throw "dotnet SDK not found: $dotnet"
}
if (-not (& $dotnet --list-sdks)) {
    throw "No .NET SDK found at: $dotnet"
}

$iscc = @(
    (Join-Path $env:LOCALAPPDATA 'Programs\Inno Setup 6\ISCC.exe'),
    'C:\Program Files (x86)\Inno Setup 6\ISCC.exe',
    'C:\Program Files\Inno Setup 6\ISCC.exe'
) | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $iscc) {
    throw 'ISCC.exe not found. Install Inno Setup 6 first.'
}

$distRoot = Join-Path $root 'dist'
$versionDir = Join-Path $distRoot $Version
$latestDir = Join-Path $distRoot 'latest'
New-Item -ItemType Directory -Force -Path $versionDir, $latestDir | Out-Null
foreach ($name in @('BandKey-Setup.exe', 'BandKey.apk', 'SHA256SUMS.txt', 'VERSION.txt')) {
    $path = Join-Path $latestDir $name
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Force
    }
}

if (-not $SkipWindows) {
    Write-Host '==> Publish Windows Service'
    & $dotnet publish $serviceProject -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=true -p:DebugType=None -p:DebugSymbols=false
    if ($LASTEXITCODE -ne 0) {
        throw "BandKeyService publish failed with exit code $LASTEXITCODE"
    }

    Write-Host '==> Publish Windows Tray'
    & $dotnet publish $trayProject -c Release -r win-x64 --self-contained true `
        -p:PublishSingleFile=true -p:DebugType=None -p:DebugSymbols=false
    if ($LASTEXITCODE -ne 0) {
        throw "BandKeyTray publish failed with exit code $LASTEXITCODE"
    }

    Write-Host '==> Build Credential Provider'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $root 'windows\BandKeyCredentialProvider\build.ps1')
    if ($LASTEXITCODE -ne 0) {
        throw "Credential Provider build failed with exit code $LASTEXITCODE"
    }

    Write-Host '==> Build Windows installer'
    & $iscc $issPath
    if ($LASTEXITCODE -ne 0) {
        throw "Inno Setup failed with exit code $LASTEXITCODE"
    }
}

if (-not $SkipAndroid) {
    Write-Host '==> Build Android release APK'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $root 'android\build.ps1') assembleRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Android release build failed with exit code $LASTEXITCODE"
    }

    $apkSource = Join-Path $root 'android\app\build\outputs\apk\release\app-release.apk'
    if (-not (Test-Path -LiteralPath $apkSource)) {
        throw "Release APK not found: $apkSource"
    }
    Copy-Item -LiteralPath $apkSource `
        -Destination (Join-Path $versionDir "BandKey-$Version-android.apk") -Force
}

$setupSource = Join-Path $versionDir "BandKey-$Version-windows-x64.exe"
$androidSource = Join-Path $versionDir "BandKey-$Version-android.apk"
if (Test-Path -LiteralPath $setupSource) {
    Copy-Item -LiteralPath $setupSource -Destination (Join-Path $latestDir 'BandKey-Setup.exe') -Force
}
if (Test-Path -LiteralPath $androidSource) {
    Copy-Item -LiteralPath $androidSource -Destination (Join-Path $latestDir 'BandKey.apk') -Force
}

function Write-Checksums {
    param(
        [string]$Directory,
        [string[]]$Names
    )

    $lines = @()
    foreach ($name in $Names) {
        $path = Join-Path $Directory $name
        if (Test-Path -LiteralPath $path) {
            $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash
            $lines += "$hash  $name"
        }
    }
    if ($lines.Count -gt 0) {
        Set-Content -LiteralPath (Join-Path $Directory 'SHA256SUMS.txt') `
            -Value $lines -Encoding ASCII
    }
}

Write-Checksums -Directory $versionDir -Names @(
    "BandKey-$Version-windows-x64.exe",
    "BandKey-$Version-android.apk"
)
Write-Checksums -Directory $latestDir -Names @(
    'BandKey-Setup.exe',
    'BandKey.apk'
)
Set-Content -LiteralPath (Join-Path $latestDir 'VERSION.txt') `
    -Value $Version -Encoding ASCII

Write-Host ''
Write-Host "Release $Version completed."
if (Test-Path -LiteralPath $setupSource) {
    Write-Host "Windows versioned: $setupSource"
}
if (Test-Path -LiteralPath $androidSource) {
    Write-Host "Android versioned: $androidSource"
}
if (Test-Path -LiteralPath (Join-Path $latestDir 'BandKey-Setup.exe')) {
    Write-Host "Windows stable: $(Join-Path $latestDir 'BandKey-Setup.exe')"
}
if (Test-Path -LiteralPath (Join-Path $latestDir 'BandKey.apk')) {
    Write-Host "Android stable: $(Join-Path $latestDir 'BandKey.apk')"
}
