using System.IO.Pipes;
using System.Text;
using System.Text.Json;

namespace BandKeyTray;

internal sealed record CredentialSaveResult(bool Success, string Error, bool PipeUnavailable);

internal static class TrayPipeClient
{
    private const string PipeName = "BandKeyTrayPipe";

    public static async Task<CredentialSaveResult> SaveCredentialAsync(
        string domain, string username, string password)
    {
        try
        {
            using var pipe = new NamedPipeClientStream(".", PipeName, PipeDirection.InOut, PipeOptions.Asynchronous);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await pipe.ConnectAsync(timeout.Token).ConfigureAwait(false);

            var request = JsonSerializer.Serialize(new
            {
                cmd = "SAVE_CREDENTIAL",
                domain,
                username,
                password
            });
            var payload = Encoding.UTF8.GetBytes(request + "\n");
            await pipe.WriteAsync(payload, timeout.Token).ConfigureAwait(false);
            await pipe.FlushAsync(timeout.Token).ConfigureAwait(false);

            using var reader = new StreamReader(pipe, Encoding.UTF8, false, 4096, leaveOpen: true);
            var line = await reader.ReadLineAsync(timeout.Token).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(line))
            {
                return new CredentialSaveResult(false, "服务没有返回保存结果", false);
            }

            using var document = JsonDocument.Parse(line);
            var root = document.RootElement;
            var success = root.TryGetProperty("success", out var successElement) && successElement.GetBoolean();
            var error = root.TryGetProperty("error", out var errorElement)
                ? errorElement.GetString() ?? ""
                : "";
            return new CredentialSaveResult(success, error, false);
        }
        catch (Exception ex) when (ex is IOException or TimeoutException or OperationCanceledException)
        {
            return new CredentialSaveResult(false, ex.Message, true);
        }
        catch (Exception ex)
        {
            return new CredentialSaveResult(false, ex.Message, false);
        }
    }
}
