# 快速开始

本文面向首次安装 BandKey 的用户。完成后，你可以用手机 App、桌面小组件或
NFC 卡发起解锁请求，由 Windows 凭据提供程序完成锁屏登录。

## 1. 准备条件

| 项目 | 要求 |
| --- | --- |
| Windows | Windows 10/11 x64；BLE 需要 Windows 10 1809 及以上和支持 Peripheral 角色的蓝牙适配器 |
| Android | Android 8.0（API 26）及以上 |
| 网络 | 手机与电脑在同一局域网时可使用 LAN/WebSocket；BLE 配对不要求同一网络 |
| NFC | 可选；需要手机支持 NFC，卡片建议使用 NTAG213/215/216 等可写 NDEF 卡 |
| 权限 | Windows 安装需要管理员权限；首次安装或升级后建议重启电脑 |

## 2. 下载安装包

从 [GitHub Releases](https://github.com/QINGYUAI/BandKey/releases/latest) 下载：

- Windows：`BandKey-<版本>-windows-x64.exe`
- Android：`BandKey-<版本>-android.apk`
- 校验：`SHA256SUMS.txt`

PowerShell 校验示例：

```powershell
Get-FileHash .\BandKey-1.8.5-windows-x64.exe -Algorithm SHA256
Get-FileHash .\BandKey-1.8.5-android.apk -Algorithm SHA256
```

将结果与 `SHA256SUMS.txt` 中的值比较。不要使用来源不明或被重新打包的安装包。

## 3. 安装 Windows 端

1. 右键 Windows 安装包，选择“以管理员身份运行”。
2. 安装器会安装服务、托盘和凭据提供程序，注册 COM CLSID、创建
   `BandKeyService` 服务，并添加仅限专用/域网络和本地子网的防火墙规则。
3. 安装完成后托盘会自动启动；服务设置为自动启动。
4. 首次安装或升级凭据提供程序后，**重启电脑或注销再登录**。LogonUI 可能仍在
   使用旧 DLL，未重启时容易出现“解锁组件未就绪”。

检查服务：

```powershell
Get-Service BandKeyService
```

正常状态应为 `Running`。托盘双击后应能看到服务、蓝牙、设备和凭据状态。

## 4. 安装 Android 端

1. 在手机上允许当前文件管理器或浏览器“安装未知应用”。
2. 安装 `BandKey-<版本>-android.apk`。
3. 打开 BandKey，按系统提示授予 Android 13+ 的附近设备权限；旧版 Android 扫描
   BLE 设备时可能还会要求定位权限。
4. 默认连接模式为“蓝牙”。如果 BLE 不可用，App 会自动尝试局域网 TCP。

> Android 端不要求 root。NFC 自动解锁和小组件仍受系统后台、锁屏和厂商策略限制。

## 5. 保存 Windows 解锁凭据

在电脑托盘菜单中选择“设置解锁凭据”，输入当前 Windows 账户的**登录密码**。

这里必须使用 Windows 账户密码，不是 Windows Hello PIN，也不是 BandKey 配对 PIN。
第三方凭据提供程序无法读取或验证 Hello PIN。凭据会在本机通过 `LogonUser` 验证，
再由 DPAPI `LocalMachine` 加密保存，不会通过网络发送。

如果 Windows 账户使用域账号，请填写正确的域/账户组合。

## 6. 配对手机与电脑

1. 确认电脑托盘正在运行，Windows 服务状态为“运行中”。
2. 在手机 App 中点击“配对”，选择蓝牙或 WiFi/LAN。
3. 电脑托盘会弹出 6 位配对 PIN。手机输入该 PIN。
4. 配对成功后，手机会保存 `deviceId`、公钥和私钥；电脑会保存已授权设备。

规则：

- PIN 有效期为 5 分钟。
- 同一配对请求连续输错 5 次会锁定，需要重新发起。
- PIN 只在电脑托盘显示，不会回传给手机。
- 已配对设备可在电脑托盘或手机端解除；解除后该设备不能再解锁。

## 7. 首次测试解锁

1. 保持手机与电脑连接正常。
2. 按 `Win+L` 锁定电脑。
3. 在手机上点击“解锁”。
4. 手机先收到“已受理”，随后通过 `unlock_status` 查询最终结果。
5. Windows 登录成功后，手机显示成功回执。

如果手机只显示“已提交”，表示请求已送达但 Windows 尚未回报最终结果。不要重复
连续点击，先查看 [故障排查](troubleshooting.md) 中的“最终状态”部分。

## 8. 可选：添加桌面小组件

在 Android 设置页点击“添加到桌面”，选择 BandKey 小组件。小组件支持：

- 显示配对、NFC 钥匙和最近解锁状态
- 一键解锁
- 打开 App
- 切换 NFC 自动解锁开关

不同启动器对尺寸和刷新频率的限制不同；小米桌面可能将它放在“安卓小部件”而不是
“小部件中心”。详见 [NFC 解锁](nfc.md) 和 [故障排查](troubleshooting.md)。

## 9. 可选：绑定 NFC 卡

1. 准备一张空白、可写、未被加密锁定的 NDEF 卡。
2. 打开 BandKey 的 NFC 页面，选择“绑定 NFC 钥匙”。
3. 将卡片贴近手机 NFC 感应区，等待写入完成。
4. 绑定成功后，在设置页开启“息屏 NFC 自动解锁”。
5. 按手机厂商要求开启锁屏 NFC、后台弹出界面、自启动和无限制省电。

已绑定旧版卡片的用户，升级到 `1.8.4` 后建议重新写入一次，以获得新的外部类型和
AAR 分发能力。详细说明见 [NFC 解锁](nfc.md)。

## 升级与卸载

- 1.8.x 安装器沿用相同 Inno `AppId`，可以直接覆盖升级。
- 安装器会停止旧服务并更新服务路径；升级后仍建议重启一次。
- 卸载会删除 `C:\ProgramData\BandKey` 中的设备、凭据和结果文件，请先确认不再需要。
- Android 覆盖安装不会清除已配对数据；卸载会清除 App 本地密钥和配对记录。

## 下一步

- 了解架构和安全边界：[系统架构](architecture.md)、[安全模型](security.md)
- NFC 不触发或读卡失败：[NFC 解锁](nfc.md)
- 服务、配对或解锁异常：[故障排查](troubleshooting.md)
