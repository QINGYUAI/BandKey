namespace BandKeyService;

public static class MessageTypes
{
    public const int ProtocolVersion = 2;

    public const string PairRequest = "pair_request";
    public const string PairChallenge = "pair_challenge";
    public const string PairConfirm = "pair_confirm";
    public const string PairSuccess = "pair_success";
    public const string Status = "status";
    public const string StatusResult = "status_result";
    public const string Unlock = "unlock";
    public const string UnlockResult = "unlock_result";
    public const string UnlockStatus = "unlock_status";
    public const string UnlockStatusResult = "unlock_status_result";
    public const string Error = "error";
}

public sealed record PairRequest(string? DeviceName, string? DevicePublicKey);
public sealed record PairConfirm(string? PairingId, string? Pin);
public sealed record StatusRequest(string? DeviceId);
public sealed record UnlockRequest(
    string? DeviceId,
    string? Nonce,
    long Timestamp,
    string? Signature,
    string? Method,
    string? KeyHint);
public sealed record UnlockStatusRequest(long RequestId);
public sealed record PairChallengeResponse(string PairingId);
public sealed record PairSuccessResponse(string DeviceId, string DeviceName);
public sealed record StatusResponse(
    int ProtocolVersion,
    string ServerVersion,
    string PcName,
    bool Online,
    bool Paired,
    bool Revoked,
    bool CredentialSet,
    bool CredentialRejected,
    bool CredentialProviderConnected,
    bool IsLocked,
    bool UnlockReady,
    long ServerTime);
public sealed record UnlockResultResponse(
    bool Ok,
    string? Error,
    long RequestId,
    bool Accepted,
    bool Completed,
    string State);
public sealed record UnlockStatusResponse(
    long RequestId,
    string State,
    bool Success,
    string? Error,
    long UpdatedAt,
    bool Completed);
public sealed record Response(string Type, object Payload);
