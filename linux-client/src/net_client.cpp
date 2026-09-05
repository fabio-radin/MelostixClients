#include "net_client.h"

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>

#include <openssl/evp.h>
#include <openssl/hmac.h>

#include <cctype>
#include <cerrno>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <optional>
#include <thread>
#include <vector>

namespace lyrics {

namespace {

// Protocollo 1.2.0 (MelostixProtocol): versione del contratto parlata da questo client, e
// clientType namespaced con cui si identifica nel clientHello opzionale - vedi protocol.md,
// "Client identification (optional)" / "Protocol version".
constexpr const char* kProtocolVersion = "1.2.0";
constexpr const char* kClientType = "melostix.linux-imgui";

// Parser JSON volutamente minimale: il protocollo e' un unico oggetto piatto per riga (mai
// annidato, solo stringhe/null/numeri), generato dal nostro stesso app-master (org.json) - non
// serve un parser JSON generale, basta saper estrarre il valore di una chiave nota da una riga.
// Ritorna std::nullopt se la chiave manca o il suo valore e' JSON null.
std::optional<std::string> jsonGetString(const std::string& json, const std::string& key) {
    const std::string needle = "\"" + key + "\"";
    size_t pos = json.find(needle);
    if (pos == std::string::npos) return std::nullopt;
    pos = json.find(':', pos + needle.size());
    if (pos == std::string::npos) return std::nullopt;
    pos++;
    while (pos < json.size() && std::isspace(static_cast<unsigned char>(json[pos]))) pos++;
    if (pos >= json.size()) return std::nullopt;

    if (json.compare(pos, 4, "null") == 0) return std::nullopt;

    if (json[pos] != '"') {
        // Token nudo (numero): non ci serve per i campi che leggiamo, ma gestito comunque
        // invece di restituire spazzatura.
        size_t end = json.find_first_of(",}", pos);
        if (end == std::string::npos) end = json.size();
        return json.substr(pos, end - pos);
    }

    pos++;  // salta la virgoletta di apertura
    std::string result;
    while (pos < json.size() && json[pos] != '"') {
        if (json[pos] == '\\' && pos + 1 < json.size()) {
            char next = json[pos + 1];
            switch (next) {
                case '"': result += '"'; break;
                case '\\': result += '\\'; break;
                case '/': result += '/'; break;
                case 'n': result += '\n'; break;
                case 't': result += '\t'; break;
                case 'r': result += '\r'; break;
                default: result += next; break;
            }
            pos += 2;
        } else {
            result += json[pos];
            pos++;
        }
    }
    return result;
}

// Base64 minimale scritto a mano (nessuna libreria di base64 gia' linkata) - usato solo per il
// nonce/HMAC dell'handshake di autenticazione opzionale (protocollo 1.1.0, MelostixProtocol),
// non per il resto del protocollo. HMAC-SHA256 vero invece delegato a OpenSSL (libcrypto): a
// differenza del parser JSON sopra (piatto, generato dal nostro stesso app-master, sicuro da
// semplificare), reimplementare SHA-256 a mano sarebbe un rischio di correttezza ingiustificato
// per una primitiva crittografica che va bene la prima volta.
const char kBase64Chars[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64Encode(const unsigned char* data, size_t len) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    size_t i = 0;
    while (i + 3 <= len) {
        unsigned int n = (static_cast<unsigned int>(data[i]) << 16) |
                          (static_cast<unsigned int>(data[i + 1]) << 8) |
                          static_cast<unsigned int>(data[i + 2]);
        out += kBase64Chars[(n >> 18) & 0x3F];
        out += kBase64Chars[(n >> 12) & 0x3F];
        out += kBase64Chars[(n >> 6) & 0x3F];
        out += kBase64Chars[n & 0x3F];
        i += 3;
    }
    size_t rem = len - i;
    if (rem == 1) {
        unsigned int n = static_cast<unsigned int>(data[i]) << 16;
        out += kBase64Chars[(n >> 18) & 0x3F];
        out += kBase64Chars[(n >> 12) & 0x3F];
        out += "==";
    } else if (rem == 2) {
        unsigned int n = (static_cast<unsigned int>(data[i]) << 16) |
                          (static_cast<unsigned int>(data[i + 1]) << 8);
        out += kBase64Chars[(n >> 18) & 0x3F];
        out += kBase64Chars[(n >> 12) & 0x3F];
        out += kBase64Chars[(n >> 6) & 0x3F];
        out += "=";
    }
    return out;
}

int base64DecodeChar(char c) {
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+') return 62;
    if (c == '/') return 63;
    return -1;
}

std::vector<unsigned char> base64Decode(const std::string& input) {
    std::vector<unsigned char> out;
    int val = 0;
    int bits = -8;
    for (char c : input) {
        if (c == '=') break;
        int d = base64DecodeChar(c);
        if (d < 0) continue;  // salta whitespace/newline difensivamente
        val = (val << 6) | d;
        bits += 6;
        if (bits >= 0) {
            out.push_back(static_cast<unsigned char>((val >> bits) & 0xFF));
            bits -= 8;
        }
    }
    return out;
}

std::vector<unsigned char> hmacSha256(const std::string& password, const std::vector<unsigned char>& nonce) {
    unsigned char result[EVP_MAX_MD_SIZE];
    unsigned int len = 0;
    HMAC(EVP_sha256(), password.data(), static_cast<int>(password.size()),
         nonce.data(), nonce.size(), result, &len);
    return std::vector<unsigned char>(result, result + len);
}

struct DiscoveredMaster {
    std::string host;
    int port;
};

/** Ascolta un broadcast UDP dal master. Ritorna nullopt allo scadere di timeoutSec. */
std::optional<DiscoveredMaster> discoverMaster(int timeoutSec) {
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0) return std::nullopt;

