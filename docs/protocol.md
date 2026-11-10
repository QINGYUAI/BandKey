# BandKey 通信协议 v1

手机（Android）与 Windows 服务（`BandKeyService`）之间使用同一套 JSON 消息与签名协议，可运行在四种传输通道上：

| 通道 | 地址 | 编码 | 用途 |
| --- | --- | --- | --- |
| WebSocket | `ws://<主机IP>:23234/bandkey` | JSON 文本帧 | WiFi 手动配置模式 |
| BLE GATT | 服务 UUID `6e400001-...` | NDJSON（`\n` 结尾） | 默认模式，电脑托盘承载 GATT Server |
| LAN TCP | `<主机IP>:47321` | NDJSON（`\n` 结尾） | BLE 失败后的局域网回退 |
| LAN UDP | `<广播地址>:47322` | UTF-8 文本 | 局域网发现电脑 |

BLE 与 LAN 通道都只是替换传输层，消息体、签名与错误码完全一致。

## 信封格式

所有业务消息为 JSON 对象：

```json
{ "type": "<消息类型>", "payload": { "...": "..." } }
```

NDJSON 通道要求每条消息以 `\n` 结尾；单条消息上限 16 KiB。WebSocket 使用文本帧。

## 密码学约定

- 签名算法：`ECDSA P-256 / SHA-256`，签名为 **P1363 原始格式**（`r||s`，64 字节）的 Base64。
- 公钥编码：**SubjectPublicKeyInfo (SPKI) DER** 的 Base64，与 Java/Kotlin 的 `ECPublicKey.encoded`、C# 的 `ECDsa.ExportSubjectPublicKeyInfo()` 互通。
- 防重放：随机 `nonce`（8~128 字符）+ Unix 秒级 `timestamp`。服务端校验时间偏差 ±120 秒，并缓存 nonce 5 分钟。
- 配对 PIN 只用于建立首次信任；后续解锁只依赖手机私钥签名。

## 配对流程

### 1. 发起配对

```json
{
  "type": "pair_request",
  "payload": {
    "deviceName": "Pixel 9 Pro",
    "devicePublicKey": "<base64 SPKI>"
  }
}
```

服务端在电脑托盘生成 6 位 PIN（不回传给手机），返回：

```json
{ "type": "pair_challenge", "payload": { "pairingId": "…" } }
```

### 2. 手机输入 PIN 确认

```json
{
  "type": "pair_confirm",
  "payload": { "pairingId": "…", "pin": "123456" }
}
```

成功：

```json
{
  "type": "pair_success",
  "payload": { "deviceId": "…", "deviceName": "Pixel 9 Pro" }
}
```

PIN 有效期 5 分钟；确认成功或过期后失效。错误 PIN 可在有效期内重试，但同一配对请求连续输错 5 次后会立即锁定，需要重新发起配对。

## 解锁流程

手机对以下字符串做 UTF-8 编码后签名：

```text
unlock:<deviceId>:<nonce>:<timestamp>
```

请求：

```json
{
  "type": "unlock",
  "payload": {
    "deviceId": "…",
    "nonce": "随机串",
    "timestamp": 1730000000,
    "signature": "<base64 P1363>",
    "method": "nfc",
    "keyHint": "BK-NFC ··· 8F2A"
  }
}
```

- `method` 可选，值为 `phone` 或 `nfc`；缺省按 `phone` 处理。
- `keyHint` 可选，仅在手机本地 NFC 验签成功后附带，格式为 `BK-NFC ··· XXXX`。
- 两者只用于电脑端展示最近解锁方式，不参与授权。Windows 仍只信任 `signature`、`nonce` 与 `timestamp` 的校验结果。
- NFC 由手机读取卡片并在手机本地匹配令牌，电脑不需要 NFC 读卡器；签名原文不包含 `method` 或 `keyHint`。

服务端验签通过、凭据可用且凭据提供程序已连接时，返回：

```json
{
  "type": "unlock_result",
  "payload": { "ok": true, "error": null, "requestId": 1 }
}
```

`requestId` 单调递增，用于服务端与凭据提供程序之间的投递确认。

## 托盘状态接口

托盘的 `GET_STATUS` 命名管道命令返回最近一次成功投递的解锁摘要（旧版 `GET /status` 仅保留在 `--dev` 开发模式）：

```json
{
  "ready": true,
  "credentialSet": true,
  "pairedCount": 1,
  "lastUnlockMethod": "nfc",
  "nfcKeyHint": "BK-NFC ··· 8F2A",
  "nfcLastUsedAt": 1762777890123
}
```

| 字段 | 说明 |
| --- | --- |
| `lastUnlockMethod` | 最近成功投递的方式：`phone` 或 `nfc` |
| `nfcKeyHint` | 最近一次 NFC 验签通过的钥匙展示指纹 |
| `nfcLastUsedAt` | 最近一次 NFC 成功投递的 Unix 毫秒时间 |

这三个字段只保存在服务进程内存中，服务重启后清空；被拒绝、未锁定或未投递的请求不会更新它们。Windows 控制中心只展示该摘要，NFC 钥匙的绑定、重命名和删除均在手机端完成。

### 解锁时序

