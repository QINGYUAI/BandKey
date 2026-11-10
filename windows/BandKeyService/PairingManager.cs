using System.Collections.Concurrent;
using System.Security.Cryptography;
using System.Text;

namespace BandKeyService;

public sealed record PendingPair(string Pin, string DeviceName, string PublicKey, DateTimeOffset CreatedAt);

public sealed record PairingInfo(string DeviceName, string Pin, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);

public sealed class PairingManager
{
    private static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);
    private const int MaxPinAttempts = 5;
    private const int MaxPendingPairings = 16;
    private const int MaxDeviceNameLength = 64;
    private const int MaxPublicKeyLength = 4096;

    private readonly string? _fixedPin;
    private readonly string? _pinFile;
    private readonly ConcurrentDictionary<string, PendingPair> _pending = new();
    private readonly ConcurrentDictionary<string, int> _attempts = new();

    public PairingManager(string? fixedPin, string? pinFile = null)
    {
        _fixedPin = fixedPin;
        _pinFile = pinFile;
    }

    public (string PairingId, string Pin) StartPairing(string deviceName, string publicKey)
    {
        if (string.IsNullOrWhiteSpace(publicKey) || publicKey.Length > MaxPublicKeyLength)
        {
            throw new CryptographicException("公钥长度无效");
        }

        using var _ = CryptoUtil.ImportPublicKey(publicKey);

        PruneExpired();
        TrimPending();

        var pairingId = Guid.NewGuid().ToString("N");
        var pin = _fixedPin ?? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        _pending[pairingId] = new PendingPair(pin, NormalizeDeviceName(deviceName), publicKey, DateTimeOffset.UtcNow);

        if (!string.IsNullOrEmpty(_pinFile))
        {
            try
            {
                var dir = Path.GetDirectoryName(_pinFile);
                if (!string.IsNullOrEmpty(dir))
                {
                    Directory.CreateDirectory(dir);
                }
                File.WriteAllText(_pinFile, pin);
            }
            catch
            {
                // 展示用文件写入失败不影响配对
            }
        }

        return (pairingId, pin);
    }

    public PairingInfo? Current()
    {
        PruneExpired();
        var pair = _pending.Values.OrderByDescending(p => p.CreatedAt).FirstOrDefault();
        return pair is null
            ? null
            : new PairingInfo(pair.DeviceName, pair.Pin, pair.CreatedAt, pair.CreatedAt + Lifetime);
    }

    public (bool Ok, PendingPair? Pair, string Error) Confirm(string pairingId, string pin)
    {
        if (!_pending.TryGetValue(pairingId, out var pair))
        {
            return (false, null, "unknown_pairing");
        }

        if (DateTimeOffset.UtcNow - pair.CreatedAt > Lifetime)
        {
            _pending.TryRemove(pairingId, out _);
            _attempts.TryRemove(pairingId, out _);
            return (false, null, "pairing_expired");
        }

        var normalizedPin = string.Concat(pin.Where(c => !char.IsWhiteSpace(c)));
        if (!PinMatches(pair.Pin, normalizedPin))
        {
            var attempts = _attempts.AddOrUpdate(pairingId, 1, (_, count) => count + 1);
            if (attempts >= MaxPinAttempts)
            {
                _pending.TryRemove(pairingId, out _);
                _attempts.TryRemove(pairingId, out _);
                return (false, null, "pairing_locked");
            }

            return (false, null, "wrong_pin");
        }

        if (!_pending.TryRemove(pairingId, out _))
        {
            return (false, null, "unknown_pairing");
        }

        _attempts.TryRemove(pairingId, out _);
        return (true, pair, string.Empty);
    }

    private void PruneExpired()
    {
        var now = DateTimeOffset.UtcNow;
        foreach (var kv in _pending)
        {
            if (now - kv.Value.CreatedAt > Lifetime)
            {
                _pending.TryRemove(kv.Key, out _);
                _attempts.TryRemove(kv.Key, out _);
            }
        }
    }

    private void TrimPending()
    {
        while (_pending.Count >= MaxPendingPairings)
        {
            var oldest = _pending
                .OrderBy(kv => kv.Value.CreatedAt)
                .Select(kv => kv.Key)
                .FirstOrDefault();
            if (oldest is null || !_pending.TryRemove(oldest, out _))
            {
                break;
            }

            _attempts.TryRemove(oldest, out _);
        }
    }

    private static string NormalizeDeviceName(string deviceName)
    {
        var normalized = (deviceName ?? "").Trim();
        if (normalized.Length == 0)
        {
            return "未命名设备";
        }

        return normalized.Length <= MaxDeviceNameLength
            ? normalized
            : normalized[..MaxDeviceNameLength];
    }

    private static bool PinMatches(string expected, string actual)
    {
        var expectedBytes = Encoding.UTF8.GetBytes(expected);
        var actualBytes = Encoding.UTF8.GetBytes(actual);
        return expectedBytes.Length == actualBytes.Length
               && CryptographicOperations.FixedTimeEquals(expectedBytes, actualBytes);
    }
}
