using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace BandKeyTray;

internal static class TechPalette
{
    public static readonly Color Background = Color.FromArgb(5, 7, 11);
    public static readonly Color BackgroundSoft = Color.FromArgb(8, 12, 18);
    public static readonly Color Surface = Color.FromArgb(13, 19, 28);
    public static readonly Color SurfaceAlt = Color.FromArgb(17, 26, 38);
    public static readonly Color Field = Color.FromArgb(8, 13, 21);
    public static readonly Color Border = Color.FromArgb(40, 64, 77);
    public static readonly Color BorderSoft = Color.FromArgb(22, 36, 47);
    public static readonly Color Mint = Color.FromArgb(99, 242, 207);
    public static readonly Color MintDeep = Color.FromArgb(46, 211, 176);
    public static readonly Color Cyan = Mint;
    public static readonly Color CyanDeep = MintDeep;
    public static readonly Color Violet = Color.FromArgb(139, 92, 246);
    public static readonly Color VioletBright = Color.FromArgb(124, 92, 255);
    public static readonly Color Text = Color.FromArgb(232, 248, 247);
    public static readonly Color Muted = Color.FromArgb(143, 163, 175);
    public static readonly Color Dim = Color.FromArgb(92, 108, 125);
    public static readonly Color Success = Mint;
    public static readonly Color Warning = Color.FromArgb(245, 185, 85);
    public static readonly Color Danger = Color.FromArgb(255, 107, 129);

    public static readonly Color Ready = Mint;
    public static readonly Color Identity = Violet;
    public static readonly Color Waiting = Warning;

    public static readonly Font Body = new("Microsoft YaHei UI", 9.5f);
    public static readonly Font BodyBold = new("Microsoft YaHei UI", 9.5f, FontStyle.Bold);
    public static readonly Font BodySmall = new("Microsoft YaHei UI", 8.75f);
    public static readonly Font Caption = new("Consolas", 8.25f, FontStyle.Bold);
    public static readonly Font Title = new("Microsoft YaHei UI", 12f, FontStyle.Bold);
    public static readonly Font Display = new("Microsoft YaHei UI", 16f, FontStyle.Bold);
    public static readonly Font Mono = new("Consolas", 9f);
    public static readonly Font Pin = new("Consolas", 30f, FontStyle.Bold);
    public static readonly Font RingValue = new("Microsoft YaHei UI", 16f, FontStyle.Bold);
    public static readonly Font MetricValue = new("Microsoft YaHei UI", 12f, FontStyle.Bold);
    public static readonly Font Button = new("Microsoft YaHei UI", 9.5f, FontStyle.Bold);
}

internal static class TechMotion
{
    private const uint SpiGetClientAreaAnimation = 0x1042;

    [DllImport("user32.dll", SetLastError = true)]
    private static extern bool SystemParametersInfo(
        uint uiAction,
        uint uiParam,
        out int pvParam,
        uint fWinIni);

    public static bool Enabled
    {
        get
        {
            try
            {
                return SystemParametersInfo(SpiGetClientAreaAnimation, 0, out var enabled, 0)
                       && enabled != 0;
            }
            catch
            {
                return true;
            }
        }
    }

    public static float EaseOutCubic(float value)
    {
        var t = Math.Clamp(value, 0f, 1f);
        var inverse = 1f - t;
        return 1f - inverse * inverse * inverse;
    }

    public static float EaseOutBack(float value)
    {
        const float c1 = 1.70158f;
        const float c3 = c1 + 1f;
        var t = Math.Clamp(value, 0f, 1f) - 1f;
        return 1f + c3 * t * t * t + c1 * t * t;
    }
}

