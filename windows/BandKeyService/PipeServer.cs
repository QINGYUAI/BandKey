using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace BandKeyService;

/// <summary>
/// 三条命名管道：
/// - BandKeyPipe：凭据提供程序专用，双向，SYSTEM 全权限。
/// - BandKeyTrayPipe：托盘专用，双向，允许已认证用户读写，用于安全保存凭据。
/// - BandKeyUnlock：旧版单向通知管道，保留兼容，避免升级后解锁链路中断。
/// </summary>
public sealed class PipeServer : IAsyncDisposable
{
    public const string CpPipeName = "BandKeyPipe";
    public const string TrayPipeName = "BandKeyTrayPipe";
    public const string LegacyPipeName = "BandKeyUnlock";

    private static readonly Encoding Utf8 = new UTF8Encoding(false);

    private readonly AutoUnlockState _state;
    private readonly CredentialStore _credentials;
    private readonly bool _devMode;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _gate = new();
    private readonly List<NamedPipeServerStream> _legacyClients = new();
    private CpConnection? _cpConnection;
    private Task? _cpLoop;
    private Task? _trayLoop;
    private Task? _legacyLoop;

    public PipeServer(AutoUnlockState state, CredentialStore credentials, bool devMode = false)
    {
        _state = state;
        _credentials = credentials;
        _devMode = devMode;
        _state.AutoUnlockRequested += OnAutoUnlockRequested;
        _state.UnlockReadyChanged += OnUnlockReadyChanged;
    }

    public bool IsCredentialProviderConnected
    {
        get
        {
            lock (_gate)
            {
                return _cpConnection is not null;
            }
        }
    }

    public void Start()
    {
        _cpLoop = Task.Run(() => RunCpLoopAsync(_cts.Token));
        _trayLoop = Task.Run(() => RunTrayLoopAsync(_cts.Token));
        // 开发模式不注册旧管道，避免与已安装服务占用同名管道。
        _legacyLoop = _devMode ? Task.CompletedTask : Task.Run(() => RunLegacyLoopAsync(_cts.Token));
    }

    public void BroadcastReady()
    {
        SendToCp($"UNLOCK_READY:{(_state.UnlockReady ? "true" : "false")}");
    }

    private void OnUnlockReadyChanged(bool ready)
    {
        SendToCp($"UNLOCK_READY:{(ready ? "true" : "false")}");
    }

    private void OnAutoUnlockRequested()
    {
        var requestId = _state.PendingAutoUnlockRequestId;
        if (requestId is null)
        {
            return;
        }

        if (SendToCp($"AUTO_UNLOCK:{requestId.Value}"))
        {
            Console.WriteLine($"[pipe] AUTO_UNLOCK:{requestId.Value} delivered");
        }
        else
        {
            _state.CancelPendingAutoUnlock();
            Console.WriteLine("[pipe] AUTO_UNLOCK canceled: credential provider not connected");
        }

        SendLegacyUnlock();
    }

