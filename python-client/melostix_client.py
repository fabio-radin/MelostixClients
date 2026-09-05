#!/usr/bin/env python3
"""Terminal client for Melostix: finds the master on the LAN (or connects to a
given host), and shows a scrolling 3-line lyrics view in a terminal via curses.

Protocol reference: protocol.md in the MelostixProtocol repo.
"""

import argparse
import base64
import curses
import hashlib
import hmac
import json
import socket
import threading
import time

DISCOVERY_PORT = 8421
SERVICE_NAME = "melostixservice"
RECONNECT_DELAY_SEC = 2
DISCOVERY_TIMEOUT_SEC = 5

# Protocol 1.2.0 (MelostixProtocol): version of the contract this client speaks, and the
# namespaced clientType it identifies itself as in the optional clientHello - see protocol.md,
# "Client identification (optional)" / "Protocol version".
PROTOCOL_VERSION = "1.2.0"
CLIENT_TYPE = "melostix.python-terminal"


class State:
    """Shared between the network thread (writer) and the curses draw loop (reader)."""

    def __init__(self):
        self._lock = threading.Lock()
        self._data = {
            "connected": False,
            "info": "Starting...",
            "status": "none",
            "title": None,
            "artist": None,
            "previous": None,
            "current": None,
            "next": None,
        }

    def update(self, **kwargs):
        with self._lock:
            self._data.update(kwargs)

    def snapshot(self):
        with self._lock:
            return dict(self._data)


