using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed record BleUiState(string Status, bool Advertising, int ConnectedClients);

internal sealed class TrayPopoverForm : TechForm
{
    private readonly ServiceApi _api;
    private readonly Func<BleUiState> _bleState;
    private readonly Action _showPairing;
    private readonly Action _showDevices;
    private readonly Action _showCredential;
    private readonly Action _restartBle;
    private readonly Action _openLog;
    private readonly Action _showUpdates;
    private readonly Action _openDocs;
    private readonly System.Windows.Forms.Timer _pollTimer = new() { Interval = 2000 };

    private readonly TechStatusPill _servicePill = new();
    private readonly ToolTip _versionTip = new();
    private readonly Label _version = new();
    private readonly Label _heroKicker = new();
    private readonly Label _heroTitle = new();
    private readonly Label _heroDescription = new();
    private readonly TechMiniRing _trustRing = new();
    private readonly TechNfcStrip _nfcStrip = new();
    private readonly TechMetric _bleMetric = new();
    private readonly TechMetric _pairedMetric = new();
    private readonly TechMetric _credentialMetric = new();
    private readonly TechActionButton _pairingAction = new();
    private readonly TechActionButton _nfcAction = new();
    private readonly TechActionButton _devicesAction = new();
    private readonly TechActionButton _credentialAction = new();
    private readonly TechActionButton _refreshAction = new();
    private readonly Label _channelLabel = new();

    private PairingInfo? _pairing;
    private bool _refreshing;

    public TrayPopoverForm(
        ServiceApi api,
        Func<BleUiState> bleState,
        Action showPairing,
        Action showDevices,
        Action showCredential,
        Action restartBle,
        Action openLog,
        Action showUpdates,
        Action openDocs)
        : base("控制中心", new Size(560, 792))
    {
        _api = api;
        _bleState = bleState;
        _showPairing = showPairing;
        _showDevices = showDevices;
        _showCredential = showCredential;
        _restartBle = restartBle;
        _openLog = openLog;
        _showUpdates = showUpdates;
        _openDocs = openDocs;

        ShowInTaskbar = false;
        StartPosition = FormStartPosition.Manual;
        Body.Padding = new Padding(32, 10, 32, 24);
        BuildContent();

        _pollTimer.Tick += (_, _) => _ = RefreshAsync();
        Shown += (_, _) =>
        {
            _pollTimer.Start();
            _ = RefreshAsync();
        };
        FormClosed += (_, _) =>
        {
            _pollTimer.Dispose();
            _versionTip.Dispose();
        };
    }

    public void ShowNearTray()
    {
        var screen = Screen.FromPoint(Cursor.Position).WorkingArea;
        var x = Cursor.Position.X - Width / 2;
        x = Math.Clamp(x, screen.Left + 12, screen.Right - Width - 12);
        var y = Math.Max(screen.Top + 12, screen.Bottom - Height - 12);
        Location = new Point(x, y);
        Show();
        Activate();
    }