```text
手机 App
  └─ unlock 请求（私钥签名）
       └─ BandKeyService 验签 + 防重放
            ├─ 检查已配对设备、DPAPI 凭据、CP 连接
            ├─ AutoUnlockState 打开 90 秒投递窗口
            └─ BandKeyPipe: AUTO_UNLOCK:<requestId>
                 └─ Credential Provider
                      ├─ 读取 DPAPI 凭据
                      ├─ 调用 KerbInteractiveLogon
                      └─ BandKeyPipe: AUTO_UNLOCK_ACK:<requestId>
```

如果系统拒绝保存的 Windows 密码，CP 回写 `PASSWORD_FAILED`。服务进入 `credentialRejected` 状态，停止后续自动解锁，直到用户在托盘重新保存并验证密码。

`UNLOCK_READY` 只表示“已配对且凭据可用”或“当前处于 90 秒投递窗口”，不代表电脑一定处于锁屏状态；真正的解锁仍要求手机对每次请求做私钥签名。

## 错误响应

```json
{ "type": "error", "payload": { "code": "bad_signature", "message": "解锁请求被拒绝" } }
```

错误码：

| code | 含义 |
| --- | --- |
| `bad_request` | 缺少字段或参数无效 |
| `bad_json` | JSON 解析失败 |
| `bad_key` | 公钥格式无效 |
| `unknown_type` | 未知消息类型 |
| `unknown_pairing` / `wrong_pin` / `pairing_locked` | 配对不存在、PIN 错误或错误次数过多已锁定 |
| `unknown_device` | 设备未配对或已被删除 |
| `timestamp_out_of_range` | 时间戳非法或超出 ±120 秒 |
| `invalid_nonce` / `replayed_nonce` | nonce 非法或已使用 |
| `bad_signature` | 签名验证失败 |
| `no_credential` | 尚未保存 Windows 解锁凭据 |
| `password_invalid` | 保存的密码已被 Windows 拒绝 |
| `computer_not_locked` | 电脑未锁定或凭据提供程序未连接 |
| `unlock_not_ready` | 当前没有可用的自动解锁条件 |
| `internal` | 服务端内部错误 |

## 局域网发现

Android 向 UDP `47322` 广播：

```text
BANDKEY_DISCOVER
```

服务端仅对私有地址/回环地址响应：

```json
{
  "event": "BANDKEY_PC",
  "name": "DESKTOP-ABC",
  "port": 47321,
  "addresses": ["192.168.1.10"]
}
```

手机以响应报文的源地址建立 TCP 连接。发现报文本身不授予任何权限，后续仍需正常配对与签名。

## 服务端内部管道

### BandKeyPipe（服务 ↔ 凭据提供程序）

双向、按行文本，ACL 仅允许 `SYSTEM`（管理员不参与 Winlogon 读取）：

| 方向 | 消息 | 说明 |
| --- | --- | --- |
| 服务 → CP | `UNLOCK_READY:true` / `UNLOCK_READY:false` | 凭据与配对状态变化时广播 |
| 服务 → CP | `AUTO_UNLOCK:<requestId>` | 投递一次自动解锁请求 |
| CP → 服务 | `AUTO_UNLOCK_ACK:<requestId>` | 确认收到并已尝试投递 |
| CP → 服务 | `PASSWORD_FAILED` | Windows 拒绝保存的密码 |

### BandKeyTrayPipe（托盘 ↔ 服务）

双向、按行 JSON，ACL 允许 `SYSTEM` / 管理员全权限、已认证用户读写（开发模式放宽为全权限）：

保存凭据：

```json
{ "cmd": "SAVE_CREDENTIAL", "domain": "DESKTOP-ABC", "username": "alice", "password": "…" }
```

响应：

```json
{ "event": "CREDENTIAL_SAVED", "success": true, "error": "" }
```

服务会做三层校验后再写入：

1. 管道客户端身份必须与提交的 Windows 账户一致（`GetImpersonationUserName`）。
2. 使用 `LogonUser` 实际验证账户密码。
3. 通过 `DPAPI LocalMachine` 加密后原子写入 `C:\ProgramData\BandKey`。

读取状态：

```json
{ "cmd": "GET_STATUS" }
```

```json
{
  "event": "STATUS",
  "ready": true,
  "credentialSet": true,
  "credentialRejected": false,
  "credentialProviderConnected": true
}
```

### BandKeyUnlock（旧版兼容）

单向管道，服务在每次自动解锁请求时写入一行 `UNLOCK\n`。仅用于兼容旧版凭据提供程序；新版优先使用 `AUTO_UNLOCK` + ACK 链路。

## 安全边界与限制

- 第三方 Credential Provider **无法读取或验证 Windows Hello PIN、指纹或人脸**。因此后台解锁凭据必须是 Windows 账户密码，托盘界面会明确提示“不是 PIN”。
- 凭据只保存在本机，使用 DPAPI `LocalMachine` 加密；不会通过网络发送。
- 局域网 TCP、UDP 发现和 WebSocket 入口都只接受私有地址与回环地址，公共网络来源会被拒绝。
- 所有解锁请求都必须通过 ECDSA 私钥签名与 nonce/时间戳校验，重放请求会被拒绝。
