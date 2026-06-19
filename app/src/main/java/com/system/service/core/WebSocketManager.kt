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
    // BUG FIX: shouldReconnect main thread se write hoti thi lekin WS callback threads se read hoti thi.
    // @Volatile ensures all threads latest value dekhein bina JVM register cache ke.
    @Volatile private var shouldReconnect = true

    fun connect() {
        shouldReconnect = true
        connectInternal()
    }

    private fun connectInternal() {
        try {
            client?.close()
            client = object : WebSocketClient(URI(serverUrl)) {

                override fun onOpen(h: ServerHandshake?) {
                    send(JSONObject().apply {
                        put("type", "register")
                        put("role", "child")
                        put("pair_code", pairCode)
                    }.toString())
                    handler.post { onConnected() }
                    startPing()
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            val data = JSONObject(it)
                            val type = data.optString("type")
                            // pong + auth_ok are internal keepalive — don't forward
                            if (type == "auth_ok" || type == "pong") return
                            if (type == "error") return
                            handler.post { onMessage(data) }
                        } catch (_: Exception) { }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    handler.post { onDisconnected() }
                    if (shouldReconnect) {
                        handler.postDelayed({ connectInternal() }, 3000)
                    }
                }

                override fun onError(ex: Exception?) {
                    if (shouldReconnect) {
                        handler.postDelayed({ connectInternal() }, 3000)
                    }
                }
            }
            client?.connect()
        } catch (_: Exception) {
            if (shouldReconnect) {
                handler.postDelayed({ connectInternal() }, 3000)
            }
        }
    }

    // Ping every 10s — keeps Railway connection alive + detects stale connections fast
    private fun startPing() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (client?.isOpen == true) {
                    try { client?.send(JSONObject().apply { put("type", "ping") }.toString()) } catch (_: Exception) {}
                    handler.postDelayed(this, 10000)
                }
            }
        }, 10000)
    }

    fun send(data: JSONObject) {
        try {
            if (client?.isOpen == true) client?.send(data.toString())
        } catch (_: Exception) { }
    }

    fun isConnected(): Boolean = client?.isOpen == true

    // Force close + reconnect — called by NetworkCallback when internet comes back
    fun forceReconnect() {
        try { client?.close() } catch (_: Exception) {}
        handler.postDelayed({ connectInternal() }, 1000)
    }

    fun disconnect() {
        shouldReconnect = false
        try { client?.close() } catch (_: Exception) {}
    }
}
