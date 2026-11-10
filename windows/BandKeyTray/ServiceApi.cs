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
    string? LastUnlockMethod,
    string? NfcKeyHint,
    long? NfcLastUsedAt,
    bool DevMode);

internal sealed class ServiceApi
{
    public Task<bool> IsOnlineAsync() => TrayPipeClient.IsServiceOnlineAsync();

    public Task<ServiceStatus?> GetStatusAsync() => TrayPipeClient.GetStatusAsync();

    public Task<PairingInfo?> GetPairingAsync() => TrayPipeClient.GetPairingAsync();

    public Task<List<DeviceInfo>> GetDevicesAsync() => TrayPipeClient.GetDevicesAsync();

    public Task DeleteDeviceAsync(string deviceId) => TrayPipeClient.DeleteDeviceAsync(deviceId);
}
