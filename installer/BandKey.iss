; 电脑钥匙 / BandKey 安装脚本
; 编译前先发布自包含版本：
;   dotnet publish windows\BandKeyService  -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
;   dotnet publish windows\BandKeyTray     -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true
; 并用 CMake 编译 BandKeyCredentialProvider.dll

#define AppVersion "1.8.7"
#define RepositoryUrl "https://github.com/QINGYUAI/BandKey"
#define DocsUrl "https://github.com/QINGYUAI/BandKey/tree/main/docs"
#define ReleasesUrl "https://github.com/QINGYUAI/BandKey/releases/latest"

[Setup]
AppName=电脑钥匙
; 1.7.0 安装器未显式设置 AppId，Inno Setup 默认使用 AppName 作为标识。
; 这里保留相同值，确保 1.8.x 能识别并覆盖升级旧版本。
AppId=电脑钥匙
AppVersion={#AppVersion}
AppPublisher=科技包
AppPublisherURL={#RepositoryUrl}
AppSupportURL={#DocsUrl}
AppUpdatesURL={#ReleasesUrl}
DefaultDirName={autopf}\BandKey
DefaultGroupName=电脑钥匙
DisableProgramGroupPage=yes
DisableWelcomePage=no
OutputDir=..\dist\{#AppVersion}
OutputBaseFilename=BandKey-{#AppVersion}-windows-x64
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64os
ArchitecturesInstallIn64BitMode=x64os
PrivilegesRequired=admin
UninstallDisplayIcon={app}\BandKeyTray.exe
SetupIconFile=assets\BandKey.ico
WizardStyle=modern dynamic
WizardImageFile=assets\wizard-large.png
WizardImageFileDynamicDark=assets\wizard-large.png
WizardImageStretch=no
WizardSmallImageFile=assets\wizard-small.png
WizardSmallImageFileDynamicDark=assets\wizard-small.png
VersionInfoVersion={#AppVersion}
VersionInfoCompany=科技包
VersionInfoDescription=BandKey 电脑钥匙安装程序
VersionInfoProductName=电脑钥匙
VersionInfoProductVersion={#AppVersion}

[Languages]
Name: "chinesesimplified"; MessagesFile: "ChineseSimplified.isl"

[Messages]
SetupAppTitle=安装程序
SetupWindowTitle=安装 %1
WelcomeLabel1=部署你的无感解锁工作站
WelcomeLabel2=即将安装 [name/ver]。%n%n安装包含 Windows 服务、托盘控制中心与登录凭据组件。安装前请保存工作并关闭其他应用程序。
FinishedHeadingLabel=安装完成
FinishedLabel=[name] 已安装到你的电脑。%n%n请重启电脑，让登录界面加载新的凭据组件。%n%n版本说明与软件更新可在托盘的「检查软件更新」中查看。

[Files]
Source: "..\windows\BandKeyService\bin\Release\net8.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
Source: "..\windows\BandKeyTray\bin\Release\net8.0-windows10.0.19041.0\win-x64\publish\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs
#ifexist "..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"
Source: "..\windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll"; DestDir: "{sys}"; Flags: restartreplace uninsrestartdelete
#endif

[Dirs]
Name: "{commonappdata}\BandKey"
Name: "{commonappdata}\BandKey\outcomes"

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
Filename: "{sys}\icacls.exe"; Parameters: """{commonappdata}\BandKey\outcomes"" /inheritance:r /grant:r ""*S-1-5-18:(OI)(CI)F"" ""*S-1-5-32-544:(OI)(CI)F"" /C"
Filename: "{sys}\sc.exe"; Parameters: "create BandKeyService binPath= ""{app}\BandKeyService.exe"" start= auto DisplayName= ""电脑钥匙服务"""; Flags: runhidden; Check: not ServiceExists
Filename: "{sys}\sc.exe"; Parameters: "config BandKeyService binPath= ""{app}\BandKeyService.exe"" start= auto DisplayName= ""电脑钥匙服务"""; Flags: runhidden; Check: ServiceExists
Filename: "{sys}\sc.exe"; Parameters: "failure BandKeyService reset= 86400 actions= restart/5000/restart/15000/restart/60000"; Flags: runhidden
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
function ServiceExists(): Boolean;
var
  ResultCode: Integer;
begin
  Result := Exec(ExpandConstant('{sys}\sc.exe'), 'query BandKeyService', '', SW_HIDE, ewWaitUntilTerminated, ResultCode) and (ResultCode = 0);
end;

procedure ConfigureFirewall;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="BandKey WebSocket"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="BandKey LAN TCP"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall delete rule name="BandKey LAN UDP"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);

  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall add rule name="BandKey WebSocket" dir=in action=allow protocol=TCP localport=23234 profile=private,domain remoteip=localsubnet program="' + ExpandConstant('{app}\BandKeyService.exe') + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall add rule name="BandKey LAN TCP" dir=in action=allow protocol=TCP localport=47321 profile=private,domain remoteip=localsubnet program="' + ExpandConstant('{app}\BandKeyService.exe') + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\netsh.exe'), 'advfirewall firewall add rule name="BandKey LAN UDP" dir=in action=allow protocol=UDP localport=47322 profile=private,domain remoteip=localsubnet program="' + ExpandConstant('{app}\BandKeyService.exe') + '"', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then
  begin
    ConfigureFirewall;
  end;
end;

function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/F /IM BandKeyTray.exe', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Exec(ExpandConstant('{sys}\net.exe'), 'stop BandKeyService /y', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Sleep(500);
  Result := '';
end;
