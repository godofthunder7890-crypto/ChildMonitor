package com.system.service.core

import android.content.Context
import android.content.Intent
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit

class MonitorWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        // Service chal raha hai ya nahi check karo
        if (CoreService.instance == null) {
            try {
                applicationContext.startForegroundService(
                    Intent(applicationContext, CoreService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // Watchdog bhi refresh karo
        WatchdogReceiver.schedule(applicationContext)
        return Result.success()
    }

    companion object {
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<MonitorWorker>(
                15, TimeUnit.MINUTES  // Minimum interval WorkManager allow karta hai
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "device_health_monitor",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
