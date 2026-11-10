# 将编译好的 BandKeyCredentialProvider.dll 安装到 System32（需要管理员权限）。
# 只替换 DLL；注册表项已由安装器写入，首次安装请用 installer\BandKey.iss 生成的安装包。
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$source = Join-Path $root 'windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll'
$target = Join-Path $env:WINDIR 'System32\BandKeyCredentialProvider.dll'

if (-not (Test-Path $source)) {
    throw "未找到编译产物：$source"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw '需要管理员权限：请右键“以管理员身份运行 PowerShell”后重试。'
}

Copy-Item -LiteralPath $source -Destination $target -Force

$sourceHash = (Get-FileHash $source -Algorithm SHA256).Hash
$targetHash = (Get-FileHash $target -Algorithm SHA256).Hash

Write-Host "来源: $source"
Write-Host "目标: $target"
Write-Host "SHA256: $sourceHash"
if ($sourceHash -ne $targetHash) {
    throw '安装后哈希不一致，请检查文件是否被占用。'
}

Write-Host '安装完成。下次锁屏时 LogonUI 会加载新版本。'
