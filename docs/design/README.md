# BandKey 2.0 页面设计稿

## 文件

- `design-board.html`：完整设计板，可直接在浏览器中打开。
- `nfc-unlock.html`：NFC 轻触解锁双端状态设计板，支持 `?view=` 导出单个界面。
- `shots/hero.png`：Android 主页面、配对、设备列表。
- `shots/phones.png`：设置、错误恢复、解锁成功。
- `shots/desktop.png`：Windows 托盘控制中心、配对弹窗、锁屏凭据卡片、设备窗口、凭据窗口。
- `shots/tokens.png`：色彩、字阶、间距、动效与改版分析。
- `shots/nfc-unlock.png`：NFC 解锁完整设计板。
- `shots/nfc-android.png`：Android NFC 六种状态总览。
- `shots/nfc-windows.png`：Windows NFC 状态与控制中心总览。

## 设计方向：信任仪表（Trust Instrument）

现有界面的问题不是“不够科技”，而是信息没有主次：配对、连接、凭据、错误全部堆在一个长页面里，用户必须先读完才能判断能不能解锁。

这一版把产品重新定义为一块“信任仪表”：

- 主页面只回答一个问题：**现在能不能解锁**。
- 三个关键状态分别占用仪表的一段弧：**通道、身份、凭据**。
- 缺口就是问题所在，中心文案直接给出结论。
- 配对、设备管理、连接设置收进二级页面或模态流程。

## 页面清单

### Android

| 页面 | 目标 | 关键组件 |
| --- | --- | --- |
| 解锁主页 | 一眼判断是否就绪，并完成解锁 | `TrustDial`、`StatusRail`、`PrimaryAction`、`BottomNav` |
| 配对流程 | 核对设备并输入 6 位 PIN | `DeviceCard`、`OtpInput`、`Countdown` |
| 授权设备 | 查看、删除可解锁设备 | `DeviceRow`、`SummaryCard` |
| 连接与安全 | 切换通道、查看服务地址与安全参数 | `SegmentedControl`、`SettingCard`、`SecurityRow` |
| 错误恢复 | 把错误翻译成可执行步骤 | `ErrorState`、`StepList` |
| 解锁成功 | 给出可核对的解锁回执 | `SuccessDial`、`ReceiptCard` |

### Windows

| 页面 | 目标 | 关键组件 |
| --- | --- | --- |
| 托盘控制中心 | 双击托盘后一屏看到服务、蓝牙、设备、凭据 | `StatusPill`、`MiniRing`、`Metric`、`ActionGrid` |
| 配对弹窗 | 大号 PIN、倒计时、设备身份 | `TechPinDisplay`、`TechProgress` |
| 设备窗口 | 管理已授权设备 | `TechListView`、`DangerButton` |
| 凭据窗口 | 保存 Windows 账户密码 | `TechField`、`WarningNote` |
| 锁屏凭据卡片 | LogonUI 中显示手机解锁状态 | 两字段：状态文本 + 提交按钮 |

## 关键优化

1. **主操作固定**：解锁按钮始终位于拇指区，不再随状态卡片数量上下移动。
2. **状态语义化**：绿色代表就绪，紫色代表身份，橙色代表等待，红色代表错误；同时配合文案和图标，不只依赖颜色。
3. **错误即操作**：`computer_not_locked`、`no_credential`、`password_invalid` 等错误分别映射到明确的恢复步骤。
4. **克制的科技感**：只保留一个信号色和一个身份色，减少霓虹描边、无意义动画和高饱和渐变。
5. **Windows 统一壳体**：托盘、配对、设备、凭据窗口共用标题、卡片、按钮和表格规范。
6. **CP 现实约束**：LogonUI 只可靠支持文本字段和提交按钮，锁屏卡片不依赖自定义动画。

## 组件落地拆分

### Android / Compose

- `BandKeyTheme`：替换现有 `NeonCyan` / `NeonViolet` 常量，增加语义色 `Ready`、`Identity`、`Waiting`、`Danger`。
- `TrustDial`：接收 `channelReady`、`identityReady`、`credentialReady` 三个布尔值和中心状态文案。
- `StatusRail`：替代现有 `KeyValueRow`，每行包含状态点、标签、值和辅助信息。
- `PrimaryAction`：固定高度 56–58dp，支持 enabled / busy / success 三种状态。
- `BottomNav`：解锁 / 设备 / 设置三个入口；配对页不显示底部导航。
- `PairingSheet`：从底部弹出的配对流程，包含设备卡、6 位 PIN、倒计时和风险提示。

### Windows / WinForms

