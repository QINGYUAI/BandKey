# 系统架构

BandKey 由 Android 客户端、Windows 系统服务、用户会话托盘和 Winlogon
凭据提供程序组成。手机负责持有私钥并签名，电脑负责验签、保存本机凭据和提交
Windows 登录。

## 组件关系

```text
┌──────────────── Android ────────────────┐
│ App / 小组件 / NFC 自动解锁 Activity    │
│  ├─ ECDSA P-256 私钥（Android Keystore）│
│  ├─ BLE Central                         │
│  ├─ LAN TCP / UDP 发现                  │
│  └─ WebSocket                           │
└───────────────────┬─────────────────────┘
                    │ JSON + ECDSA 签名
                    ▼
┌──────────────── Windows ────────────────────────────┐
│ BandKeyService（SYSTEM，自动启动）                  │
│  ├─ WebSocket :23234 / TCP :47321 / UDP :47322      │
│  ├─ 配对、设备授权、nonce/时间戳校验                │
│  ├─ DPAPI 凭据存储                                  │
│  └─ 命名管道                                        │
│       ├─ BandKeyPipe ── Credential Provider（LogonUI）│
│       └─ BandKeyTrayPipe ── BandKeyTray（用户会话）  │
└─────────────────────────────────────────────────────┘
```

## 组件职责

| 组件 | 运行位置 | 职责 |
| --- | --- | --- |
| `BandKeyService` | Windows 服务，LocalSystem | 接收手机请求、验签、管理设备和凭据、维护最终解锁状态、向 CP 投递请求 |
| `BandKeyTray` | 当前用户桌面会话 | 托盘 UI、配对 PIN、设备管理、凭据设置、BLE GATT Server、状态展示 |
| `BandKeyBle` | 托盘进程引用 | BLE GATT 外设、分帧和消息重组 |
| `BandKeyCredentialProvider` | LogonUI / Winlogon | 读取 DPAPI 凭据、构造登录凭据、调用 KerbInteractiveLogon、回写最终结果 |
| Android App | 手机 | 配对、保存私钥、发起解锁、NFC 验签、小组件和状态展示 |

BLE 监听放在托盘而不是服务中：WinRT `GattServiceProvider` 在 Session 0 的服务进程
中不可用，用户会话中的托盘才能正常广播。托盘通过 `BandKeyTrayPipe` 把请求转发给
服务，正式服务不暴露 HTTP `/rpc`。

### Android 持久化与回退

- 配对成功后，`deviceId`、公钥和私钥写入 `PairingStore`。
- 记录先序列化，再由 Android Keystore 的 AES-256-GCM 密钥加密，密文放入
  `SharedPreferences`。
- App 重启会自动恢复配对；解除配对会清除本地密文。
- “蓝牙”模式按 BLE → LAN TCP 自动回退；“WiFi”模式直接连接
  `ws://<主机IP>:23234/bandkey`。

## 通信通道

| 通道 | 地址 | 编码 | 用途 |
| --- | --- | --- | --- |
| BLE GATT | 服务 UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | NDJSON | 默认移动端通道 |
| LAN TCP | `<电脑IP>:47321` | NDJSON | BLE 失败后的局域网回退 |
| LAN UDP | `47322` | 文本/JSON | 局域网发现，不承载解锁消息 |
| WebSocket | `ws://<电脑IP>:23234/bandkey` | JSON 文本帧 | WiFi/手动配置 |

BLE 和 LAN 只是替换传输层；消息、签名和错误码保持一致。协议细节见
[protocol.md](protocol.md)。

BLE GATT 特征：

| 项目 | 值 |
| --- | --- |
| 服务 UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX（手机写入） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX（电脑通知） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| 分帧 | NDJSON，`\n` 结尾，单条上限 8 KiB |
| 属性权限 | `Plain`；安全性由应用层 ECDSA 签名保证 |

手机作为 BLE Central，电脑托盘作为 BLE Peripheral。

## 配对时序

```text
手机                        电脑服务                       托盘
 │  pair_request              │                            │
 │───────────────────────────>│ 生成 pairingId / PIN       │
 │  pair_challenge            │───────────────────────────>│ 显示 6 位 PIN
 │<───────────────────────────│                            │
 │  用户输入 PIN              │                            │
 │  pair_confirm              │                            │
 │───────────────────────────>│ 校验 PIN、保存设备公钥     │
 │  pair_success              │                            │
 │<───────────────────────────│                            │
```

