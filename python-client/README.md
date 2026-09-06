# Python Client

Terminal client for Melostix: finds the master app on the LAN (or connects to a given host) and
shows the 3 lines of text (previous/current/next) scrolling around the playback position, in your
terminal.

Protocol reference:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) in the
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) repository (public, contract
v1.2.0 — speaks the optional password handshake added in 1.1.0, and connects without issues to a
1.0.0 master with no password configured). Since 1.2.0, it also sends the optional identification
`clientHello` (`clientType = "melostix.python-terminal"`, hardcoded, plus `protocolVersion`) right
after connecting (or after the `authResponse`, if a password is in use) — no user configuration, a
master that doesn't read it behaves exactly as before. **Verified end-to-end on 2026-08-29** from a
Windows PC against an **iOS** master (iPhone SE 2022) — text received and displayed correctly, no
anomalies. First verification against an iOS master rather than Android: the two master-side
implementations are independent, so this is the first proof they speak the same protocol on the
wire. This only covers the connection working end-to-end — it was not observed whether the master
reads and records the identity declared in the `clientHello`.

## Requirements

- Python 3.8+
- **Linux/macOS**: nothing extra — the `curses` module is included in the system Python. If
  you're on a minimal distro and it's somehow missing, install `python3` from your package
  manager (it's included) — e.g. on Debian/Ubuntu: `sudo apt-get install python3`.
- **Windows**: `curses` is not included, install the drop-in replacement first:
  ```
  pip install -r requirements.txt
  ```
  (only installs `windows-curses`, a no-op on Linux/macOS thanks to the environment marker in
  requirements.txt).

## Running

```
python melostix_client.py
```

Waits for the master's UDP discovery broadcast (port 8421) and connects automatically. Press `q`
to quit.

To skip discovery and connect directly (e.g. a different subnet, VPN):

```
python melostix_client.py --host <master-ip> --port 8420
```

If the master has a shared password configured (Settings, protocol 1.1.0), pass it with
`--password`; omit the flag if the master has none configured (the default):

```
python melostix_client.py --password correct-horse-battery-staple
```

## Current status

✅ Verified on real hardware (discovery + connection + 1.1.0 password handshake against a master
with a password set). The first test (2026-08-21) found a bug — the client dropped the connection
on a timeout even with a perfectly healthy connection, whenever the master stayed a bit too long
(>5s) without sending a status update (e.g. a long line of text, or a paused track):
`socket.create_connection(..., timeout=5)` leaves that timeout set on the socket even **after** the
connection, not just during the TCP handshake, so every subsequent read (including the normal
blocking `for line in f`) failed with `socket.timeout` as soon as the master was idle for more than
5 seconds, and was treated as a real disconnection instead of just a wait. Fixed in
`network_loop()` (`melostix_client.py`) by resetting the timeout to `None` right after connecting,
then **re-tested on real hardware on 2026-08-21: no more timeouts**, stable connection.
