package com.system.service.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context ?: return
        // Service shuru karo
        try {
            context.startForegroundService(
                Intent(context, CoreService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // Watchdog schedule karo
        WatchdogReceiver.schedule(context)
    }
}
