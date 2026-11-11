# NFC 解锁

NFC 是手机端的本地身份验证入口。电脑不需要 NFC 读卡器，NFC 也不新增网络协议：
手机读取卡片、匹配本地钥匙并完成 ECDSA 签名，然后继续使用 BLE 或 LAN 解锁电脑。

## 能力边界

- **前台贴卡解锁**：打开 BandKey 的 NFC 页面后贴卡，使用 Android Reader Mode。
- **锁屏/息屏自动解锁**：开启开关后，由 Android NFC 系统分发启动
  `NfcAutoUnlockActivity`，不依赖 App 已经在前台。
- **电脑端**：只接收签名解锁请求并展示最近 NFC 验证摘要，不读取或管理卡片。
- **厂商限制**：Android 官方通常只在屏幕解锁后扫描 NFC；锁屏/息屏分发不是跨厂商
  保证能力。当前验证基线是亮屏状态可用，HyperOS 等系统可能在息屏时停止 NFC 轮询
  或清理后台进程。

## 推荐卡片

| 项目 | 建议 |
| --- | --- |
| 卡类型 | NTAG213 / NTAG215 / NTAG216 等支持 NDEF 的可写卡 |
| 初始状态 | 空白卡，或已经写入 BandKey 的卡 |
| 容量 | 至少能容纳 BandKey 外部记录和 AAR；普通 NTAG213 足够 |
| 不支持 | 加密/锁定卡、只读卡、没有 NDEF 或 NdefFormatable 技术的卡、已有其他 NDEF 数据的卡 |

卡片不应保存 Windows 密码、手机私钥、账户名或原始 UID。

## 绑定流程

1. 打开 BandKey 的 NFC 页面。
2. 选择“绑定 NFC 钥匙”。
3. 将卡片贴近手机 NFC 感应区，保持不动直到提示成功。
4. 写入成功后，手机保存钥匙指纹和加密记录；实体卡保存随机令牌。

绑定过程会保持 Reader Mode 开启，直到读取、格式化和写入全部完成。写卡失败时
不要立刻移开卡片；若提示“卡片离开感应区”，重新贴卡即可。

## 卡片数据格式

BandKey 写入一条外部类型记录和一条 Android Application Record（AAR）：

```text
NDEF record 1
  TNF: EXTERNAL_TYPE
  type: com.bandkey:unlock
  payload: bandkey:v1:<43 字符 URL-safe Base64>

NDEF record 2
  AAR: android.com:pkg -> com.bandkey.app
```

载荷是 32 字节随机令牌的 Base64 编码。手机只持久化令牌的 SHA-256 指纹，记录由
Android Keystore 的 AES-256-GCM 密钥加密。旧版 `bandkey:unlock` 外部类型和
`application/vnd.bandkey.unlock` MIME 类型仍可读取。

`1.8.4` 新增 AAR 兜底分发。升级后，旧卡仍可读取，但要让系统在锁屏/息屏时更可靠
地启动 BandKey，建议重新写入一次卡片。

## 自动解锁流程

```text
息屏/锁屏贴卡
  → Android NFC 服务优先分发 NDEF_DISCOVERED
  → 未匹配时回退 TECH_DISCOVERED
  → NfcAutoUnlockActivity（showWhenLocked / turnScreenOn）
  → 读取 Intent 缓存或 NDEF 数据
  → 校验 bandkey:v1 令牌
  → 匹配本机 NFC 钥匙
  → 使用已配对私钥签名
  → BLE 优先，LAN 回退
  → Windows 验签并尝试登录
```

自动解锁开关默认关闭。关闭时，系统即使分发了卡片事件，Activity 也会直接结束，
不会发起网络请求。

## 开启自动解锁

1. 在 BandKey 设置页开启“息屏 NFC 自动解锁”。
2. 在系统设置中确认 NFC 已开启。
3. 开启“锁屏时使用 NFC”或厂商同名选项。
4. 允许 BandKey 后台弹出界面、锁屏显示和自启动。
5. 小米/HyperOS 将电池策略设为“无限制”，并检查默认触碰付款应用是否抢占卡片。
6. Android 16+ 检查“特殊应用权限 → 通过 NFC 启动”，不要禁止 BandKey。

## 诊断

设置页的“上次卡片事件”和“检查锁屏 NFC 与系统设置”用于区分以下阶段：

| 现象 | 说明 |
| --- | --- |
| 上次卡片事件为空 | 系统没有把 NFC Intent 分发给 BandKey |
| 有事件但读取失败 | 已进入 Activity，但 NDEF 读取、格式或令牌校验失败 |
| 有事件但未解锁 | 本地验签通过但网络或 Windows 端失败，检查服务/凭据/CP 状态 |
| 亮屏锁屏可用、息屏不可用 | 系统关闭了息屏 NFC 或清理了后台，属于厂商策略 |

查看日志：

```powershell
adb logcat -s BandKeyNfc:I
```

也可以导出 App 私有目录下的 `files/nfc-events.log`。日志中包含卡片技术类型、
Intent action、令牌校验结果和解锁阶段，不包含 Windows 密码或手机私钥。

## 丢失卡片

实体卡是持有型凭据，仍可能被复制。丢失后应立即：

1. 在手机 NFC 钥匙列表中删除对应钥匙。
2. 必要时解除手机与电脑的配对，重新配对。
3. 绑定一张新的空白卡。

删除手机端钥匙后，原卡令牌无法匹配本地记录，不会再生成解锁请求。

## 常见问题

### 提示“这张卡不可写入，或已有其他 NDEF 数据”

卡片不是空白 NDEF 卡，或已经包含其他应用的数据。请更换 NTAG213/215/216 等空白
可写卡。BandKey 不会默认覆盖其他 NDEF 记录。

### 提示“这张卡不支持 NDEF 写入”

卡片没有 NDEF 或 NdefFormatable 技术，可能是加密卡、只读卡或非标准卡。设置页会
显示检测到的技术类型，按提示更换卡片。

### 锁屏贴卡没有反应

先查看“上次卡片事件”。没有事件说明系统没有分发，优先检查：

- 系统 NFC 是否开启
- 是否允许锁屏使用 NFC
- 后台弹出界面/锁屏显示权限
- 自启动和电池策略
- 默认触碰付款应用
- Android 16+ 的“通过 NFC 启动”权限

有事件但仍失败时，检查卡片令牌是否已被删除、手机是否仍与电脑配对、电脑服务是否
运行，以及最终状态是否被 Windows 拒绝。
