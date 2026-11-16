using System.Collections;
using System.Linq.Expressions;
using System.Reflection;
using System.Windows.Forms;

namespace BandKeyUiPreview;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        ApplicationConfiguration.Initialize();
        var which = args.Length > 0 ? args[0] : "credential";
        var output = args.Length > 1
            ? args[1]
            : Path.Combine(Path.GetTempPath(), $"bandkey-{which}.png");
        var assembly = Assembly.Load("BandKeyTray");

        using var form = CreateForm(assembly, which);
        form.TopMost = true;
        var timer = new System.Windows.Forms.Timer { Interval = 1400 };
        timer.Tick += (_, _) =>
        {
            timer.Stop();
            Capture(form, output);
            form.Close();
        };
        form.Shown += (_, _) =>
        {
            form.Activate();
            form.BringToFront();
            timer.Start();
        };
        Application.Run(form);
    }

    private static Form CreateForm(Assembly assembly, string which)
    {
        return which switch
        {
            "pairing" => CreatePairing(assembly),
            "devices" => CreateDevices(assembly),
            "popover" => CreatePopover(assembly),
            _ => (Form)Activator.CreateInstance(
                assembly.GetType("BandKeyTray.CredentialDialog", true)!,
                nonPublic: true)!
        };
    }

    private static Form CreatePopover(Assembly assembly)
    {
        var apiType = assembly.GetType("BandKeyTray.ServiceApi", true)!;
        var api = Activator.CreateInstance(apiType, nonPublic: true)!;
        var stateType = assembly.GetType("BandKeyTray.BleUiState", true)!;
        var state = Activator.CreateInstance(stateType, "蓝牙广播中", true, 0)!;
        var stateFactoryType = typeof(Func<>).MakeGenericType(stateType);
        var stateFactory = Expression.Lambda(
            stateFactoryType,
            Expression.Constant(state, stateType)).Compile();
        Action noOp = static () => { };
        var popoverType = assembly.GetType("BandKeyTray.TrayPopoverForm", true)!;
        return (Form)Activator.CreateInstance(
            popoverType,
            BindingFlags.Instance | BindingFlags.Public | BindingFlags.NonPublic,
            binder: null,
            args: new object[]
            {
                api,
                stateFactory,
                noOp,
                noOp,
                noOp,
                noOp,
                noOp,
                noOp,
                noOp
            },
            culture: null)!;
    }

    private static Form CreatePairing(Assembly assembly)
    {
        var infoType = assembly.GetType("BandKeyTray.PairingInfo", true)!;
        var now = DateTimeOffset.UtcNow;
        var info = Activator.CreateInstance(
            infoType,
            "Pixel 9 Pro",
            "481926",
            now,
            now.AddMinutes(5))!;
        return (Form)Activator.CreateInstance(
            assembly.GetType("BandKeyTray.PairingDialog", true)!,
            info)!;
    }

    private static Form CreateDevices(Assembly assembly)
    {
        var apiType = assembly.GetType("BandKeyTray.ServiceApi", true)!;
        var api = Activator.CreateInstance(apiType, nonPublic: true)!;
        var infoType = assembly.GetType("BandKeyTray.DeviceInfo", true)!;
        var listType = typeof(List<>).MakeGenericType(infoType);
        var list = (IList)Activator.CreateInstance(listType)!;
        var now = DateTimeOffset.UtcNow;
        list.Add(Activator.CreateInstance(infoType, "7C4A-19E2-88B0", "Pixel 9 Pro", now.AddDays(-3))!);
        list.Add(Activator.CreateInstance(infoType, "A19F-20C4-1D77", "Xiaomi 15", now.AddDays(-1))!);

        return (Form)Activator.CreateInstance(
            assembly.GetType("BandKeyTray.DevicesDialog", true)!,
            api,
            list)!;
    }

    private static void Capture(Form form, string output)
    {
        using var bitmap = new Bitmap(form.Width, form.Height);
        form.DrawToBitmap(bitmap, new Rectangle(0, 0, form.Width, form.Height));

        bitmap.Save(output);
        Console.WriteLine(output);
    }
}
