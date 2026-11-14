using System.Drawing;
using System.Windows.Forms;

namespace BandKeyTray;

internal sealed class UpdateDialog : TechForm
{
    private readonly TechStatusPill _statusPill = new();
    private readonly Label _currentVersion = new();
    private readonly Label _latestVersion = new();
    private readonly TechNote _statusNote = new("正在连接 GitHub Release…", TechPalette.Muted);
    private readonly TextBox _notes = new();
    private readonly TechProgress _progress = new();
    private readonly TechButton _checkButton = new();
    private readonly TechButton _downloadButton = new();
    private readonly Label _releaseLink = new();
    private CancellationTokenSource? _operation;
    private UpdateCheckResult? _check;
    private string? _lastInstallerPath;
    private string _releaseUrl = UpdateChecker.ReleasesUrl;
    private bool _busy;
    private bool _closed;

    public UpdateDialog() : base("软件更新", new Size(620, 742))
    {
        Body.Padding = new Padding(32, 10, 32, 24);
        BuildContent();
        Shown += async (_, _) => await CheckAsync();
        FormClosed += (_, _) =>
        {
            _closed = true;
            _operation?.Cancel();
            _operation?.Dispose();
            _operation = null;
        };
    }

    private void BuildContent()
    {
        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 12,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 104));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 14));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 58));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 18));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 30));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 16));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));

        var kicker = new Label
        {
            Text = "GITHUB RELEASE / SHA-256 VERIFIED",
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Mint,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };

        var headingRow = new Panel
        {
            Dock = DockStyle.Fill,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        var heading = new Label
        {
            Text = "软件更新",
            AutoSize = true,
            Font = TechPalette.Display,
            ForeColor = TechPalette.Text,
            BackColor = Color.Transparent,
            Location = new Point(0, 2)
        };
        _statusPill.SetStatus("正在检查", TechPalette.Muted);
        _statusPill.Anchor = AnchorStyles.Top | AnchorStyles.Right;
        _statusPill.Location = new Point(headingRow.Width - _statusPill.Width, 2);
        _statusPill.SizeChanged += (_, _) =>
            _statusPill.Left = Math.Max(heading.Right + 12, headingRow.Width - _statusPill.Width);
        headingRow.Controls.Add(heading);
        headingRow.Controls.Add(_statusPill);
        headingRow.Resize += (_, _) =>
            _statusPill.Left = Math.Max(heading.Right + 12, headingRow.Width - _statusPill.Width);

        var subtitle = new Label
        {
            Text = "从官方仓库读取稳定版 Release，下载完成后先校验安装包完整性。",
            AutoSize = false,
            Dock = DockStyle.Fill,
            Font = TechPalette.Body,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.TopLeft,
            Margin = new Padding(0)
        };

        var versionCard = new TechCard
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0),
            Padding = new Padding(22, 12, 22, 12)
        };
        var versions = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 3,
            RowCount = 1,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        versions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        versions.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 34));
        versions.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 50));
        versions.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        versions.Controls.Add(CreateVersionBlock("CURRENT / 已安装", _currentVersion, "v" + UpdateChecker.CurrentVersion), 0, 0);
        versions.Controls.Add(new Label
        {
            Text = "→",
            Dock = DockStyle.Fill,
            Font = TechPalette.Display,
            ForeColor = TechPalette.Dim,
            TextAlign = ContentAlignment.MiddleCenter,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        }, 1, 0);
        versions.Controls.Add(CreateVersionBlock("LATEST / 官方版本", _latestVersion, "待检查"), 2, 0);
        versionCard.Controls.Add(versions);

        _statusNote.Dock = DockStyle.Fill;
        _statusNote.Margin = new Padding(0);

        var notesHost = new TechCard
        {
            Dock = DockStyle.Fill,
            Margin = new Padding(0),
            Padding = new Padding(16, 12, 12, 12)
        };
        var notesLayout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        notesLayout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        notesLayout.RowStyles.Add(new RowStyle(SizeType.Absolute, 24));
        notesLayout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        notesLayout.Controls.Add(new Label
        {
            Text = "RELEASE NOTES / 版本说明",
            Dock = DockStyle.Fill,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Mint,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        }, 0, 0);
        _notes.Dock = DockStyle.Fill;
        _notes.Multiline = true;
        _notes.ReadOnly = true;
        _notes.ScrollBars = ScrollBars.Vertical;
        _notes.BorderStyle = BorderStyle.None;
        _notes.BackColor = TechPalette.Field;
        _notes.ForeColor = TechPalette.Muted;
        _notes.Font = TechPalette.BodySmall;
        _notes.Text = "更新说明会在检查完成后显示。";
        notesLayout.Controls.Add(_notes, 0, 1);
        notesHost.Controls.Add(notesLayout);

        var links = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        _releaseLink.Text = "查看版本说明  ›";
        ConfigureLinkAppearance(_releaseLink, "查看版本说明  ›");
        links.Controls.Add(_releaseLink);
        links.Controls.Add(CreateLink("GitHub 仓库  ›", UpdateChecker.RepositoryUrl));
        links.Controls.Add(CreateLink("使用文档  ›", UpdateChecker.DocsUrl));
        links.Controls.Add(CreateLink("问题反馈  ›", UpdateChecker.IssuesUrl));
        _releaseLink.Click += (_, _) => UpdateChecker.OpenExternal(_releaseUrl);

        _progress.Dock = DockStyle.Fill;
        _progress.Value = 0;
        _progress.Margin = new Padding(0, 6, 0, 4);

        var buttons = new FlowLayoutPanel
        {
            Dock = DockStyle.Fill,
            FlowDirection = FlowDirection.RightToLeft,
            BackColor = TechPalette.Background,
            Margin = new Padding(0)
        };
        _downloadButton.Text = "下载更新";
        _downloadButton.Primary = true;
        _downloadButton.Enabled = false;
        _downloadButton.Width = 142;
        _downloadButton.Margin = new Padding(8, 0, 0, 0);
        _downloadButton.Click += OnDownloadActionClick;

        _checkButton.Text = "检查更新";
        _checkButton.Width = 112;
        _checkButton.Margin = new Padding(8, 0, 0, 0);
        _checkButton.Click += async (_, _) => await CheckAsync();

        var close = new TechButton
        {
            Text = "关闭",
            Width = 84,
            DialogResult = DialogResult.Cancel,
            Margin = new Padding(8, 0, 0, 0)
        };
        buttons.Controls.Add(_downloadButton);
        buttons.Controls.Add(_checkButton);
        buttons.Controls.Add(close);

        layout.Controls.Add(kicker, 0, 0);
        layout.Controls.Add(headingRow, 0, 1);
        layout.Controls.Add(subtitle, 0, 2);
        layout.Controls.Add(versionCard, 0, 4);
        layout.Controls.Add(_statusNote, 0, 6);
        layout.Controls.Add(notesHost, 0, 8);
        layout.Controls.Add(links, 0, 9);
        layout.Controls.Add(_progress, 0, 10);
        layout.Controls.Add(buttons, 0, 11);

        Body.Controls.Add(layout);
        CancelButton = close;
    }

    private static Control CreateVersionBlock(string caption, Label value, string initial)
    {
        var panel = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            ColumnCount = 1,
            RowCount = 2,
            BackColor = Color.Transparent,
            Margin = new Padding(0)
        };
        panel.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        panel.RowStyles.Add(new RowStyle(SizeType.Absolute, 26));
        panel.RowStyles.Add(new RowStyle(SizeType.Percent, 100));
        panel.Controls.Add(new Label
        {
            Text = caption,
            Dock = DockStyle.Fill,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Dim,
            BackColor = Color.Transparent,
            TextAlign = ContentAlignment.MiddleLeft,
            Margin = new Padding(0)
        }, 0, 0);

        value.Text = initial;
        value.Dock = DockStyle.Fill;
        value.Font = new Font("Microsoft YaHei UI", 20f, FontStyle.Bold);
        value.ForeColor = TechPalette.Text;
        value.BackColor = Color.Transparent;
        value.TextAlign = ContentAlignment.MiddleLeft;
        value.Margin = new Padding(0);
        panel.Controls.Add(value, 0, 1);
        return panel;
    }

    private static Label CreateLink(string text, string url)
    {
        var link = new Label
        {
            AutoSize = true,
            Font = TechPalette.Caption,
            ForeColor = TechPalette.Muted,
            BackColor = Color.Transparent,
            Cursor = Cursors.Hand,
            Margin = new Padding(0, 7, 20, 0)
        };
        ConfigureLink(link, text, url);
        return link;
    }

    private static void ConfigureLink(Label link, string text, string url)
    {
        ConfigureLinkAppearance(link, text);
        link.Click += (_, _) =>
        {
            if (!UpdateChecker.OpenExternal(url))
            {
                TechMessageBox.Show(
                    link.FindForm(),
                    "无法打开默认浏览器，请手动访问：\n" + url,
                    "打开链接失败",
                    TechMessageKind.Warning);
            }
        };
    }

    private static void ConfigureLinkAppearance(Label link, string text)
    {
        link.Text = text;
        link.AutoSize = true;
        link.Font = TechPalette.Caption;
        link.ForeColor = TechPalette.Muted;
        link.BackColor = Color.Transparent;
        link.Cursor = Cursors.Hand;
        link.Margin = new Padding(0, 7, 20, 0);
        link.MouseEnter += (_, _) => link.ForeColor = TechPalette.Mint;
        link.MouseLeave += (_, _) => link.ForeColor = TechPalette.Muted;
    }

    private async Task CheckAsync()
    {
        if (_busy || _closed)
        {
            return;
        }

        _operation?.Cancel();
        _operation?.Dispose();
        _operation = new CancellationTokenSource();
        SetBusy(true, "正在检查更新", TechPalette.Muted);
        _check = null;
        _lastInstallerPath = null;
        _downloadButton.Text = "检查中";
        _downloadButton.Enabled = false;
        _statusNote.NoteText = "正在连接 GitHub Release…";
        _statusNote.Tone = TechPalette.Muted;
        _progress.Value = 0;

        try
        {
            var result = await UpdateChecker.CheckAsync(_operation.Token);
            if (_closed)
            {
                return;
            }

            _check = result;
            UpdateChecker.MarkAutoCheckCompleted();
            _latestVersion.Text = "v" + result.Release.VersionName;
            _notes.Text = string.IsNullOrWhiteSpace(result.Release.Notes)
                ? "该版本未提供更新说明。"
                : result.Release.Notes.Trim();
            ConfigureReleaseLink(result.Release.ReleaseUrl);

            if (!result.IsNewer)
            {
                SetBusy(false, "已是最新版本", TechPalette.Mint);
                UpdateStatus(
                    $"当前 v{UpdateChecker.CurrentVersion} 已是官方稳定版。可继续查看版本说明或仓库文档。",
                    TechPalette.Mint);
                _downloadButton.Text = "已是最新";
                _downloadButton.Enabled = false;
            }
            else if (result.Release.Installer is null)
            {
                SetBusy(false, "缺少安装包", TechPalette.Warning);
                UpdateStatus(
                    $"发现 v{result.Release.VersionName}，但该 Release 未提供 Windows x64 安装包。",
                    TechPalette.Warning);
                _downloadButton.Text = "暂无安装包";
                _downloadButton.Enabled = false;
            }
            else if (result.Release.ChecksumsUri is null)
            {
                SetBusy(false, "缺少校验文件", TechPalette.Warning);
                UpdateStatus(
                    $"发现 v{result.Release.VersionName}，但缺少 SHA256SUMS.txt，已禁止直接安装。",
                    TechPalette.Warning);
                _downloadButton.Text = "无法校验";
                _downloadButton.Enabled = false;
            }
            else
            {
                SetBusy(false, "发现新版本", TechPalette.Mint);
                UpdateStatus(
                    $"v{result.Release.VersionName} 可用 · {FormatSize(result.Release.Installer.Size)} · SHA-256 校验后安装",
                    TechPalette.Mint);
                _downloadButton.Text = $"下载 v{result.Release.VersionName}";
                _downloadButton.Enabled = true;
            }
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            if (_closed)
            {
                return;
            }

            SetBusy(false, "检查失败", TechPalette.Danger);
            UpdateStatus("检查失败：" + ex.Message, TechPalette.Danger);
            _notes.Text = "请检查网络连接后重试。若 GitHub API 返回 403，通常是临时访问频率限制。";
            _downloadButton.Text = "重试检查";
            _downloadButton.Enabled = false;
        }
    }

    private async Task DownloadAsync()
    {
        if (_busy || _closed || _check is not { IsNewer: true } check)
        {
            return;
        }

        if (check.Release.Installer is null || check.Release.ChecksumsUri is null)
        {
            return;
        }

        _operation?.Cancel();
        _operation?.Dispose();
        _operation = new CancellationTokenSource();
        SetBusy(true, "正在下载", TechPalette.Violet);
        _downloadButton.Text = "下载中";
        UpdateStatus("正在下载 Windows 安装包…", TechPalette.Violet);
        _progress.Value = 0;
        var progress = new Progress<int>(value =>
        {
            if (_closed)
            {
                return;
            }

            _progress.Value = value / 100f;
            _statusNote.NoteText = $"正在下载 Windows 安装包… {value}%";
        });

        try
        {
            var path = await UpdateChecker.DownloadAndVerifyAsync(
                check.Release,
                progress,
                _operation.Token);
            if (_closed)
            {
                return;
            }

            SetBusy(false, "校验通过", TechPalette.Mint);
            UpdateStatus("安装包已下载并通过 SHA-256 校验，可以开始安装。", TechPalette.Mint);
            _downloadButton.Text = "安装更新";
            _downloadButton.Enabled = true;
            _lastInstallerPath = path;
        }
        catch (OperationCanceledException)
        {
        }
        catch (Exception ex)
        {
            if (_closed)
            {
                return;
            }

            SetBusy(false, "下载失败", TechPalette.Danger);
            UpdateStatus("下载失败：" + ex.Message, TechPalette.Danger);
            _downloadButton.Text = "重新下载";
            _downloadButton.Enabled = true;
        }
    }

    private async void OnDownloadActionClick(object? sender, EventArgs e)
    {
        if (!string.IsNullOrWhiteSpace(_lastInstallerPath))
        {
            InstallFromLastDownload(sender, e);
            return;
        }

        await DownloadAsync();
    }

    private void InstallFromLastDownload(object? sender, EventArgs e)
    {
        if (string.IsNullOrWhiteSpace(_lastInstallerPath))
        {
            return;
        }

        var confirmed = TechMessageBox.Show(
            this,
            "即将启动安装程序。\n\n" +
            "安装会关闭当前托盘程序、更新 Windows 服务并请求管理员权限。" +
            "安装完成后建议重启电脑，让登录界面加载新的凭据组件。",
            "安装 BandKey 更新",
            TechMessageKind.Warning,
            confirm: true);
        if (confirmed != DialogResult.Yes)
        {
            return;
        }

        try
        {
            UpdateChecker.LaunchInstaller(_lastInstallerPath);
            Close();
        }
        catch (Exception ex)
        {
            TechMessageBox.Show(
                this,
                "无法启动安装程序：" + ex.Message,
                "安装失败",
                TechMessageKind.Danger);
        }
    }

    private void SetBusy(bool busy, string status, Color tone)
    {
        _busy = busy;
        _statusPill.SetStatus(status, tone);
        _checkButton.Enabled = !busy;
        _downloadButton.Enabled = !busy;
    }

    private void ConfigureReleaseLink(string url)
    {
        _releaseUrl = url;
    }

    private void UpdateStatus(string text, Color tone)
    {
        _statusNote.NoteText = text;
        _statusNote.Tone = tone;
    }

    private static string FormatSize(long bytes)
    {
        if (bytes <= 0)
        {
            return "大小未知";
        }

        return bytes >= 1024L * 1024L
            ? $"{bytes / 1024d / 1024d:F1} MB"
            : $"{Math.Max(1, bytes / 1024d):F0} KB";
    }
}
