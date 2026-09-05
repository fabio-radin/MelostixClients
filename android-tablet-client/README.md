# android-tablet-client

Client "slave" Android per un tablet generico Android 4.4.4 / API 19 (kernel 3.8.13, display 
800x480): riceve dal master le 3 righe di testo (precedente/corrente/successiva) attorno alla 
posizione di riproduzione via rete locale, e le mostra fullscreen, sfondo nero.

Modulo Gradle standalone (`:app-tablet`), non dipende da nessun altro modulo di questo repo.

## Stato attuale

**Verificato funzionante end-to-end su hardware reale il 2026-08-21** (tablet + telefono sulla 
stessa rete WiFi, incluso l'handshake password 1.1.0 contro un master con password impostata).

- Fullscreen "sticky immersive" (`SYSTEM_UI_FLAG_IMMERSIVE_STICKY`), garantito qui dato che 
  coincide esattamente col minSdk di questo modulo.
- `net/` usa `.use { }` di Kotlin sui socket: minSdk 19 è il primo livello API in cui 
  `Socket`/`DatagramSocket` implementano davvero `Closeable`, quindi qui è sicuro anche a 
  runtime, non solo a compile-time.
- Dimensioni testo verificate sul display 800x480 reale nel test del 2026-08-21 
  (`MainActivity.kt`).

## Perché niente AndroidX/Compose

Compose/AndroidX moderne richiedono minSdk 21, questo modulo è fermo ad API 19 
(Android 4.4.4 KitKat esatto).

## Protocollo master → slave

Nessun HTTP/TLS, socket TCP grezzo, un JSON per riga, push
unidirezionale master → slave, scoperta automatica via broadcast UDP. Vedi il protocollo
formale nel repo pubblico [MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol)
(estratto da `MelostixContracts` il 2026-08-21) - contratto **v1.2.0**: se il master richiede una
password condivisa (opzionale, Impostazioni → Server sul master), questo client la manda tramite
l'handshake HMAC-SHA256 descritto li' (`net/MelostixClient.kt`, `net/ClientSettings.kt`);
vuota/non impostata (default) = comportamento identico al protocollo 1.0.0. Password impostabile
dal pannello impostazioni (icona "⋮" in basso a destra) - verificata su device reale il
2026-08-21, come il resto di questo client.

Da 1.2.0 (2026-08-24), subito dopo la connessione (o subito dopo l'`authResponse` se la password
è in uso), il client invia anche il `clientHello` opzionale che dichiara la propria tipologia al
master: `clientType = "melostix.android-tablet"` (cablato, vedi `MelostixClientProtocol.kt`),
più `clientVersion` (dal `versionName` di Gradle, che ha richiesto abilitare
`buildFeatures.buildConfig` in `build.gradle.kts` - spento di default da AGP 8+) e
`protocolVersion`. Nessuna configurazione utente: un master che non legge questa riga si comporta
esattamente come prima. **Verificato end-to-end il 2026-08-29** su un tablet Android 4.4.4 contro
un master **iOS** (iPhone SE 2022) — testi ricevuti e visualizzati correttamente, nessuna anomalia.
Prima verifica contro un master iOS anziché Android: le due implementazioni lato master sono
indipendenti, quindi è la prima prova che parlino lo stesso protocollo sul filo. Copre solo il
funzionamento end-to-end della connessione — non è stato osservato se il master legge e registra
l'identità dichiarata nel `clientHello`.

## Come buildare

```bash
./gradlew :app-tablet:assembleDebug
```

Stessa toolchain di MelostixMaster: Gradle 8.9 + AGP 8.7.1, JDK pinnato in `gradle.properties`
(`org.gradle.java.home`) - aggiorna quel percorso se il tuo JDK 21 è altrove.
