using System.Text;

namespace BandKeyService;

/// <summary>
/// Windows 服务没有可见控制台，日志同时写入数据目录下的 service.log。
/// 单文件上限 2 MiB，超出后轮转为 service.log.1，避免长期运行无限增长。
/// </summary>
public static class ServiceLog
{
    private const long MaxBytes = 2 * 1024 * 1024;
    private static readonly object Gate = new();
    private static string? _path;
    private static volatile bool _disabled;
    private static int _failures;

    public static void Initialize(string directory)
    {
        try
        {
            _path = Path.Combine(directory, "service.log");
        }
        catch
        {
            _path = null;
        }
    }

    public static void Write(string message)
    {
        Console.WriteLine(message);

        var path = _path;
        if (path is null || _disabled)
        {
            return;
        }

        try
        {
            lock (Gate)
            {
                var directory = Path.GetDirectoryName(path);
                if (!string.IsNullOrEmpty(directory))
                {
                    Directory.CreateDirectory(directory);
                }

                var info = new FileInfo(path);
                if (info.Exists && info.Length >= MaxBytes)
                {
                    File.Move(path, path + ".1", overwrite: true);
                }

                File.AppendAllText(
                    path,
                    $"{DateTimeOffset.Now:yyyy-MM-dd HH:mm:ss.fff zzz} {message}{Environment.NewLine}",
                    new UTF8Encoding(false));
                Interlocked.Exchange(ref _failures, 0);
            }
        }
        catch
        {
            if (Interlocked.Increment(ref _failures) >= 5)
            {
                _disabled = true;
            }
        }
    }
}
