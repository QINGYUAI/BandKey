using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed class DevicesDialog : TechForm
{
    private readonly ServiceApi _api;
    private readonly TechDeviceList _list = new() { Dock = DockStyle.Fill, Margin = new Padding(0) };
    private readonly TechButton _delete;
    private readonly Label _count;
    private readonly TechStatusPill _statusPill = new();

    public DevicesDialog(ServiceApi api, IReadOnlyList<DeviceInfo> devices)
        : base("授权记录", new Size(760, 560))
    {
        _api = api;

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 5,
            BackColor = TechPalette.Background
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var kicker = new Label
        {
            Text = "AUTHORIZED RECORDS / 已授权",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Mint,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        var heading = new Label
        {
            Text = "已授权记录",
            AutoSize = true,
            Font = TechPalette.Display,
            ForeColor = TechPalette.Text,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        var subtitle = new Label
        {
            Text = "每条记录对应一把签名密钥；同一安装实例重新配对会原地轮换。",
            AutoSize = true,
            Font = TechPalette.Body,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 4, 0, 0)
        };

        var countHost = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        _count = new Label
        {
            Text = "",
            AutoSize = true,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = TechPalette.Mono,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Location = new Point(0, 9)
        };
        _statusPill.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _statusPill.Location = new Point(countHost.Width - _statusPill.Width, 4);
        countHost.Controls.Add(_count);
        countHost.Controls.Add(_statusPill);
        countHost.Resize += (_, _) => _statusPill.Left = Math.Max(0, countHost.Width - _statusPill.Width);

        _list.SelectionChanged += (_, _) => UpdateActions();

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        var close = new TechButton
        {
            Text = "关闭",
            Width = 88,
            DialogResult = DialogResult.Cancel,
            Margin = new Padding(8, 0, 0, 0)
        };
        _delete = new TechButton
        {
            Text = "撤销所选",
            Width = 112,
            Accent = TechPalette.Danger,
            Margin = new Padding(8, 0, 0, 0)
        };
        var refresh = new TechButton
        {
            Text = "刷新",
            Width = 88,
            Margin = new Padding(8, 0, 0, 0)
        };
        _delete.Click += async (_, _) => await DeleteSelectedAsync();
        refresh.Click += async (_, _) => await RefreshAsync();
        buttons.Controls.Add(close);
        buttons.Controls.Add(_delete);
        buttons.Controls.Add(refresh);

        layout.Controls.Add(kicker, 0, 0);
        layout.Controls.Add(heading, 0, 1);
        layout.Controls.Add(subtitle, 0, 2);
        layout.Controls.Add(countHost, 0, 3);
        layout.Controls.Add(_list, 0, 4);
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        var footer = new Panel
        {
            Dock = DockStyle.Bottom,
            Height = 58,
            BackColor = TechPalette.Background,
            Padding = new Padding(0, 12, 0, 0)
        };
        footer.Controls.Add(buttons);

        Body.Controls.Add(layout);
        Body.Controls.Add(footer);
        CancelButton = close;
        Fill(devices);
    }

    private void Fill(IReadOnlyList<DeviceInfo> devices)
    {
        _list.SetDevices(devices);
        _count.Text = $"AUTHORIZED RECORDS / {devices.Count:00}";
        var legacyCount = devices.Count(device => !device.InstallIdBound);
        _statusPill.SetStatus(
            devices.Count == 0
                ? "等待设备配对"
                : legacyCount > 0
                    ? $"{devices.Count} 条授权 · {legacyCount} 条旧记录"
                    : $"{devices.Count} 条授权",
            devices.Count == 0 ? TechPalette.Warning : TechPalette.Mint);
        UpdateActions();
    }

    private void UpdateActions()
    {
        _delete.Enabled = _list.SelectedDevice is not null;
    }

    private async Task RefreshAsync()
    {
        try
        {
            Fill(await _api.GetDevicesAsync());
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                this,
                "读取授权列表失败：" + ex.Message + "\n\n请确认服务状态为运行中。",
                "授权记录",
                TechMessageKind.Danger);
        }
    }

    private async Task DeleteSelectedAsync()
    {
        if (_list.SelectedDevice is not { } device)
        {
            return;
        }

        var confirm = TechMessageBox.Show(
            this,
            $"确定撤销“{device.Name}”的授权吗？\n撤销后这把私钥将无法再解锁本机。",
            "撤销授权",
            TechMessageKind.Warning,
            confirm: true);
        if (confirm != DialogResult.Yes)
        {
            return;
        }

        try
        {
            await _api.DeleteDeviceAsync(device.DeviceId);
            Fill(await _api.GetDevicesAsync());
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                this,
                "撤销失败：" + ex.Message,
                "授权记录",
                TechMessageKind.Danger);
        }
    }
}
