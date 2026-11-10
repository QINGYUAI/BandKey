using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using BandKeyService;
using Microsoft.Extensions.Hosting;

var options = CommandLine.Parse(args);

var store = new DeviceStore(options.ConfigFile);
store.Load();

var dataDir = Path.GetDirectoryName(Path.GetFullPath(options.ConfigFile)) ?? "";
var pairing = new PairingManager(options.PairPin, Path.Combine(dataDir, "last_pairing_pin.txt"));
var verifier = new UnlockVerifier(store);
var credentials = new CredentialStore(dataDir);
var autoUnlock = new AutoUnlockState(() =>
    store.Devices.Count > 0 && (options.DevMode || credentials.IsUsable));
var pipes = new PipeServer(autoUnlock, credentials, options.DevMode);
pipes.Start();

var activity = new UnlockActivityState();
var router = new RouterContext(
    store,
    pairing,
    verifier,
    autoUnlock,
    pipes,
    credentials,
    activity,
    options.DevMode);
var lan = new LanListener(router);
lan.Start();

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = Array.Empty<string>(),
    ContentRootPath = AppContext.BaseDirectory
});
builder.Services.AddWindowsService(serviceOptions => serviceOptions.ServiceName = "BandKeyService");
builder.WebHost.UseUrls(options.Urls);
var app = builder.Build();

app.Lifetime.ApplicationStopping.Register(() =>
{
    try
    {
        pipes.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }
    catch
    {
        // 停止阶段不阻塞服务退出
    }

    try
    {
        lan.DisposeAsync().AsTask().GetAwaiter().GetResult();
    }
    catch
    {
        // 停止阶段不阻塞服务退出
    }
});

app.UseWebSockets();

app.MapGet("/health", () => Results.Text("ok"));

app.MapGet("/status", (HttpContext context) =>
{
    if (!IsLoopback(context))
    {
        return Results.StatusCode(StatusCodes.Status403Forbidden);
    }

    var recent = activity.Snapshot();
    return Results.Json(new
    {
        ready = autoUnlock.UnlockReady,
        credentialSet = credentials.IsUsable,
        credentialRejected = autoUnlock.CredentialRejected,
        pairedCount = store.Devices.Count,
        credentialProviderConnected = pipes.IsCredentialProviderConnected,
        lanTcpListening = lan.TcpListening,
        lanUdpListening = lan.UdpListening,
        lanTcpPort = LanListener.TcpPort,
        lanUdpPort = LanListener.UdpPort,
        lastUnlockMethod = recent.LastUnlockMethod,
        nfcKeyHint = recent.NfcKeyHint,
        nfcLastUsedAt = recent.NfcLastUsedAt,
        devMode = options.DevMode
    });
});

app.MapGet("/pairing", (HttpContext context) =>
{
    if (!IsLoopback(context))
    {
        return Results.StatusCode(StatusCodes.Status403Forbidden);
    }

    var info = pairing.Current();
    return info is null ? Results.NoContent() : Results.Json(info);
});

app.MapGet("/devices", (HttpContext context) => IsLoopback(context)
    ? Results.Json(store.Devices.Select(d => new { d.DeviceId, d.Name, d.CreatedAt }))
    : Results.StatusCode(StatusCodes.Status403Forbidden));

app.MapDelete("/devices/{id}", (string id, HttpContext context) =>
{
    if (!IsLoopback(context))
    {
        return Results.StatusCode(StatusCodes.Status403Forbidden);
    }

    var removed = store.Remove(id);
    if (removed)
    {
        autoUnlock.NotifyBaseStateChanged();
    }

    return removed ? Results.Ok(new { ok = true }) : Results.NotFound(new { ok = false });
});