internal static class TechDraw
{
    public static GraphicsPath RoundedRect(RectangleF rect, float radius)
    {
        var path = new GraphicsPath();
        var diameter = Math.Min(radius * 2f, Math.Min(rect.Width, rect.Height));
        if (diameter <= 1f)
        {
            path.AddRectangle(rect);
            return path;
        }

        path.AddArc(rect.X, rect.Y, diameter, diameter, 180, 90);
        path.AddArc(rect.Right - diameter, rect.Y, diameter, diameter, 270, 90);
        path.AddArc(rect.Right - diameter, rect.Bottom - diameter, diameter, diameter, 0, 90);
        path.AddArc(rect.X, rect.Bottom - diameter, diameter, diameter, 90, 90);
        path.CloseFigure();
        return path;
    }

    public static Color Blend(Color from, Color to, float amount)
    {
        var t = Math.Clamp(amount, 0f, 1f);
        return Color.FromArgb(
            (int)Math.Round(from.A + (to.A - from.A) * t),
            (int)Math.Round(from.R + (to.R - from.R) * t),
            (int)Math.Round(from.G + (to.G - from.G) * t),
            (int)Math.Round(from.B + (to.B - from.B) * t));
    }

    public static Color Alpha(Color color, int alpha)
    {
        return Color.FromArgb(Math.Clamp(alpha, 0, 255), color);
    }
}

internal class TechForm : Form
{
    private const int WmNcLButtonDown = 0xA1;
    private const int HtCaption = 0x2;
    private readonly System.Windows.Forms.Timer _showTimer = new() { Interval = 15 };
    private System.Diagnostics.Stopwatch? _showWatch;
    private Point _showTarget;

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, IntPtr wParam, IntPtr lParam);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    protected internal Panel Body { get; }

    public TechForm(string title, Size clientSize)
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw,
            true);

        FormBorderStyle = FormBorderStyle.None;
        Text = title;
        AutoScaleMode = AutoScaleMode.None;
        ClientSize = clientSize;
        StartPosition = FormStartPosition.CenterScreen;
        BackColor = TechPalette.Background;
        ForeColor = TechPalette.Text;
        Font = TechPalette.Body;
#if DEBUG
        ShowInTaskbar = true;
#else
        ShowInTaskbar = false;
