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

object CrashLogger {

    private const val CRASH_FILE   = "crash_log.json"
    private const val RESTART_FILE = "restart_log.json"
    private const val MAX_ENTRIES  = 50

    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun install(context: Context) {
        val appCtx = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { logCrash(appCtx, throwable, thread.name) } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun logCrash(context: Context, throwable: Throwable, threadName: String = "unknown") {
        try {
            val sw = StringWriter(); throwable.printStackTrace(PrintWriter(sw))
            appendEntry(context, CRASH_FILE, JSONObject().apply {
                put("time",       sdf.format(Date()))
                put("timestamp",  System.currentTimeMillis())
                put("type",       "CRASH")
                put("thread",     threadName)
                put("exception",  throwable.javaClass.name)
                put("message",    throwable.message ?: "no message")
                put("stacktrace", sw.toString().take(2000))
            })
        } catch (_: Exception) {}
    }

    fun logWatchdogRestart(context: Context) {
        try {
            appendEntry(context, RESTART_FILE, JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "WATCHDOG_RESTART")
                put("message",   "Service was dead — watchdog restarted it")
            })
        } catch (_: Exception) {}
    }

    fun logServiceStop(context: Context, reason: String) {
        try {
            appendEntry(context, RESTART_FILE, JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "SERVICE_STOP")
                put("reason",    reason)
                put("message",   reason)
            })
        } catch (_: Exception) {}
    }

    fun logServiceStart(context: Context, reason: String = "normal") {
        try {
            appendEntry(context, RESTART_FILE, JSONObject().apply {
                put("time",      sdf.format(Date()))
                put("timestamp", System.currentTimeMillis())
                put("type",      "SERVICE_START")
                put("reason",    reason)
                put("android",   android.os.Build.VERSION.SDK_INT)
                put("device",    "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            })
        } catch (_: Exception) {}
    }

    // ── Accessors for HealthReporter ───────────────────────────────────────────

    fun getCrashCount(context: Context): Int =
        try { readEntries(context, CRASH_FILE).length() } catch (_: Exception) { 0 }

    fun getRestartCount(context: Context): Int {
        return try {
            val arr = readEntries(context, RESTART_FILE)
            (0 until arr.length()).count {
                arr.getJSONObject(it).optString("type") == "WATCHDOG_RESTART"
            }
        } catch (_: Exception) { 0 }
    }

    fun getLastCrash(context: Context): JSONObject? {
        return try {
            val arr = readEntries(context, CRASH_FILE)
            if (arr.length() > 0) arr.getJSONObject(arr.length() - 1) else null
        } catch (_: Exception) { null }
    }

    fun getLastRestart(context: Context): JSONObject? {
        return try {
            val arr = readEntries(context, RESTART_FILE)
            // Find last WATCHDOG_RESTART entry
            var last: JSONObject? = null
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                if (e.optString("type") == "WATCHDOG_RESTART") last = e
            }
            last
        } catch (_: Exception) { null }
    }

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

    fun clearAll(context: Context) {
        try { getFile(context, CRASH_FILE).delete() } catch (_: Exception) {}
        try { getFile(context, RESTART_FILE).delete() } catch (_: Exception) {}
    }

    fun hasCrashData(context: Context): Boolean =
        try { getFile(context, CRASH_FILE).exists() } catch (_: Exception) { false }

    private fun appendEntry(context: Context, filename: String, entry: JSONObject) {
        val arr = readEntries(context, filename)
        arr.put(entry)
        val trimmed = JSONArray()
        val start = maxOf(0, arr.length() - MAX_ENTRIES)
        for (i in start until arr.length()) trimmed.put(arr.getJSONObject(i))
        getFile(context, filename).writeText(trimmed.toString())
    }

    private fun readEntries(context: Context, filename: String): JSONArray {
        return try {
            val file = getFile(context, filename)
            if (file.exists()) JSONArray(file.readText()) else JSONArray()
        } catch (_: Exception) { JSONArray() }
    }

    private fun getFile(context: Context, filename: String): File =
        File(context.filesDir, filename)
}
