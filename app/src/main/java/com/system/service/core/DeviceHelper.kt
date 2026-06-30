package com.system.service.core

import android.content.Context
import android.os.Build
import android.provider.Settings

object DeviceHelper {

    fun getDeviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: "unknown"

    fun getDeviceModel(): String =
        "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun getAppVersion(context: Context): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

    fun getAndroidVersion(): String = "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    fun getSummary(context: Context): String = buildString {
        appendLine("device_id=${getDeviceId(context)}")
        appendLine("model=${getDeviceModel()}")
        appendLine("android=${getAndroidVersion()}")
        appendLine("app_version=${getAppVersion(context)}")
        appendLine("brand=${Build.BRAND}")
        appendLine("product=${Build.PRODUCT}")
        appendLine("abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"}")
    }
}
