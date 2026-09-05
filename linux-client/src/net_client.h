#pragma once

#include <atomic>
#include <mutex>
#include <string>
#include <thread>

// Client di rete per il protocollo Melostix (vedi protocol.md nel repo pubblico
// MelostixProtocol, https://github.com/fabio-radin/MelostixProtocol - estratto da
// MelostixContracts/protocols/master-slave il 2026-08-21): discovery via broadcast UDP sulla
// porta 8421, poi lettura di righe JSON dal canale dati TCP sulla porta indicata. Gira in un
// thread separato da quello di rendering; lo stato condiviso e' protetto da un mutex, letto dal
// loop di rendering a ogni frame.

namespace lyrics {

constexpr int kDiscoveryPort = 8421;
constexpr const char* kServiceName = "melostixservice";

/** Snapshot immutabile dello stato corrente, per il thread di rendering: una copia per frame,
 *  cosi' non serve tenere il mutex mentre si disegna. */
struct StateSnapshot {
    bool connected = false;
    std::string info;      // messaggio di stato quando non connesso (o appena connesso)
    std::string status = "none";
    std::string title;
    std::string artist;
    std::string previous;
    std::string current;
    std::string next;
};

/** Stato condiviso fra il thread di rete (scrittore) e il loop di rendering (lettore). */
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

/** Avvia (bloccante, va chiamata in un thread dedicato) il loop discovery -> connessione ->
 *  lettura, con riconnessione automatica finche' stopFlag non diventa true. Se fixedHost non e'
 *  vuoto, salta del tutto la discovery UDP e si connette sempre li'. Se password non e' vuota,
 *  risponde all'handshake di autenticazione opzionale del protocollo 1.1.0 (MelostixProtocol) se
 *  il master lo richiede - vuota (default) = nessuna password configurata su questo client,
 *  comportamento identico al protocollo 1.0.0 con un master che non la richiede. Invia anche,
 *  subito dopo l'eventuale handshake, il clientHello opzionale del protocollo 1.2.0 che dichiara
 *  la propria tipologia al master (vedi kClientType in net_client.cpp) - un master che non lo
 *  legge si comporta esattamente come prima. */
void runNetworkClient(
    SharedState& state,
    std::atomic<bool>& stopFlag,
    const std::string& fixedHost,
    int fixedPort,
    const std::string& password);

}  // namespace lyrics
