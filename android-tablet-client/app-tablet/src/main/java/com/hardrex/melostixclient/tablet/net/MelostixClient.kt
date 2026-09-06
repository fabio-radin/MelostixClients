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
 * Manages the entire connection lifecycle with the master: discovery via UDP broadcast, TCP
 * connection, reading JSON updates line by line, automatic reconnection (goes back to
 * searching for the master from scratch) if the connection drops or is never found. Runs on a
 * dedicated thread: no coroutines/AndroidX, just java.net + a Handler to get back on the UI
 * thread - staying on this minimal skeleton avoids introducing a Gradle dependency for such a
 * small client.
 *
 * [passwordProvider] is read on every new connection, not captured once: changing the
 * password from Settings applies to the next connection attempt without restarting the
 * client. Empty/null = no password configured (identical behavior to protocol 1.0.0: if the
 * master doesn't require one the connection still works; if it does, this client won't be able
 * to authenticate - see readFrom).
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
        Log.i(TAG, "started, listening for broadcasts on port ${MelostixClientProtocol.DISCOVERY_PORT}")
        while (running) {
            val host = DiscoveryListener.listenOnce(DISCOVERY_TIMEOUT_MS) ?: continue
            Log.i(TAG, "master found: ${host.address.hostAddress}:${host.port}")
            readFrom(host)
            notifyConnectionState(false)
        }
    }

    private fun readFrom(host: DiscoveredHost) {
        val socket = try {
            Socket().apply { connect(InetSocketAddress(host.address, host.port), CONNECT_TIMEOUT_MS) }
        } catch (e: IOException) {
            Log.w(TAG, "connection failed: ${e.message}")
            return
        }

        // minSdk 19: Socket implements Closeable from here on, .use{} is safe - see the
        // equivalent comment in DiscoveryListener.kt.
        socket.use {
            try {
                val reader = BufferedReader(InputStreamReader(it.getInputStream(), Charsets.UTF_8))
                var line = reader.readLine() ?: return

                // Protocol 1.1.0 (MelostixProtocol): if the master requires a password, the
                // very first line is an authChallenge instead of a normal status update - see
                // protocol.md, "Authentication (optional)". A master with no password
                // configured sends a status update directly, exactly as in protocol 1.0.0:
                // this client stays compatible with both without knowing in advance which one
                // it will meet (authRequired in the discovery packet is only an early
                // informational signal, not the only source of truth).
                val challenge = runCatching { JSONObject(line) }.getOrNull()
                if (challenge?.optString("kind") == "authChallenge") {
                    val password = passwordProvider()
                    if (password.isNullOrEmpty()) {
                        Log.w(TAG, "the master requires a password that isn't configured on this client")
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
                Log.i(TAG, "connection interrupted: ${e.message}")
            }
        }
    }

    /** Protocol 1.2.0 (MelostixProtocol): optional line, sent once per connection, that
     *  declares this client's type to the master - see protocol.md, "Client identification
     *  (optional)". Sent after the optional authResponse (or right after connecting if no
     *  authentication is needed) and never before reading the first status line: the master
     *  doesn't wait for it and doesn't reply, so there's no need to wait for anything after
     *  writing it. */
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
            Log.w(TAG, "invalid message: $line", e)
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
