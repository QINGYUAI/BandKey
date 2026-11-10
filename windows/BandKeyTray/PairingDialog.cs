using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed class PairingDialog : TechForm
{
    private readonly PairingInfo _pairing;
    private readonly Label _countdown;
    private readonly TechProgress _progress;
    private readonly TechPinDisplay _pinDisplay;
    private readonly TechDeviceCard _device;
    private readonly TechButton _copy;
    private readonly System.Windows.Forms.Timer _timer = new() { Interval = 250 };
    private readonly System.Windows.Forms.Timer _copyTimer = new() { Interval = 1600 };

    public PairingDialog(PairingInfo pairing) : base("配对请求", new Size(520, 640))
    {
        _pairing = pairing;

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 12,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var kicker = new Label
        {
            Text = "PAIRING REQUEST / 新设备",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Mint,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        var heading = new Label
        {
            Text = "手机正在请求配对",
            AutoSize = true,
            Font = TechPalette.Display,
            ForeColor = TechPalette.Text,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 4, 0, 0)
        };
        var subtitle = new Label
        {
            Text = "确认设备名称后，把下面的 6 位 PIN 输入到手机端。",
            AutoSize = true,
            Font = TechPalette.Body,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 3, 0, 0)
        };

        _device = new TechDeviceCard
        {
            DeviceName = pairing.DeviceName,
            Detail = "Android · BLE REQUEST",
            Badge = "在线",
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };

        _pinDisplay = new TechPinDisplay
        {
            Pin = FormatPin(pairing.Pin),
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };

        _countdown = new Label
        {
            Text = "",
            Dock = DockStyle.Fill,
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = TechPalette.Mono,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Margin = new Padding(2, 0, 0, 0)
        };
        _progress = new TechProgress
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0, 4, 0, 0)
        };

        var countdownPanel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            BackColor = TechPalette.Background,
            Margin = new Padding(0, 1, 0, 0)
        };
        countdownPanel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        countdownPanel.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        countdownPanel.RowStyles.Add(new RowStyle(SizeType.Absolute, 9));
        countdownPanel.Controls.Add(_countdown, 0, 0);
        countdownPanel.Controls.Add(_progress, 0, 1);

        var hint = new TechNote(
            "PIN 只对本次配对有效，请勿通过聊天或截图发送给他人。\n配对成功后该 PIN 会立即失效。",
            TechPalette.Warning)
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };
        var channel = new Label
        {
            Text = "SECURE CHANNEL · ECDSA P-256 · LOCAL ONLY",
            Dock = DockStyle.Fill,
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 8, 0, 0)
        };

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        _copy = new TechButton
        {
            Text = "复制 PIN",
            Primary = true,
            Width = 124,
            Margin = new Padding(8, 0, 0, 0)
        };
        var close = new TechButton
        {
            Text = "关闭",
            Width = 88,
            DialogResult = DialogResult.Cancel,
            Margin = new Padding(8, 0, 0, 0)
        };
        _copy.Click += (_, _) => CopyPin();
        _copyTimer.Tick += (_, _) =>
        {
            _copyTimer.Stop();
            _copy.Text = "复制 PIN";
        };
        buttons.Controls.Add(_copy);
        buttons.Controls.Add(close);

        layout.Controls.Add(kicker, 0, 0);
        layout.Controls.Add(heading, 0, 1);
        layout.Controls.Add(subtitle, 0, 2);
        layout.Controls.Add(_device, 0, 4);
        layout.Controls.Add(_pinDisplay, 0, 6);
        layout.Controls.Add(countdownPanel, 0, 7);
        layout.Controls.Add(hint, 0, 8);
        layout.Controls.Add(channel, 0, 9);
        layout.Controls.Add(buttons, 0, 11);

        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 12));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 64));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 12));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 145));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 66));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));

        Body.Controls.Add(layout);
        CancelButton = close;

        _timer.Tick += (_, _) => UpdateCountdown();
        Shown += (_, _) =>
        {
            UpdateCountdown();
            _timer.Start();
        };
        FormClosed += (_, _) =>
        {
            _timer.Dispose();
            _copyTimer.Dispose();
        };
    }

    private void UpdateCountdown()
    {
        var total = _pairing.ExpiresAt - _pairing.CreatedAt;
        if (total <= TimeSpan.Zero)
        {
            total = TimeSpan.FromMinutes(5);
        }

        var remaining = _pairing.ExpiresAt - DateTimeOffset.UtcNow;
        if (remaining < TimeSpan.Zero)
        {
            remaining = TimeSpan.Zero;
        }

        var seconds = (int)Math.Ceiling(remaining.TotalSeconds);
        if (seconds > 0)
        {
            _countdown.Text = $"PIN 有效期剩余  {seconds / 60:00}:{seconds % 60:00}";
            _countdown.ForeColor = seconds <= 60 ? TechPalette.Warning : TechPalette.Muted;
            _pinDisplay.Tone = seconds <= 60 ? TechPalette.Warning : TechPalette.Mint;
            _device.Tone = seconds <= 60 ? TechPalette.Warning : TechPalette.Mint;
        }
        else
        {
            _countdown.Text = "PIN 已过期，请在手机上重新发起配对";
            _countdown.ForeColor = TechPalette.Danger;
            _pinDisplay.Tone = TechPalette.Danger;
            _device.Tone = TechPalette.Danger;
            _device.Badge = "已过期";
            _timer.Stop();
        }

        _progress.Value = (float)Math.Clamp(remaining.TotalSeconds / total.TotalSeconds, 0, 1);
        _copy.Enabled = seconds > 0;
    }

    private void CopyPin()
    {
        try
        {
            Clipboard.SetText(_pairing.Pin);
            _copy.Text = "已复制";
            _copyTimer.Stop();
            _copyTimer.Start();
        }
        catch
        {
            _copy.Text = "复制失败";
        }
    }

    private static string FormatPin(string pin)
    {
        return pin.Length == 6 ? pin[..3] + "  " + pin[3..] : pin;
    }
}
