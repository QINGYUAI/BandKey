using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace BandKeyService;

/// <summary>
/// 局域网备用通道：
/// - UDP 47322 响应 BANDKEY_DISCOVER / LAN_DISCOVER 发现请求；
/// - TCP 47321 使用与 WebSocket/BLE 相同的 NDJSON JSON 协议。
/// 解锁仍然依赖手机私钥签名，发现报文本身不授予任何权限。
/// </summary>
public sealed class LanListener : IAsyncDisposable
{
    public const int TcpPort = 47321;
    public const int UdpPort = 47322;

    private static readonly Encoding Utf8 = new UTF8Encoding(false);

    private readonly RouterContext _router;
    private readonly CancellationTokenSource _cts = new();
    private TcpListener? _tcp;
    private UdpClient? _udp;
    private Task? _tcpLoop;
    private Task? _udpLoop;

    public LanListener(RouterContext router) => _router = router;

    public bool TcpListening { get; private set; }
    public bool UdpListening { get; private set; }

    public void Start()
    {
        try
        {
            _tcp = new TcpListener(IPAddress.Any, TcpPort);
            _tcp.Start();
            TcpListening = true;
            _tcpLoop = Task.Run(() => RunTcpAsync(_cts.Token));
            ServiceLog.Write($"[lan] TCP listening on {TcpPort}");
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[lan] TCP unavailable: {ex.Message}");
        }

        try
        {
            _udp = new UdpClient(new IPEndPoint(IPAddress.Any, UdpPort));
            UdpListening = true;
            _udpLoop = Task.Run(() => RunUdpAsync(_cts.Token));
            ServiceLog.Write($"[lan] UDP discovery listening on {UdpPort}");
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[lan] UDP unavailable: {ex.Message}");
        }
    }

    private async Task RunTcpAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _tcp is not null)
        {
            TcpClient? client = null;
            try
            {
                client = await _tcp.AcceptTcpClientAsync(ct).ConfigureAwait(false);
                var connected = client;
                client = null;
                _ = Task.Run(() => HandleTcpClientAsync(connected, ct), ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (Exception ex)
            {
                ServiceLog.Write($"[lan] TCP accept error: {ex.Message}");
                try
                {
                    await Task.Delay(1000, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
            finally
            {
                client?.Dispose();
            }
        }
    }

    private async Task HandleTcpClientAsync(TcpClient client, CancellationToken ct)
    {
        try
        {
            using (client)
            {
                client.NoDelay = true;
                if (client.Client.RemoteEndPoint is not IPEndPoint remote
                    || !NetworkAddressUtil.IsPrivateOrLoopback(remote.Address))
                {
                    return;
                }

                using var stream = client.GetStream();
                using var reader = new StreamReader(stream, Utf8, false, 4096, leaveOpen: true);
                using var writer = new StreamWriter(stream, Utf8, 4096, leaveOpen: true) { AutoFlush = true };

                while (!ct.IsCancellationRequested)
                {
                    var line = await reader.ReadLineAsync(ct).ConfigureAwait(false);
                    if (line is null)
                    {
                        break;
                    }

                    if (line.Length is 0 or > 16384)
                    {
                        break;
                    }

                    var reply = MessageRouter.Handle(line, _router);
                    await writer.WriteLineAsync(reply).ConfigureAwait(false);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // 正常关闭
        }
        catch (Exception ex)
        {
            ServiceLog.Write($"[lan] TCP client error: {ex.Message}");
        }
    }

    private async Task RunUdpAsync(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested && _udp is not null)
        {
            try
            {
                var result = await _udp.ReceiveAsync(ct).ConfigureAwait(false);
                var text = Utf8.GetString(result.Buffer).Trim();
                if (!text.StartsWith("BANDKEY_DISCOVER", StringComparison.OrdinalIgnoreCase)
                    && !text.StartsWith("LAN_DISCOVER", StringComparison.OrdinalIgnoreCase))
                {
                    continue;
                }

                if (!NetworkAddressUtil.IsPrivateOrLoopback(result.RemoteEndPoint.Address))
                {
                    continue;
                }

                var payload = JsonSerializer.Serialize(new
                {
                    @event = "BANDKEY_PC",
                    name = Environment.MachineName,
                    port = TcpPort,
                    addresses = GetLocalIPv4Addresses()
                });
                var bytes = Utf8.GetBytes(payload);
                await _udp.SendAsync(bytes, result.RemoteEndPoint, ct).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (Exception ex)
            {
                ServiceLog.Write($"[lan] UDP error: {ex.Message}");
                try
                {
                    await Task.Delay(500, ct).ConfigureAwait(false);
                }
                catch (OperationCanceledException)
                {
                    break;
                }
            }
        }
    }

    public static List<string> GetLocalIPv4Addresses()
    {
        try
        {
            return NetworkInterface.GetAllNetworkInterfaces()
                .Where(nic => nic.OperationalStatus == OperationalStatus.Up
                              && nic.NetworkInterfaceType is not NetworkInterfaceType.Loopback
                                  and not NetworkInterfaceType.Tunnel)
                .SelectMany(nic => nic.GetIPProperties().UnicastAddresses)
                .Where(address => address.Address.AddressFamily == AddressFamily.InterNetwork)
                .Select(address => address.Address.ToString())
                .Where(address => !address.StartsWith("169.254.", StringComparison.Ordinal))
                .Distinct()
                .ToList();
        }
        catch (NetworkInformationException)
        {
            return new List<string>();
        }
    }

    public async ValueTask DisposeAsync()
    {
        _cts.Cancel();
        try
        {
            _tcp?.Stop();
        }
        catch
        {
            // 忽略关闭异常
        }

        try
        {
            _udp?.Dispose();
        }
        catch
        {
            // 忽略关闭异常
        }

        var tasks = new[] { _tcpLoop, _udpLoop }.Where(t => t is not null).Cast<Task>().ToArray();
        if (tasks.Length > 0)
        {
            try
            {
                await Task.WhenAll(tasks).WaitAsync(TimeSpan.FromSeconds(3)).ConfigureAwait(false);
            }
            catch
            {
                // 停止超时不阻塞服务退出
            }
        }

        _cts.Dispose();
    }
}