    private async Task RunCpLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = CreatePipe(CpPipeName, PipeDirection.InOut, 1, PipeOptions.FirstPipeInstance,
                    systemOnly: !_devMode);
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);

                var connection = new CpConnection(pipe);
                lock (_gate)
                {
                    _cpConnection = connection;
                }

                Console.WriteLine("[pipe] credential provider connected");
                SendToCp($"UNLOCK_READY:{(_state.UnlockReady ? "true" : "false")}");

                var pending = _state.PendingAutoUnlockRequestId;
                if (pending is not null && !SendToCp($"AUTO_UNLOCK:{pending.Value}"))
                {
                    _state.CancelPendingAutoUnlock();
                }

                using var reader = new StreamReader(pipe, Utf8, detectEncodingFromByteOrderMarks: false,
                    bufferSize: 1024, leaveOpen: true);
                while (!ct.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                    if (line is null)
                    {
                        break;
                    }

                    HandleCpLine(line.Trim());
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[pipe] CP error: {ex.Message}");
                try
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
            finally
            {
                var wasCurrent = false;
                lock (_gate)
                {
                    if (_cpConnection?.Stream == pipe)
                    {
                        _cpConnection = null;
                        wasCurrent = true;
                    }
                }

                if (wasCurrent)
                {
                    _state.CancelPendingAutoUnlock();
                    Console.WriteLine("[pipe] credential provider disconnected");
                }

                pipe?.Dispose();
            }
        }
    }

    private void HandleCpLine(string line)
    {
        if (line == "PASSWORD_FAILED")
        {
            Console.WriteLine("[pipe] credential provider reported PASSWORD_FAILED");
            _state.MarkPasswordFailed();
            return;
        }

        const string ackPrefix = "AUTO_UNLOCK_ACK:";
        if (line.StartsWith(ackPrefix, StringComparison.Ordinal)
            && long.TryParse(line[ackPrefix.Length..], out var requestId)
            && requestId > 0)
        {
            var matched = _state.TryAcknowledge(requestId);
            Console.WriteLine($"[pipe] AUTO_UNLOCK_ACK:{requestId} matched={matched}");
        }
    }

    private bool SendToCp(string line)
    {
        CpConnection? connection;
        lock (_gate)
        {
            connection = _cpConnection;
        }

        if (connection is null || connection.TryWriteLine(line))
        {
            return connection is not null;
        }

        lock (_gate)
        {
            if (ReferenceEquals(_cpConnection, connection))
            {
                _cpConnection = null;
            }
        }

        connection.Dispose();
        _state.CancelPendingAutoUnlock();
        return false;
    }

    private async Task RunLegacyLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = CreatePipe(LegacyPipeName, PipeDirection.Out,
                    NamedPipeServerStream.MaxAllowedServerInstances, PipeOptions.None, systemOnly: true);
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);
                lock (_gate)
                {
                    _legacyClients.Add(pipe);
                }

                Console.WriteLine("[pipe] legacy credential provider connected");
                while (!ct.IsCancellationRequested && pipe.IsConnected)
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[pipe] legacy error: {ex.Message}");
                try
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
            finally
            {
                if (pipe is not null)
                {
                    lock (_gate)
                    {
                        _legacyClients.Remove(pipe);
                    }

                    pipe.Dispose();
                }
            }
        }
    }

    private void SendLegacyUnlock()
    {
        NamedPipeServerStream[] clients;
        lock (_gate)
        {
            clients = _legacyClients.ToArray();
        }

        var payload = Encoding.UTF8.GetBytes("UNLOCK\n");
        foreach (var client in clients)
        {
            try
            {
                client.Write(payload, 0, payload.Length);
                client.Flush();
            }
            catch
            {
                lock (_gate)
                {
                    _legacyClients.Remove(client);
                }

                client.Dispose();
            }
        }
    }

    private async Task RunTrayLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = CreateTrayPipe();
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);
                var connected = pipe;
                pipe = null;
                _ = Task.Run(() => HandleTrayConnectionAsync(connected, ct), ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[pipe] tray accept error: {ex.Message}");
                try
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
            finally
            {
                pipe?.Dispose();
            }
        }
    }

    private async Task HandleTrayConnectionAsync(NamedPipeServerStream pipe, CancellationToken ct)
    {
        try
        {
            using var reader = new StreamReader(pipe, Utf8, false, 4096, leaveOpen: true);
            using var writer = new StreamWriter(pipe, Utf8, 4096, leaveOpen: true) { AutoFlush = true };

            var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
            if (line is null)
            {
                return;
            }

            string clientIdentity;
            try
            {
                clientIdentity = pipe.GetImpersonationUserName();
            }
            catch
            {
                clientIdentity = "";
            }

            using var document = JsonDocument.Parse(line);
            var command = document.RootElement.TryGetProperty("cmd", out var cmdElement)
                ? cmdElement.GetString()
                : null;

            if (command == "SAVE_CREDENTIAL")
            {
                var domain = document.RootElement.TryGetProperty("domain", out var domainElement)
                    ? domainElement.GetString() ?? ""
                    : "";
                var username = document.RootElement.TryGetProperty("username", out var userElement)
                    ? userElement.GetString() ?? ""
                    : "";
                var password = document.RootElement.TryGetProperty("password", out var passwordElement)
                    ? passwordElement.GetString() ?? ""
                    : "";

                var requested = WindowsIdentityUtil.CombineAccount(domain, username);
                var error = "";
                var success = false;

                if (!WindowsIdentityUtil.IdentityMatches(clientIdentity, requested))
                {
                    error = CredentialSaveResult.UserMismatch.ToString();
                }
                else if (!WindowsIdentityUtil.VerifyLogon(domain, username, password))
                {
                    error = CredentialSaveResult.InvalidPassword.ToString();
                }
                else
                {
                    var result = _credentials.SaveVerified(domain, username, password);
                    success = result == CredentialSaveResult.Saved;
                    error = success ? "" : result.ToString();
                    if (success)
                    {
                        _state.ClearPasswordFailure();
                        _state.NotifyBaseStateChanged();
                    }
                }

                await writer.WriteLineAsync(JsonSerializer.Serialize(new
                {
                    @event = "CREDENTIAL_SAVED",
                    success,
                    error
                })).ConfigureAwait(false);
            }
            else if (command == "GET_STATUS")
            {
                await writer.WriteLineAsync(JsonSerializer.Serialize(new
                {
                    @event = "STATUS",
                    ready = _state.UnlockReady,
                    credentialSet = _credentials.IsUsable,
                    credentialRejected = _state.CredentialRejected,
                    credentialProviderConnected = IsCredentialProviderConnected
                })).ConfigureAwait(false);
            }

            while (!ct.IsCancellationRequested && pipe.IsConnected)
            {
                await Task.Delay(1000, ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // 正常关闭
        }
        catch (Exception ex)
        {
            Console.WriteLine($"[pipe] tray connection error: {ex.Message}");
        }
        finally
        {
            pipe.Dispose();
        }
    }

    private static NamedPipeServerStream CreatePipe(string name, PipeDirection direction, int maxInstances,
        PipeOptions extraOptions, bool systemOnly)
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));

        if (!systemOnly)
        {
            security.AddAccessRule(new PipeAccessRule(
                new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
                PipeAccessRights.ReadWrite,
                AccessControlType.Allow));
        }

        return NamedPipeServerStreamAcl.Create(
            name,
            direction,
            maxInstances,
            PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous | extraOptions,
            4096,
            4096,
            security);
    }

    private NamedPipeServerStream CreateTrayPipe()
    {
        var security = new PipeSecurity();
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
            PipeAccessRights.FullControl,
            AccessControlType.Allow));
        security.AddAccessRule(new PipeAccessRule(
            new SecurityIdentifier(WellKnownSidType.AuthenticatedUserSid, null),
            // 生产环境由 SYSTEM 创建实例；开发模式需要允许当前用户继续创建实例。
            _devMode ? PipeAccessRights.FullControl : PipeAccessRights.ReadWrite,
            AccessControlType.Allow));

        return NamedPipeServerStreamAcl.Create(
            TrayPipeName,
            PipeDirection.InOut,
            NamedPipeServerStream.MaxAllowedServerInstances,
            PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous,
            4096,
            4096,
            security);
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        lock (_gate)
        {
            _cpConnection?.Dispose();
            _cpConnection = null;
            foreach (var client in _legacyClients)
            {
                client.Dispose();
            }

            _legacyClients.Clear();
        }

        var tasks = new[] { _cpLoop, _trayLoop, _legacyLoop }
            .Where(t => t is not null)
            .Cast<Task>()
            .ToArray();
        if (tasks.Length > 0)
        {
            try
            {
                await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
            }
            catch
            {
                // 停止超时不阻塞服务退出
            }
        }

        _cts.Dispose();
    }

    private sealed class CpConnection : IDisposable
    {
        private readonly object _writeGate = new();

        public CpConnection(NamedPipeServerStream stream) => Stream = stream;

        public NamedPipeServerStream Stream { get; }

        public bool TryWriteLine(string line)
        {
            var payload = Utf8.GetBytes(line + "\n");
            lock (_writeGate)
            {
                try
                {
                    Stream.Write(payload, 0, payload.Length);
                    Stream.Flush();
                    return true;
                }
                catch
                {
                    return false;
                }
            }
        }

        public void Dispose()
        {
            try
            {
                Stream.Dispose();
            }
            catch
            {
                // 忽略关闭异常
            }
        }
    }
}
