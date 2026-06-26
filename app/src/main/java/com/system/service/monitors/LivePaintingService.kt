package com.system.service.monitors

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import androidx.core.app.NotificationCompat

/**
 * LivePaintingService — SafeWatch Blueprint: "Live Painting"
 * Draws parent's touch strokes on child's screen as a transparent WindowManager overlay.
 * Commands: paint_stroke {x,y,color,size,action:"down"|"move"|"up"} | clear_painting
 */
class LivePaintingService : Service() {

    companion object {
        var instance: LivePaintingService? = null
        private const val CHANNEL_ID = "live_painting"
        private const val NOTIF_ID   = 77

        fun sendStroke(data: org.json.JSONObject) { instance?.applyStroke(data) }
        fun clear()                               { instance?.clearCanvas() }
        fun isRunning()                           = instance != null
    }

    private var windowManager: WindowManager? = null
    private var overlayView:   PaintOverlayView? = null
    private val mainHandler    = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        showOverlay()
    }

    // BUG FIX: live_painting_stop command sends a STOP intent via startService(), but
    // there was no onStartCommand handler, so the service never actually stopped —
    // overlay remained on screen indefinitely.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView   = PaintOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try { windowManager?.addView(overlayView, params) } catch (_: Exception) {}
    }

    // BUG FIX: CoreService called onRemoteDraw() which didn't exist — compile error.
    // Added this adapter method that CoreService can call with x,y,action floats.
    fun onRemoteDraw(x: Float, y: Float, action: String) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        val px = x.coerceIn(0f, 1f) * w
        val py = y.coerceIn(0f, 1f) * h
        mainHandler.post { overlayView?.addStroke(px, py, Color.RED, 20f, action) }
    }

    fun applyStroke(data: org.json.JSONObject) {
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        // x,y are normalized 0.0–1.0 so it works across different screen sizes
        val x      = data.optDouble("x", 0.0).toFloat().coerceIn(0f, 1f) * w
        val y      = data.optDouble("y", 0.0).toFloat().coerceIn(0f, 1f) * h
        val color  = parseHexColor(data.optString("color", "#FF4444"))
        val size   = data.optDouble("size", 12.0).toFloat().coerceIn(4f, 80f)
        val action = data.optString("action", "move")
        mainHandler.post { overlayView?.addStroke(x, y, color, size, action) }
    }

    fun clearCanvas() {
        mainHandler.post { overlayView?.clear() }
    }

    private fun parseHexColor(hex: String): Int =
        try { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") } catch (_: Exception) { Color.RED }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Annotation", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Screen Annotation Active")
            .setContentText("Parent is drawing on your screen")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    // ── Inner canvas view ────────────────────────────────────────────────────

    class PaintOverlayView(context: Context) : View(context) {

        private val paths    = mutableListOf<Pair<Path, Paint>>()
        private var curPath: Path?  = null
        private var curPaint: Paint? = null

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paths.forEach { (path, paint) -> canvas.drawPath(path, paint) }
            // BUG FIX: curPaint!! crashes if "move"/"up" arrives before "down" initialises curPaint.
            // Use safe let-chain — skip draw if either is null.
            val cp = curPath; val cpt = curPaint
            if (cp != null && cpt != null) canvas.drawPath(cp, cpt)
        }

        fun addStroke(x: Float, y: Float, color: Int, size: Float, action: String) {
            when (action) {
                "down" -> {
                    curPath  = Path().apply { moveTo(x, y) }
                    curPaint = Paint().apply {
                        this.color   = color
                        strokeWidth  = size
                        style        = Paint.Style.STROKE
                        strokeCap    = Paint.Cap.ROUND
                        strokeJoin   = Paint.Join.ROUND
                        isAntiAlias  = true
                        alpha        = 210
                    }
                }
                "move" -> curPath?.lineTo(x, y)
                "up"   -> {
                    curPath?.lineTo(x, y)
                    val p = curPath; val pt = curPaint
                    if (p != null && pt != null) {
                        paths.add(p to pt)
                        // Auto-fade stroke after 10 seconds
                        postDelayed({ paths.removeFirstOrNull(); invalidate() }, 10_000)
                    }
                    curPath = null; curPaint = null
                }
            }
            invalidate()
        }

        fun clear() {
            paths.clear(); curPath = null; curPaint = null; invalidate()
        }
    }
}
