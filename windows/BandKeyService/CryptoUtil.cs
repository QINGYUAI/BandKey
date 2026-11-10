using System.Security.Cryptography;
using System.Text;

namespace BandKeyService;

public static class CryptoUtil
{
    public static ECDsa ImportPublicKey(string spkiBase64)
    {
        var ecdsa = ECDsa.Create();
        ecdsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(spkiBase64), out _);
        return ecdsa;
    }

    public static bool VerifySignature(string spkiBase64, string data, string signatureBase64)
    {
        using var ecdsa = ImportPublicKey(spkiBase64);
        var signature = Convert.FromBase64String(signatureBase64);
        return ecdsa.VerifyData(Encoding.UTF8.GetBytes(data), signature, HashAlgorithmName.SHA256);
    }
}
