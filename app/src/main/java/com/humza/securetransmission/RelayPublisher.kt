package com.humza.securetransmission

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * Pushes JPEG frames to the online relay over a WebSocket and reconnects
 * automatically if the connection drops (phone moving between WiFi/mobile data,
 * server waking up from sleep, etc). Frames are never queued up: if the network
 * can't keep pace, new frames are simply dropped so the stream stays live.
 */
class RelayPublisher(
    baseUrl: String,
    key: String,
    private val onState: (String) -> Unit
) {
    private val url: String
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var socket: WebSocket? = null
    @Volatile private var connected = false
    @Volatile private var stopped = false
    private var retryDelayMs = 1000L

    init {
        var b = baseUrl.trim().trimEnd('/')
        b = when {
            b.startsWith("https://") -> "wss://" + b.removePrefix("https://")
            b.startsWith("http://") -> "ws://" + b.removePrefix("http://")
            b.startsWith("wss://") || b.startsWith("ws://") -> b
            else -> "wss://$b"
        }
        url = "$b/publish?key=" + java.net.URLEncoder.encode(key.trim(), "UTF-8")
    }

    fun start() {
        stopped = false
        connect()
    }

    private fun connect() {
        if (stopped) return
        onState("Relay: connecting…")
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                retryDelayMs = 1000L
                onState("Relay: connected")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost("closed")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val why = if (response?.code == 401) "wrong stream key" else (t.message ?: "error")
                lost(why)
            }
        })
    }

    private fun lost(why: String) {
        connected = false
        if (stopped) return
        onState("Relay: $why — retrying")
        handler.postDelayed({ connect() }, retryDelayMs)
        retryDelayMs = minOf(retryDelayMs * 2, 10_000L)
    }

    fun sendFrame(jpeg: ByteArray) {
        val ws = socket ?: return
        if (!connected) return
        // Drop the frame if more than ~512 KB is still waiting to upload.
        if (ws.queueSize() > 512 * 1024) return
        ws.send(jpeg.toByteString())
    }

    fun stop() {
        stopped = true
        connected = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
