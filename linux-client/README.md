# Client Linux

Client SDL2/SDL_ttf per Melostix: finestra fullscreen-friendly con le 3 righe di testo
(precedente/corrente/successiva) a scorrimento attorno alla posizione di riproduzione. Dear ImGui
è già cablato nel render loop (un piccolo overlay di stato, `F1` per attivarlo/disattivarlo) così
aggiungere impostazioni/controlli reali in futuro non richiede di collegare SDL2+ImGui da zero.

Riferimento del protocollo:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) nel repo
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) (pubblico, contratto v1.2.0 —
parla l'handshake opzionale di password aggiunto in 1.1.0, e si connette senza problemi a un master
1.0.0 senza password configurata). Da 1.2.0, invia anche il `clientHello` opzionale di
identificazione (`clientType = "melostix.linux-imgui"`, cablato, più `protocolVersion`) subito
dopo la connessione (o dopo l'`authResponse`, se la password è in uso) — nessuna configurazione
utente, un master che non lo legge si comporta esattamente come prima.

> **Verificato funzionante end-to-end su hardware reale**, incluso l'handshake password 1.1.0
> contro un master con password impostata. Il `clientHello` 1.2.0 **non è ancora stato
> verificato** (nessun toolchain g++/Linux disponibile in questa sessione, solo revisione statica
> del codice).

## Dipendenze (Debian/Ubuntu)

```
sudo apt-get install build-essential libsdl2-dev libsdl2-ttf-dev libssl-dev fonts-dejavu-core
```

- `build-essential` — g++ e make
- `libsdl2-dev` / `libsdl2-ttf-dev` — rendering
- `libssl-dev` — libcrypto, solo per HMAC-SHA256 nell'handshake opzionale di password (protocollo
  1.1.0); nient'altro in questo client usa OpenSSL
- `fonts-dejavu-core` — necessario solo se il tuo sistema non ha già DejaVu/Liberation/Noto
  installati (l'app cerca un font di sistema all'avvio e ripiega su una breve lista; usa
  `--font /path/to/font.ttf` per puntarne uno specifico)

Dear ImGui stesso è incluso come git submodule, nessun pacchetto separato necessario — assicurati
solo che sia stato scaricato:

```
git submodule update --init --recursive
```

(già fatto automaticamente se hai clonato questo repo con `git clone --recurse-submodules`)

## Build ed esecuzione

```
make
./melostix-client
```

Attende il discovery broadcast UDP del master e si connette automaticamente. `Esc` per uscire,
`F1` per attivare/disattivare l'overlay di stato.

Per saltare il discovery e connettersi direttamente:

```
./melostix-client --host <master-ip> --port 8420
```

Se il master ha una password condivisa configurata (Impostazioni, protocollo 1.1.0), passala con
`--password`; ometti il flag se il master non ne ha nessuna configurata (il default):

```
./melostix-client --password correct-horse-battery-staple
```
