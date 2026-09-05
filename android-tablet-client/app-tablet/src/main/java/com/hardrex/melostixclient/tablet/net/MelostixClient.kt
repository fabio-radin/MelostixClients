package com.hardrex.melostixclient.tablet.net

import com.hardrex.melostixclient.tablet.BuildConfig
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LyricsUpdate(
    val title: String?,
    val artist: String?,
    val status: String,
    val previous: String?,
    val current: String?,
    val next: String?,
)

interface MelostixClientListener {
    fun onUpdate(update: LyricsUpdate)
    fun onConnectionStateChanged(connected: Boolean)
}

/**
 * Gestisce l'intero ciclo di vita della connessione al master: scoperta via broadcast UDP,
 * connessione TCP, lettura degli aggiornamenti JSON riga per riga, riconnessione automatica
 * (torna a cercare il master da capo) se la connessione cade o non viene mai trovata. Gira su
 * un thread dedicato: niente coroutine/AndroidX, solo java.net + un Handler per tornare sul
 * thread UI - restare su questo skeleton minimale evita di introdurre una dipendenza Gradle
 * per un client cosi' piccolo.
 *
 * [passwordProvider] e' letto a ogni nuova connessione, non catturato una volta sola: cambiare
 * la password da Impostazioni si applica al prossimo tentativo di connessione senza dover
 * riavviare il client. Vuoto/null = nessuna password configurata (comportamento identico al
 * protocollo 1.0.0: se il master non la richiede la connessione funziona comunque; se la
 * richiede, questo client non potra' autenticarsi - vedi readFrom).
 */
class MelostixClient(
    private val listener: MelostixClientListener,
    private val passwordProvider: () -> String? = { null },
) {

    private companion object {
        const val TAG = "MelostixClient"
        const val DISCOVERY_TIMEOUT_MS = 3000
        const val CONNECT_TIMEOUT_MS = 3000
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(this::runLoop, "MelostixClient").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun runLoop() {
        Log.i(TAG, "avviato, in ascolto broadcast sulla porta ${MelostixClientProtocol.DISCOVERY_PORT}")
        while (running) {
            val host = DiscoveryListener.listenOnce(DISCOVERY_TIMEOUT_MS) ?: continue
            Log.i(TAG, "master trovato: ${host.address.hostAddress}:${host.port}")
            readFrom(host)
            notifyConnectionState(false)
        }
    }

    private fun readFrom(host: DiscoveredHost) {
        val socket = try {
            Socket().apply { connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS) }
        } catch (e: IOException) {
            Log.w(TAG, "connessione fallita: ${e.message}")
            return
        }

        // minSdk 19: Socket implementa Closeable da qui in poi, .use{} e' sicuro - vedi il
        // commento equivalente in DiscoveryListener.kt.
        socket.use {
            try {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                var line = reader.readLine() ?: return

                // Protocollo 1.1.0 (MelostixProtocol): se il master richiede una password, la
                // primissima riga e' un authChallenge invece di un normale aggiornamento di
                // stato - vedi protocol.md, "Authentication (optional)". Un master senza
                // password configurata manda direttamente un aggiornamento di stato, esattamente
                // come nel protocollo 1.0.0: questo client resta compatibile con entrambi senza
                // sapere in anticipo quale incontrera' (authRequired nel pacchetto di discovery
                // e' solo un segnale informativo precoce, non l'unica fonte di verita').
                val challenge = runCatching { JSONObject(line) }.getOrNull()
                if (challenge?.optString("kind") == "authChallenge") {
                    val password = passwordProvider()
                    if (password.isNullOrEmpty()) {
                        Log.w(TAG, "il master richiede una password non configurata su questo client")
                        return
                    }
                    val response = JSONObject()
                        .put("kind", "authResponse")
                        .put("hmac", hmacSha256(password, challenge.optString("nonce")))
                        .toString()
                    BufferedWriter(OutputStreamWriter(it.getOutputStream(), Charsets.UTF_8)).apply {
                        write(response)
                        newLine()
                        flush()
                    }
                    line = reader.readLine() ?: return
                }

                sendClientHello(it)
                notifyConnectionState(true)
                while (running) {
                    parseAndNotify(line)
                    line = reader.readLine() ?: break
                }
            } catch (e: IOException) {
                Log.i(TAG, "connessione interrotta: ${e.message}")
            }
        }
    }

    /** Protocollo 1.2.0 (MelostixProtocol): riga opzionale, unica per connessione, che dichiara
     *  al master la tipologia di questo client - vedi protocol.md, "Client identification
     *  (optional)". Inviata dopo l'eventuale authResponse (o subito dopo la connessione se non
     *  serve autenticarsi) e mai prima di leggere la prima riga di stato: il master non la
     *  aspetta e non risponde, quindi non c'e' bisogno di attendere nulla dopo averla scritta. */
    private fun sendClientHello(socket: Socket) {
        val hello = JSONObject()
            .put("kind", "clientHello")
            .put("clientType", MelostixClientProtocol.CLIENT_TYPE)
            .put("clientVersion", BuildConfig.VERSION_NAME)
            .put("protocolVersion", MelostixClientProtocol.PROTOCOL_VERSION)
            .toString()
        BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)).apply {
            write(hello)
            newLine()
            flush()
        }
    }

    private fun hmacSha256(password: String, nonceBase64: String): String {
        val nonce = Base64.decode(nonceBase64, Base64.NO_WRAP)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(nonce), Base64.NO_WRAP)
    }

    private fun parseAndNotify(line: String) {
        val json = try {
            JSONObject(line)
        } catch (e: Exception) {
            Log.w(TAG, "messaggio non valido: $line", e)
            return
        }
        val update = LyricsUpdate(
            title = json.optStringOrNull("title"),
            artist = json.optStringOrNull("artist"),
            status = json.optString("status", "none"),
            previous = json.optStringOrNull("previous"),
            current = json.optStringOrNull("current"),
            next = json.optStringOrNull("next"),
        )
        mainHandler.post { listener.onUpdate(update) }
    }

    private fun notifyConnectionState(connected: Boolean) {
        mainHandler.post { listener.onConnectionStateChanged(connected) }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key)
}
