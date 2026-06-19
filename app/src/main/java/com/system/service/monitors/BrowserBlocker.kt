package com.system.service.monitors

import android.content.Context
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject

object BrowserBlocker {

    private const val PREFS = "browser_blocker"
    private const val KEY   = "blocked_domains"

    val BROWSER_PKGS = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
        "com.opera.browser", "com.brave.browser", "com.microsoft.emmx",
        "com.UCMobile.intl", "com.uc.browser.en", "com.duckduckgo.mobile.android",
        "org.mozilla.firefox_beta", "com.opera.mini.native"
    )

    private val blockedDomains = mutableSetOf<String>()

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY, "[]") ?: "[]"
        blockedDomains.clear()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) blockedDomains.add(arr.getString(i).lowercase().trim())
        } catch (_: Exception) {}
    }

    fun setBlockedDomains(domains: List<String>, context: Context) {
        blockedDomains.clear()
        blockedDomains.addAll(domains.map { it.lowercase().trim() })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONArray(domains).toString()).apply()
    }

    fun getBlockedDomains(): List<String> = blockedDomains.toList()

    /**
     * Check URL text from browser address bar.
     * Returns matched domain if blocked, null if allowed.
     * Also sends alert to parent.
     */
    fun checkUrl(urlText: String, pkg: String, accessibilityInstance: android.accessibilityservice.AccessibilityService): String? {
        if (blockedDomains.isEmpty() || urlText.isBlank()) return null
        val lower = urlText.lowercase()
        val matched = blockedDomains.firstOrNull { lower.contains(it) } ?: return null

        // Block: go back/home
        accessibilityInstance.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

        CoreService.instance?.sendData("browser_blocked", JSONObject().apply {
            put("url",     urlText.take(200))
            put("domain",  matched)
            put("package", pkg)
            put("time",    System.currentTimeMillis())
        })
        return matched
    }

    fun isBrowserPkg(pkg: String) = BROWSER_PKGS.contains(pkg)
}
