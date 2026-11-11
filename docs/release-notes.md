# 版本记录

当前最新版本为 `1.8.4`。下载地址：
[GitHub Releases](https://github.com/QINGYUAI/BandKey/releases/latest)。

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
