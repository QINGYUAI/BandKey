# 故障排查

先判断问题发生在哪一层：手机与电脑连接、配对、凭据、CP 投递、Windows 登录，还是
NFC/小组件系统分发。不要只根据手机页面上的一个状态反复重试。

## 快速分诊

```text
电脑托盘能打开吗？
  ├─ 否：检查 BandKeyTray 是否启动、service.log / tray.log
  └─ 是
      ├─ 手机能配对吗？
      │   ├─ 否：检查配对 PIN、BLE/LAN 通道
      │   └─ 是
      ├─ 手机能发出解锁请求吗？
      │   ├─ 否：检查配对记录、设备是否被删除、网络
      │   └─ 是
      └─ Windows 是否真正登录？
          ├─ 否：检查 credential.dat、cp.log、CP 连接
          └─ 是：检查 unlock_status 回执是否超时
```

## 服务与托盘

### 托盘显示“服务状态：未运行”

```powershell
Get-Service BandKeyService
Start-Service BandKeyService
```

若无法启动，查看：

- 事件查看器中的服务错误（常见 7000/7009）
- `C:\ProgramData\BandKey\service.log`
- `%LOCALAPPDATA%\BandKey\tray.log`

安装后服务路径错误时，重新运行安装器，安装器会更新已有服务的 `binPath`。

### 托盘没有出现

- 检查 `%LOCALAPPDATA%\BandKey\tray.log`。
- 手动运行安装目录下的 `BandKeyTray.exe`。
- 确认没有多个托盘进程或旧版本进程占用同名管道。
- 托盘退出失败时，结束残留进程后重新启动。

### BandKeyService 内存或句柄持续增长

先观察句柄是否随时间线性增加：

```powershell
Get-Process BandKeyService |
  Select-Object Handles,@{n='WS_MB';e={[math]::Round($_.WorkingSet64/1MB,1)}}
```

正常情况下句柄应在几百个以内并保持稳定。如果每秒增加约 1 个，通常是托盘
`BandKeyTrayPipe` 连接没有被服务端及时关闭。1.8.4 及更早版本的
`HandleTrayConnectionAsync` 会在回复后轮询 `IsConnected`，命名管道对端关闭后该值
可能仍为 `true`，导致每个轮询请求都留下一个管道实例。

处理方式：

1. 升级到包含一次性关闭修复的版本。
2. 重启 `BandKeyService` 释放已经泄漏的实例：

```powershell
Restart-Service BandKeyService
```

3. 观察 10 分钟，句柄数应基本不变。控制中心打开时也不应再按秒增长。

## 配对

### 手机点配对，电脑没有显示 PIN

1. 确认电脑托盘正在运行，服务状态为“运行中”。
2. 确认手机已发出 `pair_request`，而不是只停留在扫描页面。
3. 查看托盘日志和服务日志，确认请求是否到达。
4. 如果使用 `--dev` 开发服务，请确认手机连接到的是开发端口，不是已安装服务。

PIN 只在电脑托盘显示，不会回传手机。

### PIN 输入错误或被锁定

- PIN 有效期为 5 分钟。
- 同一配对请求连续输错 5 次会立即锁定。
- 关闭手机上的旧配对弹层，重新发起一次配对。
- 不要在电脑上重复点击生成 PIN，避免手机输入的是旧请求的 PIN。

### 已配对设备不显示或列表为空

- 手机必须完成 `pair_success`，仅收到 challenge 不算配对成功。
- 开发模式默认使用 `%TEMP%` 下的设备文件，与已安装服务的
  `C:\ProgramData\BandKey\devices.json` 不是同一份数据。
- 托盘刷新后仍为空，重启服务并检查 `service.log` 中的设备保存错误。
- 如果设备被删除，手机端应重新配对。

## 连接

### BLE 不可用

- 确认 Windows 10 1809+，蓝牙适配器支持 Peripheral 角色。
- 确认蓝牙已开启，托盘正在运行。
- Android 13+ 需要“附近设备”权限；旧版 Android 扫描可能要求定位权限。
- 同一适配器同时广播和扫描时，本机通常扫描不到自己的广播，这是协议栈行为。
- BLE 失败时 Android 会自动回退 LAN；也可以手动切换到 WiFi/LAN 模式。

### LAN 或 WiFi 不通

- 手机和电脑必须处于同一局域网。
- 把 Windows 当前网络设为“专用网络”；“公用网络”会被安装器的防火墙策略拒绝。
- 检查防火墙是否允许本地子网访问 TCP `23234`、TCP `47321`、UDP `47322`。
- 确认电脑 IP 没有变化；DHCP 换网后可在手机端重新扫描或更新地址。
- WebSocket 地址格式为 `ws://<电脑IP>:23234/bandkey`。

## 凭据和锁屏

### 手机提示“未保存凭据”或 `no_credential`

在电脑托盘选择“设置解锁凭据”，输入当前 Windows 账户的登录密码。不是配对 PIN，
也不是 Windows Hello PIN。

### 手机提示密码错误或 `credential_invalid`

- 重新输入 Windows 账户密码并保存。
- 域账户需使用正确的域名/账户格式。
- 修改 Windows 密码后必须重新保存凭据。
- 连续失败后，服务会停止自动尝试，避免反复提交错误密码。

### 提示“解锁组件未就绪，请重启电脑”

这通常表示 CP 未连接、DLL 未生效或 LogonUI 仍加载旧组件：

1. 确认 `Get-Service BandKeyService` 为 `Running`。
2. 重新运行当前版本安装器。
3. **重启电脑或注销再登录**，不要只重启托盘。
4. 检查 `C:\Windows\System32\BandKeyCredentialProvider.dll` 是否存在。
5. 查看 `C:\ProgramData\BandKey\cp.log` 是否有 CP 启动、管道连接或
   `ReportResult` 记录。
