namespace BandKeyTray;

internal sealed record PairingInfo(string DeviceName, string Pin, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);

internal sealed record DeviceInfo(
    string DeviceId,
    string Name,
    DateTimeOffset CreatedAt,
    DateTimeOffset? LastPairedAt = null,
    bool InstallIdBound = false);

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

internal sealed record ServiceSnapshot(ServiceStatus? Status, PairingInfo? Pairing);

internal sealed class ServiceApi
{
    public Task<bool> IsOnlineAsync() => TrayPipeClient.IsServiceOnlineAsync();

    public Task<ServiceSnapshot?> GetSnapshotAsync(TimeSpan? timeout = null)
        => TrayPipeClient.GetSnapshotAsync(timeout);

    public Task<ServiceStatus?> GetStatusAsync() => TrayPipeClient.GetStatusAsync();

    public Task<PairingInfo?> GetPairingAsync() => TrayPipeClient.GetPairingAsync();

    public Task<List<DeviceInfo>> GetDevicesAsync() => TrayPipeClient.GetDevicesAsync();

    public Task DeleteDeviceAsync(string deviceId) => TrayPipeClient.DeleteDeviceAsync(deviceId);
}