def discover_master(timeout):
    """Listens for one UDP broadcast from the master. Returns (host, port), or None on timeout."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind(("", DISCOVERY_PORT))
    sock.settimeout(timeout)
    try:
        while True:
            try:
                payload, addr = sock.recvfrom(1024)
            except socket.timeout:
                return None
            try:
                message = json.loads(payload.decode("utf-8"))
            except (ValueError, UnicodeDecodeError):
                continue
            if message.get("service") != SERVICE_NAME:
                continue
            port = message.get("port")
            if not isinstance(port, int):
                continue
            return addr[0], port
    finally:
        sock.close()


def _parse_json(text):
    try:
        return json.loads(text)
    except ValueError:
        return None


def _apply_state_message(state, message):
    state.update(
        status=message.get("status", "none"),
        title=message.get("title"),
        artist=message.get("artist"),
        previous=message.get("previous"),
        current=message.get("current"),
        next=message.get("next"),
    )


def _compute_hmac(password, nonce_b64):
    """HMAC-SHA256 of the challenge nonce, keyed with the shared password - see protocol.md,
    "Authentication (optional)", in MelostixProtocol."""
    nonce = base64.b64decode(nonce_b64)
    digest = hmac.new(password.encode("utf-8"), nonce, hashlib.sha256).digest()
    return base64.b64encode(digest).decode("ascii")


def network_loop(state, stop_event, fixed_host, fixed_port, password):
    """Runs forever in a background thread: discover (unless a host was given), connect, read
    JSON lines and update `state`, reconnect on any error or disconnect."""
    while not stop_event.is_set():
        if fixed_host:
            host, port = fixed_host, fixed_port
            state.update(info="Connecting to {}:{}...".format(host, port))
        else:
            state.update(info="Searching for master (UDP broadcast)...")
            found = discover_master(DISCOVERY_TIMEOUT_SEC)
            if found is None:
                continue
            host, port = found
            state.update(info="Found master at {}:{}, connecting...".format(host, port))

        try:
            with socket.create_connection((host, port), timeout=5) as sock:
                # socket.create_connection() leaves its `timeout` argument set on the socket
                # after connecting too, not just during the handshake - without resetting it,
                # every subsequent read (including the blocking `for line in f` below) would
                # raise socket.timeout as soon as the master goes 5s without a state change to
                # push (e.g. a long line, or a paused/plain-lyrics track), and get treated as a
                # dead connection by the `except OSError` below, causing a spurious reconnect
                # loop even though the connection was perfectly healthy. Reads should just block
                # until the master actually sends something or the socket really errors/closes -
                # the network thread is a daemon (see `main()`), so there's no need for a
                # periodic timeout to re-check a stop flag like the other clients do.
                sock.settimeout(None)
                f = sock.makefile("r", encoding="utf-8")
                first_line = f.readline().strip()
                pending_message = _parse_json(first_line) if first_line else None

                # Protocol 1.1.0 (MelostixProtocol): if the master requires a password, the very
                # first line is an authChallenge instead of a normal state update - see
                # protocol.md, "Authentication (optional)". A master with no password configured
                # sends a state update directly, exactly like protocol 1.0.0 - this client stays
                # compatible with both without knowing in advance which one it'll meet.
                if pending_message is not None and pending_message.get("kind") == "authChallenge":
                    if not password:
                        state.update(connected=False, info="Master requires a password (use --password)")
                        time.sleep(RECONNECT_DELAY_SEC)
                        continue
                    response = {
                        "kind": "authResponse",
                        "hmac": _compute_hmac(password, pending_message.get("nonce", "")),
                    }
                    sock.sendall((json.dumps(response) + "\n").encode("utf-8"))
                    pending_message = None  # consumed by the handshake, not a state update

                # Protocol 1.2.0 (MelostixProtocol): optional line, sent once per connection
                # right after the authResponse above (or right after connecting, if no password
                # is in use), declaring what kind of client this is - see protocol.md, "Client
                # identification (optional)". The master never waits for it and never replies.
                hello = {
                    "kind": "clientHello",
                    "clientType": CLIENT_TYPE,
                    "protocolVersion": PROTOCOL_VERSION,
                }
                sock.sendall((json.dumps(hello) + "\n").encode("utf-8"))

                state.update(connected=True, info="Connected to {}:{}".format(host, port))
                if pending_message is not None:
                    _apply_state_message(state, pending_message)

                for line in f:
                    if stop_event.is_set():
                        break
                    line = line.strip()
                    if not line:
                        continue
                    message = _parse_json(line)
                    if message is not None:
                        _apply_state_message(state, message)
            state.update(connected=False, info="Disconnected from master")
        except OSError as exc:
            state.update(connected=False, info="Connection error: {}".format(exc))

        if stop_event.is_set():
            break
        time.sleep(RECONNECT_DELAY_SEC)


def draw(stdscr, state):
    stdscr.erase()
    height, width = stdscr.getmaxyx()
    data = state.snapshot()

    def center_text(y, text, attr=curses.A_NORMAL):
        if not (0 <= y < height - 1):
            return
        text = (text or "")[: max(0, width - 2)]
        x = max(0, (width - len(text)) // 2)
        try:
            stdscr.addstr(y, x, text, attr)
        except curses.error:
            pass  # writing to the bottom-right cell can raise despite fitting - harmless

    mid = height // 2
    status = data["status"]

    if not data["connected"]:
        center_text(mid, data["info"])
    elif status == "synced":
        center_text(mid - 1, data["previous"], curses.A_DIM)
        center_text(mid, data["current"], curses.A_BOLD)
        center_text(mid + 1, data["next"], curses.A_DIM)
    elif status == "loading":
        center_text(mid, 'Searching lyrics for "{}"...'.format(data["title"] or ""))
    elif status == "plain":
        center_text(mid - 1, "{} - {}".format(data["title"] or "", data["artist"] or ""))
        center_text(mid + 1, "(lyrics not synced)", curses.A_DIM)
    elif status == "not_found":
        center_text(mid, 'Lyrics not found for "{}"'.format(data["title"] or ""))
    elif status == "error":
        center_text(mid, "Lyrics search error")
    else:  # "none"
        if data["title"]:
            center_text(mid, "{} - {}".format(data["title"], data["artist"] or ""))
        else:
            center_text(mid, "No track playing")

    footer = "q: quit"
    try:
        stdscr.addstr(height - 1, 0, footer[: max(0, width - 1)], curses.A_DIM)
    except curses.error:
        pass
    stdscr.refresh()


def main(stdscr, args):
    curses.curs_set(0)
    stdscr.timeout(200)  # redraw ~5x/sec even without a keypress, to pick up network updates

    state = State()
    stop_event = threading.Event()
    thread = threading.Thread(
        target=network_loop,
        args=(state, stop_event, args.host, args.port, args.password),
        daemon=True,  # killed automatically on exit, even if blocked on a socket read
    )
    thread.start()

    while True:
        draw(stdscr, state)
        key = stdscr.getch()
        if key in (ord("q"), ord("Q")):
            break
    stop_event.set()


def run():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--host",
        help="Connect directly to this host, skipping UDP discovery (useful across subnets/VPNs).",
    )
    parser.add_argument(
        "--port",
        type=int,
        default=8420,
        help="TCP port to connect to when --host is given (default: 8420).",
    )
    parser.add_argument(
        "--password",
        help="Shared password for the master's optional authentication handshake "
        "(protocol 1.1.0, MelostixProtocol). Omit if the master has none configured.",
    )
    args = parser.parse_args()
    # OSC 0: sets the terminal emulator's window/tab title, same "Melostix - X Client"
    # naming used for the app icon/label everywhere else this client isn't run headless.
    print("\033]0;Melostix - Python Client\007", end="", flush=True)
    curses.wrapper(main, args)


if __name__ == "__main__":
    run()
