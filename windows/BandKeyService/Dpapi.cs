using System.Runtime.InteropServices;

namespace BandKeyService;

public static class Dpapi
{
    private const uint CryptProtectLocalMachine = 0x4;

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DataBlob pDataIn,
        string? szDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        uint dwFlags,
        out DataBlob pDataOut);

    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr hMem);

    [StructLayout(LayoutKind.Sequential)]
    private struct DataBlob
    {
        public int cbData;
        public IntPtr pbData;
    }

    public static byte[] Protect(byte[] data)
    {
        var input = new DataBlob { cbData = data.Length, pbData = Marshal.AllocHGlobal(data.Length) };
        try
        {
            Marshal.Copy(data, 0, input.pbData, data.Length);
            if (!CryptProtectData(ref input, "BandKey credential", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero,
                    CryptProtectLocalMachine, out var output))
            {
                throw new InvalidOperationException($"CryptProtectData 失败，错误码 {Marshal.GetLastWin32Error()}");
            }

            try
            {
                var result = new byte[output.cbData];
                Marshal.Copy(output.pbData, result, 0, output.cbData);
                return result;
            }
            finally
            {
                LocalFree(output.pbData);
            }
        }
        finally
        {
            Marshal.FreeHGlobal(input.pbData);
        }
    }
}
