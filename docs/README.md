# BandKey 文档

本文档适用于 BandKey `1.8.7`。如果你只想安装和使用，先阅读
[快速开始](getting-started.md)；需要改代码或发布版本时，再阅读
[开发与发布](development.md)。

## 按目标选择

| 目标 | 文档 |
| --- | --- |
| 安装、配对、保存凭据、首次解锁 | [快速开始](getting-started.md) |
| 理解手机、服务、托盘和凭据提供程序的关系 | [系统架构](architecture.md) |
| 绑定 NFC 卡、排查息屏 NFC | [NFC 解锁](nfc.md) |
| 了解密钥、凭据、权限和威胁边界 | [安全模型](security.md) |
| 处理连接、配对、解锁和日志问题 | [故障排查](troubleshooting.md) |
| 编译、测试、打包和发布 | [开发与发布](development.md) |
| 检查更新、校验安装包、发布兼容要求 | [软件更新](updates.md) |
| 查看版本变化 | [版本记录](release-notes.md) |
| 查看通信消息、签名和内部管道 | [通信协议](protocol.md) / [协议 v2 状态](protocol-v2-status.md) |
| 查看页面设计稿和实现记录 | [设计文档](design/README.md) |

## 文档地图

```text
README.md                      项目入口、功能概览、最短上手路径
docs/
├── README.md                  本文档索引
├── getting-started.md         安装、配对、凭据、首次解锁
├── architecture.md            组件、通道、时序、运行时数据
├── nfc.md                     NFC 卡格式、自动解锁、厂商限制
├── security.md                信任边界、凭据存储、威胁模型
├── troubleshooting.md         症状式排障、日志、错误码
├── development.md             环境、构建、测试、GitHub Release
├── updates.md                 应用内更新、SHA-256 校验和发布约束
├── release-notes.md           1.8.x 版本记录
├── protocol.md                业务消息、签名、配对、解锁
├── protocol-v2-status.md      状态查询和最终解锁回执
└── design/                    页面设计稿、截图和实现记录
```

## 关键术语

| 术语 | 含义 |
| --- | --- |
| 配对 PIN | 电脑托盘显示的 6 位数字，只用于首次建立手机与电脑的信任关系 |
| Windows Hello PIN | Windows 系统 PIN，由 TPM/NGC 管理；第三方凭据提供程序不能读取或代替它 |
| Windows 解锁凭据 | BandKey 保存的 Windows 账户密码，用于凭据提供程序自动登录 |
| 通道 | 手机与电脑之间的 BLE、局域网 TCP 或 WebSocket 传输 |
| 凭据提供程序（CP） | 运行在 Winlogon 锁屏中的 C++ COM 组件，读取本机加密凭据并提交登录 |

## 文档约定

- 版本号以 `installer\BandKey.iss` 的 `AppVersion` 为准，当前为 `1.8.7`。
- Windows 路径默认使用反斜杠，命令默认在 PowerShell 中执行。
- “电脑端”指 Windows 服务、托盘和凭据提供程序的组合，不单指某一个进程。
- NFC 自动解锁属于 Android 系统分发能力，受手机厂商策略影响；文档会明确区分“已实现”和“已验证范围”。
- 截图和设计稿属于历史验收资料，若与当前代码冲突，以 `README.md`、协议文档和实际构建结果为准。
