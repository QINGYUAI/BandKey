namespace BandKeyService;

public sealed record UnlockOutcomeSnapshot(
    long RequestId,
    string State,
    bool Success,
    string? Error,
    long UpdatedAt,
    bool Completed);

/// <summary>
/// 保存最近解锁请求的投递与 Windows 登录结果。
/// 手机端通过 unlock_status 轮询，只有 CP 的 ReportResult 才能把请求标记为成功或失败。
/// </summary>
public sealed class UnlockOutcomeStore
{
    public const string Accepted = "accepted";
    public const string Submitted = "submitted";
    public const string Succeeded = "succeeded";
    public const string Failed = "failed";
    public const string Unknown = "unknown";

    private const int MaxEntries = 64;
    private static readonly long OutcomeTimeoutMilliseconds =
        (long)TimeSpan.FromMinutes(2).TotalMilliseconds;
    private static readonly long DisconnectGraceMilliseconds =
        (long)TimeSpan.FromSeconds(8).TotalMilliseconds;
    private readonly object _gate = new();
    private readonly Dictionary<long, Entry> _entries = new();
    private readonly string? _journalDirectory;

    public UnlockOutcomeStore(string? journalDirectory = null)
    {
        if (!string.IsNullOrWhiteSpace(journalDirectory))
        {
            _journalDirectory = Path.GetFullPath(journalDirectory);
            EnsureJournalDirectory();
        }
    }

    public void MarkAccepted(long requestId) => Update(requestId, Accepted, null, false);

    public void MarkSubmitted(long requestId) => Update(requestId, Submitted, null, false);

    public void MarkCompleted(long requestId, bool success, string? error)
        => Update(requestId, success ? Succeeded : Failed, error, true);

    public UnlockOutcomeSnapshot? Snapshot(long requestId)
    {
        ProcessJournal();

        lock (_gate)
        {
            if (!_entries.TryGetValue(requestId, out var entry))
            {
                return null;
            }

            ExpireIfNeeded(entry);
            return entry.ToSnapshot(requestId);
        }
    }

    public UnlockOutcomeSnapshot? Latest()
    {
        ProcessJournal();

        lock (_gate)
        {
            var latest = _entries
                .OrderByDescending(pair => pair.Value.UpdatedAt)
                .FirstOrDefault();
            if (latest.Key <= 0)
            {
                return null;
            }

            ExpireIfNeeded(latest.Value);
            return latest.Value.ToSnapshot(latest.Key);
        }
    }

    /// <summary>
    /// 凭据提供程序断开后先进入宽限期，等待 CP 通过结果文件补交 ReportResult。
    /// 宽限期结束仍没有最终结果时才收敛为失败。
    /// </summary>
    public int MarkDisconnected(string error)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var marked = 0;
        lock (_gate)
        {
            foreach (var entry in _entries.Values)
            {
                if (entry.Completed)
                {
                    continue;
                }

                if (entry.DisconnectedAt == 0)
                {
                    entry.DisconnectedAt = now;
                    entry.DisconnectError = error;
                }
                marked++;
            }
        }

