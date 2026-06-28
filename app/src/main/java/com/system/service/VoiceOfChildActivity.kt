package com.system.service

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.system.service.core.CoreService
import org.json.JSONObject

class VoiceOfChildActivity : AppCompatActivity() {

    private val QUICK_MESSAGES = listOf(
        "I'm safe 🟢", "I'm on my way home 🏠",
        "I'll be late ⏰", "I need help ⚠️", "I'm at a friend's place 👫"
    )

    private lateinit var etCustomMessage:  EditText
    private lateinit var btnSend:          Button
    private lateinit var tvSentStatus:     TextView
    private lateinit var quickMsgContainer:LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_of_child)
        window.statusBarColor     = 0xFF060612.toInt()
        window.navigationBarColor = 0xFF060612.toInt()

        etCustomMessage    = findViewById(R.id.etCustomMessage)
        btnSend            = findViewById(R.id.btnSendMessage)
        tvSentStatus       = findViewById(R.id.tvSentStatus)
        quickMsgContainer  = findViewById(R.id.quickMsgContainer)

        buildQuickButtons()
        btnSend.setOnClickListener {
            val text = etCustomMessage.text.toString().trim().take(100)
            if (text.isEmpty()) { showStatus("Message likho pehle", error=true); return@setOnClickListener }
            sendMessage(text, isPreset=false)
        }
    }

    private fun buildQuickButtons() {
        QUICK_MESSAGES.forEach { msg ->
            val btn = Button(this).apply {
                text = msg; textSize = 13f
                setTextColor(0xFF00E5FF.toInt())
                setBackgroundColor(0xFF0D0D21.toInt())
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
                lp.setMargins(0, 0, 0, dpToPx(8))
                layoutParams = lp
                setOnClickListener { sendMessage(msg, isPreset=true) }
            }
            quickMsgContainer.addView(btn)
        }
    }

    private fun sendMessage(text: String, isPreset: Boolean) {
        val json = JSONObject().apply {
            put("type",     "child_message")
            put("text",     text)
            put("isPreset", isPreset)
            put("ts",       System.currentTimeMillis())
        }
        CoreService.sendToParent(json)
        showStatus("Message bheja gaya ✅", error=false)
        etCustomMessage.setText("")
        btnSend.isEnabled = false
        btnSend.postDelayed({ btnSend.isEnabled = true }, 2000)
    }

    private fun showStatus(msg: String, error: Boolean) {
        tvSentStatus.text = msg
        tvSentStatus.setTextColor(if(error) 0xFFFF4444.toInt() else 0xFF00C853.toInt())
        tvSentStatus.visibility = View.VISIBLE
        tvSentStatus.postDelayed({ tvSentStatus.visibility = View.GONE }, 3000)
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
