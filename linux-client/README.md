# Linux Client

SDL2/SDL_ttf client for Melostix: a fullscreen-friendly window with the 3 lines of text
(previous/current/next) scrolling around the playback position. Dear ImGui is already wired into
the render loop (a small status overlay, `F1` to toggle it) so adding real settings/controls in the
future doesn't require wiring up SDL2+ImGui from scratch.

Protocol reference:
[protocol.md](https://github.com/fabio-radin/MelostixProtocol/blob/main/protocol.md) in the
[MelostixProtocol](https://github.com/fabio-radin/MelostixProtocol) repository (public, contract
v1.2.0 — speaks the optional password handshake added in 1.1.0, and connects without issues to a
1.0.0 master with no password configured). Since 1.2.0, it also sends the optional identification
`clientHello` (`clientType = "melostix.linux-imgui"`, hardcoded, plus `protocolVersion`) right
after connecting (or after the `authResponse`, if a password is in use) — no user configuration, a
master that doesn't read it behaves exactly as before.

> **Verified working end-to-end on real hardware**, including the 1.1.0 password handshake
> against a master with a password set. The 1.2.0 `clientHello` **has not been verified yet**
> (no g++/Linux toolchain available in this session, only a static code review).

## Dependencies (Debian/Ubuntu)

```
sudo apt-get install build-essential libsdl2-dev libsdl2-ttf-dev libssl-dev fonts-dejavu-core
```

- `build-essential` — g++ and make
- `libsdl2-dev` / `libsdl2-ttf-dev` — rendering
- `libssl-dev` — libcrypto, only for HMAC-SHA256 in the optional password handshake (protocol
  1.1.0); nothing else in this client uses OpenSSL
- `fonts-dejavu-core` — only needed if your system doesn't already have DejaVu/Liberation/Noto
  installed (the app looks for a system font at startup and falls back to a short list; use
  `--font /path/to/font.ttf` to point at a specific one)

Dear ImGui itself is included as a git submodule, no separate package needed — just make sure it
has been fetched:

```
git submodule update --init --recursive
```

(already done automatically if you cloned this repo with `git clone --recurse-submodules`)

## Build and run

```
make
./melostix-client
```

Waits for the master's UDP discovery broadcast and connects automatically. `Esc` to quit, `F1` to
toggle the status overlay.

To skip discovery and connect directly:

```
./melostix-client --host <master-ip> --port 8420
```

If the master has a shared password configured (Settings, protocol 1.1.0), pass it with
`--password`; omit the flag if the master has none configured (the default):

```
./melostix-client --password correct-horse-battery-staple
```
