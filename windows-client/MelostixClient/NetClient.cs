using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace MelostixClient;

/// <summary>
/// Immutable snapshot of what to show on screen. See protocol.md in the MelostixProtocol
/// repo for the wire format this is built from.
/// </summary>
public sealed record LyricsState(
    bool Connected,
    string Info,
    string Status,
    string? Title,
    string? Artist,
    string? Previous,
    string? Current,
    string? Next)
{
    public static readonly LyricsState Initial = new(false, "Starting...", "none", null, null, null, null, null);
}

/// <summary>
/// Network client for the Melostix protocol: UDP discovery on port 8421, then
/// newline-delimited JSON over TCP on the port it reports (normally 8420). Reconnects
/// automatically on any drop. No WPF dependency here on purpose - <see cref="StateChanged"/>
/// fires on whatever thread the I/O happened to complete on, the caller is responsible for
/// marshalling to the UI thread (see MainWindow.xaml.cs).
/// </summary>
public sealed class NetClient
{
    public const int DiscoveryPort = 8421;
    public const string ServiceName = "melostixservice";

    /// <summary>Version of the master-slave contract (MelostixProtocol) this client speaks.
    /// Sent in the optional <c>clientHello</c>'s <c>protocolVersion</c> field (informational
    /// only, see protocol.md "Protocol version") - also the version whose "Client
    /// identification" section defines <see cref="ClientType"/> below.</summary>
    private const string ProtocolVersion = "1.2.0";

    /// <summary>Stable, namespaced identifier of this client implementation, sent in the
    /// optional <c>clientHello</c>'s <c>clientType</c> field (protocol 1.2.0) - see
    /// protocol.md, "Client identification (optional)", "Registered clientType values".</summary>
    private const string ClientType = "melostix.windows-wpf";

    private const int DefaultDataPort = 8420;
    private static readonly TimeSpan ReconnectDelay = TimeSpan.FromSeconds(2);
    private static readonly TimeSpan DiscoveryTimeout = TimeSpan.FromSeconds(5);

    private readonly string? _fixedHost;
    private readonly int _fixedPort;
    private readonly string? _password;
    private readonly object _lock = new();
    private LyricsState _current = LyricsState.Initial;

    public event Action<LyricsState>? StateChanged;

    /// <param name="password">Shared password for the master's optional authentication
    /// handshake (protocol 1.1.0, MelostixProtocol). Null/empty = no password configured on
    /// this client - works fine against a master with none configured (protocol 1.0.0
    /// behavior, unchanged), but this client won't be able to authenticate against one that
    /// does require a password.</param>
    public NetClient(string? fixedHost = null, int fixedPort = DefaultDataPort, string? password = null)
    {
        _fixedHost = fixedHost;
        _fixedPort = fixedPort;
        _password = password;
    }