#endif
        MaximizeBox = false;
        MinimizeBox = false;
        KeyPreview = true;
        Padding = new Padding(1);
        DoubleBuffered = true;

        _showTimer.Tick += (_, _) => StepShowAnimation();

        var titleBar = new Panel
        {
            Dock = DockStyle.Top,
            Height = 62,
            BackColor = TechPalette.Background,
            Cursor = Cursors.SizeAll
        };
        titleBar.Paint += (_, e) =>
        {
            using var pen = new Pen(Color.FromArgb(38, TechPalette.Cyan));
            e.Graphics.DrawLine(pen, 22, titleBar.Height - 1, titleBar.Width - 22, titleBar.Height - 1);
        };

        var brand = new Label
        {
            Text = "◈ BANDKEY",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Cyan,
            BackColor = Color.Transparent,
            Location = new Point(26, 11)
        };
        var titleLabel = new Label
        {
            Text = title,
            AutoSize = true,
            Font = TechPalette.Title,
            ForeColor = TechPalette.Text,
            BackColor = Color.Transparent,
            Location = new Point(24, 29)
        };

        var closeHost = new Panel
        {
            Dock = DockStyle.Right,
            Width = 54,
            BackColor = Color.Transparent
        };
        var close = new Label
        {
            Text = "✕",
            AutoSize = false,
            Size = new Size(32, 30),
            Location = new Point(10, 15),
            TextAlign = ContentAlignment.MiddleCenter,
            Font = new Font("Segoe UI", 10f),
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand
        };
        close.MouseEnter += (_, _) => close.ForeColor = TechPalette.Danger;
        close.MouseLeave += (_, _) => close.ForeColor = TechPalette.Muted;
        close.Click += (_, _) => Close();
        closeHost.Controls.Add(close);

        MouseEventHandler beginDrag = (_, e) =>
        {
            if (e.Button != MouseButtons.Left)
            {
                return;
            }

            ReleaseCapture();
            SendMessage(Handle, WmNcLButtonDown, (IntPtr)HtCaption, IntPtr.Zero);
        };
        titleBar.MouseDown += beginDrag;
        brand.MouseDown += beginDrag;
        titleLabel.MouseDown += beginDrag;

        Body = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = TechPalette.Background,
            Padding = new Padding(26, 6, 26, 22)
        };

        Controls.Add(Body);
        Controls.Add(titleBar);
        titleBar.Controls.Add(brand);
        titleBar.Controls.Add(titleLabel);
        titleBar.Controls.Add(closeHost);
    }

    protected override CreateParams CreateParams
    {
        get
        {
            var parameters = base.CreateParams;
            parameters.ClassStyle |= 0x20000;
            return parameters;
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        try
        {
            var dark = 1;
            DwmSetWindowAttribute(Handle, 20, ref dark, sizeof(int));
            var rounded = 2;
            DwmSetWindowAttribute(Handle, 33, ref rounded, sizeof(int));
        }
        catch
        {
            // 旧版 Windows 没有 DWM 圆角属性，保持默认边角即可。
        }
    }

    protected override void OnShown(EventArgs e)
    {
        base.OnShown(e);
        if (!TechMotion.Enabled)
        {
            Opacity = 1;
            return;
        }

        _showTarget = Location;
        Location = new Point(_showTarget.X, _showTarget.Y + 10);
        Opacity = 0.01;
        _showWatch = System.Diagnostics.Stopwatch.StartNew();
        _showTimer.Start();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.KeyCode == Keys.Escape && CancelButton is not null)
        {
            CancelButton.PerformClick();
            e.SuppressKeyPress = true;
            return;
        }

        base.OnKeyDown(e);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _showTimer.Dispose();
        }

        base.Dispose(disposing);
    }

    private void StepShowAnimation()
    {
        var watch = _showWatch;
        if (watch is null)
        {
            _showTimer.Stop();
            return;
        }

        var progress = Math.Clamp((float)watch.Elapsed.TotalMilliseconds / 210f, 0f, 1f);
        var eased = TechMotion.EaseOutCubic(progress);
        Opacity = 0.01 + eased * 0.99;
        Location = new Point(_showTarget.X, _showTarget.Y + (int)Math.Round(10f * (1f - eased)));
        if (progress >= 1f)
        {
            Opacity = 1;
            Location = _showTarget;
            _showWatch = null;
            _showTimer.Stop();
        }
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;

        var borderRect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(borderRect, 12f);
        using var border = new Pen(Color.FromArgb(85, TechPalette.Cyan));
        e.Graphics.DrawPath(border, path);

        using var cyan = new Pen(Color.FromArgb(210, TechPalette.Cyan), 2f);
        e.Graphics.DrawLine(cyan, Width - 88, 1, Width - 2, 1);
        using var violet = new Pen(Color.FromArgb(150, TechPalette.Violet), 2f);
        e.Graphics.DrawLine(violet, Width - 146, 1, Width - 92, 1);
    }
}

internal sealed class TechField : Panel
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 15 };
    private bool _focused;
    private float _focusAmount;

    public TextBox Input { get; }

    public string Value
    {
        get => Input.Text;
        set => Input.Text = value;
    }

    public TechField(bool secret = false)
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);

        Height = 44;
        BackColor = Color.Transparent;
        _animation.Tick += (_, _) =>
        {
            var target = _focused ? 1f : 0f;
            _focusAmount += (target - _focusAmount) * 0.32f;
            if (Math.Abs(target - _focusAmount) < 0.015f)
            {
                _focusAmount = target;
                _animation.Stop();
            }

            Invalidate();
        };

        Input = new TextBox
        {
            BorderStyle = BorderStyle.None,
            BackColor = TechPalette.Field,
            ForeColor = TechPalette.Text,
            Font = TechPalette.Body,
            UseSystemPasswordChar = secret,
            AutoSize = false
        };
        Input.GotFocus += (_, _) =>
        {
            _focused = true;
            StartAnimation();
        };
        Input.LostFocus += (_, _) =>
        {
            _focused = false;
            StartAnimation();
        };
        Controls.Add(Input);
        Resize += (_, _) => LayoutInput();
        LayoutInput();
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
            _focusAmount = _focused ? 1f : 0f;
            Invalidate();
            return;
        }

        _animation.Stop();
        _animation.Start();
        Invalidate();
    }

    private void LayoutInput()
    {
        Input.Left = 14;
        Input.Width = Math.Max(10, Width - 28);
        Input.Top = Math.Max(4, (Height - Input.PreferredHeight) / 2);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(1, 1, Width - 2.5f, Height - 2.5f);
        using var path = TechDraw.RoundedRect(rect, 10f);
        using var fill = new SolidBrush(TechPalette.Field);
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(
            TechDraw.Blend(
                TechDraw.Alpha(TechPalette.Border, 80),
                TechDraw.Alpha(TechPalette.Mint, 220),
                _focusAmount),
            1f + 0.4f * _focusAmount);
        e.Graphics.DrawPath(border, path);

        if (_focusAmount > 0.02f)
        {
            using var glow = new Pen(TechDraw.Alpha(TechPalette.Mint, (int)(18 * _focusAmount)), 4f);
            e.Graphics.DrawPath(glow, path);
        }
    }
}

