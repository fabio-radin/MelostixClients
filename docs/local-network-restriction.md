# Android's local network restriction, and where these clients stand

**Survey of 2026-09-19. Nothing was changed** — no permission, no `targetSdk`, no runtime
request. This page records what the code says today, so that the question does not have to be
asked again from scratch.

**Read vs. tried.** Everything below was **read**: the sources in this repository, and the two
Android documentation pages linked at the bottom, fetched on 2026-09-19. **Nothing was tried.**
No device, no emulator and no Android SDK were available in the session that wrote this, so the
restriction was never switched on and no build was run. Where a consequence is stated, it is a
consequence of what the documentation says, not something that was observed.

## The clients in this repository

Counted by listing the directories, one per row — not copied from the table in the root README.

| Client | Platform | Is Android's local network restriction relevant? |
|---|---|---|
| `android-tablet-client` | Android (Kotlin) | **yes — this is the only one the rest of this page is about** |
| `linux-client` | Linux (C++ / SDL2) | no: not an Android app |
| `windows-client` | Windows (C# / WPF) | no: not an Android app |
| `python-client` | any terminal (Python) | no: not an Android app |

Four clients, **one of them Android**. The other three open ordinary sockets on a desktop
operating system, which the Android restriction does not reach by definition. Their LAN traffic
is listed at the bottom for completeness, but they need nothing and are not analysed further.

## The short answer

**Nothing is needed, and there are two independent reasons for it.** Either one alone would be
enough.

1. **`android-tablet-client` declares `targetSdk = 19`.** The documentation is explicit that at
   SDK 36 or lower local network access is granted implicitly through `INTERNET`, and that the
   new permission must **not** be added.
2. **An app targeting API 19 cannot be installed on Android 14 or newer in the first place**, so
   it does not normally reach a device where the restriction exists at all.

## 1. The declared `targetSdk` — the question that decides the rest

Read, not assumed:

| Value | Where |
|---|---|
| `targetSdk = 19` | `android-tablet-client/app-tablet/build.gradle.kts:16` |
| `minSdk = 19` | `android-tablet-client/app-tablet/build.gradle.kts:15` |
| `compileSdk = 35` | `android-tablet-client/app-tablet/build.gradle.kts:8` |

`19` is Android 4.4.4 (KitKat), which is the actual hardware this client was written for. It is
**far below 36**, so the client sits in the legacy row of the table below:

| `targetSdk` | What the documentation requires |
|---|---|
| **≤ 36** | **nothing.** Local network access is implicit through `INTERNET`, **including on Android 17**, and the permission must not be declared or requested |
| **≥ 37** | `ACCESS_LOCAL_NETWORK`, declared **and** requested at runtime |

> "The `ACCESS_LOCAL_NETWORK` permission is required only if your app targets Android 17 (SDK 37)
> or higher. If your app targets SDK 36 or lower, local network access is implicitly granted using
> the `INTERNET` permission. For these lower target SDKs, don't add `ACCESS_LOCAL_NETWORK` to your
> manifest or request it at runtime."

**The second reason, which is specific to this client and stronger than the first.** Android 14
refuses to install an app whose `targetSdkVersion` is below 23:

> "Starting with Android 14, apps with a `targetSdkVersion` lower than 23 can't be installed."

The failure is `INSTALL_FAILED_DEPRECATED_SDK_VERSION`, and the documented way past it is a
deliberate developer override, `adb install --bypass-low-target-sdk-block`. So a normal install of
this client onto an Android 16 or 17 device does not happen; and on the one path that does exist —
a developer sideloading it with that flag — the app still targets 19, which is ≤ 36, and therefore
still needs nothing.

**The `targetSdk` was not raised.** Raising it is not what this survey is for, and on this client
it would not be a one-line change: the module is deliberately pinned to API 19 with no
AndroidX/Compose, for hardware that is an Android 4.4.4 tablet.

## 2. The local network traffic this client makes

Found by searching the module for every socket, datagram, multicast and HTTP API, not by reading a
description of it. The whole of it lives in `net/`, and it is **three points**:

| # | What | Where |
|---|---|---|
| 1 | `DatagramSocket(DISCOVERY_PORT)` — binds UDP **8421** on every interface, to hear the master's announcement | `android-tablet-client/app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/DiscoveryListener.kt:32` |
| 2 | `receive(packet)` — **receives** the master's UDP broadcast (this client only listens; it never sends a datagram) | `.../net/DiscoveryListener.kt:51` |
| 3 | `Socket().connect(InetSocketAddress(host.address, host.port), …)` — **outgoing** TCP to the master's LAN address, on the port carried by the discovery packet (default **8420**) | `.../net/MelostixClient.kt:91` |

The ports are constants: `DISCOVERY_PORT = 8421` and `DATA_PORT = 8420`, at
`.../net/MelostixClientProtocol.kt:7` and `:8`.

Everything else on the wire happens **inside the TCP connection already opened at point 3**, so it
is not a separate point of local network access: the reads at `.../net/MelostixClient.kt:102`,
`:127` and `:134`, the optional `authResponse` written at `.../net/MelostixClient.kt:122`, and the
`clientHello` written at `.../net/MelostixClient.kt:155`.

The loop that drives all three is started from the Activity at
`.../MainActivity.kt:67`, and re-enters discovery at `.../net/MelostixClient.kt:82` whenever a
connection ends.

**What is not there**, checked rather than assumed: no multicast, no mDNS or `NsdManager`, no
`.local` resolution, no HTTP client, no `ServerSocket`, no `WifiManager` or `ConnectivityManager`
call. This client never accepts an inbound TCP connection — the master opens, this side connects.

## 3. Permissions declared — answer: nothing needed

The manifest declares exactly **one** permission:

| Permission | Where |
|---|---|
| `android.permission.INTERNET` | `android-tablet-client/app-tablet/src/main/AndroidManifest.xml:5` |

`NEARBY_WIFI_DEVICES` is **not** declared. `ACCESS_LOCAL_NETWORK` is **not** declared. Nothing is
requested at runtime — there is no permission request anywhere in the module.

**And none of that needs to change.** At `targetSdk 19`, `INTERNET` is exactly what the
documentation says is sufficient, and the same page says not to add the new permission. There is
no new permission, therefore **no runtime prompt and nothing new for a store privacy form to
declare.**

## 4. What would happen with the restriction on — answer: nothing

On a device where the restriction is enforced, an app in the legacy row keeps its access. So for
this client the answer is **nothing**: no exception, no dropped packet, no silence.

**For the record, what the documentation says would happen to an app that did fall under it** —
this is a hypothetical about a `targetSdk` this client does not have, not a description of current
behaviour. All three points above are in the governed set: the table on that page lists making an
outgoing TCP connection, and receiving an incoming UDP unicast, multicast or broadcast, as
operations that require the permission. It gives two distinct failure shapes — a blocked TCP
connection surfaces as a **timeout**, and UDP denials surface as **`EPERM`** — and states that the
block is implemented "deep in the networking stack", so it applies through every networking API,
not only raw sockets.

Two details of the page are worth keeping, because they are easy to get wrong from memory. The
restriction is **opt-in during Android 16**, switched on per package with
`adb shell am compat enable RESTRICT_LOCAL_NETWORK <package>`; and in that opt-in phase the
permission that restores access is the temporary `NEARBY_WIFI_DEVICES`, which **migrates to
`ACCESS_LOCAL_NETWORK` in Android 17**. The page does not state whether `bind()` itself fails or
only the subsequent `receive()`; that was looked for and not found, so it is left unanswered here
rather than guessed.

## Out of scope — noticed, written down, not corrected

- **This client has no way to be pointed at a master by hand.** Its only route is the UDP
  discovery broadcast: the settings panel holds the master password and nothing else
  (`.../MainActivity.kt:121`), and there is no command line. The other three clients all accept
  `--host` (`linux-client/src/main.cpp:100`, `windows-client/MelostixClient/MainWindow.xaml.cs:36`,
  `python-client/melostix_client.py:260`). The root `README.md` says "the equivalent flag exists on
  every client", which is true of three of the four. Nothing was changed.
- **A consequence of the point above, if discovery is ever lost for any reason:** the loop at
  `.../net/MelostixClient.kt:82` retries forever and the screen keeps showing "Waiting for
  master...", with no fallback and no distinct error. That is the current design, not a defect
  introduced by anything here.

## Questions this survey could not answer from inside this repository

Written as questions rather than guessed, because the answers live on the master side:

1. If the master ever has to obtain a local network permission at runtime — on either platform —
   can its discovery broadcast be delayed or suppressed while that is pending? From here the two
   cases are indistinguishable: this client would show "Waiting for master..." either way.
2. Is `android-tablet-client` expected to keep working only against the Android-4.4.4-era
   deployment it was built for, or is there an intent to run it on modern Android? The answer
   decides whether the `targetSdk 19` install block above is simply a fact of the target hardware
   or a problem to open.

## The other three clients, for completeness

Not Android, so outside the restriction. Their LAN traffic has the same shape — bind UDP 8421,
receive the broadcast, connect TCP to the master:

| Client | Bind + receive (UDP 8421) | Connect (TCP) |
|---|---|---|
| `linux-client` | `src/net_client.cpp:158`, `:168`, `:184` | `src/net_client.cpp:235`, `:246` |
| `windows-client` | `MelostixClient/NetClient.cs:118`, `:120`, `:130` | `MelostixClient/NetClient.cs:163`, `:164` |
| `python-client` | `melostix_client.py:57`, `:59`, `:64` | `melostix_client.py:123` |

## Sources

Both read on 2026-09-19:

- [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission)
  — the target SDK rule, the governed operations, the failure modes, the `RESTRICT_LOCAL_NETWORK`
  opt-in flag and the `NEARBY_WIFI_DEVICES` → `ACCESS_LOCAL_NETWORK` migration.
- [Behavior changes: all apps (Android 14)](https://developer.android.com/about/versions/14/behavior-changes-all)
  — the minimum installable target API level.
