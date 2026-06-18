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
    private var shouldReconnect = true

    fun connect() {
        shouldReconnect = true
        connectInternal()
    }

    private fun connectInternal() {
        try {
            client?.close()
            client = object : WebSocketClient(URI(serverUrl)) {

                override fun onOpen(h: ServerHandshake?) {
                    // Send register + pair_code together on connect
                    send(JSONObject().apply {
                        put("type", "register")
                        put("role", "child")
                        put("pair_code", pairCode)
                    }.toString())
                    handler.post { onConnected() }
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            val data = JSONObject(it)
                            // auth_ok / error are internal — don't forward to app
                            val type = data.optString("type")
                            if (type == "auth_ok") return
                            if (type == "error") return
                            handler.post { onMessage(data) }
                        } catch (_: Exception) { }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    handler.post { onDisconnected() }
                    if (shouldReconnect) {
                        handler.postDelayed({ connectInternal() }, 5000)
                    }
                }

                override fun onError(ex: Exception?) {
                    if (shouldReconnect) {
                        handler.postDelayed({ connectInternal() }, 5000)
                    }
                }
            }
            client?.connect()
        } catch (_: Exception) {
            if (shouldReconnect) {
                handler.postDelayed({ connectInternal() }, 5000)
            }
        }
    }

    fun send(data: JSONObject) {
        try {
            if (client?.isOpen == true) {
                client?.send(data.toString())
            }
        } catch (_: Exception) { }
    }

    fun disconnect() {
        shouldReconnect = false
        client?.close()
    }
}