internal sealed class TechButton : Button
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 15 };
    private bool _hover;
    private bool _pressed;
    private float _hoverAmount;
    private float _pressAmount;

    public bool Primary { get; set; }
    public Color? Accent { get; set; }

    public TechButton()
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
        ForeColor = TechPalette.Text;
        Font = TechPalette.Button;
        Cursor = Cursors.Hand;
        Height = 42;
        UseVisualStyleBackColor = false;
        TabStop = true;
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
        _pressed = false;
        StartAnimation();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseDown(MouseEventArgs mevent)
    {
        _pressed = true;
        StartAnimation();
        base.OnMouseDown(mevent);
    }

    protected override void OnMouseUp(MouseEventArgs mevent)
    {
        _pressed = false;
        StartAnimation();
        base.OnMouseUp(mevent);
    }

    protected override void OnEnabledChanged(EventArgs e)
    {
        Invalidate();
        base.OnEnabledChanged(e);
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
            _pressAmount = _pressed ? 1f : 0f;
            Invalidate();
            return;
        }

        _animation.Stop();
        _animation.Start();
        Invalidate();
    }

    private void StepAnimation()
    {
        var hoverTarget = _hover && Enabled ? 1f : 0f;
        var pressTarget = _pressed && Enabled ? 1f : 0f;
        _hoverAmount += (hoverTarget - _hoverAmount) * 0.34f;
        _pressAmount += (pressTarget - _pressAmount) * 0.42f;

        if (Math.Abs(hoverTarget - _hoverAmount) < 0.015f
            && Math.Abs(pressTarget - _pressAmount) < 0.015f)
        {
            _hoverAmount = hoverTarget;
            _pressAmount = pressTarget;
            _animation.Stop();
        }

        Invalidate();
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var scale = 1f - _pressAmount * 0.022f;
        e.Graphics.TranslateTransform(Width / 2f, Height / 2f);
        e.Graphics.ScaleTransform(scale, scale);
        e.Graphics.TranslateTransform(-Width / 2f, -Height / 2f);

        var rect = new RectangleF(1, 1, Width - 2.5f, Height - 2.5f);
        using var path = TechDraw.RoundedRect(rect, 10f);
        var accent = Accent ?? TechPalette.Cyan;
        Color textColor;

        if (Primary && Enabled)
        {
            var top = TechDraw.Blend(TechPalette.Mint, TechPalette.MintDeep, _hoverAmount * 0.28f);
            var bottom = TechDraw.Blend(TechPalette.MintDeep, TechPalette.Mint, _pressAmount * 0.18f);
            using var fill = new LinearGradientBrush(rect, top, bottom, LinearGradientMode.Horizontal);
            e.Graphics.FillPath(fill, path);
            if (_hoverAmount > 0.02f)
            {
                using var glow = new Pen(
                    Color.FromArgb((int)(95 * _hoverAmount), Color.White),
                    1.1f);
                e.Graphics.DrawPath(glow, path);
            }

            textColor = TechPalette.Background;
        }
        else
        {
            var fillColor = TechDraw.Blend(
                TechPalette.Surface,
                TechPalette.SurfaceAlt,
                Enabled ? _hoverAmount : 0f);
            using var fill = new SolidBrush(fillColor);
            e.Graphics.FillPath(fill, path);
            using var border = new Pen(
                Enabled
                    ? TechDraw.Blend(
                        TechDraw.Alpha(accent, 90),
                        TechDraw.Alpha(accent, 175),
                        _hoverAmount)
                    : TechPalette.BorderSoft,
                1f + 0.2f * _hoverAmount);
            e.Graphics.DrawPath(border, path);
            textColor = Enabled
                ? (Accent.HasValue
                    ? accent
                    : TechDraw.Blend(TechPalette.Text, TechPalette.Mint, _hoverAmount))
                : TechPalette.Dim;
        }

        TextRenderer.DrawText(
            e.Graphics,
            Text,
            Font,
            Rectangle.Round(rect),
            textColor,
            TextFormatFlags.HorizontalCenter |
            TextFormatFlags.VerticalCenter |
            TextFormatFlags.NoPadding);
    }
}

