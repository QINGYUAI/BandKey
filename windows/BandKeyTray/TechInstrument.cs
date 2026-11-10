using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Windows.Forms;

namespace BandKeyTray;

internal class TechCard : Panel
{
    public Color CardColor { get; set; } = TechPalette.Surface;
    public Color OutlineColor { get; set; } = TechPalette.BorderSoft;
    public int Radius { get; set; } = 12;

    public TechCard()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        DoubleBuffered = true;
    }

    protected override void OnPaintBackground(PaintEventArgs e)
    {
        // 卡片自行绘制背景，避免透明 Panel 先擦掉父级渐变。
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, Radius);
        using var fill = new LinearGradientBrush(
            rect,
            TechDraw.Blend(CardColor, TechPalette.BackgroundSoft, 0.28f),
            CardColor,
            LinearGradientMode.Vertical);
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(TechDraw.Alpha(OutlineColor, 150));
        e.Graphics.DrawPath(border, path);
    }
}

internal sealed class TechStatusPill : Control
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 40 };
    private string _statusText = "检查中";
    private Color _tone = TechPalette.Muted;
    private float _phase;

    public string StatusText
    {
        get => _statusText;
        set
        {
            if (_statusText == value)
            {
                return;
            }

            _statusText = value;
            ResizeToText();
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

    public TechStatusPill()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        Font = TechPalette.BodyBold;
        Height = 29;
        Width = 120;
        _animation.Tick += (_, _) =>
        {
            _phase = (_phase + 0.08f) % 1f;
            Invalidate();
        };
        ResizeToText();
    }

    public void SetStatus(string text, Color tone)
    {
        StatusText = text;
        Tone = tone;
        if (TechMotion.Enabled && IsHandleCreated)
        {
            _animation.Start();
        }
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        if (TechMotion.Enabled)
        {
            _animation.Start();
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _animation.Dispose();
        }

        base.Dispose(disposing);
    }

    private void ResizeToText()
    {
        var measured = TextRenderer.MeasureText(_statusText, Font);
        Width = Math.Max(92, measured.Width + 42);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, Height / 2f);
        using var fill = new SolidBrush(TechDraw.Alpha(_tone, 19));
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(TechDraw.Alpha(_tone, 64));
        e.Graphics.DrawPath(border, path);

        var pulse = TechMotion.Enabled
            ? 0.5f + 0.5f * MathF.Sin(_phase * MathF.PI * 2f)
            : 0.45f;
        using var glow = new SolidBrush(TechDraw.Alpha(_tone, (int)(10 + 34 * pulse)));
        e.Graphics.FillEllipse(glow, 13 - 3 * pulse, Height / 2f - 3.5f - 3 * pulse, 9 + 6 * pulse, 9 + 6 * pulse);
        using var dot = new SolidBrush(_tone);
        e.Graphics.FillEllipse(dot, 14, Height / 2f - 3f, 6f, 6f);

        TextRenderer.DrawText(
            e.Graphics,
            _statusText,
            Font,
            new Rectangle(28, 0, Width - 34, Height),
            _tone,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }
}

