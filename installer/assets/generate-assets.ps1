[CmdletBinding()]
param(
    [string]$RepositoryRoot = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not $RepositoryRoot) {
    $scriptPath = $MyInvocation.MyCommand.Path
    $RepositoryRoot = Split-Path -Parent (
        Split-Path -Parent (Split-Path -Parent $scriptPath)
    )
}

$installerAssets = Join-Path $RepositoryRoot 'installer\assets'
$trayAssets = Join-Path $RepositoryRoot 'windows\BandKeyTray\Assets'
New-Item -ItemType Directory -Force -Path $installerAssets, $trayAssets | Out-Null

$background = [System.Drawing.Color]::FromArgb(5, 7, 11)
$surface = [System.Drawing.Color]::FromArgb(10, 17, 25)
$mint = [System.Drawing.Color]::FromArgb(99, 242, 207)
$mintDeep = [System.Drawing.Color]::FromArgb(46, 211, 176)
$iris = [System.Drawing.Color]::FromArgb(139, 92, 246)
$muted = [System.Drawing.Color]::FromArgb(117, 143, 157)

function New-RoundedPath {
    param(
        [System.Drawing.RectangleF]$Rect,
        [float]$Radius
    )

    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $diameter = [Math]::Min($Radius * 2, [Math]::Min($Rect.Width, $Rect.Height))
    $path.AddArc($Rect.X, $Rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc(
        $Rect.Right - $diameter,
        $Rect.Bottom - $diameter,
        $diameter,
        $diameter,
        0,
        90
    )
    $path.AddArc($Rect.X, $Rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function Draw-Grid {
    param(
        [System.Drawing.Graphics]$Graphics,
        [int]$Width,
        [int]$Height
    )

    $pen = [System.Drawing.Pen]::new(
        [System.Drawing.Color]::FromArgb(13, 99, 242, 207),
        1
    )
    try {
        for ($x = 0; $x -lt $Width; $x += 16) {
            $Graphics.DrawLine($pen, $x, 0, $x, $Height)
        }
        for ($y = 0; $y -lt $Height; $y += 16) {
            $Graphics.DrawLine($pen, 0, $y, $Width, $y)
        }
    }
    finally {
        $pen.Dispose()
    }
}

function Draw-ShieldMark {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$Top,
        [float]$Width,
        [bool]$Compact = $false
    )

    $half = $Width / 2
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    try {
        $path.AddBezier(
            $CenterX, $Top,
            $CenterX + $half * 0.32, $Top + $Width * 0.02,
            $CenterX + $half * 0.78, $Top + $Width * 0.08,
            $CenterX + $half, $Top + $Width * 0.14
        )
        $path.AddBezier(
            $CenterX + $half, $Top + $Width * 0.14,
            $CenterX + $half * 0.96, $Top + $Width * 0.58,
            $CenterX + $half * 0.68, $Top + $Width * 0.84,
            $CenterX, $Top + $Width
        )
        $path.AddBezier(
            $CenterX, $Top + $Width,
            $CenterX - $half * 0.68, $Top + $Width * 0.84,
            $CenterX - $half * 0.96, $Top + $Width * 0.58,
            $CenterX - $half, $Top + $Width * 0.14
        )
        $path.AddBezier(
            $CenterX - $half, $Top + $Width * 0.14,
            $CenterX - $half * 0.78, $Top + $Width * 0.08,
            $CenterX - $half * 0.32, $Top + $Width * 0.02,
            $CenterX, $Top
        )
        $path.CloseFigure()

        $bounds = $path.GetBounds()
        $fill = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
            $bounds,
            $mint,
            $mintDeep,
            [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
        )
        try {
            $Graphics.FillPath($fill, $path)
        }
        finally {
            $fill.Dispose()
        }

        $glowAlpha = if ($Compact) { 100 } else { 150 }
        $glowPen = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb($glowAlpha, 255, 255, 255),
            [Math]::Max(1, $Width * 0.012)
        )
        try {
            $Graphics.DrawPath($glowPen, $path)
        }
        finally {
            $glowPen.Dispose()
        }

        $cutout = [System.Drawing.SolidBrush]::new($background)
        try {
            $holeSize = $Width * 0.19
            $holeX = $CenterX - $holeSize / 2
            $holeY = $Top + $Width * 0.38
            $Graphics.FillEllipse($cutout, $holeX, $holeY, $holeSize, $holeSize)
            $stemWidth = $Width * 0.075
            $Graphics.FillRectangle(
                $cutout,
                $CenterX - $stemWidth / 2,
                $holeY + $holeSize * 0.62,
                $stemWidth,
                $Width * 0.19
            )
        }
        finally {
            $cutout.Dispose()
        }
    }
    finally {
        $path.Dispose()
    }
}

function New-WizardLarge {
    $width = 164
    $height = 314
    $bitmap = [System.Drawing.Bitmap]::new($width, $height)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.TextRenderingHint =
            [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit

        $backgroundRect = [System.Drawing.Rectangle]::new(0, 0, $width, $height)
        $gradient = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
            $backgroundRect,
            $background,
            $surface,
            [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
        )
        try {
            $graphics.FillRectangle($gradient, $backgroundRect)
        }
        finally {
            $gradient.Dispose()
        }

        Draw-Grid -Graphics $graphics -Width $width -Height $height

        $diagonal = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(72, 99, 242, 207),
            1
        )
        try {
            $graphics.DrawLine($diagonal, -12, 218, 184, 112)
            $graphics.DrawLine($diagonal, -12, 226, 184, 120)
        }
        finally {
            $diagonal.Dispose()
        }

        $topAccent = [System.Drawing.SolidBrush]::new($mint)
        try {
            $graphics.FillRectangle($topAccent, 18, 20, 22, 2)
        }
        finally {
            $topAccent.Dispose()
        }

        $brandFont = [System.Drawing.Font]::new(
            'Consolas',
            9,
            [System.Drawing.FontStyle]::Bold
        )
        $captionFont = [System.Drawing.Font]::new('Consolas', 6.5)
        $heroFont = [System.Drawing.Font]::new(
            'Microsoft YaHei UI',
            16,
            [System.Drawing.FontStyle]::Bold
        )
        $labelBrush = [System.Drawing.SolidBrush]::new($mint)
        $mutedBrush = [System.Drawing.SolidBrush]::new($muted)
        try {
            $graphics.DrawString('BANDKEY', $brandFont, $labelBrush, 17, 28)
            $graphics.DrawString(
                'SECURE ACCESS',
                $captionFont,
                $mutedBrush,
                18,
                50
            )
        }
        finally {
            $brandFont.Dispose()
            $captionFont.Dispose()
        }

        Draw-ShieldMark -Graphics $graphics -CenterX 82 -Top 104 -Width 86

        $ringPen = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(64, 139, 92, 246),
            1
        )
        $innerRingPen = [System.Drawing.Pen]::new(
            [System.Drawing.Color]::FromArgb(34, 99, 242, 207),
            1
        )
        try {
            $graphics.DrawEllipse($ringPen, 23, 90, 118, 118)
            $graphics.DrawEllipse($innerRingPen, 35, 102, 94, 94)
        }
        finally {
            $ringPen.Dispose()
            $innerRingPen.Dispose()
        }

        $heroBrush = [System.Drawing.SolidBrush]::new(
            [System.Drawing.Color]::FromArgb(237, 246, 250)
        )
        try {
            $graphics.DrawString('DESKTOP', $heroFont, $heroBrush, 15, 234)
            $graphics.DrawString('UNLOCK', $heroFont, $heroBrush, 15, 258)
        }
        finally {
            $heroFont.Dispose()
            $heroBrush.Dispose()
        }

        $footerFont = [System.Drawing.Font]::new('Consolas', 6.5)
        try {
            $graphics.DrawString('TRUST INSTRUMENT', $footerFont, $labelBrush, 17, 293)
            $graphics.DrawString('P-256', $footerFont, $mutedBrush, 128, 293)
        }
        finally {
            $footerFont.Dispose()
            $labelBrush.Dispose()
            $mutedBrush.Dispose()
        }

        $bitmap.Save(
            (Join-Path $installerAssets 'wizard-large.png'),
            [System.Drawing.Imaging.ImageFormat]::Png
        )
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function New-AppMark {
    param(
        [int]$Size,
        [string]$Path
    )

    $bitmap = [System.Drawing.Bitmap]::new($Size, $Size)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    try {
        $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
        $graphics.Clear([System.Drawing.Color]::Transparent)
        $padding = [Math]::Max(1, [int]($Size * 0.045))
        $rect = [System.Drawing.RectangleF]::new(
            $padding,
            $padding,
            $Size - $padding * 2,
            $Size - $padding * 2
        )
        $pathShape = New-RoundedPath -Rect $rect -Radius ($Size * 0.21)
        try {
            $fill = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
                $rect,
                [System.Drawing.Color]::FromArgb(255, 8, 15, 22),
                [System.Drawing.Color]::FromArgb(255, 3, 7, 11),
                [System.Drawing.Drawing2D.LinearGradientMode]::Vertical
            )
            try {
                $graphics.FillPath($fill, $pathShape)
            }
            finally {
                $fill.Dispose()
            }

            $border = [System.Drawing.Pen]::new(
                [System.Drawing.Color]::FromArgb(125, 99, 242, 207),
                [Math]::Max(1, $Size * 0.018)
            )
            try {
                $graphics.DrawPath($border, $pathShape)
            }
            finally {
                $border.Dispose()
            }
        }
        finally {
            $pathShape.Dispose()
        }

        Draw-ShieldMark `
            -Graphics $graphics `
            -CenterX ($Size / 2) `
            -Top ($Size * 0.235) `
            -Width ($Size * 0.49) `
            -Compact ($Size -le 32)

        $bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    }
    finally {
        $graphics.Dispose()
        $bitmap.Dispose()
    }
}

function New-WizardSmall {
    $size = 55
    New-AppMark -Size $size -Path (Join-Path $installerAssets 'wizard-small.png')
}

function New-Icon {
    param([string]$Path)

    $tempDirectory = Join-Path ([System.IO.Path]::GetTempPath()) (
        'bandkey-icon-' + [Guid]::NewGuid().ToString('N')
    )
    New-Item -ItemType Directory -Path $tempDirectory | Out-Null
    try {
        $sizes = @(16, 24, 32, 48, 64, 128, 256)
        $images = @()
        foreach ($size in $sizes) {
            $file = Join-Path $tempDirectory "icon-$size.png"
            New-AppMark -Size $size -Path $file
            $images += [PSCustomObject]@{
                Size = $size
                Bytes = [System.IO.File]::ReadAllBytes($file)
            }
        }

        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write
        )
        $writer = [System.IO.BinaryWriter]::new($stream)
        try {
            $writer.Write([UInt16]0)
            $writer.Write([UInt16]1)
            $writer.Write([UInt16]$images.Count)
            $offset = 6 + 16 * $images.Count
            foreach ($image in $images) {
                $dimension = if ($image.Size -ge 256) { 0 } else { $image.Size }
                $writer.Write([byte]$dimension)
                $writer.Write([byte]$dimension)
                $writer.Write([byte]0)
                $writer.Write([byte]0)
                $writer.Write([UInt16]1)
                $writer.Write([UInt16]32)
                $writer.Write([UInt32]$image.Bytes.Length)
                $writer.Write([UInt32]$offset)
                $offset += $image.Bytes.Length
            }

            foreach ($image in $images) {
                $writer.Write($image.Bytes)
            }
        }
        finally {
            $writer.Dispose()
            $stream.Dispose()
        }
    }
    finally {
        Remove-Item -LiteralPath $tempDirectory -Recurse -Force
    }
}

New-WizardLarge
New-WizardSmall
$iconPath = Join-Path $installerAssets 'BandKey.ico'
New-Icon -Path $iconPath
Copy-Item -LiteralPath $iconPath `
    -Destination (Join-Path $trayAssets 'BandKey.ico') -Force

Write-Host "Installer assets: $installerAssets"
Write-Host "Tray icon: $(Join-Path $trayAssets 'BandKey.ico')"