internal sealed class TechProgress : Control
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 15 };
    private float _target = 1f;
    private float _display = 1f;

    public float Value
    {
        get => _target;
        set
        {
            _target = Math.Clamp(value, 0f, 1f);
            if (!TechMotion.Enabled || !IsHandleCreated)
            {
                _display = _target;
                Invalidate();
                return;
            }

            _animation.Stop();
            _animation.Start();
            Invalidate();
        }
    }

    public TechProgress()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw,
            true);
        Height = 7;
        _animation.Tick += (_, _) =>
        {
            _display += (_target - _display) * 0.22f;
            if (Math.Abs(_target - _display) < 0.0025f)
            {
                _display = _target;
                _animation.Stop();
            }

            Invalidate();
        };
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _animation.Dispose();
        }

        base.Dispose(disposing);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var track = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using (var path = TechDraw.RoundedRect(track, Height / 2f))
        using (var fill = new SolidBrush(Color.FromArgb(30, TechPalette.Cyan)))
        {
            e.Graphics.FillPath(fill, path);
        }

        if (_display <= 0f || Width <= 2)
        {
            return;
        }

        var width = Math.Max(Height, (Width - 1f) * _display);
        var bar = new RectangleF(0.5f, 0.5f, width, Height - 1.5f);
        using var barPath = TechDraw.RoundedRect(bar, Height / 2f);
        using var gradient = new LinearGradientBrush(
            new RectangleF(0, 0, Math.Max(1, Width), Height),
            TechPalette.CyanDeep,
            TechPalette.VioletBright,
            LinearGradientMode.Horizontal);
        e.Graphics.FillPath(gradient, barPath);
    }
}

internal sealed class TechNote : Panel
{
    private readonly Color _tone;

    public TechNote(string text, Color tone)
    {
        _tone = tone;
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw,
            true);
        BackColor = TechPalette.Background;
        Padding = new Padding(18, 10, 14, 10);

        var label = new Label
        {
            Text = text,
            Dock = DockStyle.Fill,
            AutoSize = false,
            TextAlign = ContentAlignment.MiddleLeft,
            ForeColor = tone,
            BackColor = Color.Transparent,
            Font = TechPalette.Body
        };
        Controls.Add(label);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(1, 1, Width - 2.5f, Height - 2.5f);
        using var path = TechDraw.RoundedRect(rect, 10f);
        using var fill = new SolidBrush(Color.FromArgb(16, _tone));
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(Color.FromArgb(70, _tone));
        e.Graphics.DrawPath(border, path);
        using var bar = new SolidBrush(_tone);
        e.Graphics.FillRectangle(bar, 7, 12, 3, Height - 24);
    }
}

internal sealed class TechMenuRenderer : ToolStripProfessionalRenderer
{
    public TechMenuRenderer() : base(new TechMenuColorTable())
    {
        RoundedEdges = false;
    }

    protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e)
    {
        using var brush = new SolidBrush(TechPalette.Surface);
        e.Graphics.FillRectangle(brush, e.AffectedBounds);
    }

    protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
    {
        using var pen = new Pen(TechPalette.Border);
        e.Graphics.DrawRectangle(pen, 0, 0, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1);
    }

    protected override void OnRenderImageMargin(ToolStripRenderEventArgs e)
    {
        using var brush = new SolidBrush(TechPalette.Surface);
        e.Graphics.FillRectangle(brush, e.AffectedBounds);
    }

    protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
    {
        if (!e.Item.Selected)
        {
            return;
        }

        var rect = new RectangleF(3, 1, e.Item.Width - 6, e.Item.Height - 2);
        using var path = TechDraw.RoundedRect(rect, 7f);
        using var fill = new SolidBrush(Color.FromArgb(28, TechPalette.Cyan));
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(Color.FromArgb(85, TechPalette.Cyan));
        e.Graphics.DrawPath(border, path);
    }

    protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
    {
        if (!e.Item.Enabled)
        {
            e.TextColor = TechPalette.Dim;
        }
        else if (e.Item.Selected)
        {
            e.TextColor = TechPalette.Cyan;
        }
        else if (e.Item.ForeColor != Color.Empty &&
                 e.Item.ForeColor != SystemColors.ControlText &&
                 e.Item.ForeColor != SystemColors.WindowText)
        {
            e.TextColor = e.Item.ForeColor;
        }
        else
        {
            e.TextColor = TechPalette.Text;
        }

        base.OnRenderItemText(e);
    }

    protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
    {
        using var pen = new Pen(TechPalette.BorderSoft);
        var y = e.Item.Height / 2;
        e.Graphics.DrawLine(pen, 12, y, e.Item.Width - 12, y);
    }
}

internal sealed class TechMenuColorTable : ProfessionalColorTable
{
    public override Color ToolStripDropDownBackground => TechPalette.Surface;
    public override Color ImageMarginGradientBegin => TechPalette.Surface;
    public override Color ImageMarginGradientMiddle => TechPalette.Surface;
    public override Color ImageMarginGradientEnd => TechPalette.Surface;
    public override Color MenuBorder => TechPalette.Border;
    public override Color MenuItemBorder => TechPalette.Cyan;
    public override Color MenuItemSelected => TechPalette.SurfaceAlt;
    public override Color SeparatorDark => TechPalette.BorderSoft;
    public override Color SeparatorLight => TechPalette.BorderSoft;
}

internal static class TechIconFactory
{
    [DllImport("user32.dll")]
    private static extern bool DestroyIcon(IntPtr handle);

    public static Icon CreateTrayIcon()
    {
        using var bitmap = new Bitmap(32, 32);
        using (var graphics = Graphics.FromImage(bitmap))
        {
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.Clear(Color.Transparent);

            var bounds = new RectangleF(3, 3, 26, 26);
            using var disc = new GraphicsPath();
            disc.AddEllipse(bounds);
            using var ringFill = new LinearGradientBrush(
                bounds,
                TechPalette.Cyan,
                TechPalette.Violet,
                45f);
            graphics.FillPath(ringFill, disc);

            using var inner = new SolidBrush(TechPalette.Background);
            graphics.FillEllipse(inner, 8, 8, 16, 16);
            using var ring = new Pen(TechPalette.Cyan, 1.5f);
            graphics.DrawEllipse(ring, 8, 8, 16, 16);

            using var key = new SolidBrush(TechPalette.Cyan);
            graphics.FillEllipse(key, 13, 11, 6, 6);
            graphics.FillRectangle(key, 15, 16, 2, 6);
        }

        var iconHandle = bitmap.GetHicon();
        try
        {
            using var temporary = Icon.FromHandle(iconHandle);
            return (Icon)temporary.Clone();
        }
        finally
        {
            DestroyIcon(iconHandle);
        }
    }
}