    int reuse = 1;
    setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &reuse, sizeof(reuse));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(kDiscoveryPort);
    if (bind(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        close(sock);
        return std::nullopt;
    }

    timeval tv{};
    tv.tv_sec = timeoutSec;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    char buffer[1024];
    sockaddr_in sender{};
    socklen_t senderLen = sizeof(sender);

    std::optional<DiscoveredMaster> result;
    while (!result.has_value()) {
        ssize_t n = recvfrom(sock, buffer, sizeof(buffer) - 1, 0,
                              reinterpret_cast<sockaddr*>(&sender), &senderLen);
        if (n <= 0) break;  // timeout o errore
        buffer[n] = '\0';
        std::string payload(buffer, static_cast<size_t>(n));

        auto service = jsonGetString(payload, "service");
        if (!service || *service != kServiceName) continue;
        auto portStr = jsonGetString(payload, "port");
        if (!portStr) continue;
        int port = std::atoi(portStr->c_str());
        if (port <= 0) continue;

        char hostBuf[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &sender.sin_addr, hostBuf, sizeof(hostBuf));
        result = DiscoveredMaster{std::string(hostBuf), port};
    }

    close(sock);
    return result;
}

/** Legge una riga completa (terminata da '\n') dal socket in buffer, bloccando fino a
 *  stopFlag/errore/timeout. Ritorna nullopt se la connessione va chiusa (stopFlag, EOF, errore
 *  vero) senza aver completato una riga. */
std::optional<std::string> readLine(int sock, std::string& buffer, std::atomic<bool>& stopFlag) {
    size_t newlinePos = buffer.find('\n');
    while (newlinePos == std::string::npos) {
        if (stopFlag.load()) return std::nullopt;
        char chunk[4096];
        ssize_t n = recv(sock, chunk, sizeof(chunk), 0);
        if (n > 0) {
            buffer.append(chunk, static_cast<size_t>(n));
            newlinePos = buffer.find('\n');
        } else if (n == 0) {
            return std::nullopt;  // il master ha chiuso la connessione
        } else {
            if (errno == EAGAIN || errno == EWOULDBLOCK) continue;  // timeout di recv, ricontrolla stopFlag
            return std::nullopt;  // errore vero
        }
    }
    std::string line = buffer.substr(0, newlinePos);
    buffer.erase(0, newlinePos + 1);
    return line;
}

/** Connette e legge righe JSON finche' la connessione non cade o stopFlag diventa true. Il
 *  timeout di ricezione (1s) serve solo a ricontrollare stopFlag periodicamente, non e' un
 *  errore quando scade da solo (EAGAIN/EWOULDBLOCK). */
void connectAndRead(const std::string& host, int port, SharedState& state,
                     std::atomic<bool>& stopFlag, const std::string& password) {
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    if (sock < 0) return;

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) <= 0) {
        close(sock);
        return;
    }

    if (connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        close(sock);
        return;
    }

    timeval tv{};
    tv.tv_sec = 1;
    tv.tv_usec = 0;
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));

    std::string buffer;

    // Protocollo 1.1.0 (MelostixProtocol): se il master richiede una password, la primissima
    // riga e' un authChallenge invece di un normale aggiornamento di stato - vedi protocol.md,
    // "Authentication (optional)". Un master senza password configurata manda direttamente un
    // aggiornamento di stato, esattamente come nel protocollo 1.0.0: questo client resta
    // compatibile con entrambi senza sapere in anticipo quale incontrera'.
    auto firstLine = readLine(sock, buffer, stopFlag);
    if (!firstLine) {
        close(sock);
        return;
    }

    bool consumedAsHandshake = false;
    auto kind = jsonGetString(*firstLine, "kind");
    if (kind && *kind == "authChallenge") {
        if (password.empty()) {
            state.setConnected(false, "Master requires a password (not configured on this client)");
            close(sock);
            return;
        }
        auto nonce = base64Decode(jsonGetString(*firstLine, "nonce").value_or(""));
        auto mac = hmacSha256(password, nonce);
        std::string response = "{\"kind\":\"authResponse\",\"hmac\":\"" +
                                base64Encode(mac.data(), mac.size()) + "\"}\n";
        if (send(sock, response.data(), response.size(), 0) < 0) {
            close(sock);
            return;
        }
        consumedAsHandshake = true;
    }

    // Protocollo 1.2.0 (MelostixProtocol): riga opzionale, inviata una sola volta per
    // connessione, subito dopo l'authResponse sopra (o subito dopo la connessione se non serve
    // autenticarsi), che dichiara al master la tipologia di questo client - vedi protocol.md,
    // "Client identification (optional)". Il master non la aspetta e non risponde.
    std::string hello = std::string("{\"kind\":\"clientHello\",\"clientType\":\"") + kClientType +
                         "\",\"protocolVersion\":\"" + kProtocolVersion + "\"}\n";
    if (send(sock, hello.data(), hello.size(), 0) < 0) {
        close(sock);
        return;
    }

    state.setConnected(true, "Connected to " + host + ":" + std::to_string(port));
    if (!consumedAsHandshake) state.applyMessage(*firstLine);

    while (!stopFlag.load()) {
        auto line = readLine(sock, buffer, stopFlag);
        if (!line) break;
        if (!line->empty()) state.applyMessage(*line);
    }

    close(sock);
    state.setConnected(false, "Disconnected from master");
}

}  // namespace