PIN 不回传手机，手机只在本地输入。配对成功后，后续解锁只依赖手机私钥签名。

## 解锁时序

```text
手机私钥签名
  → BandKeyService 验签、校验 nonce/时间戳、检查设备授权
  → 检查 DPAPI 凭据和 CP 连接状态
  → AutoUnlockState 打开 90 秒投递窗口
  → BandKeyPipe: AUTO_UNLOCK:<requestId>
  → Credential Provider 读取凭据并提交 Windows 登录
  → AUTO_UNLOCK_ACK / AUTO_UNLOCK_SUBMITTED
  → UNLOCK_RESULT:<requestId>:<result>
  → 手机 unlock_status 查询最终状态
```

状态只能单向推进：

```text
accepted → submitted → succeeded
                     ↘ failed
```

- `accepted`：服务端验签通过并已投递。
- `submitted`：CP 已把凭据交给 Windows。
- `succeeded`：CP 的 `ReportResult` 报告成功。
- `failed`：凭据错误、登录失败、组件断开或结果超时。
- `unknown`：服务重启或请求记录已过期，手机应重新发起。

`unlock_result` 中的 `ok=true` 并不等于 Windows 已解锁。最终结果必须通过
`unlock_status` 查询，详见 [protocol-v2-status.md](protocol-v2-status.md)。

## 本地管理命令

托盘以普通用户权限运行，通过 `BandKeyTrayPipe` 与服务交互。服务端会校验管道客户端
身份必须匹配当前控制台登录用户（`--dev` 开发模式除外）：

| 命令 | 用途 |
| --- | --- |
| `GET_STATUS` | 就绪、凭据、CP、LAN 监听和最近 NFC 验证状态 |
| `GET_PAIRING` | 当前待确认配对请求 |
| `LIST_DEVICES` | 已配对设备列表 |
| `DELETE_DEVICE` | 删除指定设备 |
| `SAVE_CREDENTIAL` | 校验并保存当前用户的 Windows 登录凭据 |
| `RPC` | BLE 托盘转发的配对/解锁协议 |

HTTP 只保留 `GET /health`；`/status`、`/pairing`、`/devices`、`/credential` 等旧管理
端点仅在 `--dev` 开发模式注册。联调时可用 `--pipe-suffix _test_xxx` 给管道加后缀，
避免与已安装服务冲突。

## 运行时数据和端口

| 项目 | 位置/值 |
| --- | --- |
| 设备与凭据 | `C:\ProgramData\BandKey` |
| Windows 凭据密文 | `C:\ProgramData\BandKey\credential.dat` |
| Windows 用户名 | `C:\ProgramData\BandKey\username.txt` |
| CP 最终结果补交 | `C:\ProgramData\BandKey\outcomes\<requestId>.result` |
| Windows 服务日志 | `C:\ProgramData\BandKey\service.log` |
| 凭据提供程序日志 | `C:\ProgramData\BandKey\cp.log` |
| 托盘日志 | `%LOCALAPPDATA%\BandKey\tray.log` |
| Android NFC 事件 | App 私有目录 `files/nfc-events.log` |
| WebSocket | TCP `23234` |
| LAN TCP / UDP | TCP `47321` / UDP `47322` |

## 技术栈

| 模块 | 实现 |
| --- | --- |
| Windows 服务 | C# / .NET 8、Kestrel WebSocket、TCP/UDP、命名管道、ECDSA P-256 |
| 托盘和 BLE | .NET 8 WinForms、WinRT GATT、自绘控件 |
| 凭据提供程序 | C++17、WinSDK COM、Winlogon Credential Provider |
| Android | Kotlin、Jetpack Compose、OkHttp、Android Keystore、NFC、AppWidget |
| 打包 | Inno Setup 6、自包含单文件发布、Gradle Release 构建 |

## 设计约束

1. 电脑端不读取 NFC 卡；NFC 只是手机本地的身份验证入口。
2. 电脑端不保存手机私钥；手机端不保存 Windows 密码。
3. 第三方 CP 无法读取 Windows Hello PIN，因此后台凭据必须使用账户密码。
4. CP 运行在 LogonUI 的受限环境，不依赖网络和复杂动画。
5. 局域网入口只接受私有地址或回环地址，防火墙规则只开放本地子网。
