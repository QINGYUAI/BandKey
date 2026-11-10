using System.IO.Pipes;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace BandKeyService;

/// <summary>
/// 三条命名管道：
/// - BandKeyPipe：凭据提供程序专用，双向，SYSTEM 全权限。
/// - BandKeyTrayPipe：托盘专用，双向，允许已认证用户读写，用于安全保存凭据、
///   查询状态、管理设备和转发 BLE RPC。
/// - BandKeyUnlock：旧版单向通知管道，保留兼容，避免升级后解锁链路中断。
/// </summary>
public sealed class PipeServer : IAsyncDisposable
{
    public const string CpPipeName = "BandKeyPipe";
    public const string TrayPipeName = "BandKeyTrayPipe";
    public const string LegacyPipeName = "BandKeyUnlock";

    private static readonly Encoding Utf8 = new UTF8Encoding(false);

    private readonly DeviceStore _store;
    private readonly PairingManager _pairing;
    private readonly AutoUnlockState _state;
    private readonly CredentialStore _credentials;
    private readonly UnlockActivityState _activity;
    private readonly UnlockOutcomeStore _outcomes;
    private readonly bool _devMode;
    private readonly string _cpPipeName;
    private readonly string _trayPipeName;
    private readonly string _legacyPipeName;
    private readonly CancellationTokenSource _cts = new();
    private readonly object _gate = new();
    private readonly List<NamedPipeServerStream> _legacyClients = new();
    private CpConnection? _cpConnection;
    private Task? _cpLoop;
    private Task? _trayLoop;
    private Task? _legacyLoop;
    private Func<string, string>? _rpcHandler;
    private LanListener? _lan;

    public PipeServer(
        DeviceStore store,
        PairingManager pairing,
        AutoUnlockState state,
        CredentialStore credentials,
        UnlockActivityState activity,
        UnlockOutcomeStore outcomes,
        bool devMode = false,
        string? pipeSuffix = null)
    {
        _store = store;
        _pairing = pairing;
        _state = state;
        _credentials = credentials;
        _activity = activity;
        _outcomes = outcomes;
        _devMode = devMode;
        var suffix = string.IsNullOrWhiteSpace(pipeSuffix) ? "" : pipeSuffix.Trim();
        _cpPipeName = CpPipeName + suffix;
        _trayPipeName = TrayPipeName + suffix;
        _legacyPipeName = LegacyPipeName + suffix;
        _state.AutoUnlockRequested += OnAutoUnlockRequested;
        _state.UnlockReadyChanged += OnUnlockReadyChanged;
    }

    public void SetRpcHandler(Func<string, string> handler) => _rpcHandler = handler;

