using System.Net.Http;
using System.Net.Http.Json;
using System.Text.Json;

namespace BandKeyTray;

internal sealed record PairingInfo(string DeviceName, string Pin, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);

internal sealed record DeviceInfo(string DeviceId, string Name, DateTimeOffset CreatedAt);

internal sealed record ServiceStatus(
    bool Ready,
    bool CredentialSet,
    bool CredentialRejected,
    int PairedCount,
    bool CredentialProviderConnected,
    bool LanTcpListening,
    bool LanUdpListening,
    int LanTcpPort,
    int LanUdpPort,
    bool DevMode);

internal sealed class ServiceApi
{
    private static readonly HttpClient Http = new()
    {
        BaseAddress = new Uri("http://127.0.0.1:23234/"),
        Timeout = TimeSpan.FromSeconds(5)
    };

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public async Task<bool> IsOnlineAsync()
    {
        try
        {
            var text = await Http.GetStringAsync("health");
            return text.Trim() == "ok";
        }
        catch
        {
            return false;
        }
    }

    public async Task<ServiceStatus?> GetStatusAsync()
    {
        var text = await Http.GetStringAsync("status");
        return JsonSerializer.Deserialize<ServiceStatus>(text, JsonOptions);
    }

    public async Task<PairingInfo?> GetPairingAsync()
    {
        var text = await Http.GetStringAsync("pairing");
        if (string.IsNullOrWhiteSpace(text) || text.Trim() == "null")
        {
            return null;
        }

        return JsonSerializer.Deserialize<PairingInfo>(text, JsonOptions);
    }

    public async Task<List<DeviceInfo>> GetDevicesAsync()
    {
        var text = await Http.GetStringAsync("devices");
        return JsonSerializer.Deserialize<List<DeviceInfo>>(text, JsonOptions) ?? new List<DeviceInfo>();
    }

    public async Task DeleteDeviceAsync(string deviceId)
    {
        using var response = await Http.DeleteAsync("devices/" + Uri.EscapeDataString(deviceId));
        response.EnsureSuccessStatusCode();
    }

    public async Task SaveCredentialAsync(string username, string password)
    {
        using var response = await Http.PostAsJsonAsync("credential", new { username, password });
        if (response.IsSuccessStatusCode)
        {
            return;
        }

        var body = await response.Content.ReadAsStringAsync();
        if (string.IsNullOrWhiteSpace(body))
        {
            throw new InvalidOperationException($"HTTP {(int)response.StatusCode}");
        }

        try
        {
            using var document = JsonDocument.Parse(body);
            if (document.RootElement.TryGetProperty("message", out var message)
                && !string.IsNullOrWhiteSpace(message.GetString()))
            {
                throw new InvalidOperationException(message.GetString());
            }
        }
        catch (JsonException)
        {
            // 非 JSON 响应直接展示原文
        }

        throw new InvalidOperationException(body);
    }
}
