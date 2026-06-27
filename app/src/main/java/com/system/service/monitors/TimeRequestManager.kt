package com.system.service.monitors

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.system.service.R
import com.system.service.core.CoreService
import org.json.JSONObject

/**
 * TimeRequestManager — lets the child request extra screen time from the parent.
 *
 * Flow:
 *  1. Child taps "Request Time" in HiddenSettingsActivity or ChildRequestActivity
 *  2. Manager sends {"type":"time_request", "minutes":X, "reason":"..."} to parent via WebSocket
 *  3. Parent approves/denies
 *  4. CoreService receives {"command":"time_approved", "minutes":X} or {"command":"time_denied"}
 *  5. CoreService calls onTimeApproved/onTimeDenied here
 */
object TimeRequestManager {

    private const val CHANNEL_ID = "time_request"
    private const val NOTIF_ID   = 9900

    var lastRequestId: String = ""
    var pendingMinutes: Int = 0

    fun sendTimeRequest(ctx: Context, minutes: Int, reason: String) {
        val requestId = "req_${System.currentTimeMillis()}"
        lastRequestId = requestId
        pendingMinutes = minutes

        val prefs = ctx.getSharedPreferences(CoreService.PREFS_NAME, Context.MODE_PRIVATE)
        val pairCode = prefs.getString(CoreService.KEY_PAIR_CODE, "") ?: ""

        val msg = JSONObject().apply {
            put("type", "time_request")
            put("request_id", requestId)
            put("minutes", minutes)
            put("reason", reason)
            put("pair_code", pairCode)
        }

        CoreService.instance?.let { svc ->
            try { svc.sendToParent(msg) } catch (_: Exception) {}
        }

        showPendingNotification(ctx, minutes, reason)
    }

    fun onTimeApproved(ctx: Context, minutes: Int) {
        pendingMinutes = 0
        showApprovedNotification(ctx, minutes)
        // CoreService's AppBlockerManager can use this to grant temporary access
        CoreService.instance?.onTimeGranted(minutes)
    }

    fun onTimeDenied(ctx: Context) {
        pendingMinutes = 0
        showDeniedNotification(ctx)
    }

    private fun showPendingNotification(ctx: Context, minutes: Int, reason: String) {
        ensureChannel(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏱ Time Request Sent")
            .setContentText("Requested $minutes min extra — waiting for parent...")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Requested $minutes minutes extra screen time.\nReason: $reason\n\nWaiting for parent approval..."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(false)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, notif)
    }

    private fun showApprovedNotification(ctx: Context, minutes: Int) {
        ensureChannel(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("✅ Time Approved!")
            .setContentText("Parent approved $minutes minutes extra screen time")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID + 1, notif)
    }

    private fun showDeniedNotification(ctx: Context) {
        ensureChannel(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("❌ Time Request Denied")
            .setContentText("Parent denied your screen time request")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID + 2, notif)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Time Requests", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
