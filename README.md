# Melostix Clients

[Melostix](https://github.com/fabio-radin/MelostixProtocol) is a media player with full-screen
synchronized lyrics. One phone runs the "master" app, plays music, and finds/aligns the lyrics;
everything else on the local network is a "slave" client that just displays them.

This repository holds four such display-only clients, one per platform. Each is an independent
project with its own toolchain — pick the one for your device and build it on its own, there's
nothing to set up across the four.

| Client | Stack | Where it runs |
|---|---|---|
| [`android-tablet-client`](android-tablet-client/) | Kotlin | An Android tablet (API 19+) |
| [`linux-client`](linux-client/) | C++ / SDL2 + Dear ImGui | Linux desktop |
| [`windows-client`](windows-client/) | C# / WPF | Windows desktop |
| [`python-client`](python-client/) | Python / curses | Any terminal (Linux, macOS, Windows) |

## How it works

The master announces itself on the LAN via a UDP broadcast; a client either listens for that
broadcast or connects directly to a known host/port. Once connected, the master pushes one JSON
object per line over a plain TCP socket — no HTTP, no polling — and the client just renders
whatever it receives: the previous, current, and next line of lyrics around the playback position.
The full wire format (ports, JSON shape, optional password handshake, client identification) is
documented in the public [MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol)
repository (`protocol.md`); each client's own README links the specific sections it implements.

Android's local network restriction (opt-in on Android 16, enforced from Android 17) governs
exactly this kind of traffic. Where the Android client of this repository stands with respect
to it, as of 2026-09-19, is recorded in
[`docs/local-network-restriction.md`](docs/local-network-restriction.md).

## Building and running each client

### android-tablet-client

```bash
cd android-tablet-client
./gradlew :app-tablet:assembleDebug
```

Install the resulting APK on the tablet. See [android-tablet-client/README.md](android-tablet-client/README.md)
for requirements and details.

### linux-client

```bash
cd linux-client
git submodule update --init --recursive   # fetches the Dear ImGui submodule
sudo apt-get install build-essential libsdl2-dev libsdl2-ttf-dev libssl-dev fonts-dejavu-core
make
./melostix-client
```

See [linux-client/README.md](linux-client/README.md) for the full dependency list and options.

### windows-client

```bash
cd windows-client
dotnet build MelostixClient.sln
dotnet run --project MelostixClient
```

Or open `MelostixClient.sln` in Visual Studio 2022 and press F5. See
[windows-client/README.md](windows-client/README.md) for requirements.

### python-client

```bash
cd python-client
pip install -r requirements.txt   # only needed on Windows
python melostix_client.py
```

See [python-client/README.md](python-client/README.md) for details.

## Pointing a client at your master

By default every client listens for the master's UDP discovery broadcast and connects
automatically — nothing to configure on a normal LAN. If discovery doesn't reach the client (a
different subnet, a VPN, a network that blocks broadcasts), pass the master's address directly,
e.g.:

```bash
./melostix-client --host <master-ip> --port 8420
```

(the equivalent flag exists on every client — see its own README for the exact syntax). If the
master has an optional shared password configured, pass it too with `--password`.

## License

MIT — see [LICENSE](LICENSE). Third-party components used by these clients (Dear ImGui, SDL2,
etc.) are listed in [THIRD_PARTY.md](THIRD_PARTY.md) with their own licenses.
