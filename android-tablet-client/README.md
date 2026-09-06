# android-tablet-client

Android "slave" client for a generic Android 4.4.4 / API 19 tablet (kernel 3.8.13, 800x480
display): receives the 3 lines of text (previous/current/next) around the playback position from
the master over the local network, and displays them fullscreen on a black background.

Standalone Gradle module (`:app-tablet`), does not depend on any other module in this repo.

## Current status

**Verified working end-to-end on real hardware on 2026-08-21** (tablet + phone on the same WiFi
network, including the 1.1.0 password handshake against a master with a password set).

- "Sticky immersive" fullscreen (`SYSTEM_UI_FLAG_IMMERSIVE_STICKY`), guaranteed here since it
  coincides exactly with this module's minSdk.
- `net/` uses Kotlin's `.use { }` on sockets: minSdk 19 is the first API level where
  `Socket`/`DatagramSocket` actually implement `Closeable`, so this is safe at runtime here, not
  just at compile time.
- Text sizes verified on the real 800x480 display in the 2026-08-21 test (`MainActivity.kt`).

## Why no AndroidX/Compose

Modern Compose/AndroidX require minSdk 21; this module is pinned to API 19 (exactly Android 4.4.4
KitKat).

## Master → slave protocol

No HTTP/TLS, raw TCP socket, one JSON object per line, unidirectional push from master to slave,
automatic discovery via UDP broadcast. See the formal protocol in the public
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) repository
([`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md)) — contract
**v1.2.0**: if the master requires a shared password (optional, Settings → Server on the master),
this client sends it via the HMAC-SHA256 handshake described there (`net/MelostixClient.kt`,
`net/ClientSettings.kt`); empty/unset (default) = identical behavior to protocol 1.0.0. The
password can be set from the settings panel ("⋮" icon in the bottom right) — verified on real
hardware on 2026-08-21, like the rest of this client.

Since 1.2.0 (2026-08-24), right after connecting (or right after the `authResponse`, if a password
is in use), the client also sends the optional `clientHello` declaring its own type to the master:
`clientType = "melostix.android-tablet"` (hardcoded, see `MelostixClientProtocol.kt`), plus
`clientVersion` (from Gradle's `versionName`, which required enabling `buildFeatures.buildConfig`
in `build.gradle.kts` — off by default from AGP 8+) and `protocolVersion`. No user configuration: a
master that doesn't read this line behaves exactly as before. **Verified end-to-end on
2026-08-29** on an Android 4.4.4 tablet against an **iOS** master (iPhone SE 2022) — text received
and displayed correctly, no anomalies. First verification against an iOS master rather than
Android: the two master-side implementations are independent, so this is the first proof they
speak the same protocol on the wire. This only covers the connection working end-to-end — it was
not observed whether the master reads and records the identity declared in the `clientHello`.

## How to build

```bash
./gradlew :app-tablet:assembleDebug
```

Toolchain: Gradle 8.9 + AGP 8.7.1, JDK pinned in `gradle.properties` (`org.gradle.java.home`) —
update that path if your JDK 21 lives elsewhere.
