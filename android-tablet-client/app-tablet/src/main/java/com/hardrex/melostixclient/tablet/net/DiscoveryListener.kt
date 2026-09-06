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
    /** Optional field from protocol 1.1.0 (MelostixProtocol) - a 1.0.0 master doesn't send it
     *  at all, `optBoolean` reads it as false in that case, indistinguishable from "no password
     *  explicitly required" (correct behavior: without this field the master can't have the
     *  handshake anyway). Informational only: the client still notices if a password is needed
     *  from the first line received on the data channel (see MelostixClient), this field is not
     *  the only source of truth. */
    val authRequired: Boolean,
)

/** Listens for the master's UDP broadcast (MelostixClientDiscoveryBroadcaster on the app-master
 *  side) and extracts the announced IP address and TCP port - no manual configuration needed. */
object DiscoveryListener {

    private const val TAG = "DiscoveryListener"

    /** Blocks until a valid announcement or until timeoutMs expires (null in that case: the
     *  caller can simply retry). */
    fun listenOnce(timeoutMs: Int): DiscoveredHost? {
        val socket = try {
            DatagramSocket(MelostixClientProtocol.DISCOVERY_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "unable to open the discovery socket: ${e.javaClass.simpleName}: ${e.message}")
            // Immediate failures (e.g. bind) don't take time like a receive() timeout would:
            // a small pause avoids hammering the caller in a tight loop in case of a
            // persistent error (e.g. port already in use).
            Thread.sleep(500)
            return null
        }

        // minSdk is exactly 19, the first API level where DatagramSocket actually implements
        // Closeable - .use{} is therefore safe here, not just at the type-check level as it
        // would be on an earlier API level (which would still compile against a modern SDK
        // stub but would throw ClassCastException at runtime on a real device).
        return socket.use {
            try {
                it.soTimeout = timeoutMs
                val buffer = ByteArray(512)
                val packet = DatagramPacket(buffer, buffer.size)
                it.receive(packet)
                Log.i(TAG, "packet received from ${packet.address?.hostAddress}, ${packet.length} bytes")

                val json = JSONObject(String(packet.data, 0, packet.length, Charsets.UTF_8))
                if (json.optString("service") != MelostixClientProtocol.SERVICE_NAME) {
                    Log.i(TAG, "packet ignored, different service: ${json.optString("service")}")
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
                Log.w(TAG, "listenOnce failed: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
    }
}
