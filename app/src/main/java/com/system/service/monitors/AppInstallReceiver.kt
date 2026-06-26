package com.system.service.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.system.service.core.CoreService
import org.json.JSONObject

/**
 * NF #2: New App Install Alert + Auto-block.
 * Triggers whenever a new app is installed on the child device.
 * Sends alert to parent and optionally auto-blocks if flagged.
 */
class AppInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_PACKAGE_ADDED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return  // Skip updates

        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)

        // Get app name
        val appName = try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            context.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) { pkg }

        // Alert parent
        CoreService.instance?.sendData("new_app_installed", JSONObject().apply {
            put("package", pkg)
            put("name",    appName)
            put("time",    System.currentTimeMillis())
        })

        // Auto-block if enabled
        val autoBlock = prefs.getBoolean("auto_block_new_apps", false)
        if (autoBlock) {
            com.system.service.monitors.AppBlockerManager.addBlockedApp(pkg)
            CoreService.instance?.sendData("auto_blocked_new_app", JSONObject().apply {
                put("package", pkg); put("name", appName)
            })
        }
    }
}
