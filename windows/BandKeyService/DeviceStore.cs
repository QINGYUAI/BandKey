using System.Text.Json;

namespace BandKeyService;

public sealed record Device(string DeviceId, string Name, string PublicKey, DateTimeOffset CreatedAt);

public sealed class DeviceStore
{
    private readonly string _path;
    private readonly object _gate = new();
    private List<Device> _devices = new();

    public DeviceStore(string path) => _path = path;

    public IReadOnlyList<Device> Devices
    {
        get { lock (_gate) { return _devices.ToList(); } }
    }

    public void Load()
    {
        lock (_gate)
        {
            if (!File.Exists(_path))
            {
                return;
            }

            var json = File.ReadAllText(_path);
            var file = JsonSerializer.Deserialize<DeviceFile>(json, JsonOpts);
            _devices = file?.Devices ?? new List<Device>();
        }
    }

    public Device? Find(string deviceId)
    {
        lock (_gate)
        {
            return _devices.FirstOrDefault(d => d.DeviceId == deviceId);
        }
    }

    public Device Add(string name, string publicKey)
    {
        lock (_gate)
        {
            var device = new Device(Guid.NewGuid().ToString("N"), name, publicKey, DateTimeOffset.UtcNow);
            _devices.Add(device);
            Save();
            return device;
        }
    }

    public bool Remove(string deviceId)
    {
        lock (_gate)
        {
            var removed = _devices.RemoveAll(d => d.DeviceId == deviceId);
            if (removed == 0)
            {
                return false;
            }

            Save();
            return true;
        }
    }

    private void Save()
    {
        var dir = Path.GetDirectoryName(_path);
        if (!string.IsNullOrEmpty(dir))
        {
            Directory.CreateDirectory(dir);
        }

        var json = JsonSerializer.Serialize(new DeviceFile(_devices), JsonOpts);
        File.WriteAllText(_path, json);
    }

    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        WriteIndented = true
    };

    private sealed record DeviceFile(List<Device> Devices);
}
