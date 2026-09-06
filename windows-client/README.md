# Windows Client

WPF client for Melostix: a small black window with the 3 lines of text (previous/current/next)
scrolling around the playback position.

Protocol reference:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) in the
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) repository (public, contract
v1.2.0 — speaks the optional password handshake added in 1.1.0, and connects without issues to a
1.0.0 master with no password configured). The discovery/TCP client logic lives in `NetClient.cs`,
no external NuGet package — JSON parsing uses `System.Text.Json` and HMAC-SHA256 for the handshake
uses `System.Security.Cryptography`, both from the BCL. Since 1.2.0, it also sends the optional
identification `clientHello` (`clientType = "melostix.windows-wpf"`, hardcoded, plus
`protocolVersion`) right after connecting (or after the `authResponse`, if a password is in use) —
no user configuration, a master that doesn't read it behaves exactly as before.

**Verified working end-to-end on real hardware on 2026-08-21**, including the 1.1.0 password
handshake (`--password`) against a master with a password set. The 1.2.0 `clientHello` was
**verified end-to-end on 2026-08-29**, from the same Windows PC, against an **iOS** master (iPhone
SE 2022) — text received and displayed correctly, no anomalies. First verification against an iOS
master rather than Android: the two master-side implementations are independent, so this is the
first proof they speak the same protocol on the wire. This only covers the connection working
end-to-end — it was not observed whether the master reads and records the identity declared in the
`clientHello`.

## Requirements

- .NET 9 SDK
- Visual Studio 2022 (17.12+) with the ".NET desktop development" workload, or just the `dotnet`
  CLI

## Opening in Visual Studio 2022

Open `MelostixClient.sln`, press F5.

## Build and run from the CLI

```
dotnet build MelostixClient.sln
dotnet run --project MelostixClient
```

Waits for the master's UDP discovery broadcast and connects automatically. `Esc` to close the
window.

To skip discovery and connect directly:

```
dotnet run --project MelostixClient -- --host <master-ip> --port 8420
```

If the master has a shared password configured (Settings, protocol 1.1.0), pass it with
`--password`; omit the flag if the master has none configured (the default):

```
dotnet run --project MelostixClient -- --password correct-horse-battery-staple
```
