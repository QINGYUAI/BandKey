# 版本记录

当前最新版本为 `1.8.7`。下载地址：
[GitHub Releases](https://github.com/QINGYUAI/BandKey/releases/latest)。

## 1.8.7

控制中心布局修复与授权记录优化。

- 修复同一手机反复配对后电脑端出现多条授权记录的问题：手机新增持久化安装实例标识，
  电脑端按安装实例原地轮换公钥，保留原设备 ID 和创建时间。
- 手机解除配对时会签名通知电脑撤销授权；电脑离线或版本过旧时自动排队并在恢复连接后重试。
- 设备管理改称“授权记录”，展示最近配对时间，并将旧版本记录标记为“旧记录”。
- 修复 125% / 150% DPI 下控制中心页脚链接与存储信息重叠的问题。
- 页脚改为结构化双列布局，文档、更新、日志入口独立排列。
- 增加控制中心 UI 预览入口，便于后续做布局回归验证。
- 版本升级至 `1.8.7 / versionCode 14`。

## 1.8.6

安装体验与应用内更新。

- Windows 安装器新增深色安全仪表向导图、专属多尺寸图标、中文欢迎页和完成页，
  并在系统“应用和功能”中补充发布者、支持与更新链接。
- Windows 托盘新增“检查软件更新…”和“仓库与使用文档”，控制中心版本号与页脚也可
  直接打开更新窗口。
- Windows 更新支持读取 GitHub Stable Release、SHA-256 校验、
  `%LOCALAPPDATA%` 下载缓存和管理员权限安装。
- Android 设置页新增“软件更新”和“仓库与文档”卡片，支持 APK 下载、SHA-256 校验、
  FileProvider 交付与“安装未知应用”授权引导。
- 两端启动时每 24 小时自动检查一次；只提示新版本，不静默下载或安装。
- 新增 `docs/updates.md`，记录更新流程、安全边界和 Release 资产命名约束。
- 版本升级至 `1.8.6 / versionCode 13`。

## 1.8.5

Windows 托盘管道句柄泄漏修复。

- 修复 `BandKeyTrayPipe` 每次轮询后未及时关闭导致的句柄和内存持续增长。
- 旧版 `BandKeyUnlock` 管道增加连接寿命上限，避免实例永久占用。
- 新增 `GET_SNAPSHOT`，一次返回服务状态与配对请求，托盘轮询连接数减半。
- 托盘空闲轮询调整为 3 秒，控制中心调整为 2 秒，控制中心打开时停止托盘重复轮询。
- 增加 60 次连续管道请求的句柄回归测试，补充内存排障文档。
- 版本升级至 `1.8.5 / versionCode 12`。

## 1.8.4

桌面小组件与 NFC 自动解锁。

- 新增 Android/MIUI 桌面小组件，支持不同尺寸布局、一键解锁、打开 App 和切换
  NFC 自动解锁。
- 新增锁屏/息屏 NFC 自动解锁入口，使用系统 NFC 分发和透明 Activity。
- NFC 写卡改为 `com.bandkey:unlock` 外部类型并追加 AAR，兼容旧卡读取。
- 新增 NFC 诊断、TECH/TAG 过滤、上次卡片事件和 logcat 事件记录。
- 息屏释放 Reader Mode，亮屏按需恢复，完善锁屏态 Activity 生命周期。
- 新增 Android 16+“通过 NFC 启动”权限检测和小米后台权限直达入口。
- 版本升级至 `1.8.4 / versionCode 11`。

升级说明：

- 已绑定旧 NFC 卡的用户建议重新写入一次，以获得 AAR 分发能力。
- NFC 自动解锁依赖系统在锁屏/息屏时分发 Intent；当前验证基线是亮屏可用，
  HyperOS 等系统在真正息屏时可能停止轮询或清理后台。

## 1.8.3

NFC 写卡时序修复。

- 绑定流程保持 Reader Mode 开启，直到读取、格式化和写入全部完成。
- 解决 RF 场提前关闭导致的 `TagLostException` 和写卡通信失败。
- 读卡解锁读取令牌后立即关闭 Reader Mode，再进入网络解锁流程。
- 区分卡片离开、卡片加密/锁定和普通 IO 错误。

## 1.8.2

NFC Reader Mode 修复。

- 移除 `FLAG_READER_SKIP_NDEF_CHECK`，恢复 Android 对 `Ndef` /
  `NdefFormatable` 技术的枚举。
- 修复所有 NFC 卡都被误判为“不支持 NDEF”的问题。
- 对真正不支持 NDEF 的卡显示检测到的技术类型。

## 1.8.1

最终回执可靠性修复。

- CP 在 `ReportResult` 中把最终结果原子写入
  `C:\ProgramData\BandKey\outcomes\<requestId>.result`。
- 服务端在 CP 断开后保留 8 秒宽限期，并可在查询 `unlock_status` 时补收结果。
- 修复 Windows 已登录、手机仍显示 `component_disconnected` 的会话切换竞态。
- 增加正常回执、断开后补交、断开后超时三类管道回归。

## 1.8.0

协议升级到 v2。

- 手机通过 `status` 查询电脑真实状态，不再仅凭本地配对记录显示“已就绪”。
- `unlock_result.accepted=true` 只表示服务端验签并投递。
- 最终结果通过 `unlock_status` 查询，状态为
  `accepted -> submitted -> succeeded / failed`。
- 手机超时后显示“已提交”，支持刷新最终状态，不提前展示成功。
- 区分电脑离线、设备撤销、凭据缺失/失效、未锁屏和 CP 未就绪。
- 加固本地管理通道，正式服务只保留 `/health`，开发端点仅在 `--dev` 注册。

## 1.7.0

- 接入真实 NFC 读取、绑定、本地验签和 Windows 状态回写。
- 支持最多 3 张 NFC 钥匙，令牌由 Android Keystore 加密保存。
- 托盘新增 NFC 最近验证摘要和手机端管理说明。
- 控制中心调整为 `560×780`，改善遥测和操作区布局。

## 1.6.0

- Windows 托盘和 Android 端切换到“信任仪表”暗色科技风。
- 增加信任环、状态胶囊、配对 PIN、设备列表和凭据弹窗的统一组件。
- Android 增加解锁/设备/设置三页信息架构和配对底部弹层。
- Windows 增加双击托盘打开的一屏式控制中心。

更早版本可通过 Git 标签和 GitHub Releases 查看。
