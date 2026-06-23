package com.system.service.monitors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.system.service.core.CoreService
import com.system.service.core.ShizukuManager
import org.json.JSONObject

/**
 * Bug #10 fix: block_contact was only saving to SharedPrefs.
 * This receiver intercepts PHONE_STATE broadcasts and uses Shizuku
 * to actually terminate calls from blocked numbers.
 *
 * Flow: RINGING detected → check blocked_contacts SharedPrefs
 *       → if match: ShizukuManager.exec("telecom end-call")
 *       → send call_blocked event to parent
 *
 * Fallback (no Shizuku): rejects via CALL_PHONE + reflection (Android 9 only)
 */
class CallBlockerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING) return

        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            ?: return

        val prefs   = context.getSharedPreferences("blocked_contacts", Context.MODE_PRIVATE)
        val blocked = prefs.getStringSet("numbers", emptySet()) ?: emptySet()

        val isBlocked = blocked.any { stored ->
            stored == incomingNumber ||
            incomingNumber.endsWith(stored.takeLast(10)) ||
            stored.endsWith(incomingNumber.takeLast(10))
        }

        if (!isBlocked) return

        // Primary: use Shizuku (works Android 10+, requires Shizuku service running)
        val shizukuOk = ShizukuManager.exec("telecom end-call")

        // Fallback: reflection-based endCall (works Android 5-9, deprecated API 28)
        if (!shizukuOk) {
            try {
                val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                val method = tm.javaClass.getDeclaredMethod("endCall")
                method.isAccessible = true
                method.invoke(tm)
            } catch (_: Exception) {}
        }

        // Notify parent that a call was blocked
        CoreService.instance?.sendData("call_blocked", JSONObject().apply {
            put("number",  incomingNumber)
            put("time",    System.currentTimeMillis())
            put("via",     if (shizukuOk) "shizuku" else "reflection")
        })
    }
}
