$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"
$dll = Join-Path $root 'windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll'
$cfg = Join-Path $env:TEMP 'bandkey_pipe_devices.json'
$key = Join-Path $env:TEMP 'bandkey_pipe_phone_key.pem'
$probeOut = Join-Path $env:TEMP 'bandkey_pipe_probe.log'
$client = Join-Path $root 'tools\simulate_phone.py'
$port = 23236

Remove-Item $cfg, $key, $probeOut -ErrorAction SilentlyContinue

$serviceJob = Start-Job -ScriptBlock {
    param($d, $serviceDll, $config, $listenPort)
    & $d $serviceDll --urls "http://127.0.0.1:$listenPort" --config $config --pair-pin 123456 --dev
} -ArgumentList $dotnet, $dll, $cfg, $port

$probeJob = $null
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

    $probeJob = Start-Job -ScriptBlock {
        param($out, $seconds)
        $pipe = [System.IO.Pipes.NamedPipeClientStream]::new('.', 'BandKeyPipe', [System.IO.Pipes.PipeDirection]::InOut, [System.IO.Pipes.PipeOptions]::Asynchronous)
        $pipe.Connect(10000)
        $utf8 = New-Object System.Text.UTF8Encoding($false)
        $reader = [System.IO.StreamReader]::new($pipe, $utf8, $false, 1024, $true)
        $writer = [System.IO.StreamWriter]::new($pipe, $utf8, 1024, $true)
        $writer.AutoFlush = $true
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        $task = $reader.ReadLineAsync()
        while ($watch.Elapsed.TotalSeconds -lt $seconds) {
            if (-not $task.Wait(100)) { continue }
            $line = $task.Result
            if ($null -eq $line) { break }
            Add-Content -LiteralPath $out -Value $line
            if ($line -like 'AUTO_UNLOCK:*') {
                $id = $line.Substring('AUTO_UNLOCK:'.Length)
                $writer.WriteLine("AUTO_UNLOCK_ACK:$id")
            }
            $task = $reader.ReadLineAsync()
        }
        $pipe.Dispose()
    } -ArgumentList $probeOut, 30

    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        if ((Test-Path $probeOut) -and (Select-String -Path $probeOut -Pattern 'UNLOCK_READY' -Quiet)) {
            break
        }
        Start-Sleep -Milliseconds 200
    }

    python $client --url "ws://127.0.0.1:$port/bandkey" --keyfile $key --pin 123456
    Start-Sleep -Seconds 2
}
finally {
    if ($probeJob) {
        Stop-Job $probeJob -ErrorAction SilentlyContinue
        Remove-Job $probeJob -Force -ErrorAction SilentlyContinue
    }
    Stop-Job $serviceJob -ErrorAction SilentlyContinue
    $serviceOutput = Receive-Job $serviceJob -ErrorAction SilentlyContinue
    Remove-Job $serviceJob -Force -ErrorAction SilentlyContinue
}

Write-Output '===== PIPE PROBE ====='
if (Test-Path $probeOut) { Get-Content $probeOut }
Write-Output '===== SERVICE OUTPUT ====='
$serviceOutput
