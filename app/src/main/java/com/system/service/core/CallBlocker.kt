package com.system.service.core

import android.telecom.Call
import android.telecom.CallScreeningService
import org.json.JSONObject

/**
 * FIX #24: Real call blocking via CallScreeningService (Android 10+).
 * Previously block_contact only saved to SharedPrefs but never actually blocked calls.
 * Supports both blacklist (block specific numbers) and unknown number blocking.
 * Supports whitelist mode (only allow specific contacts, block all others).
 *
 * Register in AndroidManifest.xml with BIND_SCREENING_SERVICE permission.
 */
class CallBlocker : CallScreeningService() {

    override fun onScreenCall(details: Call.Details) {
        val num = details.handle?.schemeSpecificPart ?: ""

        val blockedPrefs  = applicationContext.getSharedPreferences("blocked_contacts", MODE_PRIVATE)
        val callPrefs     = applicationContext.getSharedPreferences("call_safety",       MODE_PRIVATE)

        val blacklist      = blockedPrefs.getStringSet("numbers", emptySet()) ?: emptySet()
        val whitelist      = callPrefs.getStringSet("whitelist", emptySet()) ?: emptySet()
        val whitelistMode  = callPrefs.getBoolean("whitelist_mode", false)
        val blockUnknown   = callPrefs.getBoolean("block_unknown", false)

        val shouldBlock = when {
            // Whitelist mode: only allow numbers in whitelist
            whitelistMode && whitelist.isNotEmpty() -> !whitelist.any { num.contains(it) }
            // Blacklist: block specific numbers
            blacklist.any { num.contains(it) } -> true
            // Block unknown callers
            blockUnknown && (num.isBlank() || num == "unknown") -> true
            else -> false
        }

        if (shouldBlock) {
            CoreService.instance?.sendData("call_blocked", JSONObject().apply {
                put("number", num)
                put("time", System.currentTimeMillis())
            })
        }

        respondToCall(
            details,
            CallResponse.Builder()
                .setDisallowCall(shouldBlock)
                .setRejectCall(shouldBlock)
                .build()
        )
    }
}
