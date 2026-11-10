# BandKey Credential Provider 构建脚本（建议以管理员运行）
# 前置：已安装 VS 2022 Build Tools，含 MSVC v143、Windows SDK、CMake 组件：
#   winget install --id Microsoft.VisualStudio.2022.BuildTools --accept-package-agreements --accept-source-agreements --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --add Microsoft.VisualStudio.Component.Windows11SDK.22621 --add Microsoft.VisualStudio.Component.VC.CMake.Project"
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir = Join-Path $root 'build'

$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
if (-not (Test-Path $vswhere)) {
    throw "未找到 vswhere，请先安装 VS 2022 Build Tools（见脚本顶部注释）。"
}

$vsPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
if (-not $vsPath) {
    $vsPath = (& $vswhere -all -products * -property installationPath) | Select-Object -First 1
}
if (-not $vsPath) {
    $defaultVs = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\2022\BuildTools'
    if (Test-Path (Join-Path $defaultVs 'VC\Tools\MSVC')) {
        $vsPath = $defaultVs
    }
}
if (-not $vsPath) {
    throw "未找到 MSVC v143 工具集，请安装 Microsoft.VisualStudio.Component.VC.Tools.x86.x64。"
}

$cmake = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin\cmake.exe'
if (-not (Test-Path $cmake)) {
    $cmd = Get-Command cmake -ErrorAction SilentlyContinue
    if ($cmd) { $cmake = $cmd.Source } else {
        throw "未找到 CMake，请安装 Microsoft.VisualStudio.Component.VC.CMake.Project。"
    }
}

& $cmake -S $root -B $buildDir -G "Visual Studio 17 2022" -A x64
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $cmake --build $buildDir --config Release
exit $LASTEXITCODE