internal sealed class TechMiniRing : Control
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 33 };
    private float _target = 1f;
    private float _display = 1f;
    private float _phase;
    private string _centerValue = "3/3";
    private string _centerCaption = "READY";
    private Color _tone = TechPalette.Mint;

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

            _animation.Start();
        }
    }

    public string CenterValue
    {
        get => _centerValue;
        set
        {
            _centerValue = value;
            Invalidate();
        }
    }

    public string CenterCaption
    {
        get => _centerCaption;
        set
        {
            _centerCaption = value;
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

    public TechMiniRing()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        Size = new Size(116, 116);
        _animation.Tick += (_, _) =>
        {
            _display += (_target - _display) * 0.12f;
            _phase = (_phase + 0.012f) % 1f;
            if (Math.Abs(_target - _display) < 0.002f && !TechMotion.Enabled)
            {
                _display = _target;
                _animation.Stop();
            }

            Invalidate();
        };
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);
        if (TechMotion.Enabled)
        {
            _animation.Start();
        }
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
        var size = Math.Min(Width, Height) - 12f;
        var bounds = new RectangleF((Width - size) / 2f, (Height - size) / 2f, size, size);
        var center = new PointF(bounds.Left + size / 2f, bounds.Top + size / 2f);
        var radius = size / 2f - 5f;
        var pulse = TechMotion.Enabled
            ? 0.5f + 0.5f * MathF.Sin(_phase * MathF.PI * 2f)
            : 0.5f;

        using (var halo = new SolidBrush(TechDraw.Alpha(_tone, (int)(5 + pulse * 7))))
        {
            e.Graphics.FillEllipse(halo, bounds);
        }

        using (var inner = new SolidBrush(TechPalette.BackgroundSoft))
        {
            e.Graphics.FillEllipse(inner, RectangleF.Inflate(bounds, -8f, -8f));
        }

        DrawTicks(e.Graphics, center, radius + 5f);

        const float segmentSweep = 94f;
        const float segmentStep = 103f;
        var filledSegments = _display * 3f;
        for (var index = 0; index < 3; index++)
        {
            var start = -90f + index * segmentStep;
            using (var track = new Pen(TechDraw.Alpha(_tone, 22), 3.2f))
            {
                track.StartCap = LineCap.Round;
                track.EndCap = LineCap.Round;
                e.Graphics.DrawArc(track, ArcRect(center, radius), start, segmentSweep);
            }

            var segmentFill = Math.Clamp(filledSegments - index, 0f, 1f);
            if (segmentFill <= 0.001f)
            {
                continue;
            }

            using var glow = new Pen(TechDraw.Alpha(_tone, 24), 8f);
            glow.StartCap = LineCap.Round;
            glow.EndCap = LineCap.Round;
            e.Graphics.DrawArc(glow, ArcRect(center, radius), start, segmentSweep * segmentFill);

            using var arc = new Pen(_tone, 3.7f);
            arc.StartCap = LineCap.Round;
            arc.EndCap = LineCap.Round;
            e.Graphics.DrawArc(arc, ArcRect(center, radius), start, segmentSweep * segmentFill);
        }

        TextRenderer.DrawText(
            e.Graphics,
            _centerValue,
            TechPalette.RingValue,
            new Rectangle(0, (Height - 50) / 2, Width, 34),
            _tone,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
        TextRenderer.DrawText(
            e.Graphics,
            _centerCaption,
            TechPalette.Caption,
            new Rectangle(0, (Height + 18) / 2, Width, 20),
            TechPalette.Muted,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter | TextFormatFlags.NoPadding);
    }

    private void DrawTicks(Graphics graphics, PointF center, float radius)
    {
        for (var index = 0; index < 60; index++)
        {
            var angle = (index * 6f - 90f) * MathF.PI / 180f;
            var major = index % 5 == 0;
            var outer = radius;
            var inner = radius - (major ? 3.2f : 1.7f);
            using var pen = new Pen(
                TechDraw.Alpha(_tone, major ? 45 : 22),
                major ? 1.1f : 0.8f);
            graphics.DrawLine(
                pen,
                center.X + MathF.Cos(angle) * inner,
                center.Y + MathF.Sin(angle) * inner,
                center.X + MathF.Cos(angle) * outer,
                center.Y + MathF.Sin(angle) * outer);
        }
    }

    private static RectangleF ArcRect(PointF center, float radius)
    {
        return new RectangleF(center.X - radius, center.Y - radius, radius * 2f, radius * 2f);
    }
}

internal sealed class TechMetric : Control
{
    private string _caption = "";
    private string _value = "";
    private Color _tone = TechPalette.Text;

