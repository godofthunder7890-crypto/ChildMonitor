package com.system.service.monitors

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

object LocationHistoryManager {

    private const val PREFS  = "location_history"
    private const val KEY    = "history"
    private const val MAX_POINTS = 200

    private val history = CopyOnWriteArrayList<LocationPoint>()

    data class LocationPoint(
        val lat: Double,
        val lng: Double,
        val accuracy: Float,
        val ts: Long,
        val address: String = ""
    )

    fun init(context: Context) {
        history.clear()
        try {
            val p   = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(p.getString(KEY, "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(LocationPoint(
                    lat      = o.optDouble("lat"),
                    lng      = o.optDouble("lng"),
                    accuracy = o.optDouble("accuracy", 0.0).toFloat(),
                    ts       = o.optLong("ts"),
                    address  = o.optString("address", "")
                ))
            }
        } catch (_: Exception) {}
    }

    fun recordLocation(lat: Double, lng: Double, accuracy: Float, context: Context) {
        if (lat == 0.0 && lng == 0.0) return
        val point = LocationPoint(lat, lng, accuracy, System.currentTimeMillis())
        history.add(point)
        if (history.size > MAX_POINTS) {
            repeat(history.size - MAX_POINTS) { history.removeAt(0) }
        }
        persist(context)
    }

    fun getHistoryJson(limitHours: Int = 24): JSONArray {
        val cutoff = System.currentTimeMillis() - (limitHours * 3_600_000L)
        val arr    = JSONArray()
        val fmt    = SimpleDateFormat("HH:mm", Locale.US)
        history.filter { it.ts >= cutoff }.forEach { p ->
            arr.put(JSONObject().apply {
                put("lat",      p.lat)
                put("lng",      p.lng)
                put("accuracy", p.accuracy)
                put("ts",       p.ts)
                put("time",     fmt.format(Date(p.ts)))
            })
        }
        return arr
    }

    fun getStayLocations(minStayMinutes: Int = 5): JSONArray {
        val result  = JSONArray()
        if (history.size < 2) return result
        val sorted  = history.sortedBy { it.ts }
        val STAY_RADIUS_M = 100.0
        var stayStart = sorted[0]
        var stayCount = 1

        for (i in 1 until sorted.size) {
            val cur  = sorted[i]
            val prev = sorted[i - 1]
            val dist = distance(prev.lat, prev.lng, cur.lat, cur.lng)
            if (dist <= STAY_RADIUS_M) {
                stayCount++
            } else {
                val durationMs = prev.ts - stayStart.ts
                if (durationMs >= minStayMinutes * 60_000L) {
                    result.put(buildStayJson(stayStart, prev, durationMs))
                }
                stayStart = cur; stayCount = 1
            }
        }
        val last = sorted.last()
        val dur  = last.ts - stayStart.ts
        if (dur >= minStayMinutes * 60_000L) {
            result.put(buildStayJson(stayStart, last, dur))
        }
        return result
    }

    private fun buildStayJson(from: LocationPoint, to: LocationPoint, durMs: Long): JSONObject {
        val fmt    = SimpleDateFormat("HH:mm", Locale.US)
        val durMin = (durMs / 60_000L).toInt()
        return JSONObject().apply {
            put("lat",        from.lat)
            put("lng",        from.lng)
            put("start_ts",   from.ts)
            put("end_ts",     to.ts)
            put("start_time", fmt.format(Date(from.ts)))
            put("end_time",   fmt.format(Date(to.ts)))
            put("duration_minutes", durMin)
            put("label",      if (durMin >= 60) "${durMin / 60}h ${durMin % 60}min" else "${durMin}min")
        }
    }

    private fun distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val R     = 6371000.0
        val dLat  = Math.toRadians(lat2 - lat1)
        val dLng  = Math.toRadians(lng2 - lng1)
        val a     = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLng / 2).let { it * it }
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    private fun persist(context: Context) {
        try {
            val arr = JSONArray()
            history.takeLast(MAX_POINTS).forEach { p ->
                arr.put(JSONObject().apply {
                    put("lat",      p.lat); put("lng",  p.lng)
                    put("accuracy", p.accuracy); put("ts", p.ts)
                })
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        history.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY).apply()
    }
}
