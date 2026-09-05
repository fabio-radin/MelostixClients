# Client Python

Client da terminale per Melostix: trova l'app master sulla LAN (o si connette a un host indicato)
e mostra le 3 righe di testo (precedente/corrente/successiva) a scorrimento attorno alla
posizione di riproduzione, nel tuo terminale.

Riferimento del protocollo:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) nel repo
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) (pubblico, contratto v1.2.0 —
parla l'handshake opzionale di password aggiunto in 1.1.0, e si connette senza problemi a un master
1.0.0 senza password configurata). Da 1.2.0, invia anche il `clientHello` opzionale di
identificazione (`clientType = "melostix.python-terminal"`, cablato, più `protocolVersion`) subito
dopo la connessione (o dopo l'`authResponse`, se la password è in uso) — nessuna configurazione
utente, un master che non lo legge si comporta esattamente come prima. **Verificato end-to-end il
2026-08-29** da un PC Windows contro un master **iOS** (iPhone SE 2022) — testi ricevuti e
visualizzati correttamente, nessuna anomalia. Prima verifica contro un master iOS anziché Android:
le due implementazioni lato master sono indipendenti, quindi è la prima prova che parlino lo stesso
protocollo sul filo. Copre solo il funzionamento end-to-end della connessione — non è stato
osservato se il master legge e registra l'identità dichiarata nel `clientHello`.

## Requisiti

- Python 3.8+
- **Linux/macOS**: nulla in più — il modulo `curses` è incluso nel Python di sistema. Se sei su
  una distro minimale e per qualche motivo manca, installa `python3` dal tuo package manager (è
  incluso) — es. su Debian/Ubuntu: `sudo apt-get install python3`.
- **Windows**: `curses` non è incluso, installa prima il sostituto drop-in:
  ```
  pip install -r requirements.txt
  ```
  (installa solo `windows-curses`, un no-op su Linux/macOS grazie all'environment marker nel
  requirements.txt).

## Esecuzione

```
python melostix_client.py
```

Attende il discovery broadcast UDP del master (porta 8421) e si connette automaticamente. Premi
`q` per uscire.

Per saltare il discovery e connettersi direttamente (es. subnet diversa, VPN):

```
python melostix_client.py --host <master-ip> --port 8420
```

Se il master ha una password condivisa configurata (Impostazioni, protocollo 1.1.0), passala con
`--password`; ometti il flag se il master non ne ha nessuna configurata (il default):

```
python melostix_client.py --password correct-horse-battery-staple
```

## Stato attuale

✅ Verificato su hardware reale (discovery + connessione + handshake password 1.1.0 contro un
master con password impostata). Il primo test (2026-08-21) aveva trovato un bug — il client
perdeva la connessione per timeout anche con una connessione perfettamente sana, quando il master
restava un po' troppo a lungo (>5s) senza inviare un aggiornamento di stato (es. una riga di testo
lunga, o un brano in pausa): `socket.create_connection(..., timeout=5)` lascia quel timeout
impostato sul socket anche **dopo** la connessione, non solo durante l'handshake TCP, quindi ogni
lettura successiva (incluso il normale `for line in f` bloccante) falliva con `socket.timeout` non
appena il master stava fermo più di 5 secondi, e veniva trattata come una disconnessione reale
invece che come una semplice attesa. Corretto in `network_loop()` (`melostix_client.py`)
resettando il timeout a `None` subito dopo la connessione, poi **ri-testato su hardware reale il
2026-08-21: nessun timeout più comparso**, connessione stabile.
