package com.system.service.monitors

// ══════════════════════════════════════════════════════════════════════════════
//  FCM STUB — Firebase Cloud Messaging
//
//  STATUS: NOT ACTIVE — stub only. Activate when Firebase project is created.
//
//  SETUP STEPS:
//  ─────────────────────────────────────────────────────────────────────────
//  1. Go to https://console.firebase.google.com → Create project "SafeWatch"
//  2. Add Android app → package: com.system.service → download google-services.json
//  3. Place google-services.json in:  app/google-services.json
//  4. In app/build.gradle add:
//       apply plugin: 'com.google.gms.google-services'
//     And in dependencies:
//       implementation 'com.google.firebase:firebase-messaging-ktx:23.4.0'
//  5. In root build.gradle add to classpath:
//       classpath 'com.google.gms:google-services:4.4.0'
//  6. Uncomment the AndroidManifest.xml entry for FcmService (search "FCM_STUB")
//  7. UNCOMMENT THE CODE BELOW (delete the block comment markers)
//  ─────────────────────────────────────────────────────────────────────────
//
//  HOW IT WORKS (after setup):
//  ─────────────────────────────────────────────────────────────────────────
//  • Parent app sends FCM push → child device wakes up instantly
//  • Even if WebSocket is disconnected, FCM ensures delivery
//  • Commands: start_service, take_photo, get_location, emergency_lock
//  ══════════════════════════════════════════════════════════════════════════════

/*
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.system.service.core.CoreService
import org.json.JSONObject

class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Send new FCM token to parent via WebSocket so they can target this device
        CoreService.instance?.sendData("fcm_token_updated", JSONObject().apply {
            put("token", token)
        })
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data

        // Make sure CoreService is running
        val svcIntent = Intent(this, CoreService::class.java)
        startForegroundService(svcIntent)

        // Convert FCM data payload to a command JSON and handle it
        val command = data["command"] ?: return
        val json = JSONObject().apply {
            put("command", command)
            data.forEach { (k, v) ->
                if (k != "command") {
                    // Attempt int/double parsing for numeric values
                    try { put(k, v.toInt()) } catch (_: Exception) {
                        try { put(k, v.toDouble()) } catch (_: Exception) {
                            put(k, v)
                        }
                    }
                }
            }
        }

        // Route to CoreService command handler
        // Small delay to ensure service is started
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            CoreService.instance?.let { svc ->
                // Directly trigger common critical commands from FCM
                when (command) {
                    "wake_up" -> {
                        svc.sendData("fcm_wake_ack", JSONObject().apply {
                            put("message_id", message.messageId ?: "")
                        })
                    }
                    "emergency_lock" -> {
                        // Block all apps immediately
                        svc.sendData("emergency_locked", JSONObject().apply {
                            put("source", "fcm")
                        })
                    }
                    else -> {
                        // Forward to normal command router
                        svc.sendData("fcm_command_received", JSONObject().apply {
                            put("command", command)
                            put("source", "fcm")
                        })
                    }
                }
            }
        }, 1500)
    }
}
*/

// Placeholder class so the package compiles even before Firebase is set up
class FcmService {
    companion object {
        const val STATUS = "STUB_NOT_ACTIVE"
        const val SETUP_REQUIRED = true
    }
}
