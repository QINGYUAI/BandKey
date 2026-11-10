namespace BandKeyService;

public sealed record CommandLineOptions(string ConfigFile, string Urls, string? PairPin, bool DevMode);

public static class CommandLine
{
    public static CommandLineOptions Parse(string[] args)
    {
        string? config = null;
        string urls = "http://0.0.0.0:23234";
        string? pairPin = null;
        var devMode = false;

        for (var i = 0; i < args.Length; i++)
        {
            switch (args[i])
            {
                case "--config" when i + 1 < args.Length:
                    config = args[++i];
                    break;
                case "--urls" when i + 1 < args.Length:
                    urls = args[++i];
                    break;
                case "--pair-pin" when i + 1 < args.Length:
                    pairPin = args[++i];
                    break;
                case "--dev":
                    devMode = true;
                    break;
            }
        }

        config ??= Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData),
            "BandKey",
            "devices.json");

        return new CommandLineOptions(config, urls, pairPin, devMode);
    }
}
