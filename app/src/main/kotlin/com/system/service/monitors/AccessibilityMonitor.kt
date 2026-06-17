package com.system.service.monitors

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class AccessibilityMonitor : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d("AccessibilityMonitor", "View Clicked")
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                Log.d("AccessibilityMonitor", "Text Changed")
            }
        }
    }

    override fun onInterrupt() {
        Log.d("AccessibilityMonitor", "Interrupted")
    }
}
