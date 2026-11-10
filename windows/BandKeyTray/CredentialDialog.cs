using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed class CredentialDialog : TechForm
{
    private readonly TechField _account = new();
    private readonly TechField _password = new(secret: true);
    private readonly TechField _confirm = new(secret: true);

    public string Account => _account.Value.Trim();
    public string Password => _password.Value;
    public string ConfirmPassword => _confirm.Value;

    public CredentialDialog() : base("设置解锁凭据", new Size(540, 590))
    {
        _account.Value = $"{Environment.UserDomainName}\\{Environment.UserName}";
        foreach (var field in new[] { _account, _password, _confirm })
        {
            field.Dock = DockStyle.Fill;
            field.Margin = new Padding(0);
        }

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 16,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));

        var kicker = new Label
        {
            Text = "WINDOWS CREDENTIAL / 本机加密存储",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Mint,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        var heading = new Label
        {
            Text = "设置解锁凭据",
            AutoSize = true,
            Font = TechPalette.Display,
            ForeColor = TechPalette.Text,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 4, 0, 0)
        };
        var subtitle = new Label
        {
            Text = "锁屏后由手机私钥签名触发自动登录，凭据只保存在本机。",
            AutoSize = true,
            Font = TechPalette.Body,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 3, 0, 0)
        };

        layout.Controls.Add(kicker, 0, 0);
        layout.Controls.Add(heading, 0, 1);
        layout.Controls.Add(subtitle, 0, 2);
        layout.Controls.Add(CreateLabel("Windows 账户"), 0, 4);
        layout.Controls.Add(_account, 0, 5);
        layout.Controls.Add(CreateLabel("Windows 登录密码"), 0, 7);
        layout.Controls.Add(_password, 0, 8);
        layout.Controls.Add(CreateLabel("确认密码"), 0, 10);
        layout.Controls.Add(_confirm, 0, 11);

        var hint = new TechNote(
            "这里必须填写登录 Windows 时使用的账户密码。\nWindows Hello PIN、指纹、人脸都不能用于后台解锁。",
            TechPalette.Warning)
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };
        layout.Controls.Add(hint, 0, 13);

        var security = new Label
        {
            Text = "DPAPI LOCAL MACHINE · 仅回环管道提交 · 不经过手机",
            Dock = DockStyle.Fill,
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 6, 0, 0)
        };
        layout.Controls.Add(security, 0, 14);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        var ok = new TechButton
        {
            Text = "验证并保存",
            Primary = true,
            Width = 132,
            DialogResult = DialogResult.OK,
            Margin = new Padding(8, 0, 0, 0)
        };
        var cancel = new TechButton
        {
            Text = "取消",
            Width = 88,
            DialogResult = DialogResult.Cancel,
            Margin = new Padding(8, 0, 0, 0)
        };
        buttons.Controls.Add(ok);
        buttons.Controls.Add(cancel);
        layout.Controls.Add(buttons, 0, 15);

        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 18));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 10));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 10));
        layout.RowStyles.Add(new RowStyle(SizeType.AutoSize));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 72));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));

        Body.Controls.Add(layout);
        AcceptButton = ok;
        CancelButton = cancel;
        Shown += (_, _) => _password.Input.Focus();
    }

    private static Label CreateLabel(string text)
    {
        return new Label
        {
            Text = text,
            AutoSize = true,
            Font = TechPalette.BodyBold,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Margin = new Padding(0, 0, 0, 6)
        };
    }
}
