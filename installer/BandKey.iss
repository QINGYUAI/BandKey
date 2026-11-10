; 电脑钥匙 / BandKey 安装脚本
; 编译前先发布自包含版本：
;   dotnet publish windows\BandKeyService  -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
;   dotnet publish windows\BandKeyTray     -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
; 并用 CMake 编译 BandKeyCredentialProvider.dll

[Setup]
AppName=电脑钥匙
AppVersion=1.7.0
AppPublisher=科技包
DefaultDirName={autopf}\BandKey
DefaultGroupName=电脑钥匙
DisableProgramGroupPage=yes
OutputBaseFilename=BandKey-1.7.0-windows-x64
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
PrivilegesRequired=admin
UninstallDisplayIcon={app}\BandKeyTray.exe

[Files]
Source: "..\windows\BandKeyService\bin\Release\net8.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "..\windows\BandKeyTray\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
#ifexist "..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"
Source: "..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"; DestDir: "{sys}"; Flags: restartreplace uninsrestartdelete
#endif

[Dirs]
Name: "{commonappdata}\BandKey"

[Registry]
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Run"; ValueName: "BandKeyTray"; ValueType: String; ValueData: """{app}\BandKeyTray.exe"""; Flags: uninsdeletevalue
#ifexist "..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"
Root: HKLM; Subkey: "SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}"; ValueType: String; ValueData: "BandKeyCredentialProvider"; Flags: uninsdeletekey
Root: HKCR; Subkey: "CLSID\{{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}"; ValueType: String; ValueData: "BandKeyCredentialProvider"; Flags: uninsdeletekey
Root: HKCR; Subkey: "CLSID\{{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}\InprocServer32"; ValueType: String; ValueData: "{sys}\BandKeyCredentialProvider.dll"; Flags: uninsdeletekey
Root: HKCR; Subkey: "CLSID\{{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}\InprocServer32"; ValueName: "ThreadingModel"; ValueType: String; ValueData: "Apartment"; Flags: uninsdeletekey
#endif

[Run]
Filename: "{sys}\icacls.exe"; Parameters: """{commonappdata}\BandKey"" /inheritance:r /grant:r ""*S-1-5-18:(OI)(CI)F"" ""*S-1-5-32-544:(OI)(CI)F"" /C"
Filename: "{sys}\sc.exe"; Parameters: "create BandKeyService binPath= ""{app}\BandKeyService.exe"" start= auto DisplayName= ""电脑钥匙服务"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "failure BandKeyService reset= 86400 actions= restart/5000/restart/15000/restart/60000"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""BandKey WebSocket"" dir=in action=allow protocol=TCP localport=23234 profile=any program=""{app}\BandKeyService.exe"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""BandKey LAN TCP"" dir=in action=allow protocol=TCP localport=47321 profile=any program=""{app}\BandKeyService.exe"""; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall add rule name=""BandKey LAN UDP"" dir=in action=allow protocol=UDP localport=47322 profile=any program=""{app}\BandKeyService.exe"""; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "start BandKeyService"; Flags: runhidden
Filename: "{app}\BandKeyTray.exe"; Description: "启动电脑钥匙托盘"; Flags: postinstall nowait skipifsilent runasoriginaluser

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/F /IM BandKeyTray.exe"; RunOnceId: "StopBandKeyTray"; Flags: runhidden
Filename: "{sys}\net.exe"; Parameters: "stop BandKeyService /y"; RunOnceId: "StopBandKeyService"; Flags: runhidden
Filename: "{sys}\sc.exe"; Parameters: "delete BandKeyService"; RunOnceId: "DeleteBandKeyService"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""BandKey WebSocket"""; RunOnceId: "DeleteFirewall"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""BandKey LAN TCP"""; RunOnceId: "DeleteLanTcpFirewall"; Flags: runhidden
Filename: "{sys}\netsh.exe"; Parameters: "advfirewall firewall delete rule name=""BandKey LAN UDP"""; RunOnceId: "DeleteLanUdpFirewall"; Flags: runhidden

[UninstallDelete]
Type: filesandordirs; Name: "{commonappdata}\BandKey"

[Code]
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM BandKeyTray.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\sc.exe'), 'stop BandKeyService', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(2500);
  Result := '';
end;
