using System.IO.Pipes;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace BandKeyTray;

internal sealed record CredentialSaveResult(bool Success, string Error, bool PipeUnavailable);

internal static class TrayPipeClient
{
    private const string PipeName = "BandKeyTrayPipe";

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public static async Task<bool> IsServiceOnlineAsync()
    {
        try
        {
            await GetStatusAsync(TimeSpan.FromMilliseconds(750)).ConfigureAwait(false);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public static async Task<ServiceStatus?> GetStatusAsync(TimeSpan? timeout = null)
    {
        var line = await SendRawAsync(
            new { cmd = "GET_STATUS" },
            timeout ?? TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        return JsonSerializer.Deserialize<ServiceStatus>(line, JsonOptions);
    }

    public static async Task<PairingInfo?> GetPairingAsync()
    {
        var line = await SendRawAsync(
            new { cmd = "GET_PAIRING" }, TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        using var document = JsonDocument.Parse(line);
        if (!document.RootElement.TryGetProperty("pairing", out var pairingElement)
            || pairingElement.ValueKind == JsonValueKind.Null)
        {
            return null;
        }

        return JsonSerializer.Deserialize<PairingInfo>(
            pairingElement.GetRawText(), JsonOptions);
    }

    public static async Task<List<DeviceInfo>> GetDevicesAsync()
    {
        var line = await SendRawAsync(
            new { cmd = "LIST_DEVICES" }, TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        using var document = JsonDocument.Parse(line);
        if (!document.RootElement.TryGetProperty("devices", out var devicesElement))
        {
            return new List<DeviceInfo>();
        }

        return JsonSerializer.Deserialize<List<DeviceInfo>>(
            devicesElement.GetRawText(), JsonOptions) ?? new List<DeviceInfo>();
    }

    public static async Task DeleteDeviceAsync(string deviceId)
    {
        var line = await SendRawAsync(
            new { cmd = "DELETE_DEVICE", deviceId }, TimeSpan.FromSeconds(5)).ConfigureAwait(false);
        using var document = JsonDocument.Parse(line);
        var root = document.RootElement;
        if (root.TryGetProperty("success", out var successElement)
            && successElement.ValueKind == JsonValueKind.True)
        {
            return;
        }

        var error = root.TryGetProperty("error", out var errorElement)
            ? errorElement.GetString() ?? "delete_failed"
            : "delete_failed";
        throw new InvalidOperationException(error);
    }

    public static async Task<string> RpcAsync(string request)
    {
        var line = await SendRawAsync(
            new { cmd = "RPC", request }, TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        using var document = JsonDocument.Parse(line);
        if (document.RootElement.TryGetProperty("reply", out var replyElement)
            && replyElement.ValueKind == JsonValueKind.String)
        {
            return replyElement.GetString() ?? "";
        }

        throw new InvalidOperationException("服务返回的 RPC 响应无效");
    }

    public static async Task<CredentialSaveResult> SaveCredentialAsync(
        string domain, string username, string password)
    {
        try
        {
            var line = await SendRawAsync(new
            {
                cmd = "SAVE_CREDENTIAL",
                domain,
                username,
                password
            }, TimeSpan.FromSeconds(5)).ConfigureAwait(false);

            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            var success = root.TryGetProperty("success", out var successElement)
                          && successElement.GetBoolean();
            var error = root.TryGetProperty("error", out var errorElement)
                ? errorElement.GetString() ?? ""
                : "";
            return new CredentialSaveResult(success, error, false);
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or OperationCanceledException)
        {
            return new CredentialSaveResult(false, ex.Message, true);
        }
        catch (Exception ex)
        {
            return new CredentialSaveResult(false, ex.Message, false);
        }
    }

    private static async Task<string> SendRawAsync(object request, TimeSpan timeout)
    {
        using var pipe = new NamedPipeClientStream(
            ".",
            PipeName,
            PipeDirection.InOut,
            PipeOptions.Asynchronous,
            TokenImpersonationLevel.Impersonation);
        using var cts = new CancellationTokenSource(timeout);
        await pipe.ConnectAsync(cts.Token).ConfigureAwait(false);

        var payload = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(request) + "\n");
        await pipe.WriteAsync(payload, cts.Token).ConfigureAwait(false);
        await pipe.FlushAsync(cts.Token).ConfigureAwait(false);

        using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
        var line = await reader.ReadLineAsync(cts.Token).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(line))
        {
            throw new IOException("服务没有返回结果");
        }

        using var document = JsonDocument.Parse(line);
        var root = document.RootElement;
        if (root.TryGetProperty("event", out var eventElement)
            && string.Equals(eventElement.GetString(), "ERROR", StringComparison.Ordinal))
        {
            var code = root.TryGetProperty("code", out var codeElement)
                ? codeElement.GetString() ?? "error"
                : "error";
            var message = root.TryGetProperty("message", out var messageElement)
                ? messageElement.GetString() ?? "服务返回错误"
                : "服务返回错误";
            throw new InvalidOperationException($"{code}: {message}");
        }

        return line;
    }
}
