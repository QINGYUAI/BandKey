# BandKey Android 一键构建脚本
# 用法：powershell -ExecutionPolicy Bypass -File build.ps1 [task]
# 默认构建 Debug APK；如需 release 传入 assembleRelease
# assembleRelease 完成后会自动复制到仓库根目录 dist/latest/BandKey.apk。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

$jdk = 'C:\Users\13086\.bandkey-jdk17\jdk-17.0.20.1+1'
if (Test-Path (Join-Path $jdk 'bin\java.exe')) {
    $env:JAVA_HOME = $jdk
}

$proxy = $env:BANDKEY_HTTP_PROXY
if (-not $proxy) {
    $probe = [System.Net.Sockets.TcpClient]::new()
    try {
        $connect = $probe.ConnectAsync('127.0.0.1', 7892)
        if ($connect.Wait(150) -and $probe.Connected) {
            $proxy = 'http://127.0.0.1:7892'
        }
    }
    catch { }
    finally { $probe.Dispose() }
}
if ($proxy) {
    $proxyUri = [Uri]$proxy
    $env:JAVA_OPTS = "-Dhttp.proxyHost=$($proxyUri.Host) -Dhttp.proxyPort=$($proxyUri.Port) -Dhttps.proxyHost=$($proxyUri.Host) -Dhttps.proxyPort=$($proxyUri.Port)"
}

if (-not $env:ANDROID_HOME) {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $sdk) {
        $env:ANDROID_HOME = $sdk
        $env:ANDROID_SDK_ROOT = $sdk
    }
}

$task = if ($args.Count -gt 0) { $args[0] } else { 'assembleDebug' }

Set-Location $root

$gradle = 'C:\Users\13086\.bandkey-gradle\gradle-8.7\bin\gradle.bat'
if (Test-Path $gradle) {
    & $gradle $task --console=plain --no-daemon
} else {
    & (Join-Path $root 'gradlew.bat') $task --console=plain --no-daemon
}
exit $LASTEXITCODE
