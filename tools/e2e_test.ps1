$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"
$dll = Join-Path $root 'windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll'
$cfg = Join-Path $env:TEMP 'bandkey_devices.json'
$key = Join-Path $env:TEMP 'bandkey_phone_key.pem'
$client = Join-Path $root 'tools\simulate_phone.py'
$port = 23235
$suffix = '_e2e_' + [guid]::NewGuid().ToString('N').Substring(0, 8)

Remove-Item $cfg, $key -ErrorAction SilentlyContinue

$job = Start-Job -ScriptBlock {
    param($d, $serviceDll, $config, $listenPort, $pipeSuffix)
    & $d $serviceDll --urls "http://127.0.0.1:$listenPort" --config $config --pair-pin 123456 --dev --pipe-suffix $pipeSuffix
} -ArgumentList $dotnet, $dll, $cfg, $port, $suffix

try {
    $deadline = (Get-Date).AddSeconds(15)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        $tcp = New-Object System.Net.Sockets.TcpClient
        try {
            $async = $tcp.BeginConnect('127.0.0.1', $port, $null, $null)
            if ($async.AsyncWaitHandle.WaitOne(300)) {
                $tcp.EndConnect($async)
                $ready = $true
            }
        }
        catch { }
        finally { $tcp.Close() }
        if ($ready) { break }
        Start-Sleep -Milliseconds 300
    }
    if (-not $ready) { throw '服务未在超时时间内启动' }

    python $client --url "ws://127.0.0.1:$port/bandkey" --keyfile $key --pin 123456 --replay --nfc

    $status = Invoke-RestMethod -Uri "http://127.0.0.1:$port/status" -TimeoutSec 5
    Write-Output "status_after_unlock: $($status | ConvertTo-Json -Compress)"
    $dots = [string]::new([char]0x00B7, 3)
    $expectedHint = "BK-NFC $dots 8F2A"
    if ($status.lastUnlockMethod -ne 'nfc') {
        throw "lastUnlockMethod 未记录为 nfc"
    }
    if ($status.nfcKeyHint -ne $expectedHint) {
        throw "nfcKeyHint 不符合预期: $($status.nfcKeyHint)"
    }
    if ($null -eq $status.nfcLastUsedAt) {
        throw "nfcLastUsedAt 未记录"
    }
}
finally {
    Stop-Job $job -ErrorAction SilentlyContinue
    $serviceOutput = Receive-Job $job -ErrorAction SilentlyContinue
    Remove-Job $job -Force -ErrorAction SilentlyContinue
}

Write-Output '===== SERVICE OUTPUT ====='
$serviceOutput
Write-Output '===== devices.json ====='
if (Test-Path $cfg) { Get-Content $cfg -Raw }
