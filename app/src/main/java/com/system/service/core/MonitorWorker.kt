package com.system.service.core

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import java.util.concurrent.TimeUnit

class MonitorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext

        if (CoreService.instance == null) {
            CrashLogger.logWatchdogRestart(ctx)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(Intent(ctx, CoreService::class.java))
                } else {
                    ctx.startService(Intent(ctx, CoreService::class.java))
                }
            } catch (e: Exception) {
                CrashLogger.logCrash(ctx, e, "MonitorWorker.doWork")
            }
        }

        // Always refresh watchdog alarm — keeps both layers alive
        WatchdogReceiver.schedule(ctx)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "device_health_monitor"

        fun enqueue(context: Context) {
            // No network constraint — must run even offline
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.MINUTES)
                .build()

            // BUG FIX: REPLACE is deprecated in WorkManager 2.8+ and can cause
            // "work already running" ANR on some OEM schedulers. CANCEL_AND_REENQUEUE
            // is the safe replacement — cancels old work cleanly before enqueueing new.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
