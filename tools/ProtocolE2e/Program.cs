using System.Net.Http.Json;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

static void Assert(bool condition, string message)
{
    if (!condition)
    {
        throw new Exception("ASSERT: " + message);
    }
}

static async Task<JsonElement> Exchange(ClientWebSocket socket, object request)
{
    var json = JsonSerializer.Serialize(request);
    var send = Encoding.UTF8.GetBytes(json);
    await socket.SendAsync(send, WebSocketMessageType.Text, true, CancellationToken.None);

    var buffer = new byte[65536];
    using var stream = new MemoryStream();
    WebSocketReceiveResult result;
    do
    {
        result = await socket.ReceiveAsync(buffer, CancellationToken.None);
        stream.Write(buffer, 0, result.Count);
    }
    while (!result.EndOfMessage);

    using var document = JsonDocument.Parse(stream.ToArray());
    return document.RootElement.Clone();
}

static ECDsa NewKey() => ECDsa.Create(ECCurve.NamedCurves.nistP256);

static string Spki(ECDsa key) => Convert.ToBase64String(key.ExportSubjectPublicKeyInfo());

static string SignRevoke(ECDsa key, string deviceId, string nonce)
{
    var data = $"revoke:{deviceId}:{nonce}";
    return Convert.ToBase64String(key.SignData(
        Encoding.UTF8.GetBytes(data),
        HashAlgorithmName.SHA256,
        DSASignatureFormat.IeeeP1363FixedFieldConcatenation));
}

using var socket = new ClientWebSocket();
await socket.ConnectAsync(new Uri("ws://127.0.0.1:23299/bandkey"), CancellationToken.None);

async Task<string> Pair(ECDsa key)
{
    var challenge = await Exchange(socket, new
    {
        type = "pair_request",
        payload = new
        {
            deviceName = "E2E手机",
            devicePublicKey = Spki(key),
            installId = "install-e2e"
        }
    });
    var pairingId = challenge.GetProperty("payload").GetProperty("pairingId").GetString();
    Assert(!string.IsNullOrWhiteSpace(pairingId), "pairingId returned");

    var confirm = await Exchange(socket, new
    {
        type = "pair_confirm",
        payload = new { pairingId, pin = "123456" }
    });
    Assert(confirm.GetProperty("type").GetString() == "pair_success", "pair success");
    return confirm.GetProperty("payload").GetProperty("deviceId").GetString()!;
}

using var firstKey = NewKey();
var firstDeviceId = await Pair(firstKey);
using var secondKey = NewKey();
var secondDeviceId = await Pair(secondKey);
Assert(firstDeviceId == secondDeviceId, "same install rotates same device id");

using var http = new HttpClient { BaseAddress = new Uri("http://127.0.0.1:23299") };
var devicesAfterRotate = await http.GetFromJsonAsync<JsonElement>("/devices");
Assert(devicesAfterRotate.GetArrayLength() == 1, "rotation keeps one authorization record");
var record = devicesAfterRotate[0];
Assert(record.GetProperty("deviceId").GetString() == secondDeviceId, "record device id matches");
Assert(record.GetProperty("installIdBound").GetBoolean(), "record is bound to install id");

var status = await Exchange(socket, new
{
    type = "status",
    payload = new { deviceId = secondDeviceId }
});
Assert(status.GetProperty("payload").GetProperty("paired").GetBoolean(), "record is paired before revoke");

var oldNonce = "old-ticket-0123456789";
var oldTicket = await Exchange(socket, new
{
    type = "device_revoke",
    payload = new
    {
        deviceId = secondDeviceId,
        nonce = oldNonce,
        signature = SignRevoke(firstKey, secondDeviceId, oldNonce)
    }
});
Assert(oldTicket.GetProperty("type").GetString() == "error", "old key cannot revoke rotated record");
Assert(oldTicket.GetProperty("payload").GetProperty("code").GetString() == "bad_signature",
    "old key gets bad_signature");

var nonce = "new-ticket-0123456789";
var revoke = await Exchange(socket, new
{
    type = "device_revoke",
    payload = new
    {
        deviceId = secondDeviceId,
        nonce,
        signature = SignRevoke(secondKey, secondDeviceId, nonce)
    }
});
Assert(revoke.GetProperty("type").GetString() == "device_revoke_result", "revoke result type");
Assert(revoke.GetProperty("payload").GetProperty("removed").GetBoolean(), "record removed");

var devicesAfterRevoke = await http.GetFromJsonAsync<JsonElement>("/devices");
Assert(devicesAfterRevoke.GetArrayLength() == 0, "revoke leaves no authorization record");
var statusAfter = await Exchange(socket, new
{
    type = "status",
    payload = new { deviceId = secondDeviceId }
});
Assert(!statusAfter.GetProperty("payload").GetProperty("paired").GetBoolean(), "record no longer paired");
Assert(statusAfter.GetProperty("payload").GetProperty("revoked").GetBoolean(), "record is revoked");

Console.WriteLine(JsonSerializer.Serialize(new
{
    deviceId = secondDeviceId,
    rotationKeptOneRecord = true,
    oldKeyRejected = true,
    revokeVerified = true,
    revoked = true
}, new JsonSerializerOptions { WriteIndented = true }));
