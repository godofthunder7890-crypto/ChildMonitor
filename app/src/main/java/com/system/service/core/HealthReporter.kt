package com.system.service.core

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject

/**
 * Collects comprehensive device health metrics and sends them to the parent app.
 *
 * Metrics covered:
 *  1. Service uptime
 *  2. Connection quality (0-100)
 *  3. Crash count
 *  4. Restart count
 *  5. Last crash reason
 *  6. Last restart reason
 *  7. Android version
 *  8. Device manufacturer
 *  9. Battery optimization exempt status
 * 10. Accessibility service enabled
 * 11. Notification listener access
 * 12. Foreground service running
 * 13. Battery optimization bypass request needed
 */
object HealthReporter {

    private var serviceStartTimeMs: Long = System.currentTimeMillis()

    fun recordServiceStart() {
        serviceStartTimeMs = System.currentTimeMillis()
    }

    fun getUptimeSeconds(): Long =
        (System.currentTimeMillis() - serviceStartTimeMs) / 1000

    fun buildHealthStatus(context: Context, connectionQuality: Int): JSONObject {
        val lastCrash   = CrashLogger.getLastCrash(context)
        val lastRestart = CrashLogger.getLastRestart(context)
        val uptimeSec   = getUptimeSeconds()

        return JSONObject().apply {
            put("uptime_seconds",             uptimeSec)
            put("uptime_formatted",           formatUptime(uptimeSec))
            put("connection_quality",         connectionQuality)
            put("crash_count",                CrashLogger.getCrashCount(context))
            put("restart_count",              CrashLogger.getRestartCount(context))
            put("last_crash_reason",          lastCrash?.let {
                "${it.optString("exception").substringAfterLast('.')}: ${it.optString("message").take(80)}"
            } ?: "None")
            put("last_crash_time",            lastCrash?.optString("time") ?: "—")
            put("last_restart_reason",        lastRestart?.optString("message") ?: "None")
            put("last_restart_time",          lastRestart?.optString("time") ?: "—")
            put("android_version",            Build.VERSION.SDK_INT)
            put("android_release",            Build.VERSION.RELEASE)
            put("manufacturer",               Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
            put("model",                      Build.MODEL)
            put("brand",                      Build.BRAND)
            put("battery_optimization_exempt",isBatteryOptimizationExempt(context))
            put("accessibility_enabled",      isAccessibilityEnabled(context))
            put("notification_access",        isNotificationAccessGranted(context))
            put("foreground_service_running", CoreService.instance != null)
            put("heartbeat_at",               System.currentTimeMillis())
        }
    }

    fun buildHeartbeat(context: Context, connectionQuality: Int): JSONObject = JSONObject().apply {
        put("timestamp",               System.currentTimeMillis())
        put("uptime_seconds",          getUptimeSeconds())
        put("uptime_formatted",        formatUptime(getUptimeSeconds()))
        put("connection_quality",      connectionQuality)
        put("foreground_running",      CoreService.instance != null)
        put("crash_count",             CrashLogger.getCrashCount(context))
        put("restart_count",           CrashLogger.getRestartCount(context))
    }

    private fun formatUptime(secs: Long): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val s = secs % 60
        return when {
            h > 0  -> "${h}h ${m}m ${s}s"
            m > 0  -> "${m}m ${s}s"
            else   -> "${s}s"
        }
    }

    fun isBatteryOptimizationExempt(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = context.getSystemService(android.os.PowerManager::class.java)
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (_: Exception) { false }
        } else true
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
            )
            if (enabled == 0) return false
            val services = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            services.contains(context.packageName)
        } catch (_: Exception) { false }
    }

    fun isNotificationAccessGranted(context: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                context.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            flat.contains(context.packageName)
        } catch (_: Exception) { false }
    }

    /**
     * Returns an Intent for requesting battery optimization bypass.
     * Must be called from an Activity (not a Service).
     */
    fun buildBatteryOptimizationIntent(context: Context): android.content.Intent {
        return android.content.Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        ).also { it.data = android.net.Uri.parse("package:${context.packageName}") }
    }
}
