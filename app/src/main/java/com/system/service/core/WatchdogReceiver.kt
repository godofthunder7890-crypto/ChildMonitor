package com.system.service.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Watchdog alarm — fires every 90 seconds on Realme Android 16 (Doze-safe).
 * Uses dual-alarm strategy: one exact alarm + one inexact alarm as fallback.
 * On Realme UI 7 / ColorOS, setExactAndAllowWhileIdle is heavily throttled,
 * so we also schedule setAndAllowWhileIdle at a shorter interval as backup.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        if (CoreService.instance == null) {
            try {
                val svcIntent = Intent(context, CoreService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Reschedule next watchdog tick
        schedule(context)
    }

    companion object {
        private const val REQUEST_EXACT    = 7890
        private const val REQUEST_INEXACT  = 7891
        private const val INTERVAL_EXACT_MS   = 90 * 1000L   // 90 seconds — exact
        private const val INTERVAL_INEXACT_MS = 60 * 1000L   // 60 seconds — inexact fallback

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val pi = pendingIntent(context, REQUEST_EXACT)
            val piFallback = pendingIntent(context, REQUEST_INEXACT)

            val triggerAt = System.currentTimeMillis() + INTERVAL_EXACT_MS

            // Strategy 1: Exact alarm (Doze-safe) — best for responsiveness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } catch (_: Exception) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                } else {
                    // On Realme Android 12+ without SCHEDULE_EXACT_ALARM permission, use window alarm
                    try {
                        am.setWindow(
                            AlarmManager.RTC_WAKEUP, triggerAt,
                            30 * 1000L,  // 30s window
                            pi
                        )
                    } catch (_: Exception) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                }
            } else {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } catch (_: Exception) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            }

            // Strategy 2: Inexact fallback alarm at shorter interval
            // Realme UI 7 kills exact alarms — this provides a second chance
            try {
                am.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + INTERVAL_INEXACT_MS,
                    piFallback
                )
            } catch (_: Exception) {}
        }

        private fun pendingIntent(context: Context, reqCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, reqCode,
                Intent(context, WatchdogReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
    }
}
