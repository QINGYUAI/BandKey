using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed class TechNfcStrip : Button
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 15 };
    private string _kicker = "NFC KEY · WAITING";
    private string _value = "等待手机本地验签";
    private string _detail = "NFC 由手机读取";
    private Color _tone = TechPalette.Warning;
    private bool _hover;
    private float _hoverAmount;

    public string Kicker
    {
        get => _kicker;
        set
        {
            _kicker = value;
            Invalidate();
        }
    }

    public string Value
    {
        get => _value;
        set
        {
            _value = value;
            Invalidate();
        }
    }

    public string Detail
    {
        get => _detail;
        set
        {
            _detail = value;
            Invalidate();
        }
    }

    public Color Tone
    {
        get => _tone;
        set
        {
            _tone = value;
            Invalidate();
        }
    }

    public TechNfcStrip()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        FlatStyle = FlatStyle.Flat;
        FlatAppearance.BorderSize = 0;
        BackColor = Color.Transparent;
        Cursor = Cursors.Hand;
        Height = 62;
        TabStop = true;
        AccessibleName = "NFC 钥匙状态";
        AccessibleRole = AccessibleRole.PushButton;
        _animation.Tick += (_, _) => StepAnimation();
    }

    protected override void OnMouseEnter(EventArgs e)
    {
        _hover = true;
        StartAnimation();
        base.OnMouseEnter(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _hover = false;
        StartAnimation();
        base.OnMouseLeave(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _animation.Dispose();
        }

        base.Dispose(disposing);
    }

    private void StartAnimation()
    {
        if (!TechMotion.Enabled)
        {
            _hoverAmount = _hover ? 1f : 0f;
            Invalidate();
            return;
        }

        _animation.Stop();
        _animation.Start();
    }

    private void StepAnimation()
    {
        var target = _hover ? 1f : 0f;
        _hoverAmount += (target - _hoverAmount) * 0.3f;
        if (Math.Abs(target - _hoverAmount) < 0.015f)
        {
            _hoverAmount = target;
            _animation.Stop();
        }

        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, 12f);
        using var fill = new LinearGradientBrush(
            rect,
            TechDraw.Blend(TechPalette.SurfaceAlt, TechPalette.Surface, 0.08f * _hoverAmount),
            TechDraw.Blend(TechPalette.Surface, TechPalette.SurfaceAlt, 0.28f * _hoverAmount),
            LinearGradientMode.Horizontal);
        e.Graphics.FillPath(fill, path);

        using (var border = new Pen(TechDraw.Alpha(_tone, 55 + (int)(42 * _hoverAmount))))
        {
            e.Graphics.DrawPath(border, path);
        }

        var iconPlate = new RectangleF(14f, Height / 2f - 18f, 36f, 36f);
        using var iconPath = TechDraw.RoundedRect(iconPlate, 10f);
        using (var plateFill = new SolidBrush(TechDraw.Alpha(_tone, 12 + (int)(8 * _hoverAmount))))
        {
            e.Graphics.FillPath(plateFill, iconPath);
        }

        DrawNfcGlyph(e.Graphics, _tone, 23f, Height / 2f);

        const int contentLeft = 62;
        const int rightWidth = 184;
        var contentWidth = Math.Max(80, Width - contentLeft - rightWidth - 14);
        TextRenderer.DrawText(
            e.Graphics,
            _kicker,
            TechPalette.Caption,
            new Rectangle(contentLeft, 8, contentWidth, 17),
            _tone,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(
            e.Graphics,
            _value,
            TechPalette.BodyBold,
            new Rectangle(contentLeft, 27, contentWidth, 24),
            TechPalette.Text,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);

        var rightLeft = Width - rightWidth - 14;
        TextRenderer.DrawText(
            e.Graphics,
            _detail,
            TechPalette.BodySmall,
            new Rectangle(rightLeft, 8, rightWidth, 20),
            TechPalette.Muted,
            TextFormatFlags.Right | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(
            e.Graphics,
            "查看说明  ›",
            TechPalette.BodySmall,
            new Rectangle(rightLeft, 30, rightWidth, 20),
            TechDraw.Blend(TechPalette.Dim, _tone, 0.45f + _hoverAmount * 0.35f),
            TextFormatFlags.Right | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);

        if (Focused && ShowFocusCues)
        {
            ControlPaint.DrawFocusRectangle(
                e.Graphics,
                Rectangle.Inflate(ClientRectangle, -3, -3),
                _tone,
                Color.Transparent);
        }
    }

    private static void DrawNfcGlyph(Graphics graphics, Color color, float x, float centerY)
    {
        using var pen = new Pen(color, 1.55f)
        {
            StartCap = LineCap.Round,
            EndCap = LineCap.Round
        };
        using var dot = new SolidBrush(color);
        graphics.FillEllipse(dot, x, centerY - 2f, 4f, 4f);
        graphics.DrawArc(pen, x + 2f, centerY - 7f, 8f, 14f, -58f, 116f);
        graphics.DrawArc(pen, x + 4f, centerY - 11f, 14f, 22f, -58f, 116f);
    }
}
