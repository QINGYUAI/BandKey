# 软件更新

本文说明 Windows 托盘和 Android App 的应用内更新流程、校验边界和 Release 发布约束。

## 检查策略

两端都读取 GitHub 的 Latest Stable Release：

```text
https://api.github.com/repos/QINGYUAI/BandKey/releases/latest
```

- App 启动时最多每 24 小时自动检查一次。
- 设置页或控制中心可随时手动检查。
- 自动检查只提示新版本，不会静默下载或安装。
- 最新版本不高于当前版本时显示“已是最新”。

Windows 的检查时间记录在 `%LOCALAPPDATA%\BandKey\last-update-check.txt`；Android
使用同名应用私有 SharedPreferences。

## Windows 更新流程

入口位于托盘菜单“检查软件更新…”以及控制中心的版本号和页脚“更新”。

1. 读取 Release 的 `tag_name`、版本说明和资产列表。
2. 查找 `BandKey-<version>-windows-x64.exe` 与 `SHA256SUMS.txt`。
3. 下载到 `%LOCALAPPDATA%\BandKey\updates\<version>\`。
4. 对下载文件计算 SHA-256，并与官方校验文件中的同名条目比较。
5. 校验通过后，由用户确认启动安装器；安装器通过 `runas` 请求管理员权限。
6. 安装器关闭托盘和服务，覆盖服务、托盘与凭据提供程序，完成后建议重启电脑。

下载中的临时文件使用 `.part` 后缀；失败或校验不通过时会删除，不会执行未校验文件。

## Android 更新流程

入口位于“设置 → 软件更新”。

1. 读取同一个 GitHub Release。
2. 查找 `BandKey-<version>-android.apk` 与 `SHA256SUMS.txt`。
3. 下载到应用外部私有目录 `Android/data/com.bandkey.app/files/Download/updates/`。
4. 校验 APK SHA-256。
5. 通过 `FileProvider` 把文件 URI 交给系统 Package Installer。
6. 如果未开启“安装未知应用”，先跳转系统授权页；返回后再次点击安装。

更新使用与首次安装相同的签名证书，因此可以覆盖安装并保留本机配对、NFC 密钥和设置。
不要把 `debug` 签名包覆盖到正式包上，否则系统会拒绝安装。

## 安全边界

- 只接受 HTTPS GitHub Release 资产。
- 仓库必须公开；客户端不内置 GitHub Token，也不能读取私有仓库 Release。
- 安装前必须校验 SHA-256；缺少校验文件时禁止安装。
- 自动检查不会静默执行安装程序。
- Windows 安装需要管理员权限，Android 安装始终由系统界面确认。
- SHA-256 能发现下载损坏或资产被替换，但不能替代 GitHub 账户、仓库和发布流程本身的安全。

## 发布约束

每个需要被客户端识别的 Release 必须：

- 标记为 GitHub 的 Latest Release，不使用预发布标签作为稳定更新来源。
- 包含对应平台安装包，文件名严格匹配
  `BandKey-<version>-windows-x64.exe` / `BandKey-<version>-android.apk`。
- 包含 `SHA256SUMS.txt`，每行格式为 `<64 位 SHA-256>  <文件名>`。
- 保持 Android `versionCode` 单调递增，并使用同一 Release 签名证书。

## 排障

| 现象 | 处理 |
| --- | --- |
| 检查失败，HTTP 403 | GitHub API 临时限流，稍后重试 |
| 发现版本但没有安装包 | 检查 Release 资产命名 |
| 缺少校验文件 | 重新上传 `SHA256SUMS.txt` 后再发布 |
| SHA-256 不一致 | 删除下载缓存后重试，不要绕过校验 |
| Android 无法打开安装器 | 在系统设置中允许 BandKey 安装未知应用 |
| 覆盖安装失败 | 确认设备上的旧版本与当前 APK 使用同一签名 |
