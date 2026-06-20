package com.system.service.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        val action = intent?.action ?: return

        // Guard: only handle relevant actions
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        // BUG FIX: Previous code always called startForegroundService without checking
        // Build version. minSdk=26 (Android O) so startForegroundService always available,
        // but explicit check makes intent clearer and handles edge cases in custom ROMs.
        try {
            val svcIntent = Intent(context, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (e: Exception) {
            CrashLogger.logCrash(context, e, "BootReceiver.startService")
        }

        // Schedule watchdog alarm immediately after boot
        WatchdogReceiver.schedule(context)

        // OEM-specific: try to open the autostart permission screen so the
        // ROM's startup manager "learns" this app wants autostart.
        // User still must enable manually — this just puts app in the list.
        tryRealmeAutostart(context)
    }

    /**
     * Sends brand-specific intents so the device's startup/autostart manager
     * discovers this app. On Realme / OPPO, this causes the app to appear in
     * their App Startup Manager list — user must still tap Enable themselves,
     * but at least the app shows up.
     *
     * BUG FIX: Previous implementation built the intents but never launched them.
     * Now we actually startActivity() each one with a fallback chain.
     */
    private fun tryRealmeAutostart(context: Context) {
        val brand = Build.BRAND.lowercase()
        val mfr   = Build.MANUFACTURER.lowercase()

        val intents = buildList<Intent> {
            when {
                brand.contains("realme") || mfr.contains("realme") ||
                brand.contains("oppo")   || mfr.contains("oppo") -> {
                    add(Intent().setClassName("com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                    add(Intent().setClassName("com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"))
                }
                brand.contains("vivo") || mfr.contains("vivo") -> {
                    add(Intent().setClassName("com.vivo.permissionmanagement",
                        "com.vivo.permissionmanagement.activity.SoftPermissionDetailActivity"))
                }
                brand.contains("xiaomi") || mfr.contains("xiaomi") || brand.contains("redmi") -> {
                    add(Intent().setClassName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                }
                brand.contains("huawei") || brand.contains("honor") -> {
                    add(Intent().setClassName("com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                }
                // Samsung: no known public autostart activity — skip
            }
        }

        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(i)
                return   // first successful launch is enough
            } catch (_: Exception) {}
        }
    }
}
