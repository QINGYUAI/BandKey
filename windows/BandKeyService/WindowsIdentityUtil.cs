using System.Runtime.InteropServices;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;

namespace BandKeyService;

public static class WindowsIdentityUtil
{
    private const int Logon32LogonInteractive = 2;
    private const int Logon32ProviderDefault = 0;

    [DllImport("kernel32.dll")]
    private static extern uint WTSGetActiveConsoleSessionId();

    [DllImport("wtsapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool WTSQueryUserToken(uint sessionId, out IntPtr token);

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool LogonUser(
        string username,
        string domain,
        string password,
        int logonType,
        int logonProvider,
        out SafeAccessTokenHandle token);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseHandle(IntPtr handle);

    /// <summary>
    /// 获取当前控制台登录用户的 SID。服务运行在 Session 0 时也能拿到桌面用户身份。
    /// </summary>
    public static SecurityIdentifier? GetActiveConsoleUserSid()
    {
        try
        {
            var sessionId = WTSGetActiveConsoleSessionId();
            if (sessionId == 0xFFFFFFFF)
            {
                return null;
            }

            if (!WTSQueryUserToken(sessionId, out var token) || token == IntPtr.Zero)
            {
                return null;
            }

            try
            {
                using var identity = new WindowsIdentity(token);
                return identity.User;
            }
            finally
            {
                CloseHandle(token);
            }
        }
        catch
        {
            return null;
        }
    }

    public static SecurityIdentifier? GetCurrentProcessUserSid()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            return identity.User;
        }
        catch
        {
            return null;
        }
    }

    public static bool IdentityMatches(string? actualAccount, string? requestedAccount)
    {
        if (string.IsNullOrWhiteSpace(actualAccount) || string.IsNullOrWhiteSpace(requestedAccount))
        {
            return false;
        }

        if (string.Equals(Normalize(actualAccount), Normalize(requestedAccount), StringComparison.OrdinalIgnoreCase))
        {
            return true;
        }

        var actualSid = ResolveSid(actualAccount);
        var requestedSid = ResolveSid(requestedAccount);
        return actualSid is not null && requestedSid is not null && actualSid.Equals(requestedSid);
    }

    public static bool SidMatchesAccount(SecurityIdentifier? sid, string? requestedAccount)
    {
        if (sid is null || string.IsNullOrWhiteSpace(requestedAccount))
        {
            return false;
        }

        var requestedSid = ResolveSid(requestedAccount);
        return requestedSid is not null && sid.Equals(requestedSid);
    }

    public static bool VerifyLogon(string domain, string username, string password)
    {
        if (string.IsNullOrWhiteSpace(username) || password is null)
        {
            return false;
        }

        try
        {
            if (!LogonUser(username, domain ?? "", password, Logon32LogonInteractive,
                    Logon32ProviderDefault, out var token))
            {
                return false;
            }

            token.Dispose();
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static (string Domain, string Username) SplitAccount(string account)
    {
        var value = (account ?? "").Trim();
        var slash = value.IndexOf('\\');
        if (slash > 0 && slash < value.Length - 1)
        {
            return (value[..slash], value[(slash + 1)..]);
        }

        return ("", value);
    }

    public static string CombineAccount(string domain, string username)
    {
        var user = (username ?? "").Trim();
        var dom = (domain ?? "").Trim().TrimEnd('\\');
        return string.IsNullOrEmpty(dom) ? user : $"{dom}\\{user}";
    }

    private static SecurityIdentifier? ResolveSid(string account)
    {
        var value = Normalize(account);
        try
        {
            if (value.StartsWith("S-1-", StringComparison.OrdinalIgnoreCase))
            {
                return new SecurityIdentifier(value);
            }

            return (SecurityIdentifier)new NTAccount(value).Translate(typeof(SecurityIdentifier));
        }
        catch (Exception ex) when (ex is ArgumentException or IdentityNotMappedException or SystemException)
        {
            return null;
        }
    }

    private static string Normalize(string value) => value.Trim().Replace('/', '\\');
}