void SharedState::setInfo(const std::string& info) {
    std::lock_guard<std::mutex> lock(mutex_);
    data_.info = info;
}

void SharedState::setConnected(bool connected, const std::string& info) {
    std::lock_guard<std::mutex> lock(mutex_);
    data_.connected = connected;
    data_.info = info;
}

void SharedState::applyMessage(const std::string& jsonLine) {
    std::lock_guard<std::mutex> lock(mutex_);
    data_.status = jsonGetString(jsonLine, "status").value_or("none");
    data_.title = jsonGetString(jsonLine, "title").value_or("");
    data_.artist = jsonGetString(jsonLine, "artist").value_or("");
    data_.previous = jsonGetString(jsonLine, "previous").value_or("");
    data_.current = jsonGetString(jsonLine, "current").value_or("");
    data_.next = jsonGetString(jsonLine, "next").value_or("");
}

StateSnapshot SharedState::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return data_;
}

void runNetworkClient(SharedState& state, std::atomic<bool>& stopFlag,
                       const std::string& fixedHost, int fixedPort, const std::string& password) {
    while (!stopFlag.load()) {
        std::string host;
        int port = 0;

        if (!fixedHost.empty()) {
            host = fixedHost;
            port = fixedPort;
            state.setInfo("Connecting to " + host + ":" + std::to_string(port) + "...");
        } else {
            state.setInfo("Searching for master (UDP broadcast)...");
            auto found = discoverMaster(5);
            if (!found) continue;
            host = found->host;
            port = found->port;
            state.setInfo("Found master at " + host + ":" + std::to_string(port) + ", connecting...");
        }

        connectAndRead(host, port, state, stopFlag, password);

        if (stopFlag.load()) break;
        std::this_thread::sleep_for(std::chrono::seconds(2));
    }
}

}  // namespace lyrics
