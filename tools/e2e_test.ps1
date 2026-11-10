$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"
$dll = Join-Path $root 'windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll'
$cfg = Join-Path $env:TEMP 'bandkey_devices.json'
$key = Join-Path $env:TEMP 'bandkey_phone_key.pem'
$client = Join-Path $root 'tools\simulate_phone.py'
$port = 23235

Remove-Item $cfg, $key -ErrorAction SilentlyContinue

$job = Start-Job -ScriptBlock {
    param($d, $serviceDll, $config, $listenPort)
    & $d $serviceDll --urls "http://127.0.0.1:$listenPort" --config $config --pair-pin 123456 --dev
} -ArgumentList $dotnet, $dll, $cfg, $port

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

    python $client --url "ws://127.0.0.1:$port/bandkey" --keyfile $key --pin 123456 --replay
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
