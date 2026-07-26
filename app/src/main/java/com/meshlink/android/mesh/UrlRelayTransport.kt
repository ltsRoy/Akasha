package com.MeshLink.android.mesh

import android.content.Context
import android.util.Log
import com.MeshLink.android.model.RoutedPacket
import com.MeshLink.android.net.OkHttpProvider
import com.MeshLink.android.protocol.MeshLinkPacket
import com.MeshLink.android.util.toHexString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.ConcurrentHashMap

/**
 * URL/WebSocket packet bridge for laptop-hosted relays.
 *
 * This transport forwards the existing binary MeshLinkPacket frames over
 * WebSocket binary messages. The laptop side does not need to understand
 * encryption or chat semantics to help Android peers communicate.
 */
class UrlRelayTransport(
    private val context: Context,
    private val myPeerID: String,
    private val onPacketReceived: (RoutedPacket) -> Unit
) {
    companion object {
        private const val TAG = "UrlRelayTransport"
        const val RELAY_ADDRESS_PREFIX = "url-relay:"
        private const val RECONNECT_DELAY_MS = 5_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sockets = ConcurrentHashMap<String, WebSocket>()
    private val connected = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = false

    fun start() {
        val urls = UrlRelayPreferences.getRelayUrls(context)
        if (!UrlRelayPreferences.isEnabled(context) || urls.isEmpty()) {
            Log.d(TAG, "URL relay disabled")
            return
        }
        running = true
        urls.forEach { url ->
            if (!sockets.containsKey(url)) connect(url)
        }
    }

    fun stop() {
        running = false
        sockets.values.forEach { socket ->
            try { socket.close(1000, "stopping") } catch (_: Exception) { }
        }
        sockets.clear()
        connected.clear()
        scope.cancel()
    }

    fun send(routed: RoutedPacket) {
        if (!running) return
        if (routed.relayAddress?.startsWith(RELAY_ADDRESS_PREFIX) == true) return
        val bytes = routed.packet.toBinaryData() ?: return
        val frame = bytes.toByteString()
        sockets.forEach { (url, socket) ->
            if (connected.contains(url)) {
                try {
                    socket.send(frame)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send packet to $url: ${e.message}")
                }
            }
        }
    }

    private fun connect(url: String) {
        if (!running || sockets.containsKey(url)) return
        val request = Request.Builder().url(url).build()
        Log.i(TAG, "Connecting URL relay $url")
        val socket = OkHttpProvider.webSocketClient().newWebSocket(request, listener(url))
        sockets[url] = socket
    }

    private fun listener(url: String): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected.add(url)
                Log.i(TAG, "URL relay connected: $url")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val packet = MeshLinkPacket.fromBinaryData(bytes.toByteArray()) ?: return
                val peerID = packet.senderID.toHexString()
                if (peerID == myPeerID) return
                onPacketReceived(RoutedPacket(packet, peerID, "$RELAY_ADDRESS_PREFIX$url"))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scheduleReconnect(url)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "URL relay failed ($url): ${t.message}")
                scheduleReconnect(url)
            }
        }
    }

    private fun scheduleReconnect(url: String) {
        connected.remove(url)
        sockets.remove(url)
        if (!running) return
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            connect(url)
        }
    }
}
