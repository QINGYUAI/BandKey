using System.Security.Cryptography;
using System.Text;

namespace BandKeyService;

public enum CredentialSaveResult
{
    Saved,
    InvalidRequest,
    UserMismatch,
    InvalidPassword,
    WriteFailed
}

public sealed class CredentialStore
{
    private readonly string _directory;
    private readonly string _usernamePath;
    private readonly string _credentialPath;
    private readonly object _gate = new();

    public CredentialStore(string directory)
    {
        _directory = directory;
        _usernamePath = Path.Combine(directory, "username.txt");
        _credentialPath = Path.Combine(directory, "credential.dat");
    }

    public string UsernamePath => _usernamePath;

    public string CredentialPath => _credentialPath;

    public bool IsUsable
    {
        get
        {
            try
            {
                return File.Exists(_usernamePath)
                       && new FileInfo(_usernamePath).Length > 0
                       && File.Exists(_credentialPath)
                       && new FileInfo(_credentialPath).Length > 0;
            }
            catch
            {
                return false;
            }
        }
    }

    public string? ReadUsername()
    {
        try
        {
            return File.Exists(_usernamePath) ? File.ReadAllText(_usernamePath).Trim() : null;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// 保存前由服务端完成身份和 LogonUser 校验；这里只负责原子落盘。
    /// 继续写入 username.txt / credential.dat，保持与现有凭据提供程序兼容。
    /// </summary>
    public CredentialSaveResult SaveVerified(string domain, string username, string password)
    {
        if (string.IsNullOrWhiteSpace(username) || password is null)
        {
            return CredentialSaveResult.InvalidRequest;
        }

        var user = username.Trim();
        var dom = (domain ?? "").Trim().TrimEnd('\\');
        if (user.Length > 256 || dom.Length > 256 || password.Length > 1024
            || user.Contains('\n') || user.Contains('\r')
            || dom.Contains('\n') || dom.Contains('\r'))
        {
            return CredentialSaveResult.InvalidRequest;
        }

        var fullUsername = string.IsNullOrEmpty(dom) ? user : $"{dom}\\{user}";
        byte[]? passwordBytes = null;
        try
        {
            passwordBytes = Encoding.Unicode.GetBytes(password);
            var protectedBytes = Dpapi.Protect(passwordBytes);
            var usernameBytes = new UTF8Encoding(false).GetBytes(fullUsername);

            lock (_gate)
            {
                Directory.CreateDirectory(_directory);
                WriteBytesAtomically(_usernamePath, usernameBytes);
                WriteBytesAtomically(_credentialPath, protectedBytes);
            }

            return CredentialSaveResult.Saved;
        }
        catch
        {
            return CredentialSaveResult.WriteFailed;
        }
        finally
        {
            if (passwordBytes is not null)
            {
                CryptographicOperations.ZeroMemory(passwordBytes);
            }
        }
    }

    private static void WriteBytesAtomically(string path, byte[] contents)
    {
        var temp = $"{path}.{Environment.ProcessId}.{Guid.NewGuid():N}.tmp";
        try
        {
            using (var stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None, 4096,
                       FileOptions.WriteThrough))
            {
                stream.Write(contents);
                stream.Flush(flushToDisk: true);
            }

            File.Move(temp, path, overwrite: true);
        }
        finally
        {
            try
            {
                File.Delete(temp);
            }
            catch
            {
                // 临时文件清理失败不影响主流程
            }
        }
    }
}
