package leaf.novel.presentation.reader.components

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.math.abs

/**
 * Turns the page when the phone is tilted, while [enabled].
 *
 * Tilt is measured on the accelerometer's X axis, which in portrait is the left-right lean — the
 * gesture is rocking the phone the way you would tip a book, not raising or lowering it.
 *
 * A tilt has to *cross* the threshold to count, and has to come back inside a smaller one before it
 * can count again. Without that hysteresis a phone held at an angle turns pages continuously, which
 * is the way every implementation of this gets it wrong.
 *
 * Registered only while enabled, so a reader who has not asked for it is not paying for a sensor.
 */
@Composable
fun NovelTiltPageTurns(enabled: Boolean, onTurn: (forward: Boolean) -> Unit) {
    val context = LocalContext.current
    val currentOnTurn by rememberUpdatedState(onTurn)

    DisposableEffect(enabled, context) {
        if (!enabled) return@DisposableEffect onDispose {}

        val sensors = ContextCompat.getSystemService(context, SensorManager::class.java)
        val accelerometer = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: return@DisposableEffect onDispose {}

        val listener = object : SensorEventListener {
            private var armed = true

            override fun onSensorChanged(event: SensorEvent) {
                val tilt = event.values.firstOrNull() ?: return
                when {
                    armed && tilt > TURN_THRESHOLD -> {
                        armed = false
                        currentOnTurn(false)
                    }
                    armed && tilt < -TURN_THRESHOLD -> {
                        armed = false
                        currentOnTurn(true)
                    }
                    !armed && abs(tilt) < REARM_THRESHOLD -> armed = true
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensors.registerListener(listener, accelerometer, SensorManager.SENSOR_DELAY_UI)
        onDispose { sensors.unregisterListener(listener) }
    }
}

/**
 * How far the phone has to lean, in metres per second squared.
 *
 * Gravity is about 9.8, so this is a lean of roughly twenty degrees — far enough that reading in a
 * deckchair does not turn pages, close enough to reach with a thumb.
 */
private const val TURN_THRESHOLD = 3.5f

/** And how far back it has to come before the next tilt counts. */
private const val REARM_THRESHOLD = 1.5f
