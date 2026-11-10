using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;
using BandKeyBle;

namespace BandKeyTray;

internal static class Program
{
    private static readonly ServiceApi Api = new();
    private static readonly ToolStripMenuItem ServiceItem = new("服务状态：检查中…");
    private static readonly ToolStripMenuItem PairingItem = new("查看配对 PIN");

    private static NotifyIcon? _tray;
    private static Icon? _trayIcon;
    private static BleUnlockServer? _ble;
    private static TrayPopoverForm? _popover;
    private static System.Windows.Forms.Timer? _startupTimer;
    private static string? _lastNotifiedPin;
    private static bool _polling;
    private static bool _exiting;

    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
#if DEBUG
        if (TryShowPreview(args))
        {
            return;
        }
#endif
        TrayLog.Write($"tray start, version={typeof(Program).Assembly.GetName().Version}");

        _ble = new BleUnlockServer(TrayLog.Write, TrayPipeClient.RpcAsync);
        _ = _ble.StartAsync();

        _trayIcon = TechIconFactory.CreateTrayIcon();
        _tray = new NotifyIcon
        {
            Icon = _trayIcon,
            Text = "BandKey · 电脑钥匙",
            Visible = true
        };

        var menu = new ContextMenuStrip
        {
            Renderer = new TechMenuRenderer(),
            BackColor = TechPalette.Surface,
            ForeColor = TechPalette.Text,
            Font = TechPalette.Body,
            ShowImageMargin = false,
            Padding = new Padding(0, 6, 0, 6)
        };
        menu.Items.Add("打开控制中心", null, (_, _) => ShowControlCenter());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(ServiceItem);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("设置解锁凭据", null, (_, _) => ShowCredentialDialog());
        menu.Items.Add(PairingItem);
        menu.Items.Add("已配对设备…", null, (_, _) => ShowDevices());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("蓝牙状态", null, (_, _) => ShowBleStatus());
        menu.Items.Add("重新开始蓝牙广播", null, (_, _) => RestartBle());
        menu.Items.Add("打开日志目录", null, (_, _) => OpenLogDirectory());
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("退出", null, (_, _) => ExitApplication());

        PairingItem.Click += (_, _) => ShowPairingPin();
        foreach (ToolStripItem item in menu.Items)
        {
            if (item is ToolStripMenuItem)
            {
                item.Padding = new Padding(8, 5, 8, 5);
            }
        }

        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => ShowControlCenter();

        var timer = new System.Windows.Forms.Timer { Interval = 2000 };
        timer.Tick += (_, _) => _ = PollAsync();
        timer.Start();

        if (args.Any(argument =>
                string.Equals(argument, "--control-center", StringComparison.OrdinalIgnoreCase)))
        {
            _startupTimer = new System.Windows.Forms.Timer { Interval = 450 };
            _startupTimer.Tick += (_, _) =>
            {
                _startupTimer?.Stop();
                _startupTimer?.Dispose();
                _startupTimer = null;
                ShowControlCenter();
            };
            _startupTimer.Start();
        }

        Application.Run();
    }

#if DEBUG
    private static bool TryShowPreview(string[] args)
    {
        var now = DateTimeOffset.UtcNow;
        if (args.Any(argument =>
                string.Equals(argument, "--preview-pairing", StringComparison.OrdinalIgnoreCase)))
        {
            Application.Run(new PairingDialog(new PairingInfo(
                "我的手机",
                "284196",
                now,
                now.AddMinutes(5))));
            return true;
        }

        if (args.Any(argument =>
                string.Equals(argument, "--preview-credential", StringComparison.OrdinalIgnoreCase)))
        {
            Application.Run(new CredentialDialog());
            return true;
        }

        if (args.Any(argument =>
                string.Equals(argument, "--preview-devices", StringComparison.OrdinalIgnoreCase)))
        {
            var devices = new List<DeviceInfo>
            {
                new("bk_7f3a91c2e84b9c21", "我的手机", now.AddDays(-1)),
                new("bk_2d8144c8b27a4a70", "备用 iPhone", now.AddDays(-12)),
                new("bk_9b04c31db6e18c", "办公室手环", now.AddDays(-55))
            };
            Application.Run(new DevicesDialog(new ServiceApi(), devices));
            return true;
        }

        return false;
    }
