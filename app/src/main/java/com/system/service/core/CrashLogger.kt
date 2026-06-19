package com.system.service.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash & Restart Logger — Professional level diagnostic system.
 *
 * Tracks:
 * - Unhandled exceptions (crash stack traces)
 * - Watchdog-triggered restarts (service was dead)
 * - Service stop events (onDestroy)
 *
 * All logs are written to internal storage so they survive process death.
 * On next CoreService start, logs are sent to parent via WebSocket.
 */
object CrashLogger {

    private const val CRASH_FILE   = "crash_log.json"
    private const val RESTART_FILE = "restart_log.json"
    private const val MAX_ENTRIES  = 50

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    // ── Install global uncaught exception handler ──────────────────────────────
    fun install(context: Context) {
        val appCtx = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(appCtx, throwable, thread.name)
            } catch (_: Exception) {}

            // Let Android's default handler run (shows crash dialog, creates tombstone)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // ── Log an unhandled crash ─────────────────────────────────────────────────
    fun logCrash(context: Context, throwable: Throwable, threadName: String = "unknown") {
        try {
            val sw = StringWriter(); throwable.printStackTrace(PrintWriter(sw))
            val entry = JSONObject().apply {
                put("time",       sdf.format(Date()))
                put("timestamp",  System.currentTimeMillis())
                put("type",       "CRASH")
                put("thread",     threadName)
                put("exception",  throwable.javaClass.name)
                put("message",    throwable.message ?: "no message")
                put("stacktrace", sw.toString().take(2000))
            }
            appendEntry(context, CRASH_FILE, entry)
        } catch (_: Exception) {}
    }

    // ── Log a watchdog restart (service was dead when watchdog fired) ──────────
    fun logWatchdogRestart(context: Context) {
        try {
            val entry = JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "WATCHDOG_RESTART")
                put("message",   "Service was dead — watchdog restarted it")
            }
            appendEntry(context, RESTART_FILE, entry)
        } catch (_: Exception) {}
    }

    // ── Log graceful service stop ──────────────────────────────────────────────
    fun logServiceStop(context: Context, reason: String) {
        try {
            val entry = JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "SERVICE_STOP")
                put("reason",    reason)
            }
            appendEntry(context, RESTART_FILE, entry)
        } catch (_: Exception) {}
    }

    // ── Log service start ──────────────────────────────────────────────────────
    fun logServiceStart(context: Context, reason: String = "normal") {
        try {
            val entry = JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "SERVICE_START")
                put("reason",    reason)
                put("android",   android.os.Build.VERSION.SDK_INT)
                put("device",    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            }
            appendEntry(context, RESTART_FILE, entry)
        } catch (_: Exception) {}
    }

    // ── Read all logs and format as JSON for sending to parent ─────────────────
    fun buildDiagnosticReport(context: Context): JSONObject {
        return JSONObject().apply {
            put("crashes",  readEntries(context, CRASH_FILE))
            put("restarts", readEntries(context, RESTART_FILE))
            put("device",   "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            put("android",  android.os.Build.VERSION.SDK_INT)
            put("brand",    android.os.Build.BRAND)
            put("report_time", sdf.format(Date()))
        }
    }

    // ── Clear all logs ─────────────────────────────────────────────────────────
    fun clearAll(context: Context) {
        try { getFile(context, CRASH_FILE).delete() } catch (_: Exception) {}
        try { getFile(context, RESTART_FILE).delete() } catch (_: Exception) {}
    }

    // ── Has unsent crash data ──────────────────────────────────────────────────
    fun hasCrashData(context: Context): Boolean {
        return try { getFile(context, CRASH_FILE).exists() } catch (_: Exception) { false }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────
    private fun appendEntry(context: Context, filename: String, entry: JSONObject) {
        val file = getFile(context, filename)
        val arr = readEntries(context, filename)
        arr.put(entry)
        // Keep only latest MAX_ENTRIES to avoid unbounded growth
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_ENTRIES)
        for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
        file.writeText(trimmed.toString())
    }

    private fun readEntries(context: Context, filename: String): JSONArray {
        return try {
            val file = getFile(context, filename)
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    private fun getFile(context: Context, filename: String): File {
        return File(context.filesDir, filename)
    }
}
