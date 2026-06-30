package com.system.service.core

object ThemeColors {
    const val BG          = 0xFF080B14.toInt()
    const val SURFACE     = 0xFF0D1117.toInt()
    const val SURFACE2    = 0xFF111827.toInt()
    const val PRIMARY     = 0xFF10FF80.toInt()
    const val PRIMARY_DIM = 0x3310FF80
    const val SECONDARY   = 0xFF38BDF8.toInt()
    const val SUCCESS     = 0xFF10FF80.toInt()
    const val WARNING     = 0xFFFDE68A.toInt()
    const val DANGER      = 0xFFF87171.toInt()
    const val TEXT_PRI    = 0xFFF1F5F9.toInt()
    const val TEXT_SEC    = 0xFF94A3B8.toInt()

    fun statusColor(online: Boolean) = if (online) SUCCESS else DANGER

    fun moodColor(mood: String) = when (mood.lowercase()) {
        "happy"    -> 0xFFFDE68A.toInt()
        "anxious"  -> 0xFFFB923C.toInt()
        "sad"      -> 0xFFA78BFA.toInt()
        "stressed" -> 0xFFF87171.toInt()
        else       -> 0xFF38BDF8.toInt()
    }

    fun moodEmoji(mood: String) = when (mood.lowercase()) {
        "happy"    -> "😊"
        "anxious"  -> "😟"
        "sad"      -> "😢"
        "stressed" -> "😰"
        else       -> "😐"
    }
}