        return marked;
    }

    /// <summary>
    /// 请求在投递阶段就失败，CP 不可能再产生结果，立即收敛。
    /// </summary>
    public int FailOutstanding(string error)
    {
        var failed = 0;
        lock (_gate)
        {
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            foreach (var entry in _entries.Values)
            {
                if (entry.Completed)
                {
                    continue;
                }

                entry.State = Failed;
                entry.Error = error;
                entry.Completed = true;
                entry.UpdatedAt = now;
                failed++;
            }

            Prune();
        }

        return failed;
    }

    /// <summary>
    /// 读取 CP 写入的最终结果文件。文件由 CP 先写临时文件再原子重命名，
    /// 因此这里只会看到完整内容。成功读取后删除文件，避免服务重启后误用旧结果。
    /// </summary>
    public int ProcessJournal()
    {
        if (_journalDirectory is null || !Directory.Exists(_journalDirectory))
        {
            return 0;
        }

        var applied = 0;
        IEnumerable<string> files;
        try
        {
            files = Directory.EnumerateFiles(_journalDirectory, "*.result").ToArray();
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[outcome] journal enumerate failed: {ex.Message}");
            return 0;
        }

        foreach (var path in files)
        {
            var name = Path.GetFileNameWithoutExtension(path);
            if (!long.TryParse(name, out var requestId) || requestId <= 0)
            {
                continue;
            }

            string result;
            try
            {
                result = File.ReadAllText(path).Trim();
            }
            catch (Exception ex)
            {
                ServiceLog.Write($"[outcome] journal read failed {path}: {ex.Message}");
                continue;
            }

            if (result.Length == 0 || result.Length > 64)
            {
                continue;
            }

            var success = string.Equals(result, "success", StringComparison.OrdinalIgnoreCase)
                          || string.Equals(result, Succeeded, StringComparison.OrdinalIgnoreCase);
            MarkCompleted(requestId, success, success ? null : result);
            applied++;

            try
            {
                File.Delete(path);
            }
            catch (Exception ex)
            {
                ServiceLog.Write($"[outcome] journal delete failed {path}: {ex.Message}");
            }

            ServiceLog.Write($"[outcome] journal UNLOCK_RESULT:{requestId}:{result}");
        }

        return applied;
    }

    public void ClearJournal()
    {
        if (_journalDirectory is null || !Directory.Exists(_journalDirectory))
        {
            return;
        }

        try
        {
            foreach (var path in Directory.EnumerateFiles(_journalDirectory))
            {
                File.Delete(path);
            }
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[outcome] journal clear failed: {ex.Message}");
        }
    }

    private void Update(long requestId, string state, string? error, bool completed)
    {
        if (requestId <= 0)
        {
            return;
        }

        lock (_gate)
        {
            if (!_entries.TryGetValue(requestId, out var entry))
            {
                entry = new Entry();
                _entries[requestId] = entry;
            }

            if (entry.Completed)
            {
                return;
            }

            // CP 可能在 MarkAccepted 之前就先回写 submitted，状态只能单向推进，
            // 不能因为解锁路由随后执行而把 submitted 降级回 accepted。
            var currentRank = StateRank(entry.State);
            var nextRank = StateRank(state);
            if (nextRank <= currentRank)
            {
                return;
            }

            entry.State = state;
            entry.Error = error;
            entry.Completed = completed;
            entry.DisconnectedAt = 0;
            entry.DisconnectError = null;
            entry.UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            Prune();
        }
    }

    private static int StateRank(string state) => state switch
    {
        Accepted => 1,
        Submitted => 2,
        Succeeded => 3,
        Failed => 3,
        _ => 0
    };

    private static void ExpireIfNeeded(Entry entry)
    {
        if (entry.Completed)
        {
            return;
        }

        var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (now - entry.UpdatedAt < OutcomeTimeoutMilliseconds)
        {
            if (entry.DisconnectedAt > 0
                && now - entry.DisconnectedAt >= DisconnectGraceMilliseconds)
            {
                entry.State = Failed;
                entry.Error = entry.DisconnectError ?? "component_disconnected";
                entry.Completed = true;
                entry.UpdatedAt = now;
            }

            return;
        }

        entry.State = Failed;
        entry.Error = "outcome_timeout";
        entry.Completed = true;
        entry.UpdatedAt = now;
    }

    private void Prune()
    {
        if (_entries.Count <= MaxEntries)
        {
            return;
        }

        foreach (var key in _entries
                     .OrderBy(pair => pair.Value.UpdatedAt)
                     .Take(_entries.Count - MaxEntries)
                     .Select(pair => pair.Key)
                     .ToArray())
        {
            _entries.Remove(key);
        }
    }

    private sealed class Entry
    {
        public string State { get; set; } = UnlockOutcomeStore.Unknown;
        public string? Error { get; set; }
        public bool Completed { get; set; }
        public long DisconnectedAt { get; set; }
        public string? DisconnectError { get; set; }
        public long UpdatedAt { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

        public UnlockOutcomeSnapshot ToSnapshot(long requestId)
            => new(requestId, State, State == UnlockOutcomeStore.Succeeded, Error, UpdatedAt, Completed);
    }

    private void EnsureJournalDirectory()
    {
        if (_journalDirectory is null)
        {
            return;
        }

        try
        {
            Directory.CreateDirectory(_journalDirectory);
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[outcome] journal mkdir failed: {ex.Message}");
        }
    }
}
