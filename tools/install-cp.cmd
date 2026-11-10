@echo off
setlocal

rem 以管理员身份安装最新编译的 BandKey Credential Provider。
rem 双击运行即可，脚本会自行请求提权；仅替换 DLL，不改注册表。

net session >nul 2>&1
if not "%errorlevel%"=="0" (
    powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -ArgumentList '%*' -Verb RunAs"
    exit /b
)

set "SRC=%~dp0..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"
set "DST=%WINDIR%\System32\BandKeyCredentialProvider.dll"

if not exist "%SRC%" (
    echo [ERROR] Missing build output: %SRC%
    if /i not "%1"=="/quiet" pause
    exit /b 1
)

copy /y "%SRC%" "%DST%" >nul
if errorlevel 1 (
    echo [ERROR] Copy failed. Close the lock screen and retry.
    if /i not "%1"=="/quiet" pause
    exit /b 1
)

echo Installed: %DST%
certutil -hashfile "%DST%" SHA256 | findstr /v ":"
echo.
echo Done. Lock the PC and test the unlock again.
if /i not "%1"=="/quiet" pause
