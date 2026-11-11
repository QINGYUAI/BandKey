# BandKey（电脑钥匙）

用手机、手环或手机贴 NFC 卡解锁 Windows 电脑。本仓库是完整自研实现，包含 Android 端、Windows 服务、Windows 凭据提供程序（Credential Provider）、蓝牙/局域网通道、托盘程序和安装脚本。Android 端支持桌面小组件快捷解锁，以及锁屏/息屏状态下的 NFC 自动解锁。

## 目录结构

```text
bandkey/
├── docs/protocol.md                      # 协议、双管道、LAN 与自动解锁时序
├── docs/protocol-v2-status.md            # v2 状态查询与最终解锁回执
├── windows/
│   ├── BandKeyService/                   # Windows 服务（.NET 8，WebSocket + LAN + 管道）
│   ├── BandKeyTray/                      # 托盘程序（科技风 UI、凭据设置、配对 PIN、BLE GATT）
│   ├── BandKeyBle/                       # BLE GATT 外设实现（供托盘引用）
│   └── BandKeyCredentialProvider/        # 锁屏解锁插件（C++17）
├── android/                              # Android 端（Kotlin + Compose）
├── installer/BandKey.iss                 # Inno Setup 安装脚本
├── build-release.ps1                     # 统一打包入口
├── dist/                                 # 最终产物（latest + 版本归档）
└── tools/
    ├── e2e_test.ps1                      # 协议端到端测试
    ├── pipe_e2e_test.ps1                 # 自动解锁管道测试
    ├── lan_smoke_test.py                 # 局域网发现 + TCP 配对/解锁测试
    ├── simulate_phone.py                 # 模拟手机客户端
    └── BleProbe/                         # BLE 外设能力探测
```

## 技术选型

| 模块 | 实现 |
| --- | --- |
| Windows 服务 | C# / .NET 8，Kestrel WebSocket、TCP/UDP LAN、命名管道、ECDSA P-256 |
| 托盘 | .NET 8 WinForms，自绘暗色科技风控件与菜单 |
| Credential Provider | C++17 + WinSDK COM，接入 Winlogon 锁屏 |
| 手机 ↔ 电脑 | WebSocket / BLE GATT / LAN TCP，统一 JSON + ECDSA 签名 |
| Android | Kotlin + Jetpack Compose + OkHttp + Keystore + NFC Reader Mode / 系统 NFC 分发 + AppWidget |
| 打包 | Inno Setup + 自包含单文件发布，统一输出到 `dist` |

核心安全设计：

- 解锁由手机私钥签名，服务端验签并校验 nonce / 时间戳，重放会被拒绝。
- 配对 PIN 只在电脑托盘显示，手机输入确认，PIN 不通过网络回传。
- Windows 密码只在托盘通过受保护管道提交，服务二次验证后用 DPAPI `LocalMachine` 加密保存。
- 凭据提供程序运行在 SYSTEM / Winlogon 上下文，只读取本机凭据文件，不直接联网。
- NFC 卡片只保存随机身份令牌，手机本地匹配后用现有 ECDSA 私钥签名；卡片不包含 Windows 密码、手机私钥或原始 UID。

## v1.8.0 状态与最终回执

v1.8.0 将协议升级到 v2，修正“请求已投递”被误当成“电脑已解锁”的问题：

- 手机通过 `status` 查询电脑的真实状态，不再仅凭本地配对记录显示“已就绪”。
- `unlock_result.accepted=true` 只表示服务端验签通过并已投递，最终结果由 `unlock_status` 查询。
- 状态流转为 `accepted -> submitted -> succeeded / failed`，只有凭据提供程序的 `ReportResult` 能确认登录成功。
- 手机遇到 12 秒轮询超时会显示“已提交解锁请求”，并提供“刷新最终状态”，不会提前展示成功页。
- 电脑离线、设备被撤销、Windows 凭据缺失/失效、未锁屏或 CP 未就绪都会在手机端明确区分。
- 旧版电脑服务不支持 `status` 时，手机提示升级电脑端，而不是误报离线。

字段和内部管道消息见 [docs/protocol-v2-status.md](docs/protocol-v2-status.md)。

## v1.8.1 最终回执可靠性修复

v1.8.1 修复“电脑已实际解锁，手机端仍显示失败”的会话切换竞态：

- Windows 登录成功后 LogonUI 可能先断开 CP 命名管道，导致 `UNLOCK_RESULT:success`
  写入已失效的管道而丢失。