app.MapPost("/credential", async (HttpContext context) =>
{
    if (!IsLoopback(context))
    {
        return Results.StatusCode(StatusCodes.Status403Forbidden);
    }

    try
    {
        using var document = await JsonDocument.ParseAsync(context.Request.Body);
        var username = document.RootElement.TryGetProperty("username", out var userElement)
            ? userElement.GetString()
            : null;
        var password = document.RootElement.TryGetProperty("password", out var passElement)
            ? passElement.GetString()
            : null;
        var explicitDomain = document.RootElement.TryGetProperty("domain", out var domainElement)
            ? domainElement.GetString() ?? ""
            : "";

        if (string.IsNullOrWhiteSpace(username) || password is null)
        {
            return Results.BadRequest(new { ok = false, code = "invalid_request", message = "用户名或密码为空" });
        }

        var (accountDomain, accountUser) = WindowsIdentityUtil.SplitAccount(username);
        if (!string.IsNullOrWhiteSpace(explicitDomain))
        {
            accountDomain = explicitDomain.Trim();
        }

        if (!options.DevMode)
        {
            var fullAccount = WindowsIdentityUtil.CombineAccount(accountDomain, accountUser);
            var activeSid = WindowsIdentityUtil.GetActiveConsoleUserSid()
                            ?? WindowsIdentityUtil.GetCurrentProcessUserSid();
            if (!WindowsIdentityUtil.SidMatchesAccount(activeSid, fullAccount))
            {
                return Results.Json(new
                {
                    ok = false,
                    code = "user_mismatch",
                    message = "只能保存当前登录 Windows 用户的凭据"
                }, statusCode: StatusCodes.Status403Forbidden);
            }

            if (!WindowsIdentityUtil.VerifyLogon(accountDomain, accountUser, password))
            {
                return Results.BadRequest(new
                {
                    ok = false,
                    code = "invalid_password",
                    message = "Windows 密码验证失败，请输入登录电脑时使用的密码（不是 PIN）"
                });
            }
        }

        var saveResult = credentials.SaveVerified(accountDomain, accountUser, password);
        if (saveResult != CredentialSaveResult.Saved)
        {
            return Results.Json(new
            {
                ok = false,
                code = saveResult.ToString(),
                message = saveResult == CredentialSaveResult.WriteFailed
                    ? "凭据文件写入失败，请检查 C:\\ProgramData\\BandKey 权限"
                    : "凭据信息无效"
            }, statusCode: StatusCodes.Status400BadRequest);
        }

        autoUnlock.ClearPasswordFailure();
        autoUnlock.NotifyBaseStateChanged();
        pipes.BroadcastReady();
        return Results.Ok(new { ok = true });
    }
    catch (Exception ex)
    {
        return Results.Json(new { ok = false, code = "internal", message = ex.Message },
            statusCode: StatusCodes.Status500InternalServerError);
    }
});

app.MapPost("/rpc", async context =>
{
    if (!IsLoopback(context))
    {
        context.Response.StatusCode = StatusCodes.Status403Forbidden;
        await context.Response.WriteAsync("loopback only");
        return;
    }

    using var reader = new StreamReader(context.Request.Body, Encoding.UTF8);
    var text = await reader.ReadToEndAsync();
    var reply = MessageRouter.Handle(text, router);

    context.Response.ContentType = "application/json; charset=utf-8";
    await context.Response.WriteAsync(reply);
});

app.Map("/bandkey", async context =>
{
    if (!context.WebSockets.IsWebSocketRequest)
    {
        context.Response.StatusCode = 400;
        await context.Response.WriteAsync("WebSocket required");
        return;
    }

    using var ws = await context.WebSockets.AcceptWebSocketAsync();
    var buffer = new byte[65536];

    while (ws.State == WebSocketState.Open)
    {
        using var ms = new MemoryStream();
        WebSocketReceiveResult result;
        do
        {
            result = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), CancellationToken.None);
            if (result.MessageType == WebSocketMessageType.Close)
            {
                break;
            }

            ms.Write(buffer, 0, result.Count);
        }
        while (!result.EndOfMessage);

        if (result.MessageType == WebSocketMessageType.Close)
        {
            break;
        }

        if (result.MessageType == WebSocketMessageType.Text)
        {
            var text = Encoding.UTF8.GetString(ms.ToArray());
            var reply = MessageRouter.Handle(text, router);
            var bytes = Encoding.UTF8.GetBytes(reply);
            await ws.SendAsync(new ArraySegment<byte>(bytes), WebSocketMessageType.Text, true, CancellationToken.None);
        }
    }
});

Console.WriteLine($"BandKeyService listening on {options.Urls}");
if (options.DevMode)
{
    Console.WriteLine("[dev] credential and credential-provider checks are relaxed");
}

app.Run();

static bool IsLoopback(HttpContext context)
{
    var remote = context.Connection.RemoteIpAddress;
    return remote is null || System.Net.IPAddress.IsLoopback(remote);
}
