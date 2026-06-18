package com.system.service.monitors

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object AppBlockerManager {

    private const val PREFS = "app_blocker"
    private const val KEY_BLOCKED = "blocked_apps"
    private const val KEY_LIMITS  = "screen_time_limits"
    private const val KEY_USAGE   = "today_usage"
    private const val KEY_DATE    = "usage_date"

    // Cached sets for fast O(1) lookup in AccessibilityMonitor
    private val blockedApps = mutableSetOf<String>()
    // pkg -> limit in minutes
    private val timeLimits  = mutableMapOf<String, Int>()
    // pkg -> minutes used today
    private val todayUsage  = mutableMapOf<String, Long>()

    private var lastUsagePkg = ""
    private var lastUsageStart = 0L
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Reset usage if new day
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val savedDate = prefs!!.getString(KEY_DATE, "")
        if (savedDate != today) {
            prefs!!.edit().putString(KEY_DATE, today).remove(KEY_USAGE).apply()
        }
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return
        // Blocked apps
        val blockedJson = p.getString(KEY_BLOCKED, "[]") ?: "[]"
        blockedApps.clear()
        try {
            val arr = JSONArray(blockedJson)
            for (i in 0 until arr.length()) blockedApps.add(arr.getString(i))
        } catch (_: Exception) {}
        // Time limits
        val limitsJson = p.getString(KEY_LIMITS, "{}") ?: "{}"
        timeLimits.clear()
        try {
            val obj = JSONObject(limitsJson)
            for (key in obj.keys()) timeLimits[key] = obj.getInt(key)
        } catch (_: Exception) {}
        // Today usage
        val usageJson = p.getString(KEY_USAGE, "{}") ?: "{}"
        todayUsage.clear()
        try {
            val obj = JSONObject(usageJson)
            for (key in obj.keys()) todayUsage[key] = obj.getLong(key)
        } catch (_: Exception) {}
    }

    fun setBlockedApps(pkgs: List<String>) {
        blockedApps.clear()
        blockedApps.addAll(pkgs)
        val arr = JSONArray(pkgs)
        prefs?.edit()?.putString(KEY_BLOCKED, arr.toString())?.apply()
    }

    fun setTimeLimit(pkg: String, limitMinutes: Int) {
        if (limitMinutes <= 0) timeLimits.remove(pkg) else timeLimits[pkg] = limitMinutes
        prefs?.edit()?.putString(KEY_LIMITS, JSONObject(timeLimits as Map<*, *>).toString())?.apply()
    }

    fun isBlocked(pkg: String): Boolean = blockedApps.contains(pkg)

    fun isTimeLimitExceeded(pkg: String): Boolean {
        val limit = timeLimits[pkg] ?: return false
        val usedMs = todayUsage[pkg] ?: 0L
        return (usedMs / 60000L) >= limit
    }

    fun trackAppStart(pkg: String) {
        if (lastUsagePkg.isNotEmpty()) trackAppEnd()
        lastUsagePkg  = pkg
        lastUsageStart = System.currentTimeMillis()
    }

    fun trackAppEnd() {
        if (lastUsagePkg.isEmpty() || lastUsageStart == 0L) return
        val elapsed = System.currentTimeMillis() - lastUsageStart
        todayUsage[lastUsagePkg] = (todayUsage[lastUsagePkg] ?: 0L) + elapsed
        lastUsagePkg  = ""; lastUsageStart = 0L
        prefs?.edit()?.putString(KEY_USAGE, JSONObject(todayUsage as Map<*, *>).toString())?.apply()
    }

    fun getDailyReport(): JSONObject {
        trackAppEnd() // flush current session
        val apps = JSONArray()
        todayUsage.entries
            .sortedByDescending { it.value }
            .take(20)
            .forEach { (pkg, ms) ->
                apps.put(JSONObject().apply {
                    put("package", pkg)
                    put("minutes", ms / 60000L)
                })
            }
        return JSONObject().apply {
            put("date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date()))
            put("apps", apps)
            put("total_minutes", todayUsage.values.sum() / 60000L)
            put("blocked_count", blockedApps.size)
        }
    }

    fun getBlockedApps() = blockedApps.toList()
    fun getTimeLimits() = timeLimits.toMap()
    fun getTodayUsageMinutes(pkg: String) = (todayUsage[pkg] ?: 0L) / 60000L
}
