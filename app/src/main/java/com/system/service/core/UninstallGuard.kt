package com.system.service.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

/**
 * FIX #27: Uninstall Password Protection.
 * When parent sets a password hash on child device, any uninstall attempt
 * via Settings is intercepted by AccessibilityMonitor → shows this dialog.
 * Wrong password → blocked + parent alerted.
 */
object UninstallGuard {

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun showPasswordDialog(
        context: Context,
        prefs: SharedPreferences,
        onCorrect: () -> Unit,
        onWrong: () -> Unit
    ) {
        val storedHash = prefs.getString("uninstall_pass_hash", "") ?: ""
        if (storedHash.isEmpty()) { onCorrect(); return }

        val input = EditText(context).apply {
            hint = "Enter parent password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("App Protected")
            .setMessage("Enter parent password to uninstall this app")
            .setView(input)
            .setPositiveButton("Verify") { _, _ ->
                if (sha256(input.text.toString()) == storedHash) {
                    onCorrect()
                } else {
                    Toast.makeText(context, "Wrong password! Contact parent.", Toast.LENGTH_LONG).show()
                    CoreService.instance?.sendData("uninstall_attempt", JSONObject().apply {
                        put("time", System.currentTimeMillis())
                        put("reason", "wrong_password")
                    })
                    onWrong()
                }
            }
            .setNegativeButton("Cancel") { _, _ -> onWrong() }
            .setCancelable(false)
            .create()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        try { dialog.show() } catch (_: Exception) { onWrong() }
    }
}
