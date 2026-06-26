package com.system.service.monitors

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object AppBlockerManager {

    private const val PREFS      = "app_blocker"
    private const val KEY_BLOCKED = "blocked_apps"
    private const val KEY_LIMITS  = "screen_time_limits"
    private const val KEY_USAGE   = "today_usage"
    private const val KEY_DATE    = "usage_date"
    private const val KEY_LAST_PKG   = "last_pkg"
    private const val KEY_LAST_START = "last_start"

    // BUG FIX: Use thread-safe collections — AccessibilityService thread + CoreService thread
    // both read/write these simultaneously. ConcurrentModificationException was possible.
    private val blockedApps  = CopyOnWriteArraySet<String>()
    private val timeLimits   = ConcurrentHashMap<String, Int>()
    private val todayUsage   = ConcurrentHashMap<String, Long>()

    // Feature F3: Game Time Tokens — temporarily override block/limit for a package
    private val tokenExpiry  = ConcurrentHashMap<String, Long>()

    fun grantToken(pkg: String, minutes: Int) {
        tokenExpiry[pkg] = System.currentTimeMillis() + (minutes * 60_000L)
    }

    private fun isTokenActive(pkg: String): Boolean {
        val exp = tokenExpiry[pkg] ?: return false
        return if (System.currentTimeMillis() < exp) true
        else { tokenExpiry.remove(pkg); false }
    }

    private var lastUsagePkg   = ""
    private var lastUsageStart = 0L

    // BUG FIX: Prevent double-init from CoreService.onCreate() + AccessibilityMonitor.onServiceConnected()
    @Volatile private var initialized = false
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (initialized) return          // <-- prevents race + double reset
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            resetDayIfNeeded()
            loadFromPrefs()
            initialized = true
        }
    }

    // BUG FIX: Daily reset ONLY at init meant that running past midnight = stale data.
    // Now called on every trackAppStart() to catch midnight rollover.
    private fun resetDayIfNeeded() {
        val p = prefs ?: return
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.getDefault())
            .format(java.util.Date())
        if (p.getString(KEY_DATE, "") != today) {
            p.edit().putString(KEY_DATE, today).remove(KEY_USAGE)
                .remove(KEY_LAST_PKG).remove(KEY_LAST_START).apply()
            todayUsage.clear()
            lastUsagePkg   = ""
            lastUsageStart = 0L
        }
    }

    private fun loadFromPrefs() {
        val p = prefs ?: return

        blockedApps.clear()
        try {
            val arr = JSONArray(p.getString(KEY_BLOCKED, "[]") ?: "[]")
            for (i in 0 until arr.length()) blockedApps.add(arr.getString(i))
        } catch (_: Exception) {}

        timeLimits.clear()
        try {
            val obj = JSONObject(p.getString(KEY_LIMITS, "{}") ?: "{}")
            for (key in obj.keys()) timeLimits[key] = obj.getInt(key)
        } catch (_: Exception) {}

        todayUsage.clear()
        try {
            val obj = JSONObject(p.getString(KEY_USAGE, "{}") ?: "{}")
            for (key in obj.keys()) todayUsage[key] = obj.getLong(key)
        } catch (_: Exception) {}

        // BUG FIX: Restore in-flight session so service restart doesn't lose screen time
        lastUsagePkg   = p.getString(KEY_LAST_PKG, "") ?: ""
        lastUsageStart = p.getLong(KEY_LAST_START, 0L)
    }

    // NF #2: Add a single app to blocked list without replacing the full list
    fun addBlockedApp(pkg: String) {
        blockedApps.add(pkg)
    }

    fun setBlockedApps(pkgs: List<String>) {
        blockedApps.clear(); blockedApps.addAll(pkgs)
        prefs?.edit()?.putString(KEY_BLOCKED, JSONArray(pkgs).toString())?.apply()
    }

    fun setTimeLimit(pkg: String, limitMinutes: Int) {
        if (limitMinutes <= 0) timeLimits.remove(pkg) else timeLimits[pkg] = limitMinutes
        // BUG FIX: JSONObject(Map<*,*>) cast was unsafe — explicit toMap() is type-safe
        prefs?.edit()?.putString(KEY_LIMITS,
            JSONObject(timeLimits.toMap<String, Any>()).toString())?.apply()
    }

    fun isBlocked(pkg: String): Boolean {
        if (isTokenActive(pkg)) return false  // Feature F3: token overrides block
        return blockedApps.contains(pkg)
    }

    fun isTimeLimitExceeded(pkg: String): Boolean {
        if (isTokenActive(pkg)) return false  // Feature F3: token overrides time limit
        val limit = timeLimits[pkg] ?: return false
        val usedMs = todayUsage[pkg] ?: 0L
        return (usedMs / 60_000L) >= limit
    }

    fun trackAppStart(pkg: String) {
        // BUG FIX: Check for day rollover on every app switch, not just at service start
        resetDayIfNeeded()
        if (lastUsagePkg.isNotEmpty()) trackAppEnd()
        lastUsagePkg   = pkg
        lastUsageStart = System.currentTimeMillis()
        // Persist in-flight session so restart doesn't lose it
        prefs?.edit()?.putString(KEY_LAST_PKG, pkg)?.putLong(KEY_LAST_START, lastUsageStart)?.apply()
    }

    fun trackAppEnd() {
        if (lastUsagePkg.isEmpty() || lastUsageStart == 0L) return
        val elapsed = System.currentTimeMillis() - lastUsageStart
        val prev    = todayUsage[lastUsagePkg] ?: 0L
        todayUsage[lastUsagePkg] = prev + elapsed
        lastUsagePkg  = ""; lastUsageStart = 0L
        prefs?.edit()
            ?.putString(KEY_USAGE, JSONObject(todayUsage.toMap<String, Any>()).toString())
            ?.remove(KEY_LAST_PKG)?.remove(KEY_LAST_START)?.apply()
    }

    fun getDailyReport(): JSONObject {
        trackAppEnd()
        val apps = JSONArray()
        todayUsage.entries.sortedByDescending { it.value }.take(20).forEach { (pkg, ms) ->
            apps.put(JSONObject().apply { put("package", pkg); put("minutes", ms / 60_000L) })
        }
        return JSONObject().apply {
            put("date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date()))
            put("apps", apps)
            put("total_minutes", todayUsage.values.sum() / 60_000L)
            put("blocked_count", blockedApps.size)
        }
    }

    fun getBlockedApps()          = blockedApps.toList()
    fun getTimeLimits()            = timeLimits.toMap()
    fun getTodayUsageMinutes(pkg: String) = (todayUsage[pkg] ?: 0L) / 60_000L
}
