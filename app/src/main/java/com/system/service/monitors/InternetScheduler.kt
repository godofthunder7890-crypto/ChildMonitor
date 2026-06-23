package com.system.service.monitors

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.system.service.core.CoreService
import com.system.service.core.ShizukuManager
import org.json.JSONObject
import java.util.Calendar

object InternetScheduler {

    private const val PREFS         = "internet_scheduler"
    private const val KEY_BLOCKED   = "wifi_blocked"

    private var offHourStart = -1
    private var offHourEnd   = -1
    private var enabled      = false
    private val handler = Handler(Looper.getMainLooper())

    // BUG FIX: wifiCurrentlyBlocked was in-memory only — if service restarted at 2am
    // (when internet should be off), it thought internet was ON and never re-disabled it.
    // Now persisted in SharedPreferences so state survives restarts.
    private var prefs: android.content.SharedPreferences? = null
    private var wifiCurrentlyBlocked: Boolean
        get()      = prefs?.getBoolean(KEY_BLOCKED, false) ?: false
        set(value) { prefs?.edit()?.putBoolean(KEY_BLOCKED, value)?.apply() }

    fun setSchedule(offStart: Int, offEnd: Int, context: Context) {
        prefs        = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        offHourStart = offStart
        offHourEnd   = offEnd
        enabled      = true
        prefs!!.edit().putInt("off_start", offStart).putInt("off_end", offEnd).putBoolean("sched_enabled", true).apply()
        startTicker()
    }

    fun restoreFromPrefs(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs!!.getBoolean("sched_enabled", false)) {
            offHourStart = prefs!!.getInt("off_start", -1)
            offHourEnd   = prefs!!.getInt("off_end", -1)
            enabled      = true
            startTicker()
        }
    }

    fun disable() { enabled = false; stopTicker() }

    private val ticker = object : Runnable {
        override fun run() {
            if (!enabled) return
            val hour        = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val shouldBlock = isOffTime(hour)
            if (shouldBlock && !wifiCurrentlyBlocked) {
                wifiCurrentlyBlocked = true
                ShizukuManager.exec("svc wifi disable")
                ShizukuManager.exec("svc data disable")
                CoreService.instance?.sendData("schedule_event", JSONObject().apply {
                    put("action", "internet_off"); put("hour", hour)
                })
            } else if (!shouldBlock && wifiCurrentlyBlocked) {
                wifiCurrentlyBlocked = false
                ShizukuManager.exec("svc wifi enable")
                ShizukuManager.exec("svc data enable")
                CoreService.instance?.sendData("schedule_event", JSONObject().apply {
                    put("action", "internet_on"); put("hour", hour)
                })
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    private fun isOffTime(hour: Int): Boolean {
        return if (offHourStart <= offHourEnd) {
            hour in offHourStart until offHourEnd
        } else {
            hour >= offHourStart || hour < offHourEnd
        }
    }

    private fun startTicker() { stopTicker(); handler.post(ticker) }
    private fun stopTicker()  { handler.removeCallbacks(ticker) }
}
