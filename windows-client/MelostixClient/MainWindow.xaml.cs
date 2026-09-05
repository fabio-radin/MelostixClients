using System.Windows;
using System.Windows.Input;

namespace MelostixClient;

/// <summary>
/// Interaction logic for MainWindow.xaml
/// </summary>
public partial class MainWindow : Window
{
    private readonly MainViewModel _viewModel = new();
    private readonly NetClient _netClient;
    private readonly CancellationTokenSource _cts = new();

    public MainWindow()
    {
        InitializeComponent();
        DataContext = _viewModel;

        var (host, port, password) = ParseArgs(Environment.GetCommandLineArgs());
        _netClient = new NetClient(host, port, password);
        _netClient.StateChanged += OnStateChanged;

        Loaded += (_, _) => _ = _netClient.RunAsync(_cts.Token);
        Closed += (_, _) => _cts.Cancel();
    }

    private static (string? Host, int Port, string? Password) ParseArgs(string[] args)
    {
        string? host = null;
        int port = 8420;
        string? password = null;
        // args[0] is the executable path, skip it.
        for (var i = 1; i < args.Length; i++)
        {
            if (args[i] == "--host" && i + 1 < args.Length)
            {
                host = args[++i];
            }
            else if (args[i] == "--port" && i + 1 < args.Length && int.TryParse(args[i + 1], out var parsed))
            {
                port = parsed;
                i++;
            }
            else if (args[i] == "--password" && i + 1 < args.Length)
            {
                // Shared password for the master's optional authentication handshake
                // (protocol 1.1.0, MelostixProtocol) - omit if the master has none configured.
                password = args[++i];
            }
        }
        return (host, port, password);
    }

    private void OnStateChanged(LyricsState state)
    {
        // StateChanged fires from a background async continuation, not the UI thread.
        Dispatcher.Invoke(() => _viewModel.ApplyState(state));
    }

    private void Window_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Escape)
        {
            Close();
        }
    }
}
