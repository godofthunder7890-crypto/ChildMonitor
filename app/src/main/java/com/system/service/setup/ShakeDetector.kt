package com.system.service.setup

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.system.service.core.CoreService
import org.json.JSONObject
import kotlin.math.sqrt

class ShakeDetector(private val context: Context) : SensorEventListener {

    private val sensorMgr = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel     = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val SETTINGS_THRESHOLD = 14f
    private val SETTINGS_SHAKES    = 5
    private val SOS_THRESHOLD      = 20f
    private val SOS_SHAKES         = 3
    private val SOS_WINDOW_MS      = 2000L

    private var settingsCount       = 0
    private var settingsLastShake   = 0L
    private var settingsWindowStart = 0L

    private var sosCount       = 0
    private var sosWindowStart = 0L
    private var sosTriggered   = false

    fun start() = sensorMgr.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME)
    fun stop()  = sensorMgr.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val g = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()

        // ── SOS detection (hard + fast) ──────────────────────────────────────
        if (g > SOS_THRESHOLD) {
            if (now - sosWindowStart > SOS_WINDOW_MS) { sosCount = 0; sosWindowStart = now }
            sosCount++
            if (sosCount >= SOS_SHAKES && !sosTriggered) {
                sosTriggered = true

                // BUG FIX: SOS threshold (20g) > settings threshold (14g), so an SOS shake
                // was ALSO counted towards the hidden-settings counter. After 3 hard SOS shakes
                // the settings counter would reach 5 and open HiddenSettingsActivity during an
                // emergency. Reset settings counter here so only deliberate 5-shake gesture works.
                settingsCount = 0; settingsWindowStart = 0; settingsLastShake = 0

                triggerSOS()
                android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed({ sosTriggered = false }, 10_000)
            }
            return   // SOS shake — don't also process as settings shake
        }

        // ── Hidden settings (5 deliberate normal shakes, not SOS-level) ──────
        if (g > SETTINGS_THRESHOLD) {
            if (now - settingsWindowStart > 4000L) { settingsCount = 0; settingsWindowStart = now }
            if (now - settingsLastShake < 300) return
            settingsLastShake = now
            settingsCount++
            if (settingsCount >= SETTINGS_SHAKES) {
                settingsCount = 0; settingsWindowStart = 0
                openHiddenSettings()
            }
        }
    }

    private fun triggerSOS() {
        CoreService.instance?.sendData("sos_alert", JSONObject().apply {
            put("triggered", true)
            put("time", System.currentTimeMillis())
        })
        try {
            val fusedClient = com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(context)
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    CoreService.instance?.sendData("sos_location", JSONObject().apply {
                        put("lat", it.latitude); put("lng", it.longitude)
                        put("accuracy", it.accuracy)
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private fun openHiddenSettings() {
        context.startActivity(Intent(context, HiddenSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        })
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