- CP 现在会在 `ReportResult` 中把最终结果原子写入
  `C:\ProgramData\BandKey\outcomes\<requestId>.result`，服务端查询状态时补收。
- CP 断开后服务端保留 8 秒宽限期，不再立即把请求标记为 `component_disconnected`；
  宽限期内到达的成功结果仍会正常收敛为 `succeeded`。
- 新增管道回归：正常回执、断开后结果文件补交、断开后无结果超时失败。

## v1.8.2 NFC Reader Mode 修复

v1.8.2 修复 Android 端所有 NFC 卡都被误判为“不支持 NDEF”的问题：

- Reader Mode 之前设置了 `FLAG_READER_SKIP_NDEF_CHECK`，Android 会因此不枚举
  `Ndef` / `NdefFormatable` 技术，`Ndef.get()` 和 `NdefFormatable.get()` 始终为空。
- 现已移除该标志，保留 NFC A/B/F/V 轮询，NDEF 卡可以正常读取、格式化和写入。
- 对真正不支持 NDEF 的卡，错误提示会显示检测到的技术类型，便于判断是否用错了卡。

## v1.8.3 NFC 写卡时序修复

v1.8.3 修复“NDEF 卡可识别，但写卡时报与卡片通信失败”的问题：

- 之前收到 NFC 卡后会立即关闭 Reader Mode，RF 场随之中断，后续 `Ndef.connect()` /
  `writeNdefMessage()` 容易抛出 `TagLostException`。
- 现在绑定流程会保持 Reader Mode，直到读取、格式化和写入全部完成后再关闭。
- 读卡解锁在读取到令牌后立即关闭 Reader Mode，再进入后续网络解锁流程。
- 写卡异常会区分“卡片离开感应区”“卡片加密/锁定”和普通 IO 错误，便于定位。

## v1.8.4 桌面小组件与息屏 NFC 自动解锁

v1.8.4 为 Android 端增加两个无需打开 App 的入口：

- 新增桌面小组件「BandKey 快捷解锁」，显示配对状态、NFC 钥匙数量、自动解锁开关和最近一次解锁结果。
- 设置页提供「添加到桌面」入口，通过系统固定小组件流程一键添加；不支持的启动器会提示手动添加。
- 小组件会根据实际格子尺寸自动切换迷你、紧凑、标准、横向四种布局，并在缩放时实时重排。
- 尺寸映射：1 行使用单行迷你布局；2 列/2–4 行使用紧凑布局，高度不足时自动隐藏最近状态和次要按钮；3×3 使用标准布局；4×3 及以上使用横向布局，避免文本和按钮被裁切。
- 小组件支持「一键解锁」「打开应用」「NFC 自动：开/关」三个操作；解锁请求在透明 Activity 中执行，不占用桌面广播的短生命周期。
- 新增「息屏 NFC 自动解锁」开关，默认关闭。开启后，锁屏或息屏时贴卡会由系统 NFC 分发启动 `NfcAutoUnlockActivity`。
- NFC 同时注册 `NDEF_DISCOVERED`（外部类型 + MIME）和 `TECH_DISCOVERED`，优先于钱包类应用接收 BandKey 卡片；读取时优先使用 Intent 缓存的 NDEF 数据，减少亮屏瞬间重连导致的读卡失败。
- 息屏入口复用同一套本地验证逻辑：读取 NDEF → 校验 `bandkey:v1` 令牌 → 匹配本机 NFC 钥匙 → 使用已配对私钥签名 → BLE 优先、局域网回退。
- 锁屏反馈页使用不透明全屏主题，并在首帧真正绘制后才开始自动关闭计时，避免冷启动或低性能机型上状态页刚出现就消失。
- 设置页新增「上次卡片事件」与「检查锁屏 NFC 与系统设置」诊断入口，可区分系统未分发、读取失败和本地验证失败三种情况；事件同时写入 `files/nfc-events.log` 与 logcat 标签 `BandKeyNfc`。
- 新增 Android 16+「通过 NFC 启动」权限检测；若用户关闭了 BandKey 的 NFC Intent 接收权限，设置页会直接提示并提供权限页入口。
- 新增小米 / HyperOS 专属 2×2 桌面卡片，按小米规范使用 `miuiWidget` 标识、`:widgetProvider` 独立进程、曝光刷新、`miuiWidgetVersion` 和 `@android:id/background` 根布局，并使用 24dp 大圆角深色背景。
- 小米机型设置页新增「自启动与后台权限」「锁屏显示与权限」「省电策略」直达入口，用于排查锁屏 NFC 事件未被系统分发的问题。
- 自动解锁不要求打开 App，也不会绕过电脑端 ECDSA 验签与 Windows 凭据提供程序确认。
- 受手机厂商策略影响，部分机型默认禁止锁屏 NFC 读取；需要在系统 NFC 设置中允许锁屏/息屏使用 NFC。