    public string Caption
    {
        get => _caption;
        set
        {
            _caption = value;
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

    public Color Tone
    {
        get => _tone;
        set
        {
            _tone = value;
            Invalidate();
        }
    }

    public TechMetric()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        Height = 63;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, 10f);
        using var fill = new SolidBrush(TechPalette.Surface);
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(TechPalette.BorderSoft);
        e.Graphics.DrawPath(border, path);

        using var dot = new SolidBrush(TechDraw.Alpha(_tone, 190));
        e.Graphics.FillEllipse(dot, Width - 16, 12, 4, 4);

        TextRenderer.DrawText(
            e.Graphics,
            _caption,
            TechPalette.Body,
            new Rectangle(12, 8, Width - 26, 20),
            TechPalette.Muted,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(
            e.Graphics,
            _value,
            TechPalette.MetricValue,
            new Rectangle(11, 27, Width - 22, 28),
            _tone,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }
}

internal sealed class TechDeviceCard : Control
{
    private string _deviceName = "我的手机";
    private string _detail = "Android · BLE REQUEST";
    private string _badge = "在线";
    private Color _tone = TechPalette.Mint;

    public string DeviceName
    {
        get => _deviceName;
        set
        {
            _deviceName = value;
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

    public string Badge
    {
        get => _badge;
        set
        {
            _badge = value;
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

    public TechDeviceCard()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        Height = 62;
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, 11f);
        using var fill = new SolidBrush(TechPalette.Surface);
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(TechPalette.BorderSoft);
        e.Graphics.DrawPath(border, path);

        var iconRect = new RectangleF(13, 14, 34, 34);
        using var iconFill = new SolidBrush(TechDraw.Alpha(_tone, 16));
        using var iconPath = TechDraw.RoundedRect(iconRect, 9f);
        e.Graphics.FillPath(iconFill, iconPath);

        using var phone = new Pen(_tone, 1.5f);
        e.Graphics.DrawRoundedRectangleSafe(phone, 24, 20, 12, 22, 3f);
        e.Graphics.DrawLine(phone, 28.5f, 38.5f, 31.5f, 38.5f);

        TextRenderer.DrawText(
            e.Graphics,
            _deviceName,
            TechPalette.BodyBold,
            new Rectangle(59, 12, Width - 148, 22),
            TechPalette.Text,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(
            e.Graphics,
            _detail,
            TechPalette.Caption,
            new Rectangle(59, 31, Width - 148, 20),
            TechPalette.Dim,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);

        var badgeWidth = TextRenderer.MeasureText(_badge, TechPalette.BodyBold).Width + 20;
        var badgeRect = new RectangleF(Width - badgeWidth - 14, Height / 2f - 11f, badgeWidth, 22f);
        using var badgePath = TechDraw.RoundedRect(badgeRect, 11f);
        using var badgeFill = new SolidBrush(TechDraw.Alpha(_tone, 18));
        e.Graphics.FillPath(badgeFill, badgePath);
        using var badgeBorder = new Pen(TechDraw.Alpha(_tone, 70));
        e.Graphics.DrawPath(badgeBorder, badgePath);
        TextRenderer.DrawText(
            e.Graphics,
            _badge,
            TechPalette.BodyBold,
            Rectangle.Round(badgeRect),
            _tone,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
    }
}

internal sealed class TechDeviceList : Control
{
    private const int HeaderHeight = 38;
    private const int RowHeight = 44;

    private readonly List<DeviceInfo> _devices = new();
    private DeviceInfo? _selected;
    private int _firstRow;
    private int _hoverRow = -1;

    public event EventHandler? SelectionChanged;

    public IReadOnlyList<DeviceInfo> Devices => _devices;

    public DeviceInfo? SelectedDevice
    {
        get => _selected;
        set
        {
            if (ReferenceEquals(_selected, value))
            {
                return;
            }

            _selected = value;
            EnsureSelectionVisible();
            Invalidate();
            SelectionChanged?.Invoke(this, EventArgs.Empty);
        }
    }

    public TechDeviceList()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.Selectable,
            true);
        BackColor = TechPalette.Surface;
        TabStop = true;
        AccessibleName = "已授权设备列表";
    }

    public void SetDevices(IEnumerable<DeviceInfo> devices)
    {
        var previousId = _selected?.DeviceId;
        _devices.Clear();
        _devices.AddRange(devices);
        _selected = _devices.FirstOrDefault(device => device.DeviceId == previousId)
                    ?? _devices.FirstOrDefault();
        _firstRow = 0;
        Invalidate();
        SelectionChanged?.Invoke(this, EventArgs.Empty);
    }

    protected override void OnMouseDown(MouseEventArgs e)
    {
        Focus();
        var row = RowAt(e.Y);
        if (row >= 0 && row < _devices.Count)
        {
            SelectedDevice = _devices[row];
        }

        base.OnMouseDown(e);
    }

    protected override void OnMouseMove(MouseEventArgs e)
    {
        var row = RowAt(e.Y);
        if (row != _hoverRow)
        {
            _hoverRow = row;
            Cursor = row >= 0 && row < _devices.Count ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }

        base.OnMouseMove(e);
    }

    protected override void OnMouseLeave(EventArgs e)
    {
        _hoverRow = -1;
        Cursor = Cursors.Default;
        Invalidate();
        base.OnMouseLeave(e);
    }

    protected override void OnMouseWheel(MouseEventArgs e)
    {
        var maxFirst = Math.Max(0, _devices.Count - VisibleRows);
        if (maxFirst > 0)
        {
            _firstRow = Math.Clamp(_firstRow - Math.Sign(e.Delta), 0, maxFirst);
            Invalidate();
        }

        base.OnMouseWheel(e);
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (_devices.Count > 0 && (e.KeyCode == Keys.Down || e.KeyCode == Keys.Up))
        {
            var index = _selected is null ? 0 : _devices.IndexOf(_selected);
            index = Math.Clamp(index + (e.KeyCode == Keys.Down ? 1 : -1), 0, _devices.Count - 1);
            SelectedDevice = _devices[index];
            e.Handled = true;
            return;
        }

        base.OnKeyDown(e);
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        e.Graphics.SmoothingMode = SmoothingMode.AntiAlias;
        var bounds = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var outer = TechDraw.RoundedRect(bounds, 11f);
        using (var fill = new SolidBrush(TechPalette.Surface))
        {
            e.Graphics.FillPath(fill, outer);
        }

        var previousClip = e.Graphics.Clip;
        using (var clip = TechDraw.RoundedRect(bounds, 11f))
        {
            e.Graphics.SetClip(clip, CombineMode.Intersect);
            DrawHeader(e.Graphics);
            DrawRows(e.Graphics);
            DrawScrollbar(e.Graphics);
        }

        e.Graphics.Clip = previousClip;
        using var border = new Pen(TechPalette.BorderSoft);
        e.Graphics.DrawPath(border, outer);

        if (Focused && ShowFocusCues)
        {
            ControlPaint.DrawFocusRectangle(e.Graphics, ClientRectangle, TechPalette.Mint, Color.Transparent);
        }
    }

    private int VisibleRows => Math.Max(1, (Height - HeaderHeight) / RowHeight);

    private int RowAt(int y)
    {
        if (y < HeaderHeight)
        {
            return -1;
        }

        return _firstRow + (y - HeaderHeight) / RowHeight;
    }

    private void DrawHeader(Graphics graphics)
    {
        using var header = new SolidBrush(TechPalette.SurfaceAlt);
        graphics.FillRectangle(header, 1, 1, Width - 2, HeaderHeight - 1);
        using var line = new Pen(TechPalette.BorderSoft);
        graphics.DrawLine(line, 1, HeaderHeight - 1, Width - 2, HeaderHeight - 1);

        var columns = GetColumns();
        DrawText(graphics, "设备名称", TechPalette.BodyBold, TechPalette.Mint, columns.Name);
        DrawText(graphics, "设备 ID", TechPalette.BodyBold, TechPalette.Mint, columns.DeviceId);
        DrawText(graphics, "添加时间", TechPalette.BodyBold, TechPalette.Mint, columns.CreatedAt);
    }

    private void DrawRows(Graphics graphics)
    {
        if (_devices.Count == 0)
        {
            using var icon = new Pen(TechPalette.Dim, 1.5f);
            graphics.DrawRoundedRectangleSafe(icon, Width / 2f - 12, HeaderHeight + 38, 24, 18, 3f);
            graphics.DrawLine(icon, Width / 2f - 6, HeaderHeight + 63, Width / 2f + 6, HeaderHeight + 63);
            TextRenderer.DrawText(
                graphics,
                "尚无已配对设备",
                TechPalette.BodyBold,
                new Rectangle(0, HeaderHeight + 76, Width, 24),
                TechPalette.Muted,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            TextRenderer.DrawText(
                graphics,
                "在手机上发起配对后，设备会出现在这里",
                TechPalette.Body,
                new Rectangle(0, HeaderHeight + 101, Width, 24),
                TechPalette.Dim,
                TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);
            return;
        }

        var columns = GetColumns();
        var availableRows = Math.Min(VisibleRows, _devices.Count - _firstRow);
        for (var visibleIndex = 0; visibleIndex < availableRows; visibleIndex++)
        {
            var index = _firstRow + visibleIndex;
            var device = _devices[index];
            var rowRect = new Rectangle(1, HeaderHeight + visibleIndex * RowHeight, Width - 2, RowHeight);
            var selected = ReferenceEquals(device, _selected);
            var hovered = index == _hoverRow;

            if (selected)
            {
                using var selectedFill = new SolidBrush(TechDraw.Alpha(TechPalette.Mint, 17));
                graphics.FillRectangle(selectedFill, rowRect);
                using var accent = new SolidBrush(TechPalette.Mint);
                graphics.FillRectangle(accent, rowRect.Left, rowRect.Top + 4, 2, rowRect.Height - 8);
            }
            else if (hovered)
            {
                using var hoverFill = new SolidBrush(TechDraw.Alpha(TechPalette.Mint, 8));
                graphics.FillRectangle(hoverFill, rowRect);
            }

            using (var divider = new Pen(TechPalette.BorderSoft))
            {
                graphics.DrawLine(
                    divider,
                    rowRect.Left,
                    rowRect.Bottom - 1,
                    rowRect.Right,
                    rowRect.Bottom - 1);
            }

            DrawText(
                graphics,
                device.Name,
                TechPalette.BodyBold,
                TechPalette.Text,
                Offset(columns.Name, rowRect.Top));
            DrawText(
                graphics,
                device.DeviceId,
                TechPalette.Mono,
                selected ? TechPalette.Mint : TechPalette.Muted,
                Offset(columns.DeviceId, rowRect.Top));
            DrawText(
                graphics,
                device.CreatedAt.ToLocalTime().ToString("yyyy-MM-dd HH:mm"),
                TechPalette.Body,
                TechPalette.Muted,
                Offset(columns.CreatedAt, rowRect.Top));
        }

        var filledBottom = HeaderHeight + availableRows * RowHeight;
        if (filledBottom < Height)
        {
            using var empty = new SolidBrush(TechPalette.Surface);
            graphics.FillRectangle(empty, 1, filledBottom, Width - 2, Height - filledBottom - 1);
        }
    }

    private void DrawScrollbar(Graphics graphics)
    {
        var maxFirst = Math.Max(0, _devices.Count - VisibleRows);
        if (maxFirst <= 0)
        {
            return;
        }

        var track = new Rectangle(Width - 7, HeaderHeight + 8, 3, Height - HeaderHeight - 16);
        using var trackBrush = new SolidBrush(TechDraw.Alpha(TechPalette.Border, 90));
        graphics.FillRectangle(trackBrush, track);

        var thumbHeight = Math.Max(22, (int)(track.Height * (VisibleRows / (float)_devices.Count)));
        var progress = _firstRow / (float)maxFirst;
        var thumbY = track.Top + (int)((track.Height - thumbHeight) * progress);
        using var thumb = new SolidBrush(TechPalette.Mint);
        graphics.FillRectangle(thumb, track.Left, thumbY, track.Width, thumbHeight);
    }

    private (Rectangle Name, Rectangle DeviceId, Rectangle CreatedAt) GetColumns()
    {
        var innerWidth = Math.Max(100, Width - 32);
        var nameWidth = (int)(innerWidth * 0.32f);
        var idWidth = (int)(innerWidth * 0.38f);
        var dateWidth = innerWidth - nameWidth - idWidth;
        return (
            new Rectangle(16, 0, nameWidth - 8, HeaderHeight),
            new Rectangle(16 + nameWidth, 0, idWidth - 8, HeaderHeight),
            new Rectangle(16 + nameWidth + idWidth, 0, dateWidth, HeaderHeight));
    }

    private static Rectangle Offset(Rectangle rectangle, int y)
    {
        return new Rectangle(rectangle.X, y, rectangle.Width, RowHeight);
    }

    private static void DrawText(Graphics graphics, string text, Font font, Color color, Rectangle rectangle)
    {
        TextRenderer.DrawText(
            graphics,
            text,
            font,
            rectangle,
            color,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }

    private void EnsureSelectionVisible()
    {
        if (_selected is null)
        {
            return;
        }

        var index = _devices.IndexOf(_selected);
        if (index < 0)
        {
            return;
        }

        if (index < _firstRow)
        {
            _firstRow = index;
        }
        else if (index >= _firstRow + VisibleRows)
        {
            _firstRow = index - VisibleRows + 1;
        }
    }
}

internal sealed class TechPinDisplay : Control
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 33 };
    private string _pin = "";
    private string _caption = "ONE-TIME PAIRING CODE";
    private Color _tone = TechPalette.Mint;
    private long _startedAt;

    public string Pin
    {
        get => _pin;
        set
        {
            _pin = value;
            _startedAt = Environment.TickCount64;
            Invalidate();
        }
    }

    public string Caption
    {
        get => _caption;
        set
        {
            _caption = value;
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

    public TechPinDisplay()
    {
        SetStyle(
            ControlStyles.AllPaintingInWmPaint |
            ControlStyles.UserPaint |
            ControlStyles.OptimizedDoubleBuffer |
            ControlStyles.ResizeRedraw |
            ControlStyles.SupportsTransparentBackColor,
            true);
        BackColor = Color.Transparent;
        Height = 145;
        _animation.Tick += (_, _) => Invalidate();
    }

    protected override void OnVisibleChanged(EventArgs e)
    {
        base.OnVisibleChanged(e);
        if (Visible && TechMotion.Enabled)
        {
            _startedAt = Environment.TickCount64;
            _animation.Start();
        }
        else
        {
            _animation.Stop();
        }
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
        e.Graphics.TextRenderingHint = TextRenderingHint.ClearTypeGridFit;

        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, 13f);
        using var fill = new LinearGradientBrush(
            rect,
            TechPalette.BackgroundSoft,
            TechPalette.Surface,
            LinearGradientMode.Vertical);
        e.Graphics.FillPath(fill, path);
        using var border = new Pen(TechDraw.Alpha(_tone, 55));
        e.Graphics.DrawPath(border, path);

        var elapsed = Math.Max(0f, (Environment.TickCount64 - _startedAt) / 1000f);
        var breath = TechMotion.Enabled
            ? 0.5f + 0.5f * MathF.Sin(elapsed * 1.45f)
            : 0.35f;
        using (var glowPath = new GraphicsPath())
        {
            glowPath.AddEllipse(Width * 0.18f, Height * 0.28f, Width * 0.64f, Height * 0.54f);
            using var glow = new PathGradientBrush(glowPath)
            {
                CenterColor = TechDraw.Alpha(_tone, (int)(12 + 10 * breath)),
                SurroundColors = new[] { Color.Transparent }
            };
            e.Graphics.FillPath(glow, glowPath);
        }

        TextRenderer.DrawText(
            e.Graphics,
            _caption,
            TechPalette.Caption,
            new Rectangle(0, 17, Width, 20),
            TechPalette.Dim,
            TextFormatFlags.HorizontalCenter | TextFormatFlags.VerticalCenter);

        DrawPin(e.Graphics, elapsed);

        if (TechMotion.Enabled)
        {
            var scanY = 31f + (Height - 52f) * ((elapsed * 0.12f) % 1f);
            using var scan = new Pen(TechDraw.Alpha(_tone, 12), 1f);
            e.Graphics.DrawLine(scan, 24, scanY, Width - 24, scanY);
        }
    }

    private void DrawPin(Graphics graphics, float elapsed)
    {
        if (string.IsNullOrEmpty(_pin))
        {
            return;
        }

        var font = TechPalette.Pin;
        var widths = _pin
            .Select(character =>
                graphics.MeasureString(character.ToString(), font, int.MaxValue, StringFormat.GenericTypographic).Width)
            .ToArray();
        var totalWidth = widths
                             .Select((width, index) => Math.Max(width, _pin[index] == ' ' ? 15f : 0f))
                             .Sum()
                         + Math.Max(0, _pin.Length - 1) * 1.6f;
        var x = (Width - totalWidth) / 2f;
        var baseline = Height / 2f - font.GetHeight(graphics) / 2f + 9f;
        var index = 0;

        foreach (var character in _pin)
        {
            var delay = index * 0.045f;
            var progress = TechMotion.Enabled
                ? Math.Clamp((elapsed - delay) / 0.28f, 0f, 1f)
                : 1f;
            var eased = TechMotion.EaseOutCubic(progress);
            var alpha = (int)(255 * eased);
            var yOffset = (1f - eased) * 5f;

            if (character != ' ')
            {
                using var brush = new SolidBrush(TechDraw.Alpha(_tone, alpha));
                graphics.DrawString(
                    character.ToString(),
                    font,
                    brush,
                    x,
                    baseline + yOffset,
                    StringFormat.GenericTypographic);
            }

            x += Math.Max(widths[index], character == ' ' ? 15f : 0f) + 1.6f;
            index++;
        }
    }
}

internal enum TechActionGlyph
{
    Key,
    Devices,
    Lock,
    Refresh,
    Log
}

internal sealed class TechActionButton : Button
{
    private readonly System.Windows.Forms.Timer _animation = new() { Interval = 15 };
    private bool _hover;
    private bool _pressed;
    private float _hoverAmount;
    private float _pressAmount;
    private string? _subText;

    public bool Primary { get; set; }
    public Color? Accent { get; set; }
    public TechActionGlyph Glyph { get; set; } = TechActionGlyph.Key;

    public string? SubText
    {
        get => _subText;
        set
        {
            _subText = value;
            Invalidate();
        }
    }

    public TechActionButton()
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
        Height = 48;
        UseVisualStyleBackColor = false;
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
        var scale = 1f - _pressAmount * 0.018f;
        e.Graphics.TranslateTransform(Width / 2f, Height / 2f);
        e.Graphics.ScaleTransform(scale, scale);
        e.Graphics.TranslateTransform(-Width / 2f, -Height / 2f);

        var accent = Accent ?? TechPalette.Mint;
        var rect = new RectangleF(0.5f, 0.5f, Width - 1.5f, Height - 1.5f);
        using var path = TechDraw.RoundedRect(rect, 10f);

        if (Primary && Enabled)
        {
            var fill = new LinearGradientBrush(
                rect,
                TechDraw.Blend(TechPalette.Mint, TechPalette.MintDeep, _hoverAmount * 0.2f),
                TechPalette.MintDeep,
                LinearGradientMode.Horizontal);
            using (fill)
            {
                e.Graphics.FillPath(fill, path);
            }

            DrawGlyph(e.Graphics, TechPalette.Background, 18, Height / 2f, _pressAmount);
            DrawText(e.Graphics, TechPalette.Background, TechPalette.Background);
        }
        else
        {
            using (var fill = new SolidBrush(
                       TechDraw.Blend(TechPalette.Surface, TechPalette.SurfaceAlt, _hoverAmount)))
            {
                e.Graphics.FillPath(fill, path);
            }

            using (var border = new Pen(
                       TechDraw.Blend(
                           TechDraw.Alpha(accent, 70),
                           TechDraw.Alpha(accent, 155),
                           _hoverAmount)))
            {
                e.Graphics.DrawPath(border, path);
            }

            DrawGlyph(e.Graphics, accent, 18, Height / 2f, _hoverAmount);
            DrawText(
                e.Graphics,
                TechDraw.Blend(TechPalette.Text, accent, _hoverAmount * 0.65f),
                TechPalette.Dim);
        }

        if (Focused && ShowFocusCues)
        {
            ControlPaint.DrawFocusRectangle(e.Graphics, ClientRectangle, TechPalette.Mint, Color.Transparent);
        }
    }

    private void DrawText(Graphics graphics, Color main, Color sub)
    {
        if (string.IsNullOrWhiteSpace(SubText))
        {
            TextRenderer.DrawText(
                graphics,
                Text,
                Font,
                new Rectangle(52, 0, Width - 64, Height),
                main,
                TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
            return;
        }

        TextRenderer.DrawText(
            graphics,
            Text,
            Font,
            new Rectangle(52, 7, Width - 64, 21),
            main,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
        TextRenderer.DrawText(
            graphics,
            SubText,
            TechPalette.Caption,
            new Rectangle(52, 26, Width - 64, 17),
            sub,
            TextFormatFlags.Left | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis);
    }

    private void DrawGlyph(Graphics graphics, Color color, float x, float centerY, float activity)
    {
        using var pen = new Pen(color, 1.6f)
        {
            StartCap = LineCap.Round,
            EndCap = LineCap.Round,
            LineJoin = LineJoin.Round
        };
        var y = centerY;

        switch (Glyph)
        {
            case TechActionGlyph.Devices:
                graphics.DrawRoundedRectangleSafe(pen, x + 1, y - 7, 18, 13, 2.5f);
                graphics.DrawLine(pen, x + 7, y + 6, x + 7, y + 9);
                graphics.DrawLine(pen, x + 13, y + 6, x + 13, y + 9);
                graphics.DrawLine(pen, x + 5, y + 9, x + 15, y + 9);
                break;
            case TechActionGlyph.Lock:
                graphics.DrawRoundedRectangleSafe(pen, x + 2, y - 2, 16, 12, 2.5f);
                graphics.DrawArc(pen, x + 6, y - 10, 8, 12, 180, 180);
                using (var dot = new SolidBrush(color))
                {
                    graphics.FillEllipse(dot, x + 9.2f, y + 2.2f, 1.8f, 1.8f);
                }
                break;
            case TechActionGlyph.Refresh:
                graphics.DrawArc(pen, x + 2, y - 7, 16, 15, 35, 275);
                graphics.DrawLine(pen, x + 17, y - 8, x + 18, y - 3.5f);
                graphics.DrawLine(pen, x + 17, y - 8, x + 12.8f, y - 7);
                break;
            case TechActionGlyph.Log:
                graphics.DrawRoundedRectangleSafe(pen, x + 3, y - 8, 14, 17, 2f);
                graphics.DrawLine(pen, x + 7, y - 3, x + 13, y - 3);
                graphics.DrawLine(pen, x + 7, y + 1, x + 13, y + 1);
                graphics.DrawLine(pen, x + 7, y + 5, x + 11, y + 5);
                break;
            default:
                graphics.DrawEllipse(pen, x + 2, y - 8, 8, 8);
                graphics.DrawLine(pen, x + 9, y - 1, x + 15, y + 5);
                graphics.DrawLine(pen, x + 14, y + 4, x + 17, y + 7);
                graphics.DrawLine(pen, x + 12, y + 6, x + 15, y + 9);
                break;
        }

        if (activity > 0.15f)
        {
            using var glow = new SolidBrush(TechDraw.Alpha(color, (int)(22 * activity)));
            graphics.FillEllipse(glow, x - 2, y - 11, 24, 24);
        }
    }
}

internal static class GraphicsExtensions
{
    public static void DrawRoundedRectangleSafe(
        this Graphics graphics,
        Pen pen,
        float x,
        float y,
        float width,
        float height,
        float radius)
    {
        using var path = TechDraw.RoundedRect(new RectangleF(x, y, width, height), radius);
        graphics.DrawPath(pen, path);
    }
}
