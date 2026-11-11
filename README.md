# BandKey（电脑钥匙）

用手机或 NFC 卡解锁 Windows 电脑。BandKey 是一套完整自研实现，包含
Android 客户端、Windows 服务、Winlogon 凭据提供程序、BLE/局域网通道、托盘程序和
安装脚本。

> 当前版本：`1.8.4` · [下载最新版](https://github.com/QINGYUAI/BandKey/releases/latest) ·
> [快速开始](docs/getting-started.md) · [文档索引](docs/README.md)

## 功能

- **手机解锁**：手机私钥签名，Windows 服务验签并调用凭据提供程序登录。
- **BLE + 局域网回退**：默认 BLE GATT；失败后自动回退 LAN TCP，也可手动使用
  WebSocket。
- **NFC 钥匙**：手机读取空白 NDEF 卡，本地匹配令牌后复用同一签名解锁链路。
- **锁屏/息屏 NFC 自动解锁**：开启后由 Android NFC 系统分发启动，不要求 App 在
  前台；实际可用性受手机厂商策略影响。
- **桌面小组件**：支持 Android/MIUI 不同尺寸的一键解锁、状态展示和 NFC 开关。
- **本机凭据保护**：Windows 密码由 DPAPI `LocalMachine` 加密，CP 不联网。
- **科技风控制中心**：托盘、配对、设备和凭据窗口使用统一的“信任仪表”视觉。

## 工作方式

```text
Android App / 小组件 / NFC
        │  ECDSA P-256 签名
        ▼
BLE GATT / LAN TCP / WebSocket
        │  JSON
        ▼
BandKeyService（SYSTEM）
        │  验签、设备授权、DPAPI 凭据
        ▼
BandKeyPipe
        │
        ▼
Credential Provider（LogonUI）→ Windows 登录
```

托盘通过 `BandKeyTrayPipe` 管理服务、配对 PIN、设备和凭据。电脑端不读取 NFC 卡，
手机也不保存 Windows 密码。完整时序见 [系统架构](docs/architecture.md)。

## 安全模型

- 解锁由手机 ECDSA P-256 私钥签名，服务端校验 nonce 和时间戳，重放请求会被拒绝。
- 配对 PIN 只在电脑托盘显示，有效 5 分钟，连续输错 5 次锁定。
- Windows 密码只在托盘通过受保护管道提交，服务二次验证后用 DPAPI 加密保存。
- 凭据提供程序运行在 SYSTEM/Winlogon 上下文，只读取本机凭据文件，不直接联网。
- NFC 卡只保存随机令牌，不含 Windows 密码、手机私钥或原始 UID。
- 正式服务不暴露旧 HTTP 管理端点，局域网入口只接受私有地址和回环地址。

> 必须保存 **Windows 账户密码** 作为自动登录凭据，不能使用 Windows Hello PIN。
> 第三方 CP 无法读取或验证 Hello PIN。更多限制见 [安全模型](docs/security.md)。

## 快速开始

### 1. 安装 Windows 端

从 [Releases](https://github.com/QINGYUAI/BandKey/releases/latest) 下载
`BandKey-<版本>-windows-x64.exe`，以管理员身份运行。安装器会：

- 安装 `BandKeyService`、托盘程序和凭据提供程序
- 注册 COM CLSID / Credential Provider
- 配置专用/域网络和本地子网防火墙规则
- 自动启动服务，并在用户登录后启动托盘

首次安装或升级 CP 后请**重启电脑或注销再登录**，避免 LogonUI 继续加载旧 DLL。

### 2. 安装 Android 端

安装 `BandKey-<版本>-android.apk`，按系统提示授予附近设备/定位权限，并确认 NFC
已开启。默认连接模式为“蓝牙”；BLE 失败时自动尝试局域网。

### 3. 保存解锁凭据

双击托盘打开控制中心，选择“设置解锁凭据”，输入当前 Windows 账户的登录密码。
这不是配对 PIN，也不是 Windows Hello PIN。

### 4. 配对

在手机 App 中点击“配对”，电脑托盘会显示 6 位 PIN；将 PIN 输入手机完成配对。
配对成功后手机保存 `deviceId`、公钥和私钥，电脑保存已授权设备。

### 5. 锁定并测试

按 `Win+L` 锁定电脑，在手机上点击“解锁”。手机先收到“已受理”，再通过
`unlock_status` 查询 Windows 最终登录结果。

完整步骤和 NFC 绑定见 [快速开始](docs/getting-started.md)。

## 能力边界

- **Windows Hello PIN 不可用**：后台登录凭据必须是账户密码。真正的无密码方案需要
  改为证书登录，当前尚未实现。
- **NFC 自动解锁是尽力而为**：Android 通常只在屏幕解锁后扫描 NFC；锁屏/息屏分发
  取决于厂商 NFC、后台和锁屏策略。当前验证基线是亮屏可用，HyperOS 在真正息屏时
  可能停止轮询或清理后台。
- **小米“小部件中心”需要平台审核**：标准 `AppWidgetProvider` 只保证出现在
  “安卓小部件”。小米小部件中心需要应用商店上架、开放平台申请和审核，不能仅靠
  修改代码绕过。
- **没有互联网远程解锁**：设计目标是同机局域网/BLE。不要把服务端口映射到公网。
- **无应用内二次生物识别**：私钥由 Android Keystore 加密，但 App 本身不提供
  指纹/人脸确认，请为手机设置系统锁屏。

## 项目结构

```text
bandkey/
├── android/                              Kotlin + Compose 客户端
├── windows/
│   ├── BandKeyService/                   Windows 服务
│   ├── BandKeyTray/                      托盘、配对、凭据和 BLE GATT
│   ├── BandKeyBle/                       BLE 分帧与重组
│   └── BandKeyCredentialProvider/        Winlogon 凭据提供程序（C++17）
├── installer/BandKey.iss                 Inno Setup 安装脚本
├── build-release.ps1                     统一打包入口
├── tools/                                端到端、管道、LAN 和 BLE 测试
├── docs/                                 使用、架构、协议、安全和设计文档
└── dist/                                 本地构建产物（Git 忽略）
```

## 技术栈

| 模块 | 实现 |
| --- | --- |
| Windows 服务 | C# / .NET 8、Kestrel WebSocket、TCP/UDP、命名管道、ECDSA |
| 托盘与 BLE | .NET 8 WinForms、WinRT GATT、自绘暗色控件 |
| 凭据提供程序 | C++17 + WinSDK COM，运行于 LogonUI |
| Android | Kotlin、Jetpack Compose、OkHttp、Keystore、NFC、AppWidget |
| 打包 | Inno Setup 6、自包含单文件发布、Gradle Release |

## 开发

主要依赖：.NET 8 SDK、VS 2022 Build Tools（MSVC v143 + Windows SDK + CMake）、
Inno Setup 6、JDK 17、Android SDK 35。

```powershell
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"

& $dotnet build windows\BandKeyService\BandKeyService.csproj -c Release
& $dotnet build windows\BandKeyTray\BandKeyTray.csproj -c Release

cd windows\BandKeyCredentialProvider
powershell -ExecutionPolicy Bypass -File build.ps1
```

Android Release 构建需要本机 `android/keystore.properties` 和 keystore，二者均被
`.gitignore` 忽略。构建和签名说明见 [开发与发布](docs/development.md)。

### 测试

```powershell
powershell -ExecutionPolicy Bypass -File tools\e2e_test.ps1
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1
python tools\lan_smoke_test.py
```

### 统一打包

```powershell
.\build-release.ps1
.\build-release.ps1 -SkipAndroid
.\build-release.ps1 -SkipWindows
```

产物固定输出到：

```text
dist\<version>\BandKey-<version>-windows-x64.exe
dist\<version>\BandKey-<version>-android.apk
dist\latest\BandKey-Setup.exe
dist\latest\BandKey.apk
```

`dist/` 不进入 Git，发布时作为 Release 资产上传。详细流程见
[开发与发布](docs/development.md)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [快速开始](docs/getting-started.md) | 安装、配对、凭据、首次解锁、NFC 入口 |
| [系统架构](docs/architecture.md) | 组件、通道、时序、端口和运行时数据 |
| [NFC 解锁](docs/nfc.md) | 卡片格式、绑定、自动解锁和厂商限制 |
| [安全模型](docs/security.md) | 密钥、DPAPI、信任边界和威胁模型 |
| [故障排查](docs/troubleshooting.md) | 服务、连接、凭据、CP、NFC 和日志 |
| [开发与发布](docs/development.md) | 环境、构建、测试、打包和 GitHub Release |
| [版本记录](docs/release-notes.md) | 1.8.x 版本变化和升级说明 |
| [通信协议](docs/protocol.md) | 消息、签名、配对、解锁和错误码 |
| [协议 v2 状态](docs/protocol-v2-status.md) | 状态查询、最终回执和内部管道 |
| [设计文档](docs/design/README.md) | 页面设计稿、截图和实现记录 |

## 版本状态

- 协议版本：v2
- Android：`1.8.4` / `versionCode 11`
- Windows Service / Tray：`1.8.4`
- 最新 Release：[v1.8.4](https://github.com/QINGYUAI/BandKey/releases/tag/v1.8.4)

已知待完善：真实 Winlogon 全链路回归、证书登录、心跳与连接状态可视化、安装包体积
优化。