    private void BuildContent()
    {
        var root = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 15,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        root.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 34));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 182));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 18));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 8));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 84));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 62));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 8));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 180));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 10));
        root.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));

        var statusRow = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        _servicePill.Location = new Point(0, 1);
        _servicePill.SetStatus("正在检查服务", TechPalette.Muted);
        _version.Text = "v" + (typeof(TrayPopoverForm).Assembly.GetName().Version?.ToString(3) ?? "1.8.0");
        _version.AutoSize = true;
        _version.Font = TechPalette.Mono;
        _version.ForeColor = TechPalette.Dim;
        _version.BackColor = Color.Transparent;
        _version.Cursor = Cursors.Hand;
        _version.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _version.Location = new Point(statusRow.Width - _version.Width, 8);
        _versionTip.SetToolTip(_version, "检查软件更新");
        _version.Click += (_, _) => _showUpdates();
        statusRow.Controls.Add(_servicePill);
        statusRow.Controls.Add(_version);
        statusRow.Resize += (_, _) => _version.Left = Math.Max(0, statusRow.Width - _version.Width);

        var hero = new TechCard
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0),
            Padding = new Padding(26, 20, 22, 20),
            Radius = 16
        };
        var heroLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 1,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        heroLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        heroLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 150));
        heroLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var heroCopy = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        heroCopy.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        heroCopy.RowStyles.Add(new RowStyle(SizeType.Absolute, 20));
        heroCopy.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        heroCopy.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        heroCopy.RowStyles.Add(new RowStyle(SizeType.Absolute, 18));

        _heroKicker.Text = "TRUST INSTRUMENT";
        _heroKicker.AutoSize = false;
        _heroKicker.Dock = DockStyle.Fill;
        _heroKicker.Font = TechPalette.Caption;
        _heroKicker.ForeColor = TechPalette.Dim;
        _heroKicker.BackColor = Color.Transparent;
        _heroKicker.TextAlign = ContentAlignment.MiddleLeft;
        _heroKicker.Margin = new Padding(0);

        _heroTitle.Text = "正在检查本机状态";
        _heroTitle.AutoSize = false;
        _heroTitle.Dock = DockStyle.Fill;
        _heroTitle.Font = new Font("Microsoft YaHei UI", 19f, FontStyle.Bold);
        _heroTitle.ForeColor = TechPalette.Text;
        _heroTitle.BackColor = Color.Transparent;
        _heroTitle.TextAlign = ContentAlignment.MiddleLeft;
        _heroTitle.Margin = new Padding(0);

        _heroDescription.Text = "正在读取服务、设备与凭据状态。";
        _heroDescription.AutoSize = false;
        _heroDescription.Dock = DockStyle.Fill;
        _heroDescription.Font = TechPalette.Body;
        _heroDescription.ForeColor = TechPalette.Muted;
        _heroDescription.BackColor = Color.Transparent;
        _heroDescription.TextAlign = ContentAlignment.TopLeft;
        _heroDescription.Margin = new Padding(0, 3, 16, 0);

        _channelLabel.Text = "LOCAL CHANNEL · ECDSA P-256";
        _channelLabel.AutoSize = false;
        _channelLabel.Dock = DockStyle.Fill;
        _channelLabel.Font = TechPalette.Caption;
        _channelLabel.ForeColor = TechPalette.Dim;
        _channelLabel.BackColor = Color.Transparent;
        _channelLabel.TextAlign = ContentAlignment.BottomLeft;
        _channelLabel.Margin = new Padding(0);

        heroCopy.Controls.Add(_heroKicker, 0, 0);
        heroCopy.Controls.Add(_heroTitle, 0, 1);
        heroCopy.Controls.Add(_heroDescription, 0, 2);
        heroCopy.Controls.Add(_channelLabel, 0, 3);

        _trustRing.Dock = DockStyle.Fill;
        _trustRing.Margin = new Padding(8, 4, 0, 4);
        heroLayout.Controls.Add(heroCopy, 0, 0);
        heroLayout.Controls.Add(_trustRing, 1, 0);
        hero.Controls.Add(heroLayout);

        var metrics = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 3,
            RowCount = 1,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        metrics.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        metrics.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.333f));
        metrics.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 33.334f));
        metrics.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        _bleMetric.Caption = "蓝牙广播";
        _bleMetric.Value = "检查中";
        _bleMetric.Dock = DockStyle.Fill;
        _bleMetric.Margin = new Padding(0, 0, 5, 0);
        _pairedMetric.Caption = "已配对";
        _pairedMetric.Value = "—";
        _pairedMetric.Dock = DockStyle.Fill;
        _pairedMetric.Margin = new Padding(3, 0, 3, 0);
        _credentialMetric.Caption = "解锁凭据";
        _credentialMetric.Value = "检查中";
        _credentialMetric.Dock = DockStyle.Fill;
        _credentialMetric.Margin = new Padding(5, 0, 0, 0);
        metrics.Controls.Add(_bleMetric, 0, 0);
        metrics.Controls.Add(_pairedMetric, 1, 0);
        metrics.Controls.Add(_credentialMetric, 2, 0);

        _nfcStrip.Dock = DockStyle.Fill;
        _nfcStrip.Margin = new Padding(0);

        var actions = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 3,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        actions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        actions.RowStyles.Add(new RowStyle(SizeType.Absolute, 66));
        actions.RowStyles.Add(new RowStyle(SizeType.Absolute, 60));
        actions.RowStyles.Add(new RowStyle(SizeType.Absolute, 54));

        ConfigureAction(_pairingAction, "查看配对 PIN", TechActionGlyph.Key, true);
        _pairingAction.SubText = "等待手机发起配对";
        _pairingAction.Dock = DockStyle.Fill;
        _pairingAction.Margin = new Padding(0, 0, 0, 6);
        actions.SetColumnSpan(_pairingAction, 2);

        ConfigureAction(_nfcAction, "NFC 钥匙", TechActionGlyph.Nfc, false);
        _nfcAction.SubText = "由手机管理";
        _nfcAction.Dock = DockStyle.Fill;
        _nfcAction.Margin = new Padding(0, 0, 5, 6);

        ConfigureAction(_devicesAction, "已配对设备", TechActionGlyph.Devices, false);
        _devicesAction.SubText = "设备管理";
        _devicesAction.Dock = DockStyle.Fill;
        _devicesAction.Margin = new Padding(5, 0, 0, 6);

        ConfigureAction(_credentialAction, "设置解锁凭据", TechActionGlyph.Lock, false);
        _credentialAction.SubText = "Windows 账户密码";
        _credentialAction.Dock = DockStyle.Fill;
        _credentialAction.Margin = new Padding(0, 0, 5, 0);

        ConfigureAction(_refreshAction, "重新广播", TechActionGlyph.Refresh, false);
        _refreshAction.SubText = "蓝牙外设";
        _refreshAction.Dock = DockStyle.Fill;
        _refreshAction.Margin = new Padding(5, 0, 0, 0);

        actions.Controls.Add(_pairingAction, 0, 0);
        actions.Controls.Add(_nfcAction, 0, 1);
        actions.Controls.Add(_devicesAction, 1, 1);
        actions.Controls.Add(_credentialAction, 0, 2);
        actions.Controls.Add(_refreshAction, 1, 2);

        var footer = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 2,
            RowCount = 1,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        footer.ColumnStyles.Add(new ColumnStyle(SizeType.AutoSize));
        footer.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        footer.Paint += (_, e) =>
        {
            using var divider = new Pen(TechPalette.BorderSoft);
            e.Graphics.DrawLine(divider, 0, 0, footer.Width, 0);
        };

        var storage = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 2, 12, 0)
        };
        storage.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        storage.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        storage.RowStyles.Add(new RowStyle(SizeType.Percent, 50));
        var local = new Label
        {
            Text = "LOCAL ONLY · DPAPI",
            AutoSize = false,
            Dock = DockStyle.Fill,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        };
        var platform = new Label
        {
            Text = "Windows 11 · x64",
            AutoSize = false,
            Dock = DockStyle.Fill,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        };
        storage.Controls.Add(local, 0, 0);
        storage.Controls.Add(platform, 0, 1);

        var links = new FlowLayoutPanel
        {
            AutoSize = true,
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            WrapContents = false,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 6, 0, 0),
            Padding = new Padding(0)
        };
        var logLink = new Label
        {
            Text = "打开日志  ›",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand,
            Margin = new Padding(14, 0, 0, 0)
        };
        logLink.MouseEnter += (_, _) => logLink.ForeColor = TechPalette.Mint;
        logLink.MouseLeave += (_, _) => logLink.ForeColor = TechPalette.Dim;
        logLink.Click += (_, _) => _openLog();
        var docsLink = new Label
        {
            Text = "文档  ›",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand,
            Margin = new Padding(14, 0, 0, 0)
        };
        docsLink.MouseEnter += (_, _) => docsLink.ForeColor = TechPalette.Mint;
        docsLink.MouseLeave += (_, _) => docsLink.ForeColor = TechPalette.Dim;
        docsLink.Click += (_, _) => _openDocs();
        var updateLink = new Label
        {
            Text = "更新  ›",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand,
            Margin = new Padding(14, 0, 0, 0)
        };
        updateLink.MouseEnter += (_, _) => updateLink.ForeColor = TechPalette.Mint;
        updateLink.MouseLeave += (_, _) => updateLink.ForeColor = TechPalette.Dim;
        updateLink.Click += (_, _) => _showUpdates();
        links.Controls.Add(logLink);
        links.Controls.Add(updateLink);
        links.Controls.Add(docsLink);
        footer.Controls.Add(storage, 0, 0);
        footer.Controls.Add(links, 1, 0);

        root.Controls.Add(statusRow, 0, 0);
        root.Controls.Add(hero, 0, 2);
        root.Controls.Add(CreateSectionHeader("LIVE TELEMETRY", "实时通道"), 0, 4);
        root.Controls.Add(metrics, 0, 6);
        root.Controls.Add(_nfcStrip, 0, 8);
        root.Controls.Add(CreateSectionHeader("QUICK ACTIONS", "快捷操作"), 0, 10);
        root.Controls.Add(actions, 0, 12);
        root.Controls.Add(footer, 0, 14);

        _pairingAction.Click += (_, _) => _showPairing();
        _nfcAction.Click += (_, _) => ShowNfcInfo();
        _nfcStrip.Click += (_, _) => ShowNfcInfo();
        _devicesAction.Click += (_, _) => _showDevices();
        _credentialAction.Click += (_, _) => _showCredential();
        _refreshAction.Click += (_, _) => _restartBle();

        Body.Controls.Add(root);
    }

    private static void ConfigureAction(
        TechActionButton button,
        string text,
        TechActionGlyph glyph,
        bool primary)
    {
        button.Text = text;
        button.Glyph = glyph;
        button.Primary = primary;
        button.Height = primary ? 60 : 54;
    }

    private static Control CreateSectionHeader(string eyebrow, string title)
    {
        var header = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        header.Paint += (_, e) =>
        {
            var codeSize = TextRenderer.MeasureText(eyebrow, TechPalette.Caption);
            var titleSize = TextRenderer.MeasureText(title, TechPalette.BodyBold);
            var centerY = header.Height / 2f;

            TextRenderer.DrawText(
                e.Graphics,
                eyebrow,
                TechPalette.Caption,
                new Rectangle(0, 0, codeSize.Width + 2, header.Height),
                TechPalette.Mint,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
            TextRenderer.DrawText(
                e.Graphics,
                title,
                TechPalette.BodyBold,
                new Rectangle(header.Width - titleSize.Width - 2, 0, titleSize.Width + 2, header.Height),
                TechPalette.Muted,
                TextFormatFlags.Right | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);

            var lineStart = codeSize.Width + 12;
            var lineEnd = header.Width - titleSize.Width - 12;
            if (lineEnd <= lineStart)
            {
                return;
            }

            using var line = new Pen(TechPalette.BorderSoft);
            e.Graphics.DrawLine(line, lineStart, centerY, lineEnd, centerY);
        };
        return header;
    }

    private async Task RefreshAsync()
    {
        if (_refreshing || IsDisposed)
        {
            return;
        }

        _refreshing = true;
        try
        {
            var snapshot = await _api.GetSnapshotAsync();
            if (IsDisposed)
            {
                return;
            }

            if (snapshot is null)
            {
                _pairing = null;
                ApplyState(false, null, null);
                return;
            }

            _pairing = snapshot.Pairing;
            ApplyState(true, snapshot.Status, snapshot.Pairing);
        }
        catch (Exception ex)
        {
            TrayLog.Write("control center refresh failed: " + ex.Message);
            if (!IsDisposed)
            {
                ApplyState(false, null, null);
            }
        }
        finally
        {
            _refreshing = false;
        }
    }

    private void ApplyState(bool online, ServiceStatus? status, PairingInfo? pairing)
    {
        var ble = _bleState();
        var paired = status?.PairedCount ?? 0;
        var credentialSet = status?.CredentialSet == true;
        var rejected = status?.CredentialRejected == true;
        var ready = online && status?.Ready == true && !rejected;
        var nfcVerified = online && status?.NfcLastUsedAt is not null;
        var trustCount = (online ? 1 : 0)
                         + (paired > 0 ? 1 : 0)
                         + (credentialSet ? 1 : 0);

        if (!online)
        {
            _servicePill.SetStatus("服务未运行", TechPalette.Danger);
            _heroTitle.Text = "电脑钥匙服务离线";
            _heroDescription.Text = "请启动 BandKeyService，或在服务管理器中重新启动。";
            _trustRing.Tone = TechPalette.Danger;
        }
        else if (rejected)
        {
            _servicePill.SetStatus("凭据已失效", TechPalette.Danger);
            _heroTitle.Text = "Windows 拒绝了凭据";
            _heroDescription.Text = "请重新保存 Windows 登录密码，再尝试自动解锁。";
            _trustRing.Tone = TechPalette.Danger;
        }
        else if (ready)
        {
            _servicePill.SetStatus("服务运行中", TechPalette.Mint);
            _heroTitle.Text = "本机已就绪";
            _heroDescription.Text = nfcVerified
                ? ble.ConnectedClients > 0
                    ? $"手机通道已连接 · {ble.ConnectedClients} 台在线\nNFC 钥匙可复用现有签名链路"
                    : "手机钥匙已配对\nNFC 钥匙可复用现有签名链路"
                : ble.ConnectedClients > 0
                    ? $"手机通道已连接 · {ble.ConnectedClients} 台在线\n锁屏后可以直接确认解锁"
                    : "手机钥匙已配对\n锁屏后可以直接确认解锁";
            _trustRing.Tone = TechPalette.Mint;
        }
        else if (paired == 0)
        {
            _servicePill.SetStatus("等待配对", TechPalette.Warning);
            _heroTitle.Text = ble.ConnectedClients > 0 ? "手机已连接" : "等待手机配对";
            _heroDescription.Text = "在手机上发起配对\n电脑会显示 6 位一次性 PIN";
            _trustRing.Tone = TechPalette.Violet;
        }
        else if (!credentialSet)
        {
            _servicePill.SetStatus("还差解锁凭据", TechPalette.Warning);
            _heroTitle.Text = "手机已绑定，凭据未保存";
            _heroDescription.Text = "保存 Windows 账户密码后\n才能进入自动解锁链路";
            _trustRing.Tone = TechPalette.Warning;
        }
        else
        {
            _servicePill.SetStatus("部分服务未就绪", TechPalette.Warning);
            _heroTitle.Text = "通道正在准备";
            _heroDescription.Text = "服务在线，但锁屏组件尚未报告就绪。";
            _trustRing.Tone = TechPalette.Warning;
        }

        _trustRing.Value = trustCount / 3f;
        _trustRing.CenterValue = $"{trustCount}/3";
        _trustRing.CenterCaption = ready ? "READY" : "CHECK";
        _channelLabel.Text = !online
            ? "LOCAL CHANNEL · ECDSA P-256"
            : nfcVerified
                ? "NFC KEY READY · ECDSA P-256"
                : "PHONE KEY READY · ECDSA P-256";

        ApplyNfcState(online, status);

        _bleMetric.Value = !ble.Advertising
            ? Compact(ble.Status, 10)
            : ble.ConnectedClients > 0
                ? $"已连接 {ble.ConnectedClients}"
                : "广播中";
        _bleMetric.Tone = ble.Advertising ? TechPalette.Mint : TechPalette.Danger;

        _pairedMetric.Value = online ? $"{paired} 台" : "—";
        _pairedMetric.Tone = paired > 0 ? TechPalette.Violet : TechPalette.Muted;

        _credentialMetric.Value = !online
            ? "服务离线"
            : rejected
                ? "已被拒绝"
                : credentialSet
                    ? "已保存"
                    : "未保存";
        _credentialMetric.Tone = credentialSet && !rejected
            ? TechPalette.Mint
            : rejected
                ? TechPalette.Danger
                : TechPalette.Warning;

        _pairingAction.SubText = pairing is null
            ? "等待手机发起配对"
            : $"新请求 · {FormatPin(pairing.Pin)}";
        _devicesAction.SubText = online ? $"{paired} 台已授权" : "服务离线";
        _credentialAction.SubText = credentialSet
            ? (rejected ? "已被系统拒绝" : "已保存")
            : "Windows 密码";
        _nfcAction.SubText = !online
            ? "服务离线"
            : nfcVerified
                ? status?.NfcKeyHint ?? "手机本地验签"
                : "由手机管理";
        _refreshAction.SubText = ble.Advertising ? "蓝牙广播中" : Compact(ble.Status, 12);
    }

    private void ApplyNfcState(bool online, ServiceStatus? status)
    {
        if (!online)
        {
            _nfcStrip.Kicker = "NFC KEY · OFFLINE";
            _nfcStrip.Value = "无法读取 NFC 状态";
            _nfcStrip.Detail = "服务未运行";
            _nfcStrip.Tone = TechPalette.Danger;
            return;
        }

        var usedAt = status?.NfcLastUsedAt;
        if (usedAt is null)
        {
            _nfcStrip.Kicker = "NFC KEY · WAITING";
            _nfcStrip.Value = "等待手机本地验签";
            _nfcStrip.Detail = "NFC 由手机读取";
            _nfcStrip.Tone = TechPalette.Warning;
            return;
        }

        _nfcStrip.Kicker = "NFC KEY · PHONE VERIFIED";
        _nfcStrip.Value = string.IsNullOrWhiteSpace(status?.NfcKeyHint)
            ? "手机本地验签完成"
            : status!.NfcKeyHint;
        _nfcStrip.Detail = "最近验证 " + FormatUnixTime(usedAt.Value);
        _nfcStrip.Tone = TechPalette.Mint;
    }

    private void ShowNfcInfo()
    {
        TechMessageBox.Show(
            this,
            "NFC 钥匙由手机读取，并在手机本地完成验签。\n\n" +
            "电脑不读取卡片，也无法在电脑端管理钥匙列表；Windows 只显示最近一次验签通过的钥匙指纹和验证时间。\n\n" +
            "请在手机的「NFC 钥匙」页面完成绑定、重命名或删除。",
            "NFC 钥匙",
            TechMessageKind.Info);
    }

    private static string FormatUnixTime(long milliseconds)
    {
        try
        {
            return DateTimeOffset.FromUnixTimeMilliseconds(milliseconds)
                .ToLocalTime()
                .ToString("MM-dd HH:mm");
        }
        catch (ArgumentOutOfRangeException)
        {
            return "时间未知";
        }
    }

    private static string Compact(string text, int maxLength)
    {
        return text.Length <= maxLength ? text : text[..Math.Max(1, maxLength - 1)] + "…";
    }

    private static string FormatPin(string pin)
    {
        return pin.Length == 6 ? pin[..3] + " " + pin[3..] : pin;
    }
}
