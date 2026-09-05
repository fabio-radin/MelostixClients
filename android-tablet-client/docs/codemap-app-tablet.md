# app-tablet — mappa delle classi

Client per un tablet Android 4.4.4 generico (display 800x480, landscape, **minSdk 19**). Vedi
[`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) per porte,
framing e ciclo di vita della connessione — non ripetuto qui. Il modulo ha solo 4 file, tutti
documentati in questa pagina. Le dimensioni della UI (`MainActivity`) non sono ancora state
verificate su device reale.

## `MainActivity` (`class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/MainActivity.kt`

Unica Activity dell'app. Costruisce a codice (nessun XML layout) l'overlay fullscreen nero con le 3
righe di testo (precedente/corrente/successiva) e implementa `MelostixClientListener` per ricevere gli
aggiornamenti da `MelostixClient`. Usa solo `FLAG_FULLSCREEN` standard, non pilota alcun backlight
proprietario — niente pannello impostazioni oltre alla password del master, perché "un tablet
normale espone già la propria regolazione luminosità a livello di sistema" — e in
`onWindowFocusChanged` usa la combinazione "sticky immersive" completa
(`SYSTEM_UI_FLAG_IMMERSIVE_STICKY` + `LAYOUT_STABLE`/`LAYOUT_HIDE_NAVIGATION`/`LAYOUT_FULLSCREEN`/
`HIDE_NAVIGATION`/`FULLSCREEN`), disponibile perché minSdk 19 la garantisce da subito.

| Metodo/Callback | Descrizione | Riferimenti |
|---|---|---|
| `fun onCreate(savedInstanceState: Bundle?)` | Imposta i flag finestra (fullscreen + keep-screen-on), costruisce la view tree (solo lyrics view, nessun pannello extra) e mostra "In attesa del master...". | override `Activity.onCreate` |
| `fun onStart()` | Avvia `melostixClient.start()`. | override `Activity.onStart`; chiama `MelostixClient.start()` |
| `fun onStop()` | Ferma `melostixClient.stop()`. | override `Activity.onStop`; chiama `MelostixClient.stop()` |
| `private fun buildLyricsView(): View` | Crea le 3 `TextView` (prev/current/next) in una `LinearLayout` verticale centrata. | chiamato da `onCreate` |
| `private fun lyricLine(dimmed: Boolean): TextView` | Factory di una riga di testo, dimmata (grigio, 20sp) o piena (bianco, 28sp) — dimensioni non ancora verificate su device reale. | chiamato da `buildLyricsView` |
| `private fun showStatusMessage(message: String)` | Mostra un solo messaggio centrale, svuota prev/next — usato per tutti gli stati non-"synced". | chiamato da `onCreate`, `onUpdate`, `onConnectionStateChanged` |
| `override fun onUpdate(update: LyricsUpdate)` (callback `MelostixClientListener`) | Aggiorna la UI in base a `update.status` ("synced" popola le 3 righe, gli altri stati mostrano un messaggio via `showStatusMessage`). | invocato da `MelostixClient.parseAndNotify` tramite `mainHandler.post` |
| `override fun onConnectionStateChanged(connected: Boolean)` (callback `MelostixClientListener`) | Se `connected == false` mostra "Connessione al master persa, ricerco di nuovo...". | invocato da `MelostixClient.notifyConnectionState` tramite `mainHandler.post` |
| `fun onWindowFocusChanged(hasFocus: Boolean)` | Quando la finestra ottiene focus, imposta i flag `systemUiVisibility` "sticky immersive" (richiede API 19, sempre disponibile qui). | override `Activity.onWindowFocusChanged` |

**Dipende da**: `MelostixClient`, `MelostixClientListener`, `LyricsUpdate` (net/)

## `DiscoveredHost` (`data class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/DiscoveryListener.kt`

Coppia `(address: InetAddress, port: Int)` che rappresenta il master trovato via broadcast UDP.

**Usata da**: `DiscoveryListener.listenOnce` (valore di ritorno), `MelostixClient.runLoop`/`readFrom` (consumatori)

## `DiscoveryListener` (`object`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/DiscoveryListener.kt`

Ascolta un singolo pacchetto broadcast UDP del master (`MelostixClientDiscoveryBroadcaster` lato
app-master, vedi [`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md))
e ne estrae host/porta TCP annunciati — nessuna configurazione manuale. Usa `.use {}` sul
`DatagramSocket`: minSdk 19 di questo modulo è il primo livello API in cui `DatagramSocket`
implementa davvero `Closeable`, quindi il costrutto idiomatico è sicuro anche a runtime (non solo a
compile-time contro lo stub SDK moderno).

| Metodo/Callback | Descrizione | Riferimenti |
|---|---|---|
| `fun listenOnce(timeoutMs: Int): DiscoveredHost?` | Apre un `DatagramSocket` sulla porta di discovery dentro un blocco `.use {}`, blocca fino a un pacchetto valido o al timeout, valida il campo `service` del JSON contro `MelostixClientProtocol.SERVICE_NAME`, ritorna `DiscoveredHost` o `null` (timeout, servizio diverso, o errore — il chiamante può ritentare). | chiama `MelostixClientProtocol.DISCOVERY_PORT`/`DATA_PORT`/`SERVICE_NAME`; chiamato da `MelostixClient.runLoop` |

**Dipende da**: `MelostixClientProtocol`
**Usata da**: `MelostixClient` (`runLoop`)

## `LyricsUpdate` (`data class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Snapshot di un messaggio di stato ricevuto dal master: `title`, `artist`, `status` (`"synced"` /
`"loading"` / `"plain"` / `"not_found"` / `"error"` / altro), più le tre righe `previous`/`current`/`next`
(valorizzate solo quando `status == "synced"`).

**Usata da**: `MainActivity.onUpdate` (consumatore), `MelostixClient.parseAndNotify` (produttore)

## `MelostixClientListener` (`interface`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Callback verso la UI: due metodi, entrambi invocati da `MelostixClient` già sul thread main (via
`Handler`), mai dal thread di rete.

| Metodo/Callback | Descrizione | Riferimenti |
|---|---|---|
| `fun onUpdate(update: LyricsUpdate)` (callback) | Nuovo messaggio di stato dal master (una riga JSON del canale TCP). | implementato da `MainActivity`; invocato da `MelostixClient.parseAndNotify` |
| `fun onConnectionStateChanged(connected: Boolean)` (callback) | Cambio di stato della connessione TCP (connesso/disconnesso). | implementato da `MainActivity`; invocato da `MelostixClient.notifyConnectionState` |

**Usata da**: `MainActivity` (unico implementatore nel modulo)

## `MelostixClient` (`class`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClient.kt`

Gestisce l'intero ciclo di vita della connessione al master: discovery UDP → connessione TCP →
lettura riga per riga dei messaggi JSON → riconnessione automatica se la connessione cade o non
viene mai trovata (torna in cima al loop e richiama `DiscoveryListener.listenOnce`). Gira su un
`Thread` dedicato (`isDaemon = true`), nessuna coroutine/AndroidX — skeleton minimale per evitare
una dipendenza Gradle extra su un client così piccolo. Usa un `Handler(Looper.getMainLooper())`
per marshalling delle callback verso `MainActivity` sul thread UI. `readFrom` racchiude la lettura
in `socket.use { }`: minSdk 19 garantisce `Closeable` su `Socket`, quindi non serve il `close()`
manuale in `finally`.

| Metodo/Callback | Descrizione | Riferimenti |
|---|---|---|
| `fun start()` | Idempotente (no-op se già `running`); avvia il thread dedicato su `runLoop`. | chiamato da `MainActivity.onStart` |
| `fun stop()` | Imposta `running = false` e interrompe il thread. | chiamato da `MainActivity.onStop` |
| `private fun runLoop()` | Loop principale sul thread dedicato: finché `running`, chiama `DiscoveryListener.listenOnce`, e appena trova un host chiama `readFrom(host)`; al ritorno (connessione chiusa) notifica `onConnectionStateChanged(false)` e ricomincia la discovery. | chiama `DiscoveryListener.listenOnce`, `readFrom`, `notifyConnectionState` |
| `private fun readFrom(host: DiscoveredHost)` | Apre una `Socket` TCP verso l'host scoperto (timeout di connessione), poi dentro `socket.use { }` notifica `onConnectionStateChanged(true)` e legge righe da un `BufferedReader` finché `running` o EOF, passando ogni riga a `parseAndNotify`. | chiamato da `runLoop`; chiama `parseAndNotify`, `notifyConnectionState` |
| `private fun parseAndNotify(line: String)` | Fa il parsing della riga come `JSONObject`, costruisce un `LyricsUpdate` (righe non valide vengono loggate e scartate), e posta `listener.onUpdate(update)` sul main thread. | chiamato da `readFrom`; invoca `MelostixClientListener.onUpdate` (**callback verso `MainActivity`**) |
| `private fun notifyConnectionState(connected: Boolean)` | Posta `listener.onConnectionStateChanged(connected)` sul main thread. | chiamato da `runLoop`, `readFrom`; invoca `MelostixClientListener.onConnectionStateChanged` (**callback verso `MainActivity`**) |
| `private fun JSONObject.optStringOrNull(key: String): String?` | Extension helper: `null` se il campo JSON è esplicitamente `null`, altrimenti `optString`. | chiamato da `parseAndNotify` |

**Dipende da**: `DiscoveryListener`, `DiscoveredHost`, `MelostixClientProtocol`, `MelostixClientListener`, `LyricsUpdate`
**Usata da**: `MainActivity` (costruita con `this` come listener, guidata da `start()`/`stop()`)

## `MelostixClientProtocol` (`object`) — `app-tablet/src/main/java/com/hardrex/melostixclient/tablet/net/MelostixClientProtocol.kt`

Costanti di protocollo (porta discovery UDP, porta dati TCP, nome servizio) — copia mantenuta a mano
allineata a `com.hardrex.melostix.net.MelostixClientProtocol` lato app-master, non essendoci un
modulo Gradle condiviso tra i due. Valori correnti: `DISCOVERY_PORT = 8421`, `DATA_PORT = 8420`,
`SERVICE_NAME = "melostixservice"`. Vedi
[`protocol.md`](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) per la
definizione autoritativa di questi valori nel contratto condiviso.

**Usata da**: `DiscoveryListener` (`DISCOVERY_PORT`, `SERVICE_NAME`, `DATA_PORT`), `MelostixClient` (`DISCOVERY_PORT`, in log)
