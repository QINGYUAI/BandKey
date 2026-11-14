using System.Diagnostics;
using System.Net;
using System.Security.Cryptography;
using System.Text.Json;

namespace BandKeyTray;

internal sealed record UpdateAsset(string Name, Uri Uri, long Size);

internal sealed record UpdateRelease(
    Version Version,
    string Tag,
    string Title,
    string Notes,
    string ReleaseUrl,
    UpdateAsset? Installer,
    Uri? ChecksumsUri)
{
    public string VersionName => Version.ToString(3);
}

internal sealed record UpdateCheckResult(UpdateRelease Release, bool IsNewer);

internal sealed class UpdateCheckException : Exception
{
    public UpdateCheckException(string message, Exception? inner = null)
        : base(message, inner)
    {
    }
}

internal static class UpdateChecker
{
    public const string RepositoryUrl = "https://github.com/QINGYUAI/BandKey";
    public const string DocsUrl = "https://github.com/QINGYUAI/BandKey/tree/main/docs";
    public const string IssuesUrl = "https://github.com/QINGYUAI/BandKey/issues";
    public const string ReleasesUrl = "https://github.com/QINGYUAI/BandKey/releases/latest";

    private const string LatestReleaseApi =
        "https://api.github.com/repos/QINGYUAI/BandKey/releases/latest";

    private static readonly HttpClient Http = CreateClient();

    public static Version CurrentVersion
    {
        get
        {
            var version = typeof(UpdateChecker).Assembly.GetName().Version ?? new Version(1, 0, 0);
            return new Version(version.Major, version.Minor, Math.Max(0, version.Build));
        }
    }

    public static async Task<UpdateCheckResult> CheckAsync(CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, LatestReleaseApi);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        request.Headers.TryAddWithoutValidation("X-GitHub-Api-Version", "2022-11-28");

        using var response = await Http.SendAsync(
            request,
            HttpCompletionOption.ResponseHeadersRead,
            cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            var reason = response.StatusCode switch
            {
                HttpStatusCode.NotFound =>
                    "无法访问 GitHub 仓库（404）。请确认仓库已设为公开并发布 Stable Release",
                HttpStatusCode.Forbidden =>
                    "GitHub API 访问频率受限，请稍后重试",
                _ => $"GitHub 返回 HTTP {(int)response.StatusCode}"
            };
            throw new UpdateCheckException(reason);
        }

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
        var root = document.RootElement;

        var tag = ReadString(root, "tag_name");
        var version = ParseVersion(tag);
        var releaseUrl = ReadString(root, "html_url");
        var title = ReadString(root, "name");
        var notes = ReadString(root, "body");
        var assets = root.TryGetProperty("assets", out var assetsElement)
                     && assetsElement.ValueKind == JsonValueKind.Array
            ? assetsElement
            : default;

        var expectedInstaller = $"BandKey-{version.ToString(3)}-windows-x64.exe";
        UpdateAsset? installer = null;
        Uri? checksums = null;
        if (assets.ValueKind == JsonValueKind.Array)
        {
            foreach (var asset in assets.EnumerateArray())
            {
                var name = ReadString(asset, "name");
                var downloadUrl = ReadString(asset, "browser_download_url");
                if (!Uri.TryCreate(downloadUrl, UriKind.Absolute, out var uri))
                {
                    continue;
                }

                if (string.Equals(name, expectedInstaller, StringComparison.OrdinalIgnoreCase))
                {
                    installer = new UpdateAsset(name, uri, ReadInt64(asset, "size"));
                }
                else if (string.Equals(name, "SHA256SUMS.txt", StringComparison.OrdinalIgnoreCase))
                {
                    checksums = uri;
                }
            }
        }

