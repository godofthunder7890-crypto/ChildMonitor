package com.system.service.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Layer 3 of 3 restart-safety net: JobScheduler periodic job.
 * AlarmManager (WatchdogReceiver) → WorkManager (MonitorWorker) → JobScheduler (this).
 *
 * Rationale: Realme/ColorOS/OPPO delay setExactAndAllowWhileIdle alarms to ~15 min
 * under battery saver, and WorkManager is sometimes also throttled. JobScheduler
 * with setPersisted(true) survives reboots and is not subject to the same OEM
 * alarm restrictions, giving us a third independent restart path.
 */
class RestartJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        if (CoreService.instance == null) {
            CrashLogger.logWatchdogRestart(applicationContext)
            try {
                val svcIntent = Intent(this, CoreService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            } catch (e: Exception) {
                CrashLogger.logCrash(this, e, "RestartJobService")
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 9999

        fun schedule(context: Context) {
            try {
                val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                val componentName = ComponentName(context, RestartJobService::class.java)
                val jobInfo = JobInfo.Builder(JOB_ID, componentName)
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
                js.schedule(jobInfo)
            } catch (_: Exception) {}
        }
    }
}