    public void SetLanListener(LanListener lan) => _lan = lan;

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
            ServiceLog.Write($"[pipe] AUTO_UNLOCK:{requestId.Value} delivered");
        }
        else
        {
            var failedRequestId = requestId.Value;
            _state.CancelPendingAutoUnlock();
            _outcomes.MarkCompleted(failedRequestId, false, "component_not_connected");
            ServiceLog.Write("[pipe] AUTO_UNLOCK canceled: credential provider not connected");
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
                pipe = CreatePipe(_cpPipeName, PipeDirection.InOut, 1, PipeOptions.FirstPipeInstance,
                    systemOnly: !_devMode);
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);

                var connection = new CpConnection(pipe);
                lock (_gate)
                {
                    _cpConnection = connection;
                }

                ServiceLog.Write("[pipe] credential provider connected");
                SendToCp($"UNLOCK_READY:{(_state.UnlockReady ? "true" : "false")}");

                var pending = _state.PendingAutoUnlockRequestId;
                if (pending is not null && !SendToCp($"AUTO_UNLOCK:{pending.Value}"))
                {
                    _state.CancelPendingAutoUnlock();
                    _outcomes.FailOutstanding("component_not_connected");
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
                ServiceLog.Write($"[pipe] CP error: {ex.Message}");
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
                    _outcomes.MarkDisconnected("component_disconnected");

                    ServiceLog.Write("[pipe] credential provider disconnected");
                }

                pipe?.Dispose();
            }
        }
    }

    private void HandleCpLine(string line)
    {
        if (line == "PASSWORD_FAILED")
        {
            ServiceLog.Write("[pipe] credential provider reported PASSWORD_FAILED");
            _state.MarkPasswordFailed();
            return;
        }

        const string ackPrefix = "AUTO_UNLOCK_ACK:";
        if (line.StartsWith(ackPrefix, StringComparison.Ordinal)
            && long.TryParse(line[ackPrefix.Length..], out var requestId)
            && requestId > 0)
        {
            var matched = _state.TryAcknowledge(requestId);
            ServiceLog.Write($"[pipe] AUTO_UNLOCK_ACK:{requestId} matched={matched}");
            return;
        }

        const string submittedPrefix = "AUTO_UNLOCK_SUBMITTED:";
        if (line.StartsWith(submittedPrefix, StringComparison.Ordinal)
            && long.TryParse(line[submittedPrefix.Length..], out var submittedRequestId)
            && submittedRequestId > 0)
        {
            _outcomes.MarkSubmitted(submittedRequestId);
            ServiceLog.Write($"[pipe] AUTO_UNLOCK_SUBMITTED:{submittedRequestId}");
            return;
        }

        const string resultPrefix = "UNLOCK_RESULT:";
        if (line.StartsWith(resultPrefix, StringComparison.Ordinal))
        {
            var parts = line[resultPrefix.Length..].Split(':', 2);
            if (parts.Length == 2
                && long.TryParse(parts[0], out var completedRequestId)
                && completedRequestId > 0)
            {
                var result = parts[1].Trim();
                var success = string.Equals(result, "success", StringComparison.OrdinalIgnoreCase);
                _outcomes.MarkCompleted(completedRequestId, success, success ? null : result);
                ServiceLog.Write($"[pipe] UNLOCK_RESULT:{completedRequestId}:{result}");
            }
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
        _outcomes.MarkDisconnected("component_disconnected");

        return false;
    }

    private async Task RunLegacyLoopAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            NamedPipeServerStream? pipe = null;
            try
            {
                pipe = CreatePipe(_legacyPipeName, PipeDirection.Out,
                    NamedPipeServerStream.MaxAllowedServerInstances, PipeOptions.None, systemOnly: true);
                await pipe.WaitForConnectionAsync(ct).ConfigureAwait(false);
                lock (_gate)
                {
                    _legacyClients.Add(pipe);
                }

                ServiceLog.Write("[pipe] legacy credential provider connected");
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
                ServiceLog.Write($"[pipe] legacy error: {ex.Message}");
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
                ServiceLog.Write($"[pipe] tray accept error: {ex.Message}");
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
            var root = document.RootElement;
            var command = root.TryGetProperty("cmd", out var cmdElement)
                ? cmdElement.GetString()
                : null;

            if (!IsTrustedTrayClient(clientIdentity))
            {
                ServiceLog.Write($"[pipe] tray client rejected: '{clientIdentity}'");
                await WriteTrayErrorAsync(writer, "forbidden", "当前会话无权管理电脑钥匙服务")
                    .ConfigureAwait(false);
                return;
            }

            switch (command)
            {
                case "SAVE_CREDENTIAL":
                    await HandleSaveCredentialAsync(writer, root, clientIdentity).ConfigureAwait(false);
                    break;

                case "GET_STATUS":
                    await WriteTrayJsonAsync(writer, BuildStatusResponse()).ConfigureAwait(false);
                    break;

                case "GET_PAIRING":
                    await WriteTrayJsonAsync(writer, new
                    {
                        @event = "PAIRING",
                        pairing = _pairing.Current()
                    }).ConfigureAwait(false);
                    break;

                case "LIST_DEVICES":
                    await WriteTrayJsonAsync(writer, new
                    {
                        @event = "DEVICES",
                        devices = _store.Devices
                            .Select(d => new { d.DeviceId, d.Name, d.CreatedAt })
                            .ToList()
                    }).ConfigureAwait(false);
                    break;

                case "DELETE_DEVICE":
                {
                    var deviceId = root.TryGetProperty("deviceId", out var idElement)
                        ? idElement.GetString()
                        : null;
                    if (string.IsNullOrWhiteSpace(deviceId))
                    {
                        await WriteTrayErrorAsync(writer, "invalid_request", "缺少 deviceId")
                            .ConfigureAwait(false);
                        break;
                    }

                    bool removed;
                    try
                    {
                        removed = _store.Remove(deviceId);
                    }
                    catch (Exception ex)
                    {
                        ServiceLog.Write($"[pipe] delete device failed: {ex.Message}");
                        await WriteTrayJsonAsync(writer, new
                        {
                            @event = "DEVICE_DELETED",
                            success = false,
                            error = "write_failed"
                        }).ConfigureAwait(false);
                        break;
                    }

                    if (removed)
                    {
                        _state.NotifyBaseStateChanged();
                    }

                    await WriteTrayJsonAsync(writer, new
                    {
                        @event = "DEVICE_DELETED",
                        success = removed,
                        error = removed ? "" : "not_found"
                    }).ConfigureAwait(false);
                    break;
                }

                case "RPC":
                {
                    var request = root.TryGetProperty("request", out var requestElement)
                        ? requestElement.GetString()
                        : null;
                    var handler = _rpcHandler;
                    if (string.IsNullOrWhiteSpace(request) || handler is null)
                    {
                        await WriteTrayErrorAsync(writer, "invalid_request", "RPC 请求无效")
                            .ConfigureAwait(false);
                        break;
                    }

                    string reply;
                    try
                    {
                        reply = handler(request);
                    }
                    catch (Exception ex)
                    {
                        ServiceLog.Write($"[pipe] RPC failed: {ex.Message}");
                        reply = JsonSerializer.Serialize(new
                        {
                            type = "error",
                            payload = new { code = "internal", message = ex.Message }
                        });
                    }

                    await WriteTrayJsonAsync(writer, new
                    {
                        @event = "RPC_RESULT",
                        reply
                    }).ConfigureAwait(false);
                    break;
                }

                default:
                    await WriteTrayErrorAsync(writer, "unknown_command", $"未知命令: {command}")
                        .ConfigureAwait(false);
                    break;
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
        catch (JsonException)
        {
            ServiceLog.Write("[pipe] tray connection received bad json");
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[pipe] tray connection error: {ex.Message}");
        }
        finally
        {
            pipe.Dispose();
        }
    }

    private object BuildStatusResponse()
    {
        var recent = _activity.Snapshot();
        return new
        {
            @event = "STATUS",
            ready = _state.UnlockReady,
            credentialSet = _credentials.IsUsable,
            credentialRejected = _state.CredentialRejected,
            pairedCount = _store.Devices.Count,
            credentialProviderConnected = IsCredentialProviderConnected,
            pcName = Environment.MachineName,
            serverVersion = typeof(PipeServer).Assembly.GetName().Version?.ToString(3) ?? "0.0.0",
            protocolVersion = MessageTypes.ProtocolVersion,
            isLocked = IsCredentialProviderConnected,
            lanTcpListening = _lan?.TcpListening ?? false,
            lanUdpListening = _lan?.UdpListening ?? false,
            lanTcpPort = LanListener.TcpPort,
            lanUdpPort = LanListener.UdpPort,
            lastUnlockMethod = recent.LastUnlockMethod,
            nfcKeyHint = recent.NfcKeyHint,
            nfcLastUsedAt = recent.NfcLastUsedAt,
            devMode = _devMode
        };
    }

    private async Task HandleSaveCredentialAsync(
        StreamWriter writer,
        JsonElement root,
        string clientIdentity)
    {
        var domain = root.TryGetProperty("domain", out var domainElement)
            ? domainElement.GetString() ?? ""
            : "";
        var username = root.TryGetProperty("username", out var userElement)
            ? userElement.GetString() ?? ""
            : "";
        var password = root.TryGetProperty("password", out var passwordElement)
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

        await WriteTrayJsonAsync(writer, new
        {
            @event = "CREDENTIAL_SAVED",
            success,
            error
        }).ConfigureAwait(false);
    }

    private bool IsTrustedTrayClient(string identity)
    {
        if (_devMode)
        {
            return true;
        }

        var activeSid = WindowsIdentityUtil.GetActiveConsoleUserSid()
                        ?? WindowsIdentityUtil.GetCurrentProcessUserSid();
        return WindowsIdentityUtil.SidMatchesAccount(activeSid, identity);
    }

    private static Task WriteTrayJsonAsync(StreamWriter writer, object value)
        => writer.WriteLineAsync(JsonSerializer.Serialize(value));

    private static Task WriteTrayErrorAsync(StreamWriter writer, string code, string message)
        => WriteTrayJsonAsync(writer, new { @event = "ERROR", code, message });

    private static NamedPipeServerStream CreatePipe(string name, PipeDirection direction, int maxInstances,
        PipeOptions extraOptions, bool systemOnly)
    {
        var security = new PipeSecurity();
        AddServiceAccountFullControl(security);
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
        AddServiceAccountFullControl(security);
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
            _trayPipeName,
            PipeDirection.InOut,
            NamedPipeServerStream.MaxAllowedServerInstances,
            PipeTransmissionMode.Byte,
            PipeOptions.Asynchronous,
            4096,
            4096,
            security);
    }

    private static void AddServiceAccountFullControl(PipeSecurity security)
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            var sid = identity.User;
            if (sid is null
                || sid.IsWellKnown(WellKnownSidType.LocalSystemSid)
                || sid.IsWellKnown(WellKnownSidType.BuiltinAdministratorsSid))
            {
                return;
            }

            security.AddAccessRule(new PipeAccessRule(
                sid, PipeAccessRights.FullControl, AccessControlType.Allow));
        }
        catch
        {
            // 获取当前账号失败时保留默认 ACL
        }
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
