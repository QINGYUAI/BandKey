namespace BandKeyService;

public static class MessageTypes
{
    public const string PairRequest = "pair_request";
    public const string PairChallenge = "pair_challenge";
    public const string PairConfirm = "pair_confirm";
    public const string PairSuccess = "pair_success";
    public const string Unlock = "unlock";
    public const string UnlockResult = "unlock_result";
    public const string Error = "error";
}

public sealed record PairRequest(string? DeviceName, string? DevicePublicKey);
public sealed record PairConfirm(string? PairingId, string? Pin);
public sealed record UnlockRequest(
    string? DeviceId,
    string? Nonce,
    long Timestamp,
    string? Signature,
    string? Method,
    string? KeyHint);
public sealed record PairChallengeResponse(string PairingId);
public sealed record PairSuccessResponse(string DeviceId, string DeviceName);
public sealed record UnlockResultResponse(bool Ok, string? Error, long RequestId);
public sealed record Response(string Type, object Payload);
