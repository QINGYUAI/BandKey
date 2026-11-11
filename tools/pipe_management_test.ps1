$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"
$dll = Join-Path $root 'windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll'
$suffix = '_test_' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$cfg = Join-Path $env:TEMP "bandkey_pipe_management$suffix.json"
$key = Join-Path $env:TEMP "bandkey_pipe_management$suffix.pem"
$client = Join-Path $root 'tools\simulate_phone.py'
$trayPipe = 'BandKeyTrayPipe' + $suffix
$port = 23237

Remove-Item $cfg, $key -ErrorAction SilentlyContinue

function Invoke-TrayPipe {
    param(
        [string]$PipeName,
        [hashtable]$Command
    )

    $pipe = [System.IO.Pipes.NamedPipeClientStream]::new(
        '.',
        $PipeName,
        [System.IO.Pipes.PipeDirection]::InOut,
        [System.IO.Pipes.PipeOptions]::Asynchronous,
        [System.Security.Principal.TokenImpersonationLevel]::Impersonation)
    try {
        $pipe.Connect(5000)
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $writer = [System.IO.StreamWriter]::new($pipe, $utf8, 4096, $true)
        $writer.AutoFlush = $true
        $writer.WriteLine(($Command | ConvertTo-Json -Compress))

        $reader = [System.IO.StreamReader]::new($pipe, $utf8, $false, 4096, $true)
        $line = $reader.ReadLine()
        if ([string]::IsNullOrWhiteSpace($line)) {
            throw "pipe $PipeName returned nothing"
        }
        return $line | ConvertFrom-Json
    }
    finally {
        $pipe.Dispose()
    }
}

$serviceJob = Start-Job -ScriptBlock {
    param($d, $serviceDll, $config, $listenPort, $pipeSuffix)
    & $d $serviceDll --urls "http://127.0.0.1:$listenPort" --config $config --pair-pin 123456 --pipe-suffix $pipeSuffix
} -ArgumentList $dotnet, $dll, $cfg, $port, $suffix

try {
    $deadline = (Get-Date).AddSeconds(15)
    $ready = $false
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod "http://127.0.0.1:$port/health" -TimeoutSec 1
            if ($health -eq 'ok') { $ready = $true; break }
        }
        catch { }
        Start-Sleep -Milliseconds 300
    }
    if (-not $ready) { throw 'service did not start in time' }

    $status = Invoke-TrayPipe $trayPipe @{ cmd = 'GET_STATUS' }
    if ($status.event -ne 'STATUS') { throw "GET_STATUS failed: $($status | ConvertTo-Json -Compress)" }
    if ($status.devMode -ne $false) { throw 'unexpected devMode value' }
    if ($status.pairedCount -ne 0) { throw 'initial device count is not zero' }

    $pairing = Invoke-TrayPipe $trayPipe @{ cmd = 'GET_PAIRING' }
    if ($pairing.event -ne 'PAIRING' -or $null -ne $pairing.pairing) {
        throw "GET_PAIRING failed: $($pairing | ConvertTo-Json -Compress)"
    }

    $snapshot = Invoke-TrayPipe $trayPipe @{ cmd = 'GET_SNAPSHOT' }
    if ($snapshot.event -ne 'SNAPSHOT') {
        throw "GET_SNAPSHOT failed: $($snapshot | ConvertTo-Json -Compress)"
    }
    if ($snapshot.status.event -ne 'STATUS' -or $snapshot.status.pairedCount -ne 0) {
        throw "GET_SNAPSHOT status is invalid: $($snapshot | ConvertTo-Json -Compress)"
    }
    if ($null -ne $snapshot.pairing) {
        throw "GET_SNAPSHOT pairing should be null: $($snapshot | ConvertTo-Json -Compress)"
    }

    python $client --url "ws://127.0.0.1:$port/bandkey" --keyfile $key --pin 123456
    Start-Sleep -Milliseconds 300

    $devices = Invoke-TrayPipe $trayPipe @{ cmd = 'LIST_DEVICES' }
    if ($devices.event -ne 'DEVICES' -or $devices.devices.Count -ne 1) {
        throw "LIST_DEVICES failed: $($devices | ConvertTo-Json -Compress)"
    }

    $rpc = Invoke-TrayPipe $trayPipe @{
        cmd = 'RPC'
        request = '{"type":"unknown_probe","payload":{}}'
    }
    if ($rpc.event -ne 'RPC_RESULT' -or $rpc.reply -notmatch 'unknown_type') {
        throw "RPC forwarding failed: $($rpc | ConvertTo-Json -Compress)"
    }

    $deleted = Invoke-TrayPipe $trayPipe @{
        cmd = 'DELETE_DEVICE'
        deviceId = $devices.devices[0].deviceId
    }
    if ($deleted.event -ne 'DEVICE_DELETED' -or $deleted.success -ne $true) {
        throw "DELETE_DEVICE failed: $($deleted | ConvertTo-Json -Compress)"
    }

    $after = Invoke-TrayPipe $trayPipe @{ cmd = 'LIST_DEVICES' }
    if ($after.devices.Count -ne 0) { throw 'device list is not empty after delete' }

    $devProcess = Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -in @('dotnet.exe', 'BandKeyService.exe') -and
            $_.CommandLine -like '*BandKeyService.dll*' -and
            $_.CommandLine -like "*$suffix*"
        } |
        Select-Object -First 1
    if (-not $devProcess) { throw 'dev BandKeyService process not found' }

    $devPid = [int]$devProcess.ProcessId
    $beforeHandles = (Get-Process -Id $devPid).Handles
    for ($i = 0; $i -lt 60; $i++) {
        Invoke-TrayPipe $trayPipe @{ cmd = 'GET_SNAPSHOT' } | Out-Null
    }
    Start-Sleep -Milliseconds 800
    $afterHandles = (Get-Process -Id $devPid).Handles
    if ($afterHandles - $beforeHandles -gt 30) {
        throw "tray pipe handle leak: before=$beforeHandles after=$afterHandles"
    }
    Write-Output "handle_check: before=$beforeHandles after=$afterHandles"

    $httpBlocked = $false
    try {
        Invoke-WebRequest "http://127.0.0.1:$port/status" -TimeoutSec 2 | Out-Null
    }
    catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 404) {
            $httpBlocked = $true
        }
    }
    if (-not $httpBlocked) { throw 'HTTP /status is still reachable in production mode' }

    Write-Output 'pipe_management: PASS'
}
finally {
    Stop-Job $serviceJob -ErrorAction SilentlyContinue
    $serviceOutput = Receive-Job $serviceJob -ErrorAction SilentlyContinue
    Remove-Job $serviceJob -Force -ErrorAction SilentlyContinue
    if ($env:BANDKEY_TEST_VERBOSE -eq '1') {
        Write-Output '===== SERVICE OUTPUT ====='
        $serviceOutput
    }
}
