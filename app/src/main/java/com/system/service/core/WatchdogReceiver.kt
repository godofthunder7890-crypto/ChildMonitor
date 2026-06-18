package com.system.service.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        // Service chal raha hai ya nahi check karo
        if (CoreService.instance == null) {
            try {
                context.startForegroundService(
                    Intent(context, CoreService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Agla alarm schedule karo (2 minute baad)
        scheduleNext(context)
    }

    companion object {
        private const val REQUEST_CODE = 7890

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = PendingIntent.getBroadcast(
                context, REQUEST_CODE,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                // setExactAndAllowWhileIdle — Doze mode mein bhi kaam karta hai
                am.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 2 * 60 * 1000L,
                    intent
                )
            } catch (e: Exception) {
                // Fallback to setAndAllowWhileIdle
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + 2 * 60 * 1000L,
                    intent
                )
            }
        }

        private fun scheduleNext(context: Context) {
            schedule(context)
        }
    }
}
