package com.system.service.setup

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import com.system.service.monitors.TimeRequestManager

/**
 * ChildRequestActivity — hidden activity child can open to request more screen time.
 *
 * Accessible via:
 *  - HiddenSettingsActivity "Request Time" button
 *  - Shake gesture (if configured)
 *  - Notification action (if pending block notification is shown)
 *
 * Disguised as a simple "Feedback" form to avoid suspicion.
 */
class ChildRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0A1628.toInt())
            setPadding(32, 64, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Request Screen Time"
            setTextColor(0xFFF1F5F9.toInt()); textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Ask your parent for extra time. They'll get notified on their phone."
            setTextColor(0xFF64748B.toInt()); textSize = 13f
            gravity = Gravity.CENTER; setPadding(0, 0, 0, 24)
        })

        root.addView(TextView(this).apply {
            text = "How many minutes do you need?"; setTextColor(0xFF94A3B8.toInt()); textSize = 13f; setPadding(0, 0, 0, 8)
        })

        // Quick presets
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val minsOptions = listOf(15, 30, 45, 60)
        var selectedMins = 30
        val presetBtns = mutableListOf<Button>()
        minsOptions.forEach { mins ->
            val btn = Button(this).apply {
                text = "${mins}m"
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (mins == selectedMins) 0xFF1565C0.toInt() else 0xFF1E293B.toInt()
                )
                setTextColor(0xFFFFFFFF.toInt()); textSize = 13f
            }
            btn.setOnClickListener {
                selectedMins = mins
                presetBtns.forEachIndexed { i, b ->
                    b.backgroundTintList = android.content.res.ColorStateList.valueOf(
                        if (minsOptions[i] == selectedMins) 0xFF1565C0.toInt() else 0xFF1E293B.toInt()
                    )
                }
            }
            presetBtns.add(btn)
            presetRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(4, 0, 4, 0)
            })
        }
        root.addView(presetRow)

        root.addView(TextView(this).apply {
            text = "Reason (optional)"; setTextColor(0xFF94A3B8.toInt()); textSize = 13f
            setPadding(0, 16, 0, 4)
        })
        val etReason = EditText(this).apply {
            hint = "e.g. Homework is done, just 30 more minutes"
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF475569.toInt())
            setBackgroundColor(0xFF1E293B.toInt()); setPadding(16, 12, 16, 12); textSize = 13f
            minLines = 2
        }
        root.addView(etReason)

        val btnSend = Button(this).apply {
            text = "📤 Send Request to Parent"
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 0) }
        }
        btnSend.setOnClickListener {
            val reason = etReason.text?.toString()?.trim() ?: ""
            TimeRequestManager.sendTimeRequest(this, selectedMins, reason)
            Toast.makeText(this, "⏱ Request sent! Waiting for parent...", Toast.LENGTH_LONG).show()
            finish()
        }
        root.addView(btnSend)

        val btnCancel = Button(this).apply {
            text = "Cancel"
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF334155.toInt())
            setTextColor(0xFFCBD5E1.toInt()); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 8, 0, 0) }
        }
        btnCancel.setOnClickListener { finish() }
        root.addView(btnCancel)

        setContentView(root)
    }
}