    public async Task RunAsync(CancellationToken cancellationToken)
    {
        while (!cancellationToken.IsCancellationRequested)
        {
            string host;
            int port;

            if (!string.IsNullOrEmpty(_fixedHost))
            {
                host = _fixedHost;
                port = _fixedPort;
                Update(s => s with { Connected = false, Info = $"Connecting to {host}:{port}..." });
            }
            else
            {
                Update(s => s with { Connected = false, Info = "Searching for master (UDP broadcast)..." });
                var found = await DiscoverMasterAsync(cancellationToken).ConfigureAwait(false);
                if (found is null)
                {
                    continue; // timed out, try again
                }
                (host, port) = found.Value;
                Update(s => s with { Connected = false, Info = $"Found master at {host}:{port}, connecting..." });
            }

            await ConnectAndReadAsync(host, port, cancellationToken).ConfigureAwait(false);

            if (cancellationToken.IsCancellationRequested)
            {
                break;
            }
            try
            {
                await Task.Delay(ReconnectDelay, cancellationToken).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private static async Task<(string Host, int Port)?> DiscoverMasterAsync(CancellationToken cancellationToken)
    {
        using var udp = new UdpClient();
        udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, DiscoveryPort));

        using var timeoutCts = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeoutCts.CancelAfter(DiscoveryTimeout);

        while (true)
        {
            UdpReceiveResult result;
            try
            {
                result = await udp.ReceiveAsync(timeoutCts.Token).ConfigureAwait(false);
            }
            catch (OperationCanceledException)
            {
                return null; // discovery timeout (or real shutdown, caller checks the outer token)
            }

            try
            {
                using var doc = JsonDocument.Parse(result.Buffer);
                var root = doc.RootElement;
                if (!root.TryGetProperty("service", out var serviceProp) ||
                    serviceProp.GetString() != ServiceName)
                {
                    continue;
                }
                if (!root.TryGetProperty("port", out var portProp) || !portProp.TryGetInt32(out var port))
                {
                    continue;
                }
                return (result.RemoteEndPoint.Address.ToString(), port);
            }
            catch (JsonException)
            {
                continue; // pacchetto non nostro/malformato, ignorato
            }
        }
    }

    private async Task ConnectAndReadAsync(string host, int port, CancellationToken cancellationToken)
    {
        try
        {
            using var client = new TcpClient();
            await client.ConnectAsync(host, port, cancellationToken).ConfigureAwait(false);

            using var stream = client.GetStream();
            using var reader = new StreamReader(stream, Encoding.UTF8);

            var firstLine = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
            if (firstLine is null)
            {
                Update(s => s with { Connected = false, Info = "Disconnected from master" });
                return;
            }

            // Protocol 1.1.0 (MelostixProtocol): if the master requires a password, the very
            // first line is an authChallenge instead of a normal state update - see
            // protocol.md, "Authentication (optional)". A master with no password configured
            // sends a state update directly, exactly like protocol 1.0.0 - this client stays
            // compatible with both without knowing in advance which one it'll meet.
            var consumedAsHandshake = false;
            if (TryGetChallengeNonce(firstLine, out var nonceBase64))
            {
                if (string.IsNullOrEmpty(_password))
                {
                    Update(s => s with { Connected = false, Info = "Master requires a password (use --password)" });
                    return;
                }
                var response = $"{{\"kind\":\"authResponse\",\"hmac\":\"{ComputeHmac(_password, nonceBase64)}\"}}\n";
                await stream.WriteAsync(Encoding.UTF8.GetBytes(response), cancellationToken).ConfigureAwait(false);
                consumedAsHandshake = true;
            }

            // Protocol 1.2.0 (MelostixProtocol): optional line, sent once per connection right
            // after the authResponse above (or right after connecting, if no password is in
            // use), declaring what kind of client this is - see protocol.md, "Client
            // identification (optional)". The master never waits for it and never replies, so
            // there is nothing to await after writing it.
            var hello = $"{{\"kind\":\"clientHello\",\"clientType\":\"{ClientType}\",\"protocolVersion\":\"{ProtocolVersion}\"}}\n";
            await stream.WriteAsync(Encoding.UTF8.GetBytes(hello), cancellationToken).ConfigureAwait(false);

            Update(s => s with { Connected = true, Info = $"Connected to {host}:{port}" });
            if (!consumedAsHandshake && firstLine.Length > 0)
            {
                ApplyMessage(firstLine);
            }

            while (!cancellationToken.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(cancellationToken).ConfigureAwait(false);
                if (line is null)
                {
                    break; // il master ha chiuso la connessione
                }
                if (line.Length > 0)
                {
                    ApplyMessage(line);
                }
            }
        }
        catch (OperationCanceledException)
        {
            // chiusura in corso, si passa comunque per l'aggiornamento "disconnected" sotto
        }
        catch (Exception ex) when (ex is SocketException or IOException)
        {
            Update(s => s with { Connected = false, Info = $"Connection error: {ex.Message}" });
            return;
        }

        Update(s => s with { Connected = false, Info = "Disconnected from master" });
    }

    private static bool TryGetChallengeNonce(string line, out string nonceBase64)
    {
        nonceBase64 = string.Empty;
        try
        {
            using var doc = JsonDocument.Parse(line);
            var root = doc.RootElement;
            if (!root.TryGetProperty("kind", out var kindProp) || kindProp.GetString() != "authChallenge")
            {
                return false;
            }
            if (root.TryGetProperty("nonce", out var nonceProp) && nonceProp.ValueKind == JsonValueKind.String)
            {
                nonceBase64 = nonceProp.GetString() ?? string.Empty;
            }
            return true;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    private static string ComputeHmac(string password, string nonceBase64)
    {
        var nonce = Convert.FromBase64String(nonceBase64);
        using var hmac = new HMACSHA256(Encoding.UTF8.GetBytes(password));
        return Convert.ToBase64String(hmac.ComputeHash(nonce));
    }

    private void ApplyMessage(string jsonLine)
    {
        try
        {
            using var doc = JsonDocument.Parse(jsonLine);
            var root = doc.RootElement;

            static string? GetString(JsonElement element, string name) =>
                element.TryGetProperty(name, out var prop) && prop.ValueKind == JsonValueKind.String
                    ? prop.GetString()
                    : null;

            Update(s => s with
            {
                Connected = true,
                Status = GetString(root, "status") ?? "none",
                Title = GetString(root, "title"),
                Artist = GetString(root, "artist"),
                Previous = GetString(root, "previous"),
                Current = GetString(root, "current"),
                Next = GetString(root, "next"),
            });
        }
        catch (JsonException)
        {
            // riga malformata: ignorata, si continua con la prossima
        }
    }

    private void Update(Func<LyricsState, LyricsState> updater)
    {
        LyricsState updated;
        lock (_lock)
        {
            _current = updater(_current);
            updated = _current;
        }
        StateChanged?.Invoke(updated);
    }
}
