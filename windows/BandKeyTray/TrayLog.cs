using System.Text;

namespace BandKeyTray;

internal static class TrayLog
{
    private static readonly object Gate = new();
    private static readonly string DirectoryPath = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "BandKey");

    public static string LogFile => Path.Combine(DirectoryPath, "tray.log");

    public static void Write(string message)
    {
        lock (Gate)
        {
            try
            {
                Directory.CreateDirectory(DirectoryPath);
                var file = new FileInfo(LogFile);
                if (file.Exists && file.Length > 1024 * 1024)
                {
                    File.Delete(LogFile);
                }

                var line = $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} {message}{Environment.NewLine}";
                File.AppendAllText(LogFile, line, new UTF8Encoding(false));
            }
            catch
            {
                // 日志失败不影响主流程
            }
        }
    }
}
