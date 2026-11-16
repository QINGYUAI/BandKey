# 开发与发布

本文说明如何从源码构建 BandKey、运行测试、生成安装包并发布到 GitHub Releases。

## 环境要求

| 工具 | 版本/用途 |
| --- | --- |
| .NET SDK | 8.0，构建 Windows 服务和托盘 |
| Visual Studio 2022 Build Tools | MSVC v143、Windows 11 SDK 22621、CMake，构建 CP |
| Inno Setup | 6.x，生成 Windows 安装包 |
| JDK | 17，构建 Android |
| Android SDK | Platform 35、Build Tools；设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` |
| Python | 3.x，运行 LAN smoke test |
| GitHub CLI | 可选，创建 Release 和上传资产 |

CP 构建组件可一次安装：

```powershell
winget install --id Microsoft.VisualStudio.2022.BuildTools `
  --accept-package-agreements --accept-source-agreements `
  --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --add Microsoft.VisualStudio.Component.Windows11SDK.22621 --add Microsoft.VisualStudio.Component.VC.CMake.Project"
```

## 仓库结构

```text
android/                            Kotlin + Compose 客户端
windows/BandKeyService/             Windows 服务
windows/BandKeyTray/                托盘和 BLE GATT
windows/BandKeyBle/                 BLE 实现
windows/BandKeyCredentialProvider/  C++ 凭据提供程序
installer/BandKey.iss               Inno Setup 脚本
tools/                              端到端、管道和 LAN 测试
build-release.ps1                   统一打包入口
docs/                               使用、协议、安全和设计文档
dist/                               本地构建产物，Git 忽略
```

## Windows 开发构建

发布脚本默认查找 `$env:USERPROFILE\.dotnet\dotnet.exe`。如果你的 .NET SDK 在
其他位置，请先确认脚本所需路径，或调整 `build-release.ps1` 中的 `$dotnet`。

```powershell
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"

& $dotnet build windows\BandKeyService\BandKeyService.csproj -c Release
& $dotnet build windows\BandKeyTray\BandKeyTray.csproj -c Release

cd windows\BandKeyCredentialProvider
powershell -ExecutionPolicy Bypass -File build.ps1
```

CP 产物：

```text
windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll
```

手工注册需要管理员权限：

```powershell
regsvr32 windows\BandKeyCredentialProvider\build\Release\BandKeyCredentialProvider.dll
```

开发模式服务不会注册旧版 `BandKeyUnlock` 管道，避免与已安装服务冲突：

```powershell
$dll = "windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll"
& $dotnet $dll --urls http://127.0.0.1:23234 `
  --config "$env:TEMP\bandkey_devices.json" --pair-pin 123456 --dev
```

`--dev` 会放宽凭据和 CP 连接检查，只用于联调，不能用于正式安装。开发服务的设备
文件位于 `%TEMP%`，不要拿它和 `C:\ProgramData\BandKey\devices.json` 比较。

## Android 构建

### 签名配置

Release 构建需要本机 `android/keystore.properties` 和对应 keystore。两者都被
`.gitignore` 忽略，不会随仓库分发。新环境请创建自己的签名：

```powershell
keytool -genkeypair -v -keystore android\bandkey-release.keystore `
  -alias bandkey -keyalg RSA -keysize 2048 -validity 10000
```

`android/keystore.properties` 示例：

```properties
storeFile=bandkey-release.keystore
storePassword=替换为你的密码
keyAlias=bandkey
keyPassword=替换为你的密码
```

正式发布应使用自己的密钥并离线备份；丢失签名密钥后无法覆盖升级已安装的 APK。

### 构建命令

```powershell
cd android
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 assembleRelease
```

如果依赖下载需要代理，可设置：

```powershell
$env:BANDKEY_HTTP_PROXY = "http://127.0.0.1:7892"
```

Debug 产物位于 `android\app\build\outputs\apk\debug\app-debug.apk`。Release 构建
会通过 Gradle finalizer 复制到 `dist\latest\BandKey.apk` 和
`dist\<version>\BandKey-<version>-android.apk`。

Android 工程参数：`compileSdk=35`、`targetSdk=35`、`minSdk=26`、JVM 17。

## 测试

协议端到端测试：

```powershell
powershell -ExecutionPolicy Bypass -File tools\e2e_test.ps1
```

自动解锁管道测试：

```powershell
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1 -DropAfterSubmit
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1 -DropWithoutResult
```

三种场景分别验证正常回执、CP 断开后结果文件补交、断开后无结果超时。预期：

