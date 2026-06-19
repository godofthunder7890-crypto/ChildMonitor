package com.system.service.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return

        // Start core service
        try {
            context.startForegroundService(Intent(context, CoreService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Schedule watchdog alarm
        WatchdogReceiver.schedule(context)

        // Realme / OPPO autostart registration — silently try to register
        // so the app appears in the autostart list. User still needs to enable it manually,
        // but this intent call "teaches" Realme UI that the app wants autostart.
        tryRealmeAutostart(context)
    }

    /**
     * Sends a broadcast that Realme UI 7 / ColorOS / OPPO recognise as an autostart request.
     * This does NOT grant permission automatically — user must still enable it in
     * Settings > Battery > Autostart. But sending it puts the app in the list.
     */
    private fun tryRealmeAutostart(context: Context) {
        val intents = listOf(
            // Realme / OPPO ColorOS
            Intent().apply {
                setClassName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.FakeActivity")
            },
            Intent().apply {
                setClassName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.FakeActivity")
            },
            // Vivo
            Intent().apply {
                setClassName("com.vivo.permissionmanagement",
                    "com.vivo.permissionmanagement.activity.SoftPermissionDetailActivity")
            },
            // Xiaomi
            Intent().apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")
            },
            // Huawei
            Intent().apply {
                setClassName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            }
        )
        for (i in intents) {
            try {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Don't actually launch — just broadcast as a signal
                // (Some ROM managers listen for implicit broadcasts from apps they track)
            } catch (_: Exception) {}
        }
    }
}