### 小米小部件中心说明

标准 `AppWidgetProvider` 只会出现在小米桌面的「安卓小部件」入口。要出现在小米「小部件中心」，必须完成以下平台侧流程：

1. 应用先上架或更新到小米应用商店，并通过应用商店审核。
2. 首次适配需邮件联系 `miui-widget@xiaomi.com`，申请开通小部件开放平台入口。
3. 在 [小米小部件开放平台](https://widget.xiaomi.com/) 上传小部件资料、预览图和尺寸信息。
4. 提交小米审核，审核通过后才会在小部件中心展示；仅修改代码无法绕过该审核流程。

## 快速开始（Windows）

```powershell
$dotnet = "$env:USERPROFILE\.dotnet\dotnet.exe"

& $dotnet build windows\BandKeyService\BandKeyService.csproj -c Release
& $dotnet build windows\BandKeyTray\BandKeyTray.csproj -c Release

cd windows\BandKeyCredentialProvider
powershell -ExecutionPolicy Bypass -File build.ps1
```

开发模式运行服务（不注册旧版 `BandKeyUnlock` 管道，避免与已安装服务冲突）：

```powershell
$dll = "windows\BandKeyService\bin\Release\net8.0\BandKeyService.dll"
& $dotnet $dll --urls http://127.0.0.1:23234 --config "$env:TEMP\bandkey_devices.json" --pair-pin 123456 --dev
```

`--dev` 会放宽凭据与 CP 连接检查，并禁用旧管道；仅用于联调，生产安装不要使用。

## 测试

协议端到端测试（状态查询 + 配对 + 解锁 + 最终状态 + 防重放）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\e2e_test.ps1
```

自动解锁管道测试（`UNLOCK_READY` → `AUTO_UNLOCK` → ACK → SUBMITTED → 最终成功）：

```powershell
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1 -DropAfterSubmit
powershell -ExecutionPolicy Bypass -File tools\pipe_e2e_test.ps1 -DropWithoutResult
```

`-DropAfterSubmit` 模拟 CP 提交后管道先断开、随后通过结果文件补交成功，
预期手机最终收到 `state=succeeded`；`-DropWithoutResult` 模拟断开后没有任何
最终结果，预期 8 秒宽限期后收到 `state=failed`、`error=component_disconnected`。

局域网测试（UDP 发现 + TCP 配对/解锁）：

```powershell
python tools\lan_smoke_test.py
```

测试脚本会自动选择备用端口，不会干扰已安装在 `23234` 上运行的服务。PowerShell 5.1 对无 BOM 中文脚本较敏感，联调脚本保持 ASCII。

## 通道与消息

手机与电脑使用同一套协议，可走：

- WebSocket：`ws://<主机IP>:23234/bandkey`
- BLE GATT：服务 UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- LAN TCP：`<主机IP>:47321`，NDJSON
- LAN UDP：`<广播地址>:47322`，响应 `BANDKEY_DISCOVER`

完整消息、错误码、解锁时序和内部管道格式见 [docs/protocol.md](docs/protocol.md)。

## 自动解锁链路

```text
手机私钥签名
    -> BandKeyService 验签 + 防重放
    -> 检查已配对设备、DPAPI 凭据、CP 连接
    -> AutoUnlockState 打开 90 秒窗口
    -> BandKeyPipe: AUTO_UNLOCK:<requestId>
    -> Credential Provider 读取凭据并调用 KerbInteractiveLogon
    -> AUTO_UNLOCK_ACK:<requestId>
    -> AUTO_UNLOCK_SUBMITTED:<requestId>
    -> UNLOCK_RESULT:<requestId>:success|credential_invalid|logon_failure
    -> 手机 unlock_status 轮询到 succeeded / failed
```

如果 Windows 拒绝保存的密码，CP 回写 `PASSWORD_FAILED`，服务停止自动尝试，直到用户在托盘重新保存凭据。

`UNLOCK_READY` 表示“已配对且凭据可用”或“处于 90 秒投递窗口”，不代表电脑一定锁屏；真正解锁仍要求手机每次签名。

## 蓝牙（BLE）通道

手机作为 BLE Central，电脑托盘作为 BLE Peripheral：

| 项目 | 值 |
| --- | --- |
| 服务 UUID | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| RX（手机写入） | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` |
| TX（电脑通知） | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` |
| 分帧 | NDJSON，`\n` 结尾，单条上限 8 KiB |
| 属性权限 | `Plain`，安全性由应用层签名保证 |

监听放在托盘而不是系统服务：WinRT 的 `GattServiceProvider` 在 Session 0 的系统服务中不可用，托盘运行在用户会话，能正常广播。服务对托盘通过 `BandKeyTrayPipe` 命名管道转发 RPC，不再暴露 HTTP `/rpc` 入口。

自检：

```powershell
& $dotnet run --project tools\BleProbe -c Release -- --serve
& $dotnet run --project tools\BleProbe -c Release -- --framing
```

注意：

- 需要 Windows 10 1809+，蓝牙适配器支持 Peripheral 角色。
- 广播不依赖自定义名称；手机按服务 UUID 过滤。
- 同一适配器同时广播和扫描时，本机扫描不到自己的广播，这是协议栈行为。

## 局域网通道

- 手机广播 `BANDKEY_DISCOVER` 到 UDP `47322`，电脑返回名称、TCP 端口和本机 IPv4。
- 手机随后连接 TCP `47321`，按行发送 JSON；连接可连续处理多条请求。
- WebSocket、TCP 和 UDP 发现都只接受私有地址/回环地址，公共网络来源会被直接关闭。
- 安装器只会在“专用/域”网络配置文件和本地子网内开放 `BandKey WebSocket`、`BandKey LAN TCP`、`BandKey LAN UDP` 规则。
- 如果手机无法通过 WiFi 连接，请把电脑当前 WiFi 网络设置为“专用网络”；“公用网络”会被防火墙策略拒绝。

Android 在「蓝牙」模式下先尝试 BLE；BLE 失败后自动尝试 LAN（优先使用已填写的服务地址主机，否则 UDP 发现）。「WiFi」模式直接走 WebSocket。

## NFC 解锁

NFC 是手机端的本地身份验证入口，不新增网络协议，也不要求电脑具备 NFC 读卡器：

```text
手机贴卡 -> Reader Mode 读取随机令牌 -> 手机本地匹配 SHA-256 指纹
         -> 使用已配对私钥签名 unlock 请求 -> BLE / LAN 发送
         -> Windows 验签并解锁 -> 双端展示最近 NFC 验证状态
```

息屏/锁屏自动解锁使用系统 NFC 分发，不依赖 Reader Mode，但属于系统尽力而为能力：

```text
息屏/锁屏贴卡 -> Android NFC 服务优先分发 NDEF_DISCOVERED
              -> 未匹配时回退 TECH_DISCOVERED
         -> NfcAutoUnlockActivity（透明、showWhenLocked）
         -> 读取 NDEF -> 校验令牌 -> 匹配本机钥匙 -> 后台签名解锁
```

- 卡片写入 `bandkey:v1:<43 字符 URL-safe Base64>`，内容为 32 字节随机令牌，不写入账户、密码、私钥或卡片 UID。
- 新写入的卡片第一条记录使用规范外部类型 `com.bandkey:unlock`，并追加 Android Application Record（AAR）兜底启动 BandKey；旧的 `bandkey:unlock` 卡片继续兼容。
- 绑定只接受空白卡、可格式化卡或已有的 BandKey 卡；检测到其他 NDEF 数据时会拒绝覆盖。
- 手机最多保存 3 张钥匙，只持久化令牌 SHA-256 与展示指纹；记录由 Android Keystore 的 AES-256-GCM 加密。
- 已绑定卡贴卡后自动发起解锁；未授权卡只在手机本地拒绝，不会生成 Windows 解锁请求。
- 需要在「设置 → 息屏 NFC 自动解锁」中显式开启自动解锁；未开启时系统入口会直接结束，不发送网络请求。
- 自动解锁开启后，系统入口会短暂点亮屏幕并显示解锁进度，完成后自动返回锁屏。
- Android 官方说明设备通常只在屏幕解锁后扫描 NFC；息屏/锁屏分发取决于厂商 NFC 策略，不能把它当作所有机型都保证可用的后台读卡接口。
- 实体卡属于持有型凭据，仍可能被复制；请像保管钥匙一样保管卡片，丢失后立即在手机端删除对应钥匙并重新绑定新卡。
- 解锁请求附带 `method=nfc` 与 `keyHint=BK-NFC ··· XXXX`，电脑仅用于展示最近验证方式，授权仍只依赖 ECDSA 验签。
- Windows 控制中心显示最近一次成功投递的钥匙指纹和时间，不展示或管理手机端钥匙列表。
- 服务重启会清空最近 NFC 展示状态；钥匙本身仍保存在手机端。

### 息屏 NFC 排障

1. 在设置页开启「息屏 NFC 自动解锁」，并确认「系统 NFC」显示为已开启。
2. 在系统 NFC 设置中开启「锁屏时使用 NFC」；小米、华为、荣耀等机型还需在应用权限中允许「后台弹出界面 / 锁屏显示」。
3. 小米 / HyperOS 还需允许 BandKey 自启动，并将电池策略设为「无限制」，同时检查默认触碰付款应用是否抢占卡片；若「上次卡片事件」持续显示「未收到」，说明系统没有把卡片 Intent 交给 BandKey，而不是读卡或验签失败。
4. Android 16 及以上检查「设置 → 应用 → 特殊应用权限 → 通过 NFC 启动」，确认 BandKey 未被禁止；设置页会显示「NFC 启动权限」并提供直达入口。
5. 可先点亮屏幕但保持锁屏状态贴卡：若亮屏锁屏能收到、息屏收不到，通常是系统关闭了息屏 NFC；若两者都收不到，优先检查 NFC 启动权限、后台弹出界面、锁屏显示和默认触碰付款应用。Android 通常只在屏幕解锁后扫描 NFC，息屏触发不是标准保证能力。
6. 贴卡后回到设置页查看「上次卡片事件」：时间为空表示系统没有把卡片事件分发给 BandKey；已有事件但仍解锁失败，表示进入读取或本地验证阶段后失败。
7. 需要完整日志时执行 `adb logcat -s BandKeyNfc:I`，或导出应用目录下的 `files/nfc-events.log`。

## 托盘与本地管理通道

托盘以普通用户权限运行，通过命名管道 `BandKeyTrayPipe` 与系统服务交互。服务端会校验管道客户端身份必须匹配当前控制台登录用户（`--dev` 开发模式除外）：

| 命令 | 用途 |
| --- | --- |
| `GET_STATUS` | 就绪、凭据、CP、LAN 监听及最近 NFC 验证状态 |
| `GET_PAIRING` | 当前待确认配对请求 |
| `LIST_DEVICES` | 已配对设备列表 |
| `DELETE_DEVICE` | 删除指定设备 |
| `SAVE_CREDENTIAL` | 校验并保存当前用户的 Windows 登录凭据 |
| `RPC` | BLE 托盘转发的配对/解锁协议 |

HTTP 仅保留 `GET /health` 存活检查。`/status`、`/pairing`、`/devices`、`/credential` 等旧管理端点只在 `--dev` 开发模式注册，正式服务不会暴露。

本地联调可用 `--pipe-suffix _test_xxx` 给三条命名管道加后缀，避免与已安装服务冲突；对应回归脚本为 `tools\pipe_management_test.ps1`。

托盘 UI 已统一为“信任仪表”暗色科技风：

- 双击托盘打开一屏式控制中心，展示服务、蓝牙、已配对设备与凭据状态。
- NFC 状态条显示最近一次手机本地验签通过的钥匙指纹与时间；等待状态下明确提示 NFC 由手机读取。
- 三段信任环、状态胶囊、指标卡和动作网格均使用自绘控件，薄荷绿表示就绪，紫色表示身份，橙色表示等待，红色表示错误。
- 配对请求使用大号 PIN、设备身份卡和连续倒计时；最后 60 秒转为橙色，过期转为红色。
- 设备列表使用深色 OwnerDraw ListView、等宽设备 ID、左侧信号条选中态和授权数量胶囊。
- 窗体、按钮、输入框和进度条使用克制的淡入、按压与状态过渡，并尊重系统“显示动画”设置。
- 右键菜单保留快捷入口，托盘图标为运行时绘制的薄荷绿钥匙环。

### 保存解锁凭据

托盘菜单「设置解锁凭据」通过 `BandKeyTrayPipe` 提交，服务执行：

1. 校验管道客户端身份与提交的 Windows 账户一致。
2. 使用 `LogonUser` 实际验证密码。
3. 使用 DPAPI `LocalMachine` 加密并原子写入 `C:\ProgramData\BandKey`。

保留文件：

- `C:\ProgramData\BandKey\username.txt`
- `C:\ProgramData\BandKey\credential.dat`

### 关于 Windows PIN（重要）

这里有两个完全不同的 PIN：

- **配对 PIN**：电脑托盘显示的 6 位数字，用于确认“这台手机可以加入”。它不是 Windows PIN。
- **Windows Hello PIN**：系统用来登录本机的 PIN，由 TPM / NGC 管理，第三方 Credential Provider 无法读取或验证。

因此当前实现必须保存 **Windows 账户密码** 作为后台自动登录凭据，不能改成“使用电脑 PIN 解锁”。托盘界面会明确提示“不是 PIN”。如果希望真正无密码，应改为证书登录（CP 返回 `CRED_TYPE_CERTIFICATE`），而不是尝试读取 Hello PIN。

## Credential Provider（C++）

需安装 Visual Studio 2022 Build Tools（MSVC v143 + Windows SDK + CMake）：

```powershell
winget install --id Microsoft.VisualStudio.2022.BuildTools --accept-package-agreements --accept-source-agreements --override "--quiet --wait --norestart --add Microsoft.VisualStudio.Component.VC.Tools.x86.x64 --add Microsoft.VisualStudio.Component.Windows11SDK.22621 --add Microsoft.VisualStudio.Component.VC.CMake.Project"
```

构建：

```powershell
cd windows\BandKeyCredentialProvider
powershell -ExecutionPolicy Bypass -File build.ps1
# 产物：build\Release\BandKeyCredentialProvider.dll
```

手动注册（管理员）：

```powershell
regsvr32 build\Release\BandKeyCredentialProvider.dll
```

安装包会自动注册 COM CLSID 与 Credential Provider 键。

升级注意事项：

- 1.8.x 安装器保留与 1.7.0 相同的 Inno `AppId`，可直接覆盖升级，不会生成两套卸载记录。
- 安装器会停止旧服务，并用 `sc config` 更新已有 `BandKeyService` 的路径，避免只执行 `sc create` 导致服务仍指向旧目录。
- 凭据提供程序 DLL 可能仍被 LogonUI 加载。安装完成后请**重启电脑或注销再登录**，确保新版 CP 生效。
- 重启前手机端可能仍看到旧 CP 行为或“电脑服务版本过旧”，这是升级过程中的正常现象。

## Android 端

工程位于 `android/`，已包含 Gradle Wrapper、应用图标、Compose 主题和配对/解锁界面。

**Android Studio**

1. 安装 Android Studio（JDK 17 + SDK 35）。
2. `File -> Open` 选择 `android/`。
3. 真机运行；默认「蓝牙」模式，点「配对」后扫描电脑 BLE 广播。

**命令行**

需本机 JDK 17 与 Android SDK（已设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`）：

```powershell
cd android
powershell -ExecutionPolicy Bypass -File build.ps1
powershell -ExecutionPolicy Bypass -File build.ps1 assembleRelease
```

Debug 产物：`app\build\outputs\apk\debug\app-debug.apk`。

Release 产物固定复制到 `dist\latest\BandKey.apk`，同时保留版本归档
`dist\<version>\BandKey-<version>-android.apk`（由 `assembleRelease` 自动完成）。

Release 使用仓库内置的开发签名（`android/bandkey-release.keystore` + `android/keystore.properties`，别名 `bandkey`）。仅用于侧载测试；正式发布请替换为自己的签名并妥善保管密钥。

Release 默认启用 R8 混淆与资源收缩。依赖下载需要代理时，可设置 `BANDKEY_HTTP_PROXY`（例如 `http://127.0.0.1:7892`）；未设置时 `build.ps1` 会自动探测本机 `7892` 端口。

### Android 持久化与回退

- 配对成功后，`deviceId`、公钥、私钥写入 `PairingStore`。
- 私钥等内容先序列化，再由 Android Keystore 的 AES-256-GCM 密钥加密，密文放入 `SharedPreferences`。
- 重启 App 会自动恢复配对，不需要重新配对；「解除配对」会清除本地密文。
- 「蓝牙」模式：BLE → LAN TCP 自动回退。
- 「WiFi」模式：直接连接 `ws://<主机IP>:23234/bandkey`。
- UI 使用深色太空背景、雷达脉冲动画、状态卡片、模式切换胶囊和渐变主按钮。

## 统一打包入口

所有最终产物固定输出到仓库根目录的 `dist`，不再从 `installer\Output` 或 Gradle
深层目录查找：

```text
dist/
├── 1.8.4/
│   ├── BandKey-1.8.4-windows-x64.exe
│   ├── BandKey-1.8.4-android.apk
│   └── SHA256SUMS.txt
└── latest/
    ├── BandKey-Setup.exe
    ├── BandKey.apk
    ├── SHA256SUMS.txt
    └── VERSION.txt
```

完整打包（Windows 安装包 + Android Release APK）：

```powershell
.\build-release.ps1
```

只打包单端：

```powershell
.\build-release.ps1 -SkipAndroid
.\build-release.ps1 -SkipWindows
```

脚本会从 `installer\BandKey.iss` 的 `AppVersion` 读取版本号，校验 Windows
Service、Tray 与 Android 的版本是否一致，然后执行发布、CP 编译、Inno Setup
和 Android Release 构建，最后生成 `dist\latest` 固定入口及 SHA256 校验文件。

日常安装/分发请固定使用：

- Windows：`dist\latest\BandKey-Setup.exe`
- Android：`dist\latest\BandKey.apk`

版本归档位于 `dist\<version>`，便于回溯历史版本。直接运行
`android\build.ps1 assembleRelease` 也会通过 Gradle finalizer 自动复制到
`dist`；直接运行 `ISCC installer\BandKey.iss` 也会输出到
`dist\<version>`。

不要并行执行 `dotnet publish` 与 `ISCC.exe`：安装器会读取发布目录，并发可能导致缺文件。

## 故障排查

- 托盘显示「服务状态：未运行」：在服务管理器中启动 `BandKeyService`，或查看事件查看器的 7000/7009 错误。
- 手机点配对但电脑没弹 PIN：确认服务运行中、手机已发出 `pair_request`；PIN 只在电脑托盘显示，不回传手机。
- 已配对设备列表为空：手机必须完成 `pair_success`。开发模式使用 `%TEMP%` 配置文件，与已安装服务使用的 `C:\ProgramData\BandKey\devices.json` 不是同一份数据。
- PIN 输入错误：配对 PIN 有效期 5 分钟，连续输错 5 次会锁定本次配对；请重新发起配对。
- Windows 密码被拒绝：托盘重新保存“Windows 登录密码”，不是 Windows Hello PIN。
- BLE 不可用：确认蓝牙适配器支持 Peripheral；失败时 Android 会自动回退 LAN。
- LAN 不通：确认手机与电脑同一局域网、电脑网络配置文件为“专用网络”，并确认防火墙允许本地子网的 TCP `47321` / UDP `47322`。
- 托盘日志：`%LOCALAPPDATA%\BandKey\tray.log`（菜单「打开日志目录」）。
- 服务日志：`C:\ProgramData\BandKey\service.log`（需要管理员权限查看；超过 2 MiB 轮转为 `service.log.1`）。
- 锁屏凭据日志：`C:\ProgramData\BandKey\cp.log`（记录 LogonUI 枚举与序列化结果，含 `ReportResult` 状态码）。
- `ReportResult status=0xC000000D`：CP 序列化结构不合法。`KERB_INTERACTIVE_UNLOCK_LOGON` 是 packed 结构，`UNICODE_STRING.Buffer` 必须填相对缓冲区起点的字节偏移（不是进程内绝对指针），且 `Length` 不包含结尾 NUL；1.5.1 已修复。

## 路线图

- [x] 配对 + ECDSA 验签 + 防重放
- [x] Credential Provider 锁屏接入 + 自动解锁 ACK
- [x] 双管道与凭据身份校验
- [x] BLE GATT + LAN TCP/UDP 回退
- [x] Android Keystore 配对持久化
- [x] PC / Android 科技风 UI
- [x] 协议 v2 真实状态查询与最终解锁回执
- [ ] 真实 Winlogon 锁屏全链路回归
- [ ] 证书登录（真正无密码）
- [ ] 心跳与连接状态可视化
- [ ] 体积优化（NativeAOT / trimming）
