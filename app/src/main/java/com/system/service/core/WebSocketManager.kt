package com.system.service.core

import android.os.Handler
import android.os.Looper
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class WebSocketManager(
    private val serverUrl: String,
    private val pairCode: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var client: WebSocketClient? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var shouldReconnect = true

    // BUG FIX: Double-reconnect guard — forceReconnect() calls client.close() which fires
    // onClose → scheduleReconnect(). Without this flag, two reconnects race each other,
    // causing a permanent loop of connect → close → connect → close.
    @Volatile private var reconnectScheduled = false

    // BUG FIX: Exponential backoff — hammering relay every 3s when server is down
    // caused relay to rate-limit/ban us. Now: 3s → 6s → 12s → 24s → 60s max.
    private var reconnectDelayMs = BASE_DELAY_MS

    companion object {
        private const val BASE_DELAY_MS = 3_000L
        private const val MAX_DELAY_MS  = 60_000L
        private const val PING_INTERVAL = 15_000L   // 15s ping — Railway closes idle WS at 60s
    }

    fun connect() {
        shouldReconnect  = true
        reconnectDelayMs = BASE_DELAY_MS
        connectInternal()
    }

    private fun connectInternal() {
        reconnectScheduled = false
        try {
            // Close previous client silently — set shouldReconnect=false temporarily
            // so onClose doesn't fire another reconnect while we're already reconnecting
            val old = client
            client = null
            try { old?.close() } catch (_: Exception) {}

            val ws = object : WebSocketClient(URI(serverUrl)) {

                override fun onOpen(h: ServerHandshake?) {
                    // BUG FIX: Reset backoff on successful connect
                    reconnectDelayMs = BASE_DELAY_MS
                    send(JSONObject().apply {
                        put("type",      "register")
                        put("role",      "child")
                        put("pair_code", pairCode)
                    }.toString())
                    handler.post { onConnected() }
                    startPing(this)
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            val data = JSONObject(it)
                            val type = data.optString("type")
                            if (type == "auth_ok" || type == "pong") return
                            if (type == "error") return
                            handler.post { onMessage(data) }
                        } catch (_: Exception) {}
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    // Only notify + schedule if this is still the active client
                    if (client !== this) return
                    handler.post { onDisconnected() }
                    scheduleReconnect()
                }

                override fun onError(ex: Exception?) {
                    if (client !== this) return
                    scheduleReconnect()
                }
            }
            client = ws
            ws.connect()
        } catch (_: Exception) {
            scheduleReconnect()
        }
    }

    // BUG FIX: Single scheduled reconnect with exponential backoff.
    // Previous code had no guard: onClose + onError could both schedule reconnects.
    private fun scheduleReconnect() {
        if (!shouldReconnect || reconnectScheduled) return
        reconnectScheduled = true
        val delay = reconnectDelayMs
        // Double delay for next attempt, cap at MAX
        reconnectDelayMs = minOf(reconnectDelayMs * 2, MAX_DELAY_MS)
        handler.postDelayed({ if (shouldReconnect) connectInternal() }, delay)
    }

    // BUG FIX: Ping every 15s instead of 10s — reduces unnecessary traffic.
    // Pass the specific client instance so stale ping loops don't survive reconnects.
    private fun startPing(ws: WebSocketClient) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (client !== ws || !ws.isOpen) return   // stale loop guard
                try { ws.send(JSONObject().apply { put("type", "ping") }.toString()) } catch (_: Exception) {}
                handler.postDelayed(this, PING_INTERVAL)
            }
        }, PING_INTERVAL)
    }

    fun send(data: JSONObject) {
        try {
            if (client?.isOpen == true) client?.send(data.toString())
        } catch (_: Exception) {}
    }

    fun isConnected(): Boolean = client?.isOpen == true

    // Force close + reconnect — called by NetworkCallback when internet comes back.
    // BUG FIX: Previous forceReconnect() called client.close() which fired onClose
    // → which scheduled ANOTHER reconnect. Now we use connectInternal() directly
    // after clearing the client reference so onClose of the old client is ignored.
    fun forceReconnect() {
        reconnectDelayMs = BASE_DELAY_MS   // reset backoff on explicit reconnect
        handler.post { connectInternal() }
    }

    fun disconnect() {
        shouldReconnect    = false
        reconnectScheduled = false
        handler.removeCallbacksAndMessages(null)
        try { client?.close() } catch (_: Exception) {}
        client = null
    }
}