6. 检查注册表中的 Credential Provider CLSID
   `{E86A4C6E-5B8A-4C2D-9F3A-1B2C3D4E5F60}` 是否指向该 DLL。

### 手机显示“参数错误”

优先确认手机和电脑端版本一致（至少都支持协议 v2），然后检查：

- 手机是否发送了完整的 `deviceId`、`nonce`、`timestamp` 和签名。
- 服务是否返回 `bad_request`、`bad_json` 或 `unknown_type`。
- 是否连接到了旧版服务或另一个开发实例。
- 服务日志中是否出现 JSON 解析或字段校验异常。

如果电脑实际已经解锁，但仍报参数错误，应检查是否是旧版 CP 的结果回写格式导致，
升级到同一版本后重启。

## 最终状态

### 手机显示“已提交”但一直是等待状态

`accepted`/`submitted` 只代表请求已投递，不能提前当作成功。检查：

1. 电脑是否已经实际登录；若已登录，可能是 CP 的最终结果没有回传。
2. `C:\ProgramData\BandKey\outcomes\<requestId>.result` 是否存在。
3. `cp.log` 是否记录了 `UNLOCK_RESULT` 或 `ReportResult` 状态码。
4. 服务是否在此期间重启；重启后旧 `requestId` 会返回 `unknown`。

CP 断开后服务端保留 8 秒宽限期补收结果；超过 120 秒会收敛为
`outcome_timeout`。

### `ReportResult status=0xC000000D`

`STATUS_INVALID_PARAMETER`，通常表示 CP 序列化的登录结构不合法。

- 确认使用的是当前版本 CP。
- `KERB_INTERACTIVE_UNLOCK_LOGON` 是 packed 结构。
- `UNICODE_STRING.Buffer` 必须填相对缓冲区起点的字节偏移，不是进程内绝对指针。
- `Length` 不包含结尾 NUL。

### 电脑已解锁，但手机仍显示失败

这是会话切换时的管道竞态。当前版本会把最终结果同时写入
`C:\ProgramData\BandKey\outcomes`，服务查询 `unlock_status` 时补收。

- 确认 CP 和服务都是 `1.8.1` 或更高版本。
- 确认 `outcomes` 目录 ACL 允许 SYSTEM/管理员读写。
- 查看 `service.log` 是否收到 `UNLOCK_RESULT`，或是否在宽限期内读取结果文件。
- 若服务已重启，让手机重新发起解锁。

## NFC

详细的卡片格式和自动解锁配置见 [NFC 解锁](nfc.md)。

### 锁屏/息屏贴卡没有反应

先在 App 设置页查看“上次卡片事件”：

- **为空**：系统没有分发 Intent。检查 NFC 开关、锁屏 NFC、后台弹出界面、自启动、
  电池策略、默认触碰付款应用，以及 Android 16+ 的“通过 NFC 启动”权限。
- **有事件但读取失败**：检查卡片是否支持 NDEF、是否被锁定、令牌是否仍有效。
- **有事件但解锁失败**：网络、配对或 Windows 端问题，按上面的连接/凭据步骤排查。

如果亮屏锁屏可用、真正息屏不可用，通常是系统停止了 NFC 轮询或清理了后台进程。
NFC 自动解锁是尽力而为能力，不能保证所有厂商和所有息屏状态下都可用。

### 提示卡片已有其他 NDEF 数据

BandKey 不会默认覆盖其他应用的记录。换一张空白 NTAG213/215/216，或在 App 中明确
确认覆盖已有的 BandKey 卡。

### 旧卡升级后不能自动触发

`1.8.4` 的新卡使用 `com.bandkey:unlock` 外部类型并追加 AAR。旧卡通常仍可被 App
读取，但要让系统更可靠地分发，重新在 `1.8.4` 中写入一次。

## 小组件

### 小米“小部件中心”看不到 BandKey

标准 `AppWidgetProvider` 只保证出现在“安卓小部件”。小米“小部件中心”需要：

1. 应用上架或更新到小米应用商店并通过审核。
2. 邮件联系 `miui-widget@xiaomi.com` 申请开放平台入口。
3. 在 [小米小部件开放平台](https://widget.xiaomi.com/) 上传资料并审核。

仅修改 `miuiWidget` 元数据不能绕过平台审核。可以先把 BandKey 添加到“安卓小部件”
验证功能。

### 小组件尺寸不合适或按钮消失

小组件会按格子尺寸切换迷你、紧凑、标准和横向布局。重新调整尺寸或移除后重新添加。
如果启动器不刷新，重启启动器或重新添加小组件。

## 日志和诊断

| 日志 | 路径 |
| --- | --- |
| Windows 服务 | `C:\ProgramData\BandKey\service.log` |
| 凭据提供程序 | `C:\ProgramData\BandKey\cp.log` |
| 托盘 | `%LOCALAPPDATA%\BandKey\tray.log` |
| Android NFC | App 私有目录 `files/nfc-events.log` |
| Android logcat | `adb logcat -s BandKeyNfc:I` |

常用命令：

```powershell
Get-Service BandKeyService
Get-Content 'C:\ProgramData\BandKey\service.log' -Tail 100
Get-Content 'C:\ProgramData\BandKey\cp.log' -Tail 100
Get-Content "$env:LOCALAPPDATA\BandKey\tray.log" -Tail 100
```

服务日志超过 2 MiB 会轮转为 `service.log.1`。提交问题前请先删除日志中的账户名、
设备 ID 和本地路径等隐私信息。
