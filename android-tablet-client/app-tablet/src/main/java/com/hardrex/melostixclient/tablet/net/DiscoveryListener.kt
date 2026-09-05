package com.hardrex.melostixclient.tablet.net

import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredHost(
    val address: InetAddress,
    val port: Int,
    /** Campo opzionale del protocollo 1.1.0 (MelostixProtocol) - un master 1.0.0 non lo manda
     *  affatto, `optBoolean` lo legge come false in quel caso, indistinguibile da "nessuna
     *  password richiesta esplicitamente" (comportamento corretto: senza questo campo il master
     *  non puo' comunque avere l'handshake). Solo informativo: il client si accorge comunque se
     *  serve una password dalla prima riga ricevuta sul canale dati (vedi MelostixClient), questo
     *  campo non e' l'unica fonte di verita'. */
    val authRequired: Boolean,
)

/** Ascolta il broadcast UDP del master (MelostixClientDiscoveryBroadcaster lato app-master) e ne
 *  estrae indirizzo IP e porta TCP annunciati - nessuna configurazione manuale necessaria. */
object DiscoveryListener {

    private const val TAG = "DiscoveryListener"

    /** Blocca fino a un annuncio valido o allo scadere di timeoutMs (null in quel caso: il
     *  chiamante puo' semplicemente riprovare). */
    fun listenOnce(timeoutMs: Int): DiscoveredHost? {
        val socket = try {
            DatagramSocket(MelostixClientProtocol.DISCOVERY_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "impossibile aprire il socket di discovery: ${e.javaClass.simpleName}: ${e.message}")
            // Fallimenti immediati (es. bind) non consumano tempo come una receive() in timeout:
            // una piccola pausa evita di martellare in loop stretto il chiamante in caso di
            // errore persistente (es. porta occupata).
            Thread.sleep(500)
            return null
        }

        // minSdk e' 19 esatto, il primo livello API in cui DatagramSocket implementa davvero
        // Closeable - .use{} e' quindi sicuro qui, non solo a livello di type-check come lo
        // sarebbe su un livello API precedente (dove compilerebbe comunque contro uno stub SDK
        // moderno ma andrebbe in ClassCastException a runtime sul device vero).
        return socket.use {
            try {
                it.soTimeout = timeoutMs
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                it.receive(packet)
                Log.i(TAG, "pacchetto ricevuto da ${packet.address?.hostAddress}, ${packet.length} byte")

                val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (json.optString("service") != MelostixClientProtocol.SERVICE_NAME) {
                    Log.i(TAG, "pacchetto ignorato, service diverso: ${json.optString("service")}")
                    null
                } else {
                    DiscoveredHost(
                        address = packet.address,
                        port = json.optInt("port", MelostixClientProtocol.DATA_PORT),
                        authRequired = json.optBoolean("authRequired", false),
                    )
                }
            } catch (e: SocketTimeoutException) {
                null
            } catch (e: Exception) {
                Log.w(TAG, "listenOnce fallito: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
