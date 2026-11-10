using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace BandKeyService;

public sealed class UnlockVerifier
{
    private static readonly TimeSpan MaxSkew = TimeSpan.FromSeconds(120);
    private const long NonceTtlSeconds = 300;

    private readonly DeviceStore _store;
    private readonly ConcurrentDictionary<string, long> _nonces = new();

    public UnlockVerifier(DeviceStore store) => _store = store;

    public (bool Ok, string Error) Verify(UnlockRequest request)
    {
        if (string.IsNullOrWhiteSpace(request.DeviceId))
        {
            return (false, "missing_device_id");
        }

        var device = _store.Find(request.DeviceId);
        if (device is null)
        {
            return (false, "unknown_device");
        }

        var now = DateTimeOffset.UtcNow;
        DateTimeOffset timestamp;
        try
        {
            timestamp = DateTimeOffset.FromUnixTimeSeconds(request.Timestamp);
        }
        catch (ArgumentOutOfRangeException)
        {
            return (false, "timestamp_out_of_range");
        }

        if (Math.Abs((now - timestamp).TotalSeconds) > MaxSkew.TotalSeconds)
        {
            return (false, "timestamp_out_of_range");
        }

        if (string.IsNullOrWhiteSpace(request.Nonce) || request.Nonce.Length is < 8 or > 128)
        {
            return (false, "invalid_nonce");
        }

        var data = $"unlock:{request.DeviceId}:{request.Nonce}:{request.Timestamp}";
        if (string.IsNullOrWhiteSpace(request.Signature))
        {
            return (false, "bad_signature");
        }

        try
        {
            if (!CryptoUtil.VerifySignature(device.PublicKey, data, request.Signature))
            {
                return (false, "bad_signature");
            }
        }
        catch (Exception ex) when (ex is FormatException or CryptographicException or ArgumentException)
        {
            return (false, "bad_signature");
        }

        PruneNonces(now.ToUnixTimeSeconds());
        if (!_nonces.TryAdd(request.Nonce, now.ToUnixTimeSeconds()))
        {
            return (false, "replayed_nonce");
        }

        return (true, string.Empty);
    }

    private void PruneNonces(long nowSeconds)
    {
        foreach (var kv in _nonces)
        {
            if (nowSeconds - kv.Value > NonceTtlSeconds)
            {
                _nonces.TryRemove(kv.Key, out _);
            }
        }
    }
}
