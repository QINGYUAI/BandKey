using System.Text;
using BandKeyBle;
using Windows.Devices.Bluetooth;
using Windows.Devices.Bluetooth.Advertisement;
using Windows.Devices.Bluetooth.GenericAttributeProfile;
using Windows.Storage.Streams;

Console.WriteLine("BandKey BLE Peripheral probe v2");
Console.OutputEncoding = Encoding.UTF8;

if (args.Length > 0 && args[0] == "--dump")
{
    Dump(typeof(GattServiceProviderAdvertisingParameters));
    Dump(typeof(GattSubscribedClient));
    Dump(typeof(GattLocalCharacteristic));
    Dump(typeof(GattProtectionLevel));
    Dump(typeof(GattLocalCharacteristicParameters));
    Dump(typeof(GattWriteRequest));
    Dump(typeof(GattWriteRequestedEventArgs));
    return 0;
}

if (args.Length > 0 && args[0] == "--framing")
{
    var assembler = new MessageAssembler();
    var message = "{\"type\":\"unlock\",\"payload\":{\"signature\":\"abc\"}}";
    var payload = Encoding.UTF8.GetBytes(message + "\n");
    var first = assembler.Append("dev1", payload.AsSpan(0, 7));
    var second = assembler.Append("dev1", payload.AsSpan(7, 11));
    var third = assembler.Append("dev1", payload.AsSpan(18));
    var joined = string.Join("|", first.Concat(second).Concat(third));
    Console.WriteLine($"FRAMING_MATCH={joined == message}");
    Console.WriteLine($"RESULT={(joined == message ? "FRAMING_OK" : "FRAMING_FAILED")}");
    return joined == message ? 0 : 5;
}

if (args.Length > 0 && args[0] == "--serve")
{
    var server = new BleUnlockServer(line => Console.WriteLine($"LOG {line}"));
    var started = await server.StartAsync();
    Console.WriteLine($"SERVER_STARTED={started} STATUS={server.Status}");
    await Task.Delay(10000);
    await server.DisposeAsync();
    Console.WriteLine("RESULT=SERVE_DONE");
    return started ? 0 : 6;
}

var adapter = await BluetoothAdapter.GetDefaultAsync();
if (adapter is null)
{
    Console.WriteLine("RESULT=NO_ADAPTER");
    return 2;
}

Console.WriteLine($"AdapterAddress={adapter.BluetoothAddress:X12}");
Console.WriteLine($"IsLowEnergySupported={adapter.IsLowEnergySupported}");
Console.WriteLine($"IsCentralRoleSupported={adapter.IsCentralRoleSupported}");
Console.WriteLine($"IsPeripheralRoleSupported={adapter.IsPeripheralRoleSupported}");

if (!adapter.IsPeripheralRoleSupported)
{
    Console.WriteLine("RESULT=PERIPHERAL_NOT_SUPPORTED");
    return 3;
}

var serviceUuid = new Guid("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
var rxUuid = new Guid("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
var txUuid = new Guid("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

var createResult = await GattServiceProvider.CreateAsync(serviceUuid);
Console.WriteLine($"CreateAsyncError={createResult.Error}");
if (createResult.Error != BluetoothError.Success)
{
    Console.WriteLine("RESULT=GATT_CREATE_FAILED");
    return 4;
}

var provider = createResult.ServiceProvider;

var rxParams = new GattLocalCharacteristicParameters
{
    CharacteristicProperties =
        GattCharacteristicProperties.Write |
        GattCharacteristicProperties.WriteWithoutResponse,
    UserDescription = "BandKey RX"
};
var rxResult = await provider.Service.CreateCharacteristicAsync(rxUuid, rxParams);
Console.WriteLine($"CreateRxError={rxResult.Error}");

var txParams = new GattLocalCharacteristicParameters
{
    CharacteristicProperties = GattCharacteristicProperties.Notify,
    UserDescription = "BandKey TX"
};
var txResult = await provider.Service.CreateCharacteristicAsync(txUuid, txParams);
Console.WriteLine($"CreateTxError={txResult.Error}");

if (rxResult.Error == BluetoothError.Success)
{
    rxResult.Characteristic.WriteRequested += async (sender, args) =>
    {
        var deferral = args.GetDeferral();
        try
        {
            var request = await args.GetRequestAsync();
            var buffer = new byte[request.Value.Length];
            DataReader.FromBuffer(request.Value).ReadBytes(buffer);
            Console.WriteLine($"WRITE_RECEIVED len={buffer.Length} hex={Convert.ToHexString(buffer)}");
            request.Respond();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"WRITE_HANDLER_ERROR={ex.Message}");
        }
        finally
        {
            deferral.Complete();
        }
    };
}

var advertisingParameters = new GattServiceProviderAdvertisingParameters
{
    IsDiscoverable = true,
    IsConnectable = true
};

var startedAt = DateTime.UtcNow;
provider.AdvertisementStatusChanged += (sender, args) =>
{
    Console.WriteLine($"[{(DateTime.UtcNow - startedAt).TotalMilliseconds,7:F0}ms] STATUS_CHANGED={args.Status} error={args.Error}");
};

provider.StartAdvertising(advertisingParameters);
Console.WriteLine($"AdvertisementStatus={provider.AdvertisementStatus}");

var totalAds = 0;
var watcher = new BluetoothLEAdvertisementWatcher
{
    ScanningMode = BluetoothLEScanningMode.Active
};
var sawSelf = false;
watcher.Received += (sender, args) =>
{
    totalAds++;
    var localName = args.Advertisement.LocalName;
    var addr = args.BluetoothAddress.ToString("X12");
    var matches = localName == "BandKey-PC";
    foreach (var uuid in args.Advertisement.ServiceUuids)
    {
        if (uuid == serviceUuid)
        {
            matches = true;
        }
    }

    if (matches)
    {
        sawSelf = true;
        Console.WriteLine($"SELF_ADV addr={addr} rssi={args.RawSignalStrengthInDBm} name='{localName}'");
    }
};
watcher.Start();

var deadline = DateTime.UtcNow.AddSeconds(12);
while (DateTime.UtcNow < deadline && !sawSelf)
{
    await Task.Delay(500);
}

watcher.Stop();
Console.WriteLine($"TOTAL_ADVERTISEMENTS_SEEN={totalAds}");
provider.StopAdvertising();
Console.WriteLine($"AfterStopStatus={provider.AdvertisementStatus}");
Console.WriteLine(sawSelf ? "RESULT=SELF_SCAN_OK" : "RESULT=SELF_SCAN_NO_HIT");
return 0;

static void Dump(Type type)
{
    Console.WriteLine($"--- {type.FullName} ---");
    if (type.IsEnum)
    {
        foreach (var name in Enum.GetNames(type))
        {
            Console.WriteLine($"  {name} = {Convert.ToInt32(Enum.Parse(type, name))}");
        }
        return;
    }

    foreach (var property in type.GetProperties())
    {
        var settable = property.CanWrite ? "set" : "get";
        Console.WriteLine($"  {property.PropertyType.Name} {property.Name} {{{settable}}}");
    }

    foreach (var method in type.GetMethods().Where(m => !m.IsSpecialName && m.DeclaringType == type))
    {
        var parameters = string.Join(", ", method.GetParameters().Select(p => $"{p.ParameterType.Name} {p.Name}"));
        Console.WriteLine($"  {method.ReturnType.Name} {method.Name}({parameters})");
    }
}
