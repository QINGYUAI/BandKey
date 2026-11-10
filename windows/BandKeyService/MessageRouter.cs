using System.Security.Cryptography;
using System.Text.Json;

namespace BandKeyService;

public static class MessageRouter
{
    private static readonly JsonSerializerOptions JsonOpts = new()
    {
        PropertyNameCaseInsensitive = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase
    };

    public static string Handle(string json, RouterContext context)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object)
            {
                return Error("bad_request", "根节点必须是 JSON 对象");
            }

            var type = root.TryGetProperty("type", out var typeElement) ? typeElement.GetString() ?? "" : "";
            var payload = root.TryGetProperty("payload", out var payloadElement) ? payloadElement : default;

            switch (type)
            {
                case MessageTypes.PairRequest:
                {
                    var request = Deserialize<PairRequest>(payload);
                    if (request is null || string.IsNullOrWhiteSpace(request.DevicePublicKey))
                    {
                        return Error("bad_request", "缺少 devicePublicKey");
                    }

                    var (pairingId, _) = context.Pairing.StartPairing(request.DeviceName ?? "", request.DevicePublicKey);
                    return Ok(MessageTypes.PairChallenge, new PairChallengeResponse(pairingId));
                }

                case MessageTypes.PairConfirm:
                {
                    var request = Deserialize<PairConfirm>(payload);
                    if (request is null || string.IsNullOrWhiteSpace(request.PairingId) || string.IsNullOrWhiteSpace(request.Pin))
                    {
                        return Error("bad_request", "pair_confirm 参数缺失");
                    }

                    var (ok, pair, error) = context.Pairing.Confirm(request.PairingId, request.Pin);
                    if (!ok || pair is null)
                    {
                        return Error(error, "配对确认失败");
                    }

                    var device = context.Store.Add(pair.DeviceName, pair.PublicKey);
                    context.AutoUnlock.NotifyBaseStateChanged();
                    return Ok(MessageTypes.PairSuccess, new PairSuccessResponse(device.DeviceId, device.Name));
                }

                case MessageTypes.Unlock:
                {
                    var request = Deserialize<UnlockRequest>(payload);
                    if (request is null)
                    {
                        return Error("bad_request", "unlock 参数缺失");
                    }

                    var (ok, error) = context.Verifier.Verify(request);
                    if (!ok)
                    {
                        return Error(error, "解锁请求被拒绝");
                    }

                    if (!context.DevMode)
                    {
                        if (!context.Credentials.IsUsable)
                        {
                            return Error("no_credential", "尚未保存 Windows 解锁凭据，请先在电脑托盘设置密码");
                        }

                        if (context.AutoUnlock.CredentialRejected)
                        {
                            return Error("password_invalid",
                                "保存的 Windows 密码已被系统拒绝，请在电脑托盘重新设置");
                        }

                        if (!context.Pipes.IsCredentialProviderConnected)
                        {
                            return Error("computer_not_locked", "电脑当前未锁定或解锁组件未就绪");
                        }
                    }

                    var requestId = context.AutoUnlock.RequestAutoUnlock();
                    if (requestId <= 0)
                    {
                        return Error("unlock_not_ready", "当前没有可用于自动解锁的已配对设备或凭据");
                    }

                    return Ok(MessageTypes.UnlockResult, new UnlockResultResponse(true, null, requestId));
                }

                default:
                    return Error("unknown_type", $"未知消息类型: {type}");
            }
        }
        catch (JsonException)
        {
            return Error("bad_json", "JSON 解析失败");
        }
        catch (CryptographicException)
        {
            return Error("bad_key", "公钥格式无效");
        }
        catch (Exception ex)
        {
            return Error("internal", ex.Message);
        }
    }

    private static T? Deserialize<T>(JsonElement element)
    {
        return element.ValueKind == JsonValueKind.Undefined
            ? default
            : JsonSerializer.Deserialize<T>(element.GetRawText(), JsonOpts);
    }

    private static string Ok(string type, object payload)
        => JsonSerializer.Serialize(new Response(type, payload), JsonOpts);

    private static string Error(string code, string message)
        => JsonSerializer.Serialize(new Response(MessageTypes.Error, new { code, message }), JsonOpts);
}

public sealed record RouterContext(
    DeviceStore Store,
    PairingManager Pairing,
    UnlockVerifier Verifier,
    AutoUnlockState AutoUnlock,
    PipeServer Pipes,
    CredentialStore Credentials,
    bool DevMode);
