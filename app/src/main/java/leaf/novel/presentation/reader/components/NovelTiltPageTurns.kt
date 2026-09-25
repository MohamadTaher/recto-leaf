package leaf.novel.presentation.reader.components

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
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
 * Tilt is the left-right lean of the screen as it is held — the gesture is rocking the phone the
 * way you would tip a book, not raising or lowering it. See [leanOf] for why that is not simply the
 * accelerometer's X axis.
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
        val display = ContextCompat.getDisplayOrDefault(context)

        val listener = object : SensorEventListener {
            private var armed = true

            override fun onSensorChanged(event: SensorEvent) {
                val tilt = leanOf(event.values, display.rotation) ?: return
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
 * The accelerometer's reading along the screen's width, however the screen is turned.
 *
 * The sensor's axes belong to the device, not the display. Held in landscape, its X axis runs up the
 * screen and reads gravity itself, which would turn one page and never re-arm. The mapping is the
 * one Android's own accelerometer sample uses.
 */
private fun leanOf(values: FloatArray, rotation: Int): Float? {
    if (values.size < 2) return null
    return when (rotation) {
        Surface.ROTATION_90 -> -values[1]
        Surface.ROTATION_180 -> -values[0]
        Surface.ROTATION_270 -> values[1]
        else -> values[0]
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
