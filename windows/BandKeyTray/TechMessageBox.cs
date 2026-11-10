using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal enum TechMessageKind
{
    Info,
    Success,
    Warning,
    Danger
}

internal static class TechMessageBox
{
    public static DialogResult Show(
        IWin32Window? owner,
        string message,
        string title,
        TechMessageKind kind = TechMessageKind.Info,
        bool confirm = false)
    {
        var tone = kind switch
        {
            TechMessageKind.Success => TechPalette.Success,
            TechMessageKind.Warning => TechPalette.Warning,
            TechMessageKind.Danger => TechPalette.Danger,
            _ => TechPalette.Cyan
        };

        using var form = new TechForm(title, new Size(480, confirm ? 320 : 286));
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 4,
            BackColor = TechPalette.Background
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 6));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));

        var note = new TechNote(message, tone)
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0)
        };
        layout.Controls.Add(note, 0, 1);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };

        var ok = new TechButton
        {
            Text = confirm ? "确认" : "知道了",
            Primary = true,
            Width = 112,
            DialogResult = confirm ? DialogResult.Yes : DialogResult.OK,
            Margin = new Padding(8, 0, 0, 0)
        };
        buttons.Controls.Add(ok);

        TechButton? cancel = null;
        if (confirm)
        {
            cancel = new TechButton
            {
                Text = "取消",
                Width = 92,
                DialogResult = DialogResult.Cancel,
                Margin = new Padding(8, 0, 0, 0)
            };
            buttons.Controls.Add(cancel);
        }

        layout.Controls.Add(buttons, 0, 3);
        form.Body.Controls.Add(layout);
        form.AcceptButton = ok;
        if (cancel is not null)
        {
            form.CancelButton = cancel;
        }

        return owner is null ? form.ShowDialog() : form.ShowDialog(owner);
    }
}
