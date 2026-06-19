package com.system.service.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Watchdog alarm — fires every 90s (Doze-safe dual-alarm strategy).
 * Layer 1 of 3: AlarmManager → MonitorWorker → JobScheduler
 *
 * If CoreService is dead when this fires, it logs a restart event
 * (visible in parent's diagnostic report) and restarts the service.
 */
class WatchdogReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        if (CoreService.instance == null) {
            // Log this restart so parent can see "service was killed X times"
            CrashLogger.logWatchdogRestart(context)
            try {
                val svcIntent = Intent(context, CoreService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svcIntent)
                } else {
                    context.startService(svcIntent)
                }
            } catch (e: Exception) {
                CrashLogger.logCrash(context, e, "WatchdogReceiver.onReceive")
            }
        }

        // Always reschedule — self-perpetuating alarm chain
        schedule(context)
    }

    companion object {
        private const val REQUEST_EXACT    = 7890
        private const val REQUEST_INEXACT  = 7891
        private const val INTERVAL_EXACT_MS   = 90_000L   // 90 seconds — exact
        private const val INTERVAL_INEXACT_MS = 60_000L   // 60 seconds — inexact fallback

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi         = pendingIntent(context, REQUEST_EXACT)
            val piFallback = pendingIntent(context, REQUEST_INEXACT)
            val triggerAt  = System.currentTimeMillis() + INTERVAL_EXACT_MS

            // Strategy 1: Exact alarm (Doze-safe) — best for responsiveness
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    try {
                        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    } catch (_: Exception) {
                        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                    }
                } else {
                    // Realme Android 12+ without SCHEDULE_EXACT_ALARM — use window alarm
                    try {
                        am.setWindow(AlarmManager.RTC_WAKEUP, triggerAt, 30_000L, pi)
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

            // Strategy 2: Inexact fallback — Realme UI 7 kills exact alarms,
            // this shorter interval provides a second restart chance
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