#endif

    private static void ShowControlCenter()
    {
        if (_popover is { IsDisposed: false })
        {
            if (!_popover.Visible)
            {
                _popover.ShowNearTray();
            }
            else
            {
                _popover.Activate();
            }

            return;
        }

        _popover = new TrayPopoverForm(
            Api,
            () => new BleUiState(
                _ble?.Status ?? "未初始化",
                _ble?.IsAdvertising ?? false,
                _ble?.ConnectedClientCount ?? 0),
            ShowPairingPin,
            ShowDevices,
            ShowCredentialDialog,
            RestartBle,
            OpenLogDirectory);
        _popover.FormClosed += (_, _) => _popover = null;
        _popover.ShowNearTray();
    }

    private static async void ExitApplication()
    {
        if (_exiting)
        {
            return;
        }

        _exiting = true;
        try
        {
            _startupTimer?.Stop();
            _startupTimer?.Dispose();
            _startupTimer = null;
            _popover?.Close();
            _popover = null;

            if (_tray is not null)
            {
                _tray.Visible = false;
                _tray.ContextMenuStrip = null;
                _tray.Dispose();
                _tray = null;
            }

            if (_ble is not null)
            {
                try
                {
                    await _ble.DisposeAsync();
                }
                catch (Exception ex)
                {
                    TrayLog.Write("ble shutdown failed: " + ex);
                }
                finally
                {
                    _ble = null;
                }
            }
        }
        finally
        {
            _trayIcon?.Dispose();
            _trayIcon = null;
            Application.Exit();
        }
    }

    private static async Task PollAsync()
    {
        if (_polling)
        {
            return;
        }

        _polling = true;
        try
        {
            var online = await Api.IsOnlineAsync();
            ServiceItem.Text = online ? "服务状态：运行中" : "服务状态：未运行";
            ServiceItem.ForeColor = online ? TechPalette.Success : TechPalette.Danger;

            var pairing = online ? await Api.GetPairingAsync() : null;
            if (pairing is null)
            {
                _lastNotifiedPin = null;
                PairingItem.Text = "查看配对 PIN";
                return;
            }

            PairingItem.Text = $"配对 PIN：{pairing.Pin}";
            if (pairing.Pin == _lastNotifiedPin)
            {
                return;
            }

            _lastNotifiedPin = pairing.Pin;
            TrayLog.Write($"pairing request from '{pairing.DeviceName}'");
            _tray?.ShowBalloonTip(
                10_000,
                "收到配对请求",
                $"设备：{pairing.DeviceName}\nPIN：{FormatPin(pairing.Pin)}\n请在手机上输入该 PIN。",
                ToolTipIcon.Info);
        }
        catch (Exception ex)
        {
            TrayLog.Write("poll failed: " + ex.Message);
        }
        finally
        {
            _polling = false;
        }
    }

    private static async void ShowCredentialDialog()
    {
        using var dialog = new CredentialDialog();
        if (dialog.ShowDialog() != DialogResult.OK)
        {
            return;
        }

        if (string.IsNullOrWhiteSpace(dialog.Account))
        {
            TechMessageBox.Show(null, "请输入 Windows 账户。", "解锁凭据", TechMessageKind.Warning);
            return;
        }

        if (string.IsNullOrEmpty(dialog.Password))
        {
            TechMessageBox.Show(
                null,
                "请输入 Windows 登录密码（不是 PIN）。",
                "解锁凭据",
                TechMessageKind.Warning);
            return;
        }

        if (!string.Equals(dialog.Password, dialog.ConfirmPassword, StringComparison.Ordinal))
        {
            TechMessageBox.Show(null, "两次输入的密码不一致。", "解锁凭据", TechMessageKind.Warning);
            return;
        }

        var slash = dialog.Account.IndexOf('\\');
        var domain = slash > 0 ? dialog.Account[..slash] : "";
        var username = slash > 0 ? dialog.Account[(slash + 1)..] : dialog.Account;

        try
        {
            var result = await TrayPipeClient.SaveCredentialAsync(domain, username, dialog.Password);
            if (result.PipeUnavailable)
            {
                TechMessageBox.Show(
                    null,
                    "无法连接电脑钥匙服务：" + result.Error + "\n\n请确认“服务状态”为运行中。",
                    "解锁凭据",
                    TechMessageKind.Danger);
                return;
            }

            if (!result.Success)
            {
                var message = result.Error switch
                {
                    "UserMismatch" => "服务拒绝了其他 Windows 用户的凭据，请使用当前登录账户。",
                    "InvalidPassword" => "Windows 密码验证失败。\n\n请输入登录 Windows 时使用的密码，而不是 PIN。",
                    "InvalidRequest" => "提交的账户信息无效。",
                    "WriteFailed" => "凭据写入失败，请检查 C:\\ProgramData\\BandKey 权限。",
                    _ => "保存失败：" + result.Error
                };
                TechMessageBox.Show(null, message, "解锁凭据", TechMessageKind.Danger);
                return;
            }

            TechMessageBox.Show(null, "凭据已保存。", "解锁凭据", TechMessageKind.Success);
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                null,
                "保存失败：" + ex.Message + "\n\n请确认“服务状态”为运行中。",
                "解锁凭据",
                TechMessageKind.Danger);
        }
    }

    private static async void ShowPairingPin()
    {
        try
        {
            var pairing = await Api.GetPairingAsync();
            if (pairing is null)
            {
                TechMessageBox.Show(
                    null,
                    "当前没有待确认的配对请求。\n\n请在手机上点“配对”，随后这里会自动弹出 PIN。",
                    "配对请求",
                    TechMessageKind.Info);
                return;
            }

            using var dialog = new PairingDialog(pairing);
            dialog.ShowDialog();
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                null,
                "无法连接电脑钥匙服务：" + ex.Message + "\n\n请确认“服务状态”为运行中。",
                "配对请求",
                TechMessageKind.Danger);
        }
    }

    private static async void ShowDevices()
    {
        try
        {
            var devices = await Api.GetDevicesAsync();
            using var dialog = new DevicesDialog(Api, devices);
            dialog.ShowDialog();
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                null,
                "无法读取已配对设备：" + ex.Message + "\n\n请确认“服务状态”为运行中。",
                "已配对设备",
                TechMessageKind.Danger);
        }
    }

    private static void ShowBleStatus()
    {
        var status = _ble?.Status ?? "未初始化";
        TechMessageBox.Show(
            null,
            $"蓝牙状态：{status}\n\n手机请打开 BandKey，选择“蓝牙”模式后点击配对。",
            "蓝牙状态",
            TechMessageKind.Info);
    }

    private static async void RestartBle()
    {
        TrayLog.Write("restart BLE requested");
        if (_ble is not null)
        {
            await _ble.DisposeAsync();
        }

        _ble = new BleUnlockServer(TrayLog.Write, TrayPipeClient.RpcAsync);
        var ok = await _ble.StartAsync();
        TechMessageBox.Show(
            null,
            ok ? "蓝牙广播已重新启动。" : $"蓝牙启动失败：{_ble.Status}",
            "蓝牙状态",
            ok ? TechMessageKind.Success : TechMessageKind.Danger);
    }

    private static void OpenLogDirectory()
    {
        TrayLog.Write("open log directory");
        var directory = Path.GetDirectoryName(TrayLog.LogFile);
        if (string.IsNullOrEmpty(directory))
        {
            return;
        }

        Directory.CreateDirectory(directory);
        Process.Start(new ProcessStartInfo("explorer.exe", directory) { UseShellExecute = true });
    }

    private static string FormatPin(string pin)
    {
        return pin.Length == 6 ? pin[..3] + " " + pin[3..] : pin;
    }
}
