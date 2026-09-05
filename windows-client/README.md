# Client Windows

Client WPF per Melostix: una piccola finestra nera con le 3 righe di testo
(precedente/corrente/successiva) a scorrimento attorno alla posizione di riproduzione.

Riferimento del protocollo:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) nel repo
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) (pubblico, contratto v1.2.0 —
parla l'handshake opzionale di password aggiunto in 1.1.0, e si connette senza problemi a un master
1.0.0 senza password configurata). La logica di discovery/client TCP vive in `NetClient.cs`,
nessun pacchetto NuGet esterno — il parsing JSON usa `System.Text.Json` e l'HMAC-SHA256 per
l'handshake usa `System.Security.Cryptography`, entrambi dalla BCL. Da 1.2.0, invia anche il
`clientHello` opzionale di identificazione (`clientType = "melostix.windows-wpf"`, cablato, più
`protocolVersion`) subito dopo la connessione (o dopo l'`authResponse`, se la password è in uso) —
nessuna configurazione utente, un master che non lo legge si comporta esattamente come prima.

**Verificato funzionante end-to-end su hardware reale il 2026-08-21**, incluso l'handshake di
password 1.1.0 (`--password`) contro un master con password impostata. Il `clientHello` 1.2.0 è
stato **verificato end-to-end il 2026-08-29**, dallo stesso PC Windows, contro un master **iOS**
(iPhone SE 2022) — testi ricevuti e visualizzati correttamente, nessuna anomalia. Prima verifica
contro un master iOS anziché Android: le due implementazioni lato master sono indipendenti, quindi
è la prima prova che parlino lo stesso protocollo sul filo. Copre solo il funzionamento end-to-end
della connessione — non è stato osservato se il master legge e registra l'identità dichiarata nel
`clientHello`.

## Requisiti

- .NET 9 SDK
- Visual Studio 2022 (17.12+) con il workload ".NET desktop development", oppure solo la CLI
  `dotnet`

## Apertura in Visual Studio 2022

Apri `MelostixClient.sln`, premi F5.

## Build ed esecuzione da CLI

```
dotnet build MelostixClient.sln
dotnet run --project MelostixClient
```

Attende il discovery broadcast UDP del master e si connette automaticamente. `Esc` per chiudere la
finestra.

Per saltare il discovery e connettersi direttamente:

```
dotnet run --project MelostixClient -- --host <master-ip> --port 8420
```

Se il master ha una password condivisa configurata (Impostazioni, protocollo 1.1.0), passala con
`--password`; ometti il flag se il master non ne ha nessuna configurata (il default):

```
dotnet run --project MelostixClient -- --password correct-horse-battery-staple
```