- 保留 `TechForm` 无边框壳，新增 `TechCard`、`TechStatusPill`、`TechSegmented`、`TechPinDisplay`。
- 托盘双击打开 `TrayPopoverForm`，右键菜单保留为快捷入口。
- 所有弹窗统一宽度：配对 520、凭据 540、设备 760；标题区、内容区、底部按钮区固定。
- 列表选中态使用 2px 左侧信号条，不使用整行高饱和填充。

### Credential Provider

- 保持 `BKF_STATUS` + `BKF_SUBMIT` 两个字段。
- 状态文本按 `等待手机确认` / `手机已连接` / `验证失败` 轮换。
- 不在 CP 内做复杂动画或自定义绘制，避免 LogonUI 兼容性问题。

## 下一轮待确认

1. 是否保留深色“信任仪表”方向，还是再出一版浅色 / 银灰硬件风。
2. 主色选薄荷绿 `#63F2CF`，还是回到现有青色 `#3DE1FF`。
3. Android 是否接受三 Tab 信息架构（解锁 / 设备 / 设置）。
4. Windows 托盘是否接受双击打开控制中心，右键菜单作为快捷方式。
5. 是否需要在解锁主页展示“上次解锁时间 / 通道 / 算法”回执信息。

## Android 落地状态（v1.6.0）

Android 端已按本设计板完成第一轮实现：

- `android/app/src/main/java/com/bandkey/app/TechComponents.kt`：主题、信任仪表、状态轨、分段控件、PIN 输入、倒计时、成功描边、底部导航、按钮按压和自绘图标。
- `android/app/src/main/java/com/bandkey/app/MainActivity.kt`：解锁 / 设备 / 设置三 Tab、配对底部弹层、错误恢复页、解锁成功回执和真实的配对 / 解锁业务状态。
- `android/app/build.gradle.kts`：版本升级到 `versionCode 4` / `versionName 1.6.0`，增加 Compose Animation 依赖。

已落地的动效：

- 仪表三段弧 spring 进入；忙碌时外圈旋转，就绪后停止。
- 状态点呼吸、PIN 槽位 spring 缩放、光标闪烁、倒计时最后 60 秒转橙色。
- 主按钮按压缩放 `0.965`，忙碌时显示旋转进度。
- 配对弹层从底部滑入，Tab 内容 220ms 淡入上移，成功环 650ms 描边一次。
- 读取系统 `ANIMATOR_DURATION_SCALE`，关闭系统动画时停用循环动效。

## Windows 落地状态（v1.6.0）

Windows 托盘的“信任仪表”控制中心与三个弹窗已完成第一轮落地：

- `windows/BandKeyTray/TrayPopoverForm.cs`：双击托盘打开一屏式控制中心，接入真实 `/status`、`/pairing` 与 BLE 广播状态。
- `windows/BandKeyTray/TechInstrument.cs`：新增 `TechCard`、`TechStatusPill`、`TechMiniRing`、`TechMetric`、`TechDeviceCard`、`TechPinDisplay`、`TechActionButton`。
- `windows/BandKeyTray/TechTheme.cs`：主题切换到薄荷绿信号色，窗体、输入框、主按钮和进度条增加平滑过渡。
- `windows/BandKeyTray/PairingDialog.cs`：大号 PIN、设备身份卡、剩余时间提示与平滑倒计时。
- `windows/BandKeyTray/CredentialDialog.cs`：明确区分 Windows 密码与 Hello PIN，统一本机加密提示。
- `windows/BandKeyTray/DevicesDialog.cs`：设备表使用深色表头、等宽设备 ID、左侧信号条选中态和授权数量胶囊。

Windows 动效清单：

- 窗体打开时 210ms 淡入并上移 10px，遵循系统“显示动画”设置。
- 控制中心三段信任环 spring 收敛、状态点呼吸、按钮悬停高亮与按压缩放。
- 配对 PIN 六位数字渐显，扫描线缓慢移动；最后 60 秒切换为橙色，过期切换为红色。
- 倒计时进度条使用连续插值，不再每 500ms 跳变。
- 弹窗与列表刷新不做整页循环动画，避免干扰托盘常驻体验。

## 验收截图

以下截图来自 Pixel 8 Pro API 35 模拟器，配对与错误状态连接了本机真实 Windows 服务验证：

