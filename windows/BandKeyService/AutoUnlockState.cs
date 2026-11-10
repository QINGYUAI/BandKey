namespace BandKeyService;

/// <summary>
/// 跟踪“凭据提供程序可自动解锁”的状态。
/// 只有已配对设备存在且本机凭据可用时才进入 ready；一次解锁请求会打开 90 秒窗口，
/// 凭据提供程序回复 ACK 或窗口过期后关闭。
/// </summary>
public sealed class AutoUnlockState
{
    private static readonly TimeSpan AutoUnlockWindow = TimeSpan.FromSeconds(90);

    private readonly Func<bool> _baseReady;
    private readonly object _gate = new();
    private bool _credentialRejected;
    private DateTime _windowUntil = DateTime.MinValue;
    private bool _deliveryPending;
    private long _version;

    public AutoUnlockState(Func<bool> baseReady) => _baseReady = baseReady;

    public event Action<bool>? UnlockReadyChanged;
    public event Action? AutoUnlockRequested;
    public event Action? PasswordFailed;

    public bool CredentialRejected
    {
        get { lock (_gate) { return _credentialRejected; } }
    }

    public bool UnlockReady
    {
        get { lock (_gate) { return IsUnlockReadyCore(DateTime.UtcNow); } }
    }

    public bool HasPendingAutoUnlock
    {
        get
        {
            lock (_gate)
            {
                return _deliveryPending && _windowUntil > DateTime.UtcNow;
            }
        }
    }

    public long? PendingAutoUnlockRequestId
    {
        get
        {
            lock (_gate)
            {
                if (!_deliveryPending || _windowUntil <= DateTime.UtcNow)
                {
                    return null;
                }

                return _version;
            }
        }
    }

    /// <summary>
    /// 打开自动解锁窗口并返回请求 ID。返回 0 表示当前没有可用的解锁条件。
    /// </summary>
    public long RequestAutoUnlock()
    {
        if (!_baseReady())
        {
            return 0;
        }

        long requestId;
        lock (_gate)
        {
            _windowUntil = DateTime.UtcNow + AutoUnlockWindow;
            _deliveryPending = true;
            requestId = ++_version;
        }

        AutoUnlockRequested?.Invoke();
        UnlockReadyChanged?.Invoke(UnlockReady);
        _ = ExpireWindowAsync(requestId, AutoUnlockWindow);
        return requestId;
    }

    public bool TryAcknowledge(long requestId)
    {
        bool becameNotReady;
        lock (_gate)
        {
            var now = DateTime.UtcNow;
            if (!_deliveryPending || _windowUntil <= now || _version != requestId)
            {
                return false;
            }

            _deliveryPending = false;
            _windowUntil = DateTime.MinValue;
            becameNotReady = !IsUnlockReadyCore(now);
        }

        if (becameNotReady)
        {
            UnlockReadyChanged?.Invoke(false);
        }

        return true;
    }

    public bool CancelPendingAutoUnlock()
    {
        bool becameNotReady;
        lock (_gate)
        {
            if (!_deliveryPending)
            {
                return false;
            }

            _deliveryPending = false;
            _windowUntil = DateTime.MinValue;
            _version++;
            becameNotReady = !IsUnlockReadyCore(DateTime.UtcNow);
        }

        if (becameNotReady)
        {
            UnlockReadyChanged?.Invoke(false);
        }

        return true;
    }

    /// <summary>
    /// 已配对设备或凭据发生变化时调用，用于刷新凭据提供程序的 ready 状态。
    /// </summary>
    public void NotifyBaseStateChanged()
    {
        UnlockReadyChanged?.Invoke(UnlockReady);
    }

    public void MarkPasswordFailed()
    {
        lock (_gate)
        {
            _credentialRejected = true;
            _deliveryPending = false;
            _windowUntil = DateTime.MinValue;
            _version++;
        }

        PasswordFailed?.Invoke();
        UnlockReadyChanged?.Invoke(false);
    }

    public void ClearPasswordFailure()
    {
        bool changed;
        lock (_gate)
        {
            changed = _credentialRejected;
            _credentialRejected = false;
        }

        if (changed)
        {
            UnlockReadyChanged?.Invoke(UnlockReady);
        }
    }

    private bool IsUnlockReadyCore(DateTime now)
    {
        if (_windowUntil > now)
        {
            return true;
        }

        return !_credentialRejected && _baseReady();
    }

    private async Task ExpireWindowAsync(long requestId, TimeSpan delay)
    {
        try
        {
            await Task.Delay(delay).ConfigureAwait(false);
        }
        catch
        {
            return;
        }

        bool becameNotReady;
        lock (_gate)
        {
            if (_version != requestId || !_deliveryPending)
            {
                return;
            }

            _deliveryPending = false;
            _windowUntil = DateTime.MinValue;
            becameNotReady = !IsUnlockReadyCore(DateTime.UtcNow);
        }

        if (becameNotReady)
        {
            UnlockReadyChanged?.Invoke(false);
        }
    }
}
