package de.uwumail.ui.mail.gravity

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Whether the phone is being held upside down.
 *
 * Only listens while [active], since nothing else needs the sensor and a
 * registered listener keeps a bit of hardware awake.
 */
@Composable
fun rememberUpsideDown(active: Boolean): State<Boolean> {
    val context = LocalContext.current
    val upsideDown = remember { mutableStateOf(false) }

    DisposableEffect(active) {
        if (!active) {
            upsideDown.value = false
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // In the device's own frame, gravity runs down the +y axis when
                // the phone is upright, so upside down is a strong negative y.
                // A small z rules out the phone simply lying on a table.
                val y = event.values.getOrElse(1) { 0f }
                val z = event.values.getOrElse(2) { 0f }
                upsideDown.value = y < -UPRIGHT_THRESHOLD && abs(z) < FLAT_THRESHOLD
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            runCatching { manager?.unregisterListener(listener) }
            upsideDown.value = false
        }
    }
    return upsideDown
}

/** Two thirds of g along the axis, so a tilted phone still counts. */
private const val UPRIGHT_THRESHOLD = 6.5f
private const val FLAT_THRESHOLD = 6f
