package com.system.service.monitors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.system.service.core.CoreService
import org.json.JSONObject

/**
 * Tracks connection state and alerts parent if child goes offline for too long.
 * When connection resumes, also notifies parent that device is back online.
 */
object OfflineAlertManager {

    private const val PREFS          = "offline_alert"
    private const val KEY_THRESHOLD  = "threshold_minutes"
    private const val DEFAULT_THRESH = 30

    private var handler: Handler? = null
    private var offlineSince  = 0L
    private var alertSent     = false
    private var thresholdMs   = DEFAULT_THRESH * 60 * 1000L

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (offlineSince > 0 && !alertSent) {
                val offlineMs = System.currentTimeMillis() - offlineSince
                if (offlineMs >= thresholdMs) {
                    alertSent = true
                    // Post a local device notification on the child device itself
                    // as a reminder that it has been offline for too long.
                    // When connection resumes, CoreService will notify the parent.
                    showLocalOfflineNotification(offlineMs / 60000)
                }
            }
            handler?.postDelayed(this, 60_000L)
        }
    }

    fun start(context: Context) {
        thresholdMs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_THRESHOLD, DEFAULT_THRESH) * 60_000L
        handler = Handler(Looper.getMainLooper())
        handler?.postDelayed(checkRunnable, 60_000L)
    }

    fun setThreshold(context: Context, minutes: Int) {
        thresholdMs = minutes * 60_000L
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_THRESHOLD, minutes).apply()
    }

    fun onConnected() {
        val wasOffline = offlineSince > 0
        val offlineMs  = if (wasOffline) System.currentTimeMillis() - offlineSince else 0L
        offlineSince   = 0L
        alertSent      = false
        if (wasOffline && offlineMs > 60_000L) {
            CoreService.instance?.sendData("back_online", JSONObject().apply {
                put("offline_minutes", offlineMs / 60000)
                put("time",            System.currentTimeMillis())
            })
        }
    }

    fun onDisconnected() {
        if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
    }

    fun stop() {
        handler?.removeCallbacks(checkRunnable)
        handler = null
    }

    private fun showLocalOfflineNotification(offlineMinutes: Long) {
        val ctx = CoreService.instance ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "offline_alert"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "Offline Alert", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ Child device offline")
                    .setContentText("No parent connection for ${offlineMinutes} minutes")
                    .setAutoCancel(false)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
                    .setSmallIcon(android.R.drawable.ic_dialog_alert)
                    .setContentTitle("⚠️ Child device offline")
                    .setContentText("No parent connection for ${offlineMinutes} minutes")
                    .build()
            }
            nm.notify(9922, notif)
        } catch (_: Exception) {}
    }
}
