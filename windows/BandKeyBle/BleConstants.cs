namespace BandKeyBle;

public static class BleConstants
{
    public static readonly Guid ServiceUuid = new("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid RxCharacteristicUuid = new("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static readonly Guid TxCharacteristicUuid = new("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    public const int MaxMessageBytes = 8192;
    public const byte FrameTerminator = (byte)'\n';
}
