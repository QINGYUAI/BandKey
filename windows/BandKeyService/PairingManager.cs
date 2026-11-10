using System.Collections.Concurrent;
using System.Security.Cryptography;

namespace BandKeyService;

public sealed record PendingPair(string Pin, string DeviceName, string PublicKey, DateTimeOffset CreatedAt);

public sealed record PairingInfo(string DeviceName, string Pin, DateTimeOffset CreatedAt, DateTimeOffset ExpiresAt);

public sealed class PairingManager
{
    private static readonly TimeSpan Lifetime = TimeSpan.FromMinutes(5);

    private readonly string? _fixedPin;
    private readonly string? _pinFile;
    private readonly ConcurrentDictionary<string, PendingPair> _pending = new();

    public PairingManager(string? fixedPin, string? pinFile = null)
    {
        _fixedPin = fixedPin;
        _pinFile = pinFile;
    }

    public (string PairingId, string Pin) StartPairing(string deviceName, string publicKey)
    {
        using var _ = CryptoUtil.ImportPublicKey(publicKey);

        PruneExpired();

        var pairingId = Guid.NewGuid().ToString("N");
        var pin = _fixedPin ?? RandomNumberGenerator.GetInt32(0, 1_000_000).ToString("D6");
        _pending[pairingId] = new PendingPair(pin, deviceName, publicKey, DateTimeOffset.UtcNow);

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
            return (false, null, "pairing_expired");
        }

        var normalizedPin = string.Concat(pin.Where(c => !char.IsWhiteSpace(c)));
        if (pair.Pin != normalizedPin)
        {
            return (false, null, "wrong_pin");
        }

        _pending.TryRemove(pairingId, out _);
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
            }
        }
    }
}
