#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

// Network client for the Melostix protocol (see protocol.md in the public repository
// MelostixProtocol, https://github.com/fabio-radin/MelostixProtocol): UDP broadcast discovery on
// port 8421, then reading JSON lines from the TCP data channel on the announced port. Runs on a
// thread separate from rendering; the shared state is protected by a mutex, read by the render
// loop every frame.

namespace lyrics {

constexpr int kDiscoveryPort = 8421;
constexpr const char* kServiceName = "melostixservice";

/** Immutable snapshot of the current state, for the rendering thread: one copy per frame, so
 *  the mutex doesn't need to be held while drawing. */
struct StateSnapshot {
    bool connected = false;
    std::string info;      // status message when not connected (or just connected)
    std::string status = "none";
    std::string title;
    std::string artist;
    std::string previous;
    std::string current;
    std::string next;
};

/** State shared between the network thread (writer) and the render loop (reader). */
class SharedState {
public:
    void setInfo(const std::string& info);
    void setConnected(bool connected, const std::string& info);
    void applyMessage(const std::string& jsonLine);
    StateSnapshot snapshot() const;

private:
    mutable std::mutex mutex_;
    StateSnapshot data_;
};

/** Starts (blocking, must be called on a dedicated thread) the discovery -> connect -> read
 *  loop, with automatic reconnection until stopFlag becomes true. If fixedHost is not empty,
 *  skips UDP discovery entirely and always connects there. If password is not empty, responds
 *  to the optional authentication handshake of protocol 1.1.0 (MelostixProtocol) if the master
 *  requires it - empty (default) = no password configured on this client, identical behavior to
 *  protocol 1.0.0 with a master that doesn't require one. Also sends, right after the optional
 *  handshake, the optional clientHello of protocol 1.2.0 that declares its own type to the
 *  master (see kClientType in net_client.cpp) - a master that doesn't read it behaves exactly as
 *  before. */
void runNetworkClient(
    SharedState& state,
    std::atomic<bool>& stopFlag,
    const std::string& fixedHost,
    int fixedPort,
    const std::string& password);

}  // namespace lyrics
