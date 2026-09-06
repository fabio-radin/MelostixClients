# app-tablet — class map

Client for a generic Android 4.4.4 tablet (800x480 display, landscape, **minSdk 19**). See
[`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) for ports,
framing, and the connection lifecycle — not repeated here. The module has only 4 files, all
documented on this page. The UI dimensions (`MainActivity`) have not yet been verified on real
device.

## `MainActivity` (`class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/MainActivity.kt`

The app's only Activity. Builds the black fullscreen overlay with the 3 lines of text
(previous/current/next) programmatically (no XML layout) and implements `MelostixClientListener`
to receive updates from `MelostixClient`. Uses only the standard `FLAG_FULLSCREEN`, does not drive
any proprietary backlight — no settings panel besides the master password, because "a normal tablet
already exposes its own brightness control at the system level" — and in `onWindowFocusChanged`
uses the full "sticky immersive" combination (`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` +
`LAYOUT_STABLE`/`LAYOUT_HIDE_NAVIGATION`/`LAYOUT_FULLSCREEN`/`HIDE_NAVIGATION`/`FULLSCREEN`),
available because minSdk 19 guarantees it from the start.

| Method/Callback | Description | References |
|---|---|---|
| `fun onCreate(savedInstanceState: Bundle?)` | Sets the window flags (fullscreen + keep-screen-on), builds the view tree (lyrics view only, no extra panel), and shows "Waiting for master...". | overrides `Activity.onCreate` |
| `fun onStart()` | Starts `melostixClient.start()`. | overrides `Activity.onStart`; calls `MelostixClient.start()` |
| `fun onStop()` | Stops `melostixClient.stop()`. | overrides `Activity.onStop`; calls `MelostixClient.stop()` |
| `private fun buildLyricsView(): View` | Creates the 3 `TextView`s (prev/current/next) in a centered vertical `LinearLayout`. | called by `onCreate` |
| `private fun lyricLine(dimmed: Boolean): TextView` | Factory for a line of text, dimmed (gray, 20sp) or full (white, 28sp) — sizes not yet verified on real device. | called by `buildLyricsView` |
| `private fun showStatusMessage(message: String)` | Shows a single centered message, clears prev/next — used for every non-"synced" state. | called by `onCreate`, `onUpdate`, `onConnectionStateChanged` |
| `override fun onUpdate(update: LyricsUpdate)` (`MelostixClientListener` callback) | Updates the UI based on `update.status` ("synced" populates the 3 lines, other states show a message via `showStatusMessage`). | invoked by `MelostixClient.parseAndNotify` via `mainHandler.post` |
| `override fun onConnectionStateChanged(connected: Boolean)` (`MelostixClientListener` callback) | If `connected == false`, shows "Lost connection to master, searching again...". | invoked by `MelostixClient.notifyConnectionState` via `mainHandler.post` |
| `fun onWindowFocusChanged(hasFocus: Boolean)` | When the window gains focus, sets the "sticky immersive" `systemUiVisibility` flags (requires API 19, always available here). | overrides `Activity.onWindowFocusChanged` |

**Depends on**: `MelostixClient`, `MelostixClientListener`, `LyricsUpdate` (net/)

## `DiscoveredHost` (`data class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/DiscoveryListener.kt`

`(address: InetAddress, port: Int)` pair representing the master found via UDP broadcast.

**Used by**: `DiscoveryListener.listenOnce` (return value), `MelostixClient.runLoop`/`readFrom` (consumers)

## `DiscoveryListener` (`object`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/DiscoveryListener.kt`

Listens for a single UDP broadcast packet from the master (`MelostixClientDiscoveryBroadcaster` on
the app-master side, see
[`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md)) and
extracts the announced host/TCP port — no manual configuration. Uses `.use {}` on the
`DatagramSocket`: this module's minSdk 19 is the first API level where `DatagramSocket` actually
implements `Closeable`, so the idiomatic construct is safe at runtime too (not just at compile time
against the modern SDK stub).

| Method/Callback | Description | References |
|---|---|---|
| `fun listenOnce(timeoutMs: Int): DiscoveredHost?` | Opens a `DatagramSocket` on the discovery port inside a `.use {}` block, blocks until a valid packet arrives or the timeout expires, validates the JSON's `service` field against `MelostixClientProtocol.SERVICE_NAME`, and returns `DiscoveredHost` or `null` (timeout, different service, or error — the caller can retry). | calls `MelostixClientProtocol.DISCOVERY_PORT`/`DATA_PORT`/`SERVICE_NAME`; called by `MelostixClient.runLoop` |

**Depends on**: `MelostixClientProtocol`
**Used by**: `MelostixClient` (`runLoop`)

## `LyricsUpdate` (`data class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Snapshot of a status message received from the master: `title`, `artist`, `status` (`"synced"` /
`"loading"` / `"plain"` / `"not_found"` / `"error"` / other), plus the three lines
`previous`/`current`/`next` (populated only when `status == "synced"`).

**Used by**: `MainActivity.onUpdate` (consumer), `MelostixClient.parseAndNotify` (producer)

## `MelostixClientListener` (`interface`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Callback towards the UI: two methods, both invoked by `MelostixClient` already on the main thread
(via `Handler`), never from the network thread.

| Method/Callback | Description | References |
|---|---|---|
| `fun onUpdate(update: LyricsUpdate)` (callback) | New status message from the master (one JSON line from the TCP channel). | implemented by `MainActivity`; invoked by `MelostixClient.parseAndNotify` |
| `fun onConnectionStateChanged(connected: Boolean)` (callback) | TCP connection state change (connected/disconnected). | implemented by `MainActivity`; invoked by `MelostixClient.notifyConnectionState` |

**Used by**: `MainActivity` (the only implementer in the module)

## `MelostixClient` (`class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Manages the entire connection lifecycle with the master: UDP discovery → TCP connection → reading
JSON messages line by line → automatic reconnection if the connection drops or is never found
(loops back to the top and calls `DiscoveryListener.listenOnce` again). Runs on a dedicated
`Thread` (`isDaemon = true`), no coroutines/AndroidX — a minimal skeleton to avoid an extra Gradle
dependency on such a small client. Uses a `Handler(Looper.getMainLooper())` to marshal callbacks
to `MainActivity` on the UI thread. `readFrom` wraps the read in `socket.use { }`: minSdk 19
guarantees `Closeable` on `Socket`, so no manual `close()` in a `finally` block is needed.

| Method/Callback | Description | References |
|---|---|---|
| `fun start()` | Idempotent (no-op if already `running`); starts the dedicated thread on `runLoop`. | called by `MainActivity.onStart` |
| `fun stop()` | Sets `running = false` and interrupts the thread. | called by `MainActivity.onStop` |
| `private fun runLoop()` | Main loop on the dedicated thread: while `running`, calls `DiscoveryListener.listenOnce`, and as soon as it finds a host calls `readFrom(host)`; on return (connection closed) notifies `onConnectionStateChanged(false)` and restarts discovery. | calls `DiscoveryListener.listenOnce`, `readFrom`, `notifyConnectionState` |
| `private fun readFrom(host: DiscoveredHost)` | Opens a TCP `Socket` to the discovered host (with a connection timeout), then inside `socket.use { }` notifies `onConnectionStateChanged(true)` and reads lines from a `BufferedReader` until `running` is false or EOF, passing each line to `parseAndNotify`. | called by `runLoop`; calls `parseAndNotify`, `notifyConnectionState` |
| `private fun parseAndNotify(line: String)` | Parses the line as a `JSONObject`, builds a `LyricsUpdate` (invalid lines are logged and dropped), and posts `listener.onUpdate(update)` on the main thread. | called by `readFrom`; invokes `MelostixClientListener.onUpdate` (**callback towards `MainActivity`**) |
| `private fun notifyConnectionState(connected: Boolean)` | Posts `listener.onConnectionStateChanged(connected)` on the main thread. | called by `runLoop`, `readFrom`; invokes `MelostixClientListener.onConnectionStateChanged` (**callback towards `MainActivity`**) |
| `private fun JSONObject.optStringOrNull(key: String): String?` | Extension helper: `null` if the JSON field is explicitly `null`, otherwise `optString`. | called by `parseAndNotify` |

**Depends on**: `DiscoveryListener`, `DiscoveredHost`, `MelostixClientProtocol`, `MelostixClientListener`, `LyricsUpdate`
**Used by**: `MainActivity` (built with `this` as listener, driven by `start()`/`stop()`)

## `MelostixClientProtocol` (`object`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClientProtocol.kt`

Protocol constants (UDP discovery port, TCP data port, service name) — a hand-maintained copy kept
in sync with `com.hardrex.melostix.net.MelostixClientProtocol` on the app-master side, since there
is no shared Gradle module between the two. Current values: `DISCOVERY_PORT = 8421`,
`DATA_PORT = 8420`, `SERVICE_NAME = "melostixservice"`. See
[`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) for the
authoritative definition of these values in the shared contract.

**Used by**: `DiscoveryListener` (`DISCOVERY_PORT`, `SERVICE_NAME`, `DATA_PORT`), `MelostixClient` (`DISCOVERY_PORT`, in logging)
