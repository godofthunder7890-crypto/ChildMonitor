package com.system.service.monitors

import android.content.Context
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject

object BrowserBlocker {

    private const val PREFS        = "browser_blocker"
    private const val KEY_BLOCKED  = "blocked_domains"
    private const val KEY_ALLOWED  = "allowed_domains"
    private const val KEY_WL_MODE  = "whitelist_mode"

    val BROWSER_PKGS = setOf(
        "com.android.chrome", "org.mozilla.firefox", "com.sec.android.app.sbrowser",
        "com.opera.browser", "com.brave.browser", "com.microsoft.emmx",
        "com.UCMobile.intl", "com.uc.browser.en", "com.duckduckgo.mobile.android",
        "org.mozilla.firefox_beta", "com.opera.mini.native"
    )

    private val blockedDomains  = mutableSetOf<String>()
    private val allowedDomains  = mutableSetOf<String>()
    @Volatile private var whitelistMode = false

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // Blocked domains
        blockedDomains.clear()
        try {
            val arr = JSONArray(prefs.getString(KEY_BLOCKED, "[]") ?: "[]")
            for (i in 0 until arr.length()) blockedDomains.add(arr.getString(i).lowercase().trim())
        } catch (_: Exception) {}

        // Allowed domains (whitelist)
        allowedDomains.clear()
        try {
            val arr = JSONArray(prefs.getString(KEY_ALLOWED, "[]") ?: "[]")
            for (i in 0 until arr.length()) allowedDomains.add(arr.getString(i).lowercase().trim())
        } catch (_: Exception) {}

        whitelistMode = prefs.getBoolean(KEY_WL_MODE, false)
    }

    fun setBlockedDomains(domains: List<String>, context: Context) {
        blockedDomains.clear()
        blockedDomains.addAll(domains.map { it.lowercase().trim() })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_BLOCKED, JSONArray(domains).toString()).apply()
    }

    fun getBlockedDomains(): List<String> = blockedDomains.toList()

    fun setAllowedDomains(domains: List<String>, context: Context) {
        allowedDomains.clear()
        allowedDomains.addAll(domains.map { it.lowercase().trim() })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALLOWED, JSONArray(domains).toString()).apply()
    }

    fun getAllowedDomains(): List<String> = allowedDomains.toList()

    fun setWhitelistMode(enabled: Boolean, context: Context) {
        whitelistMode = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_WL_MODE, enabled).apply()
    }

    fun isWhitelistMode(): Boolean = whitelistMode

    /**
     * Check URL text from browser address bar.
     *
     * In BLACKLIST mode (default): block if URL matches any blockedDomains entry.
     * In WHITELIST mode: block if URL does NOT match any allowedDomains entry.
     *
     * Returns matched/blocking domain if blocked, null if allowed.
     * Also sends alert to parent.
     */
    fun checkUrl(
        urlText: String,
        pkg: String,
        accessibilityInstance: android.accessibilityservice.AccessibilityService
    ): String? {
        if (urlText.isBlank()) return null
        val lower = urlText.lowercase()

        val shouldBlock: Boolean
        val blockReason: String

        if (whitelistMode) {
            // Whitelist mode: block unless URL is in the allowed list
            if (allowedDomains.isEmpty()) return null  // no whitelist configured — allow all
            val isAllowed = allowedDomains.any { lower.contains(it) }
            shouldBlock = !isAllowed
            blockReason = if (shouldBlock) "not_in_whitelist" else ""
        } else {
            // Blacklist mode: block if URL matches a blocked domain
            if (blockedDomains.isEmpty()) return null
            val matched = blockedDomains.firstOrNull { lower.contains(it) }
            shouldBlock = matched != null
            blockReason = matched ?: ""
        }

        if (!shouldBlock) return null

        // Block: go home
        accessibilityInstance.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)

        CoreService.instance?.sendData("browser_blocked", JSONObject().apply {
            put("url",     urlText.take(200))
            put("domain",  blockReason)
            put("package", pkg)
            put("time",    System.currentTimeMillis())
        })
        return blockReason
    }

    fun isBrowserPkg(pkg: String) = BROWSER_PKGS.contains(pkg)
}