        var release = new UpdateRelease(
            version,
            tag,
            string.IsNullOrWhiteSpace(title) ? $"BandKey {version}" : title,
            notes,
            string.IsNullOrWhiteSpace(releaseUrl)
                ? $"{RepositoryUrl}/releases/tag/{tag}"
                : releaseUrl,
            installer,
            checksums);
        return new UpdateCheckResult(release, version > CurrentVersion);
    }

    public static async Task<string> DownloadAndVerifyAsync(
        UpdateRelease release,
        IProgress<int>? progress = null,
        CancellationToken cancellationToken = default)
    {
        if (release.Installer is null)
        {
            throw new UpdateCheckException("该版本没有提供 Windows x64 安装包");
        }

        if (release.ChecksumsUri is null)
        {
            throw new UpdateCheckException("该版本缺少 SHA256SUMS.txt，无法校验安装包");
        }

        var directory = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BandKey",
            "updates",
            release.VersionName);
        Directory.CreateDirectory(directory);

        var finalPath = Path.Combine(directory, release.Installer.Name);
        var temporaryPath = finalPath + ".part";
        if (File.Exists(temporaryPath))
        {
            File.Delete(temporaryPath);
        }

        try
        {
            using var response = await Http.GetAsync(
                release.Installer.Uri,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken);
            response.EnsureSuccessStatusCode();

            var total = response.Content.Headers.ContentLength ?? release.Installer.Size;
            await using (var source = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var target = new FileStream(
                             temporaryPath,
                             FileMode.CreateNew,
                             FileAccess.Write,
                             FileShare.None,
                             128 * 1024,
                             useAsync: true))
            {
                var buffer = new byte[128 * 1024];
                long received = 0;
                var lastPercent = -1;
                int read;
                while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    await target.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    received += read;
                    if (total <= 0)
                    {
                        continue;
                    }

                    var percent = (int)Math.Clamp(received * 100L / total, 0L, 100L);
                    if (percent == lastPercent)
                    {
                        continue;
                    }

                    lastPercent = percent;
                    progress?.Report(percent);
                }
            }

            var expected = await ReadExpectedChecksumAsync(
                release.ChecksumsUri,
                release.Installer.Name,
                cancellationToken);
            string actual;
            await using (var file = File.OpenRead(temporaryPath))
            {
                actual = Convert.ToHexString(
                    await SHA256.HashDataAsync(file, cancellationToken));
            }

            if (!string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
            {
                throw new UpdateCheckException(
                    $"安装包 SHA-256 校验失败（期望 {expected[..12]}…，实际 {actual[..12]}…）");
            }

            File.Move(temporaryPath, finalPath, overwrite: true);
            progress?.Report(100);
            return finalPath;
        }
        catch
        {
            try
            {
                if (File.Exists(temporaryPath))
                {
                    File.Delete(temporaryPath);
                }
            }
            catch
            {
                // 清理失败不覆盖原始下载或校验错误。
            }

            throw;
        }
    }

    public static void LaunchInstaller(string path)
    {
        if (!File.Exists(path))
        {
            throw new UpdateCheckException("安装包不存在，请重新下载");
        }

        Process.Start(new ProcessStartInfo
        {
            FileName = path,
            WorkingDirectory = Path.GetDirectoryName(path) ?? string.Empty,
            UseShellExecute = true,
            Verb = "runas"
        });
    }

    public static bool OpenExternal(string url)
    {
        try
        {
            Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
            return true;
        }
        catch (Exception ex)
        {
            TrayLog.Write($"open external url failed: {url} :: {ex.Message}");
            return false;
        }
    }

    public static bool IsAutoCheckDue()
    {
        try
        {
            var path = GetAutoCheckStampPath();
            return !File.Exists(path)
                   || !DateTimeOffset.TryParse(
                       File.ReadAllText(path).Trim(),
                       out var checkedAt)
                   || DateTimeOffset.UtcNow - checkedAt >= TimeSpan.FromHours(24);
        }
        catch
        {
            return true;
        }
    }

    public static void MarkAutoCheckCompleted()
    {
        try
        {
            var path = GetAutoCheckStampPath();
            Directory.CreateDirectory(Path.GetDirectoryName(path)!);
            File.WriteAllText(path, DateTimeOffset.UtcNow.ToString("O"));
        }
        catch (Exception ex)
        {
            TrayLog.Write("write update check stamp failed: " + ex.Message);
        }
    }

    private static string GetAutoCheckStampPath()
    {
        return Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "BandKey",
            "last-update-check.txt");
    }

    private static async Task<string> ReadExpectedChecksumAsync(
        Uri checksumsUri,
        string fileName,
        CancellationToken cancellationToken)
    {
        using var response = await Http.GetAsync(checksumsUri, cancellationToken);
        if (!response.IsSuccessStatusCode)
        {
            throw new UpdateCheckException(
                $"下载校验文件失败：HTTP {(int)response.StatusCode}");
        }

        var content = await response.Content.ReadAsStringAsync(cancellationToken);
        foreach (var line in content.Split('\n'))
        {
            var parts = line.Trim().Split(
                (char[]?)null,
                StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length < 2
                || !string.Equals(
                    parts[1].TrimStart('*'),
                    fileName,
                    StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            if (parts[0].Length == 64
                && parts[0].All(character => Uri.IsHexDigit(character)))
            {
                return parts[0];
            }
        }

        throw new UpdateCheckException($"SHA256SUMS.txt 中找不到 {fileName}");
    }

    private static HttpClient CreateClient()
    {
        var client = new HttpClient
        {
            Timeout = TimeSpan.FromMinutes(10)
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(
            $"BandKey-Desktop-Updater/{CurrentVersion}");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/vnd.github+json");
        return client;
    }

    private static Version ParseVersion(string tag)
    {
        var normalized = tag.Trim().TrimStart('v', 'V');
        if (!Version.TryParse(normalized, out var version))
        {
            throw new UpdateCheckException($"无法识别 Release 版本号：{tag}");
        }

        return new Version(
            version.Major,
            version.Minor,
            Math.Max(0, version.Build));
    }

    private static string ReadString(JsonElement element, string name)
    {
        return element.TryGetProperty(name, out var value)
               && value.ValueKind == JsonValueKind.String
            ? value.GetString() ?? string.Empty
            : string.Empty;
    }

    private static long ReadInt64(JsonElement element, string name)
    {
        return element.TryGetProperty(name, out var value)
               && value.TryGetInt64(out var number)
            ? number
            : 0;
    }
}