- `verify/unlock.png`：未配对主页
- `verify/paired.png`：已配对就绪主页
- `verify/pairing.png`：配对请求弹层
- `verify/pairing-pin.png`：6 位 PIN 输入状态
- `verify/devices.png`：未绑定设备页
- `verify/devices-paired.png`：已绑定设备页
- `verify/settings.png`：连接与安全页
- `verify/unpair-confirm.png`：解除配对确认
- `verify/unlock-error.png`：电脑未锁定错误恢复页
- `verify/success.png`：解锁成功回执

Windows 验收截图：

- `verify/windows-control.png`：托盘控制中心（真实本机服务状态）
- `verify/windows-pairing.png`：配对 PIN 弹窗（预览数据）
- `verify/windows-credential.png`：Windows 解锁凭据弹窗
- `verify/windows-devices.png`：已授权设备窗口（预览数据）

## NFC 解锁（v1.7.0）

> v1.7.0 已接入真实 NFC 扫描、绑定、本地验签与 Windows 状态回写；电脑端仍不读取 NFC 卡片。

### 设计边界

- NFC 由手机读取，电脑端不依赖内置 NFC 读卡器。
- 卡片只保存随机身份令牌，不保存 Windows 密码、手机私钥或原始 UID。
- 手机完成读卡与本地验签后，继续复用现有 BLE / 局域网签名解锁链路。
- Windows 只展示钥匙指纹、最近使用时间和验证结果，不伪装成本机读卡。
- 所有状态同时使用颜色、图标和文案，不依赖单一颜色区分。

### Android 状态

| 状态 | 主文案 | 主行动 |
| --- | --- | --- |
| 未绑定 | 还没有 NFC 钥匙 | 绑定 NFC 钥匙 / 继续使用蓝牙 |
| 等待贴卡 | 贴卡解锁 | 取消扫描 |
| 正在验证 | 正在验证卡片 | 验证中不可重复提交 |
| 解锁成功 | NFC 已解锁 | 完成 / 查看回执 |
| 未授权卡 | 这张卡尚未绑定 | 绑定此卡 / 重新贴卡 |
| 硬件不支持 | 这台手机不支持 NFC | 返回蓝牙解锁 |

### Windows 视图

| 视图 | 目标 |
| --- | --- |
| 控制中心 | 在保持三列遥测结构的前提下，增加 NFC 钥匙状态条与入口 |
| NFC 状态说明 | 展示最近验证指纹和时间，并说明钥匙列表、绑定与删除均由手机管理 |
| 锁屏状态卡 | 锁屏仍使用凭据提供程序现有状态文案；未新增电脑端 NFC 专用卡片 |

### 视觉与动效

- 继续沿用薄荷绿 `#63F2CF`、身份紫 `#8C7BFF`、等待橙 `#FFB86B`、错误红 `#FF6B81`。
- 贴卡页使用三轮扩散涟漪，只有等待贴卡时循环播放。
- 验证页改为单条细进度线和扫弧，不使用频繁闪烁。
- 成功页外环 650ms 描边一次，随后保持静止。
- 遵循系统“移除动画”设置，关闭循环波纹、旋转和按压动效。

### NFC 设计截图

- `shots/nfc-unbound.png`：Android 未绑定
- `shots/nfc-waiting.png`：Android 等待贴卡
- `shots/nfc-verifying.png`：Android 本机验签
- `shots/nfc-success.png`：Android 解锁成功
- `shots/nfc-rejected.png`：Android 未授权卡
- `shots/nfc-unsupported.png`：Android 硬件不支持
- `shots/nfc-windows-control.png`：Windows 控制中心
- `shots/nfc-windows-keys.png`：Windows NFC 钥匙管理参考稿（未落地，实际由手机管理）
- `shots/nfc-windows-lock.png`：Windows 锁屏 NFC 状态卡参考稿（未落地）

### 落地结果（v1.7.0）

1. Android 前台 Reader Mode 已启用，支持 NFC A/B/F/V，并在生命周期切换时启动或停止扫描。
2. NFC 钥匙使用随机令牌、SHA-256 指纹和 Android Keystore 加密记录；支持最多 3 张钥匙、重命名和删除。
3. 贴卡成功后在手机本地匹配令牌，再复用现有 ECDSA 签名解锁链路；新增 `method` 与 `keyHint` 展示字段。
4. Windows `/status` 返回 `lastUnlockMethod`、`nfcKeyHint`、`nfcLastUsedAt`；控制中心新增 NFC 状态条和手机端管理说明。
5. 控制中心调整为 560×780，日志入口移到页脚，避免继续压缩遥测和操作区。

仍需真机验证：贴卡距离、读取时长、不同卡类型、NFC 关闭、手机无 NFC 硬件、App 重启后钥匙持久化，以及系统动画关闭场景。
