using System.Text;
using System.Text.Json;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

namespace BandKeyBle;

public sealed class BleUnlockServer : IAsyncDisposable
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    private readonly Func<string, Task<string>>? _rpcHandler;
    private readonly MessageAssembler _assembler = new();
    private readonly Action<string>? _log;
    private GattServiceProvider? _provider;
    private GattLocalCharacteristic? _tx;

    public BleUnlockServer(
        Action<string>? log = null,
        Func<string, Task<string>>? rpcHandler = null)
    {
        _log = log;
        _rpcHandler = rpcHandler;
    }

    public string Status { get; private set; } = "未启动";

    public bool IsAdvertising => _provider is not null;

    public int ConnectedClientCount
    {
        get
        {
            try
            {
                return _tx?.SubscribedClients.Count ?? 0;
            }
            catch
            {
                return 0;
            }
        }
    }

    public DateTimeOffset LastActivityUtc { get; private set; }

    public event Action? StatusChanged;

    public async Task<bool> StartAsync()
    {
        if (_provider is not null)
        {
            return true;
        }

        try
        {
            var adapter = await BluetoothAdapter.GetDefaultAsync();
            if (adapter is null)
            {
                SetStatus("未找到蓝牙适配器");
                return false;
            }

            if (!adapter.IsPeripheralRoleSupported)
            {
                SetStatus("蓝牙适配器不支持 BLE 外设模式");
                return false;
            }

            var createResult = await GattServiceProvider.CreateAsync(BleConstants.ServiceUuid);
            if (createResult.Error != BluetoothError.Success)
            {
                SetStatus($"创建 GATT 服务失败：{createResult.Error}");
                return false;
            }

            var provider = createResult.ServiceProvider;

            var rxParameters = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties =
                    GattCharacteristicProperties.Write |
                    GattCharacteristicProperties.WriteWithoutResponse,
                WriteProtectionLevel = GattProtectionLevel.Plain,
                UserDescription = "BandKey RX"
            };
            var rxResult = await provider.Service.CreateCharacteristicAsync(
                BleConstants.RxCharacteristicUuid, rxParameters);
            if (rxResult.Error != BluetoothError.Success)
            {
                SetStatus($"创建 RX 特征失败：{rxResult.Error}");
                return false;
            }

            var txParameters = new GattLocalCharacteristicParameters
            {
                CharacteristicProperties = GattCharacteristicProperties.Notify,
                UserDescription = "BandKey TX"
            };
            var txResult = await provider.Service.CreateCharacteristicAsync(
                BleConstants.TxCharacteristicUuid, txParameters);
            if (txResult.Error != BluetoothError.Success)
            {
                SetStatus($"创建 TX 特征失败：{txResult.Error}");
                return false;
            }

            rxResult.Characteristic.WriteRequested += OnWriteRequested;
            provider.AdvertisementStatusChanged += (_, args) =>
                _log?.Invoke($"advertising status={args.Status} error={args.Error}");

            var advertisingParameters = new GattServiceProviderAdvertisingParameters
            {
                IsDiscoverable = true,
                IsConnectable = true
            };

            provider.StartAdvertising(advertisingParameters);

            _provider = provider;
            _tx = txResult.Characteristic;
            SetStatus("蓝牙广播中");
            _log?.Invoke($"BLE advertising started, service={BleConstants.ServiceUuid}");
            return true;
        }
        catch (Exception ex)
        {
            SetStatus($"蓝牙启动异常：{ex.Message}");
            _log?.Invoke("BLE start exception: " + ex);
            return false;
        }
    }

    public async Task StopAsync()
    {
        var provider = _provider;
        _provider = null;
        _tx = null;

        if (provider is null)
        {
            return;
        }

        try
        {
            provider.StopAdvertising();
            foreach (var characteristic in provider.Service.Characteristics)
            {
                characteristic.WriteRequested -= OnWriteRequested;
            }

        }
        catch (Exception ex)
        {
            _log?.Invoke("BLE stop exception: " + ex);
        }

        SetStatus("已停止");
        await Task.CompletedTask;
    }

    public async ValueTask DisposeAsync()
    {
        await StopAsync();
    }

    private async void OnWriteRequested(GattLocalCharacteristic sender, GattWriteRequestedEventArgs args)
    {
        var deferral = args.GetDeferral();
        try
        {
            var request = await args.GetRequestAsync();
            var value = new byte[request.Value.Length];
            DataReader.FromBuffer(request.Value).ReadBytes(value);

            var key = args.Session?.DeviceId?.Id ?? "unknown";
            var messages = _assembler.Append(key, value);
            LastActivityUtc = DateTimeOffset.UtcNow;
            try
            {
                request.Respond();
            }
            catch (Exception ex)
            {
                _log?.Invoke("respond failed: " + ex.Message);
            }

            foreach (var message in messages)
            {
                _log?.Invoke($"rx {message.Length} chars");
                _ = HandleMessageAsync(message);
            }
        }
        catch (Exception ex)
        {
            _log?.Invoke("BLE write exception: " + ex);
        }
        finally
        {
            deferral.Complete();
        }
    }

    private async Task HandleMessageAsync(string json)
    {
        try
        {
            var reply = await ForwardAsync(json);
            await SendAsync(reply);
        }
        catch (Exception ex)
        {
            _log?.Invoke("handle message exception: " + ex);
        }
    }

    private async Task<string> ForwardAsync(string json)
    {
        var handler = _rpcHandler;
        if (handler is null)
        {
            return ErrorJson("service_unavailable", "电脑端服务未配置 RPC 通道");
        }

        try
        {
            var text = await handler(json);
            if (string.IsNullOrWhiteSpace(text))
            {
                return ErrorJson("service_error", "服务返回空响应");
            }

            return text;
        }
        catch (Exception ex)
        {
            _log?.Invoke("RPC forward failed: " + ex.Message);
            return ErrorJson("service_unavailable", "电脑端服务未运行：" + ex.Message);
        }
    }

    private async Task SendAsync(string text)
    {
        var tx = _tx;
        if (tx is null)
        {
            return;
        }

        var payload = Encoding.UTF8.GetBytes(text + "\n");
        try
        {
            foreach (var client in tx.SubscribedClients)
            {
                var chunkSize = Math.Clamp((int)client.MaxNotificationSize, 20, 512);
                var offset = 0;
                while (offset < payload.Length)
                {
                    var length = Math.Min(chunkSize, payload.Length - offset);
                    var chunk = new byte[length];
                    Array.Copy(payload, offset, chunk, 0, length);

                    var result = await tx.NotifyValueAsync(ToBuffer(chunk), client);
                    if (result.Status != GattCommunicationStatus.Success)
                    {
                        _log?.Invoke($"notify failed: {result.Status}");
                        break;
                    }

                    offset += length;
                }
            }
        }
        catch (Exception ex)
        {
            _log?.Invoke("notify exception: " + ex);
        }
    }

    private static IBuffer ToBuffer(byte[] data)
    {
        using var writer = new DataWriter();
        writer.WriteBytes(data);
        return writer.DetachBuffer();
    }

    private static string ErrorJson(string code, string message)
    {
        return JsonSerializer.Serialize(
            new { type = "error", payload = new { code, message } }, JsonOpts);
    }

    private void SetStatus(string status)
    {
        Status = status;
        _log?.Invoke("status: " + status);
        StatusChanged?.Invoke();
    }
}
