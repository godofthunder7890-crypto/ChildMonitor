package com.system.service.setup

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer  = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val SHAKE_THRESHOLD  = 14f   // g-force threshold
    private val SHAKE_COUNT_RESET_MS = 3000L  // reset shake count after 3 sec
    private val SHAKES_NEEDED    = 5     // 5 shakes to open

    private var shakeCount       = 0
    private var lastShakeTime    = 0L
    private var lastCountResetMs = 0L

    fun start() {
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        val gForce = sqrt((x * x + y * y + z * z).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH

        if (gForce > SHAKE_THRESHOLD) {
            val now = System.currentTimeMillis()

            // Reset count if too much time has passed since last shake
            if (now - lastCountResetMs > SHAKE_COUNT_RESET_MS) {
                shakeCount = 0
                lastCountResetMs = now
            }

            // Debounce — ignore shakes within 300ms of each other
            if (now - lastShakeTime < 300) return
            lastShakeTime = now

            shakeCount++

            if (shakeCount >= SHAKES_NEEDED) {
                shakeCount = 0
                lastCountResetMs = 0
                openHiddenSettings()
            }
        }
    }

    private fun openHiddenSettings() {
        val intent = Intent(context, HiddenSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
