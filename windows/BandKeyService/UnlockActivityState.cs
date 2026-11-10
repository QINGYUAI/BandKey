namespace BandKeyService;

public sealed record UnlockActivitySnapshot(
    string? LastUnlockMethod,
    string? NfcKeyHint,
    long? NfcLastUsedAt);

public sealed class UnlockActivityState
{
    private readonly object _gate = new();
    private string? _lastUnlockMethod;
    private string? _nfcKeyHint;
    private long? _nfcLastUsedAt;

    public void Record(UnlockRequest request)
    {
        var isNfc = string.Equals(request.Method?.Trim(), "nfc", StringComparison.OrdinalIgnoreCase);
        var hint = isNfc ? NormalizeNfcHint(request.KeyHint) : null;

        lock (_gate)
        {
            _lastUnlockMethod = isNfc ? "nfc" : "phone";
            if (!isNfc)
            {
                return;
            }

            if (hint is not null)
            {
                _nfcKeyHint = hint;
            }

            _nfcLastUsedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }
    }

    public UnlockActivitySnapshot Snapshot()
    {
        lock (_gate)
        {
            return new UnlockActivitySnapshot(_lastUnlockMethod, _nfcKeyHint, _nfcLastUsedAt);
        }
    }

    private static string? NormalizeNfcHint(string? raw)
    {
        const string prefix = "BK-NFC ··· ";
        var value = raw?.Trim();
        if (value is null ||
            value.Length != prefix.Length + 4 ||
            !value.StartsWith(prefix, StringComparison.Ordinal))
        {
            return null;
        }

        var tail = value[prefix.Length..];
        return tail.All(Uri.IsHexDigit)
            ? prefix + tail.ToUpperInvariant()
            : null;
    }
}
