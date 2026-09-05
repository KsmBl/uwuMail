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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import kotlin.math.hypot

/** Which way is down, and whether the phone is being held upside down. */
data class DeviceOrientation(
    /** Unit vector pointing the way things fall, in screen coordinates. */
    val down: Offset = Offset(0f, 1f),
    val upsideDown: Boolean = false
)

/**
 * Reads the phone's attitude from the gravity sensor.
 *
 * Android reports the vector along the axis pointing *up*, so down is its
 * negation; screen y grows downward where the sensor's y grows upward, which
 * flips that component back. Held flat there is no in-plane component at all,
 * and the last sensible direction is kept rather than letting everything hang
 * weightless.
 *
 * Only listens while [active]: nothing else needs the sensor, and a registered
 * listener keeps hardware awake.
 */
@Composable
fun rememberDeviceOrientation(active: Boolean): State<DeviceOrientation> {
    val context = LocalContext.current
    val orientation = remember { mutableStateOf(DeviceOrientation()) }

    DisposableEffect(active) {
        if (!active) {
            orientation.value = DeviceOrientation()
            return@DisposableEffect onDispose { }
        }
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val x = event.values.getOrElse(0) { 0f }
                val y = event.values.getOrElse(1) { 0f }
                val z = event.values.getOrElse(2) { 0f }
                val inPlane = hypot(x, y)
                orientation.value = DeviceOrientation(
                    down = if (inPlane < FLAT_EPSILON) orientation.value.down
                    else Offset(-x / inPlane, y / inPlane),
                    upsideDown = y < -UPRIGHT_THRESHOLD && abs(z) < FLAT_THRESHOLD
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose {
            runCatching { manager?.unregisterListener(listener) }
            orientation.value = DeviceOrientation()
        }
    }
    return orientation
}

/** Two thirds of g along the axis, so a tilted phone still counts. */
private const val UPRIGHT_THRESHOLD = 6.5f
private const val FLAT_THRESHOLD = 6f

/** Below this the phone is flat enough that "down the screen" means nothing. */
private const val FLAT_EPSILON = 1.5f
