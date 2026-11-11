# 安全模型

BandKey 的目标是把“手机或 NFC 卡持有者”转换为一次 Windows 登录尝试。它不会绕过
Windows 自身的登录校验，也不能替代 Windows Hello PIN。理解下面的信任边界，有助于
正确部署和评估风险。

## 保护对象

| 数据 | 所在位置 | 保护方式 |
| --- | --- | --- |
| 手机 ECDSA 私钥 | Android App 私有存储 | Android Keystore AES-256-GCM 加密 |
| 配对记录 | Android App 私有存储 | Android Keystore 加密 |
| NFC 令牌 | 实体卡 + 手机本地 SHA-256 指纹 | 随机 32 字节令牌；手机记录加密 |
| Windows 账户密码 | `C:\ProgramData\BandKey\credential.dat` | DPAPI `LocalMachine` 加密 |
| 设备授权列表 | Windows 服务数据目录 | ACL 限制为 SYSTEM/管理员 |
| 解锁最终结果 | `C:\ProgramData\BandKey\outcomes` | SYSTEM/管理员 ACL |

## 信任边界

```text
手机 App
  ├─ 私钥只在手机本地使用
  └─ 每次解锁都生成 nonce/时间戳并签名
           │
           ▼
局域网/BLE/WebSocket 通道（不承载 Windows 密码）
           │
           ▼
BandKeyService（SYSTEM）
  ├─ 验签、校验设备授权、防重放
  ├─ 读取并解密本机 Windows 凭据
  └─ 通过受保护命名管道投递登录请求
           │
           ▼
Credential Provider（LogonUI）
  └─ 构造 Windows 登录凭据并调用系统登录接口
```

## 配对安全

- 电脑生成 6 位 PIN，只在托盘显示，不通过手机网络回传。
- PIN 有效期 5 分钟，同一配对请求连续输错 5 次会锁定。
- 手机生成 ECDSA P-256 密钥对，向电脑提交公钥；私钥不离开手机。
- 配对成功后，电脑只信任该设备公钥对应的签名。
- 删除设备后，原 `deviceId` 的解锁请求会被拒绝。

## 解锁安全

签名原文：

```text
unlock:<deviceId>:<nonce>:<timestamp>
```

- 签名格式为 ECDSA P-256 / SHA-256 的 P1363 `r||s` Base64。
- 服务端校验时间偏差 ±120 秒，并缓存 nonce 5 分钟拒绝重放。
- `method` 和 `keyHint` 只用于展示，不参与授权。
- 只有服务端验签通过、设备仍被授权、凭据可用且 CP 已连接时，请求才会进入投递窗口。
- `UNLOCK_READY` 不代表电脑已锁定，也不代表登录一定成功。

## Windows 凭据

Windows Hello PIN 由 TPM/NGC 管理，第三方 Credential Provider 无法读取或验证。
因此当前实现必须保存 Windows 账户密码：

1. 托盘通过 `BandKeyTrayPipe` 提交凭据。
2. 服务确认管道客户端身份与提交账户一致。
3. 服务使用 `LogonUser` 实际验证密码。
4. 服务使用 DPAPI `LocalMachine` 加密并原子写入
   `C:\ProgramData\BandKey\credential.dat`。

Windows 或域策略拒绝该密码后，服务会标记 `credentialRejected` 并停止自动解锁，
直到用户在托盘重新保存凭据。

## 本地管理通道

- `BandKeyPipe` 只允许 SYSTEM，供服务与 LogonUI 中的 CP 通信。
- `BandKeyTrayPipe` 校验客户端身份必须匹配当前控制台登录用户。
- 正式服务只保留 `GET /health` 存活检查；旧管理端点仅在 `--dev` 模式开启。
- 安装器将 `C:\ProgramData\BandKey` 和 `outcomes` 的继承权限移除，只授权
  SYSTEM 与管理员。

管理员和 SYSTEM 本身拥有较高权限，DPAPI `LocalMachine` 不能防御已完全控制本机的
管理员。它主要防止普通用户或其他进程直接读取凭据文件。

## 传输安全边界

- WebSocket、LAN 和 BLE 当前以应用层签名保证授权，不使用自定义 TLS。
- Windows 密码不会通过手机通道传输。
- 解锁消息中的设备 ID、时间戳、状态摘要可能在局域网中被观察。
- 服务端对公网来源直接拒绝，并只对私有地址/回环地址提供 LAN 发现与解锁。
- 防火墙规则只开放专用/域网络和本地子网，部署时不要把电脑网络设为“公用网络”。

## NFC 安全边界

- 卡片只保存随机令牌，不包含账户、密码、私钥或原始 UID。
- 手机读取卡片后仍需本地匹配令牌并使用配对私钥签名。
- NFC 令牌是静态持有型凭据，可被复制；它不是不可克隆的硬件安全密钥。
- 丢失卡片后应在手机端删除对应钥匙，必要时解除配对并重新绑定。
- 可在锁屏/息屏触发 NFC 的机型，需要额外依赖系统对后台、锁屏和 NFC 的权限控制。

## 当前不提供的保护

- 不提供应用内指纹/人脸二次确认；依赖手机系统锁屏和 App 沙箱。
- 不提供 Windows Hello PIN 或证书登录。
- 不提供互联网远程解锁，也不建议把端口映射到公网。
- 不提供卡片挑战响应或防克隆硬件；实体卡应视为可复制的物理钥匙。
- 不提供多用户共用同一服务实例的隔离；凭据对应托盘当前登录用户。

## 部署建议

1. 为 Windows 账户设置强密码，并保护手机系统锁屏。
2. 只从官方 Release 下载安装包，并校验 `SHA256SUMS.txt`。
3. 不要把 TCP `23234`、`47321`、UDP `47322` 暴露到公网。
4. 丢失手机或卡片后，立即删除对应设备/NFC 钥匙。
5. 更换 Windows 密码后，在托盘重新保存解锁凭据。
6. 定期删除不再使用的已授权设备。