- 正常：`state=succeeded`
- `-DropAfterSubmit`：8 秒宽限期内补收成功
- `-DropWithoutResult`：`state=failed`、`error=component_disconnected`

LAN 测试：

```powershell
python tools\lan_smoke_test.py
```

测试脚本会自动选择备用端口，避免干扰已安装在 `23234` 上运行的服务。PowerShell
5.1 对无 BOM 中文脚本较敏感，联调脚本保持 ASCII。

BLE 自检：

```powershell
& $dotnet run --project tools\BleProbe -c Release -- --serve
& $dotnet run --project tools\BleProbe -c Release -- --framing
```

本地管理管道回归：

```powershell
powershell -ExecutionPolicy Bypass -File tools\pipe_management_test.ps1
```

BLE 需要 Windows 10 1809+ 和支持 Peripheral 角色的适配器。同一适配器同时广播和
扫描时，本机通常扫描不到自己的广播，这是协议栈行为。

## 版本同步

发布前必须同步以下位置，`build-release.ps1` 会校验三处版本一致：

| 文件 | 字段 |
| --- | --- |
| `installer\BandKey.iss` | `#define AppVersion "x.y.z"` |
| `windows\BandKeyService\BandKeyService.csproj` | `<Version>x.y.z</Version>` |
| `windows\BandKeyTray\BandKeyTray.csproj` | `<Version>x.y.z</Version>` |
| `android\app\build.gradle.kts` | `bandKeyVersionName` 和 `versionCode` |

`versionCode` 必须单调递增，不能重复使用已发布的值。当前版本为 `1.8.7` /
`versionCode 14`。

## 统一打包

完整构建 Windows 和 Android：

```powershell
.\build-release.ps1
```

只构建一端：

```powershell
.\build-release.ps1 -SkipAndroid
.\build-release.ps1 -SkipWindows
```

产物固定输出：

```text
dist/
├── 1.8.7/
│   ├── BandKey-1.8.7-windows-x64.exe
│   ├── BandKey-1.8.7-android.apk
│   └── SHA256SUMS.txt
└── latest/
    ├── BandKey-Setup.exe
    ├── BandKey.apk
    ├── SHA256SUMS.txt
    └── VERSION.txt
```

`dist/` 已被 `.gitignore` 忽略，安装包作为 GitHub Release 资产上传，不进入源码提交。

应用内更新依赖固定的资产命名和校验文件，发布时必须保留：

- `BandKey-<version>-windows-x64.exe`
- `BandKey-<version>-android.apk`
- `SHA256SUMS.txt`

如果缺少对应平台的安装包或校验文件，客户端会展示原因并禁止直接安装。详细流程见
[软件更新](updates.md)。

不要并行执行 `dotnet publish` 和 `ISCC.exe`：安装器会读取发布目录，并发可能造成
缺文件或旧文件混入。

## GitHub Release

建议流程：

1. 更新版本号和 `docs/release-notes.md`，并把当前版本段落另存为临时
   `release-body.md`；不要直接把累计版本记录作为 Release 说明上传。
2. 完整构建并执行测试。
3. 校验 `dist\<version>\SHA256SUMS.txt`。
4. 提交并推送，创建带注释的版本标签。
5. 创建 Release 并上传 Windows、Android 和校验文件。

命令示例（将 `x.y.z` 替换为实际版本）：

```powershell
git status -sb
git add .
git commit -m "feat: 发布 x.y.z"
git push origin main
git tag -a vx.y.z -m "BandKey x.y.z"
git push origin vx.y.z

gh release create vx.y.z `
  "dist\x.y.z\BandKey-x.y.z-android.apk" `
  "dist\x.y.z\BandKey-x.y.z-windows-x64.exe" `
  "dist\x.y.z\SHA256SUMS.txt" `
  --verify-tag --latest --title "BandKey x.y.z" --notes-file .\release-body.md
```

发布后核对：

```powershell
gh release view vx.y.z
git status -sb
```

确认资产名、大小、状态都为 `uploaded`，且 Release 标记为 Latest。已存在 Release
需要补传时使用 `gh release upload vx.y.z <files> --clobber`。

## 文档维护

发布涉及用户可见行为时，至少同步更新：

- `README.md` 的功能和最短上手路径
- `docs/getting-started.md` 的安装/配置变化
- `docs/nfc.md` 的卡片兼容和厂商限制
- `docs/troubleshooting.md` 的新错误和日志
- `docs/release-notes.md` 的版本变化
- 协议变化时的 `docs/protocol*.md`
