using System.Text.Json;

namespace BandKeyService;

public sealed record Device(
    string DeviceId,
    string Name,
    string PublicKey,
    DateTimeOffset CreatedAt,
    string? InstallId = null,
    DateTimeOffset? LastPairedAt = null);

public sealed class DeviceStore
{
    private const int MaxDeviceNameLength = 64;
    private const int MaxInstallIdLength = 64;

    private readonly string _path;
    private readonly string _backupPath;
    private readonly object _gate = new();
    private List<Device> _devices = new();

    public DeviceStore(string path)
    {
        _path = path;
        _backupPath = path + ".bak";
    }

    public IReadOnlyList<Device> Devices
    {
        get { lock (_gate) { return _devices.ToList(); } }
    }

    public void Load()
    {
        lock (_gate)
        {
            if (TryLoad(_path, out var devices))
            {
                _devices = devices;
                return;
            }

            if (TryLoad(_backupPath, out devices))
            {
                _devices = devices;
                try
                {
                    Save(_devices);
                }
                catch (Exception ex)
                {
                    ServiceLog.Write($"[store] 备份恢复成功，但主文件修复失败: {ex.Message}");
                }
                return;
            }

            if (File.Exists(_path))
            {
                ServiceLog.Write($"[store] 设备库和备份均不可读，已使用空设备库: {_path}");
            }

            _devices = new List<Device>();
        }
    }

    public Device? Find(string deviceId)
    {
        lock (_gate)
        {
            return _devices.FirstOrDefault(d => d.DeviceId == deviceId);
        }
    }

    public Device Add(string name, string publicKey, string? installId = null)
    {
        lock (_gate)
        {
            var normalizedName = NormalizeDeviceName(name);
            var normalizedKey = publicKey.Trim();
            var normalizedInstallId = NormalizeInstallId(installId);
            var now = DateTimeOffset.UtcNow;
            var existingIndex = normalizedInstallId is null
                ? -1
                : _devices.FindIndex(d => string.Equals(
                    d.InstallId,
                    normalizedInstallId,
                    StringComparison.Ordinal));
            if (existingIndex < 0)
            {
                existingIndex = _devices.FindIndex(d => d.PublicKey == normalizedKey);
            }

            var device = existingIndex >= 0
                ? _devices[existingIndex] with
                {
                    Name = normalizedName,
                    PublicKey = normalizedKey,
                    InstallId = _devices[existingIndex].InstallId ?? normalizedInstallId,
                    LastPairedAt = now
                }
                : new Device(
                    Guid.NewGuid().ToString("N"),
                    normalizedName,
                    normalizedKey,
                    now,
                    normalizedInstallId,
                    now);

            var next = _devices.ToList();
            if (existingIndex >= 0)
            {
                next[existingIndex] = device;
            }
            else
            {
                next.Add(device);
            }

            Save(next);
            _devices = next;
            return device;
        }
    }

    public bool Remove(string deviceId)
    {
        lock (_gate)
        {
            var next = _devices.Where(d => d.DeviceId != deviceId).ToList();
            if (next.Count == _devices.Count)
            {
                return false;
            }

            Save(next);
            _devices = next;
            return true;
        }
    }

    private void Save(IReadOnlyList<Device> devices)
    {
        var dir = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        var tempPath = _path + ".tmp";
        var json = JsonSerializer.SerializeToUtf8Bytes(new DeviceFile(devices.ToList()), JsonOpts);
        try
        {
            using (var stream = new FileStream(
                       tempPath,
                       FileMode.Create,
                       FileAccess.Write,
                       FileShare.None,
                       4096,
                       FileOptions.WriteThrough))
            {
                stream.Write(json);
                stream.Flush(flushToDisk: true);
            }

            if (File.Exists(_path))
            {
                File.Replace(tempPath, _path, _backupPath, ignoreMetadataErrors: true);
            }
            else
            {
                File.Move(tempPath, _path);
            }
        }
        finally
        {
            try
            {
                if (File.Exists(tempPath))
                {
                    File.Delete(tempPath);
                }
            }
            catch
            {
                // 临时文件清理失败不影响已成功的原子替换
            }
        }
    }

    private bool TryLoad(string path, out List<Device> devices)
    {
        devices = new List<Device>();
        if (!File.Exists(path))
        {
            return false;
        }

        try
        {
            var json = File.ReadAllText(path);
            var file = JsonSerializer.Deserialize<DeviceFile>(json, JsonOpts);
            devices = Normalize(file?.Devices);
            return true;
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[store] 设备库读取失败 {path}: {ex.Message}");
            return false;
        }
    }

    private static List<Device> Normalize(IEnumerable<Device>? devices)
    {
        var result = new List<Device>();
        if (devices is null)
        {
            return result;
        }

        var seenKeys = new HashSet<string>(StringComparer.Ordinal);
        var seenInstallIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var device in devices)
        {
            if (device is null
                || string.IsNullOrWhiteSpace(device.DeviceId)
                || string.IsNullOrWhiteSpace(device.PublicKey))
            {
                continue;
            }

            var publicKey = device.PublicKey.Trim();
            if (!seenKeys.Add(publicKey))
            {
                continue;
            }

            var installId = NormalizeInstallId(device.InstallId);
            if (installId is not null && !seenInstallIds.Add(installId))
            {
                continue;
            }

            result.Add(device with
            {
                DeviceId = device.DeviceId.Trim(),
                Name = NormalizeDeviceName(device.Name),
                PublicKey = publicKey,
                InstallId = installId
            });
        }

        return result;
    }

    private static string NormalizeDeviceName(string? name)
    {
        var normalized = (name ?? "").Trim();
        if (normalized.Length == 0)
        {
            return "未命名设备";
        }

        return normalized.Length <= MaxDeviceNameLength
            ? normalized
            : normalized[..MaxDeviceNameLength];
    }

    private static string? NormalizeInstallId(string? installId)
    {
        var normalized = (installId ?? "").Trim();
        if (normalized.Length == 0)
        {
            return null;
        }

        if (normalized.Length > MaxInstallIdLength
            || normalized.Any(c => !char.IsAsciiLetterOrDigit(c) && c is not '-' and not '_'))
        {
            return null;
        }

        return normalized;
    }

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };

    private sealed record DeviceFile(List<Device> Devices);
}
