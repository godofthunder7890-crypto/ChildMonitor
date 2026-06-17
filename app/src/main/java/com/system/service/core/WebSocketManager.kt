package com.system.service.core

import android.os.Handler
import android.os.Looper
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI

class WebSocketManager(
    private val serverUrl: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: () -> Unit
) {
    private var client: WebSocketClient? = null
    private val handler = Handler(Looper.getMainLooper())

    fun connect() {
        try {
            client = object : WebSocketClient(URI(serverUrl)) {

                override fun onOpen(h: ServerHandshake?) {
                    send(JSONObject().apply {
                        put("type", "register")
                        put("role", "child")
                    }.toString())
                    onConnected()
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            onMessage(JSONObject(it))
                        } catch (e: Exception) { }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    onDisconnected()
                    // Auto reconnect 5 sec baad
                    handler.postDelayed({ connect() }, 5000)
                }

                override fun onError(ex: Exception?) {
                    handler.postDelayed({ connect() }, 5000)
                }
            }
            client?.connect()
        } catch (e: Exception) {
            handler.postDelayed({ connect() }, 5000)
        }
    }

    fun send(data: JSONObject) {
        try {
            if (client?.isOpen == true) {
                client?.send(data.toString())
            }
        } catch (e: Exception) { }
    }

    fun disconnect() {
        client?.close()
    }
}
