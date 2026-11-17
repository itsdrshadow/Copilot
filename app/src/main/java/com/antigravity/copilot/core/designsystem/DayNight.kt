package com.antigravity.copilot.core.designsystem

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.antigravity.copilot.core.designsystem.colors.ThemeAppearanceMode
import kotlinx.coroutines.delay
import java.util.Calendar

/**
 * Resolves the Raahi appearance.
 *
 * - DAY / NIGHT: manual override from Settings.
 * - AUTO: ambient light sensor when the device has one (day above [DAY_LUX], night below
 *   [NIGHT_LUX], unchanged in between, and a change must hold for [SETTLE_MS] so a tunnel or an
 *   overpass does not flip the theme); otherwise the clock ([DAY_START_MIN]..[DAY_END_MIN]).
 */
object DayNightPolicy {
    const val DAY_LUX = 400f
    const val NIGHT_LUX = 40f
    const val SETTLE_MS = 8_000L
    const val DAY_START_MIN = 6 * 60 + 30
    const val DAY_END_MIN = 18 * 60 + 30

    fun isDayByClock(cal: Calendar = Calendar.getInstance()): Boolean {
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return m in DAY_START_MIN until DAY_END_MIN
    }

    /** Hysteresis step: returns the new day flag for a lux reading, or [current] inside the band. */
    fun nextFromLux(
        current: Boolean,
        lux: Float,
    ): Boolean =
        when {
            lux >= DAY_LUX -> true
            lux <= NIGHT_LUX -> false
            else -> current
        }
}

@Composable
fun rememberIsDay(mode: ThemeAppearanceMode): Boolean {
    if (mode == ThemeAppearanceMode.DAY) return true
    if (mode == ThemeAppearanceMode.NIGHT) return false

    val context = LocalContext.current
    var isDay by remember { mutableStateOf(DayNightPolicy.isDayByClock()) }
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val lightSensor = remember { sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT) }

    if (sensorManager != null && lightSensor != null) {
        DisposableEffect(lightSensor) {
            var candidate = isDay
            var candidateSince = 0L
            val listener =
                object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val proposed = DayNightPolicy.nextFromLux(isDay, event.values.firstOrNull() ?: return)
                        val now = System.currentTimeMillis()
                        if (proposed == isDay) {
                            candidate = isDay
                            return
                        }
                        if (proposed != candidate) {
                            candidate = proposed
                            candidateSince = now
                        } else if (now - candidateSince >= DayNightPolicy.SETTLE_MS) {
                            isDay = proposed
                        }
                    }

                    override fun onAccuracyChanged(
                        sensor: Sensor?,
                        accuracy: Int,
                    ) {}
                }
            sensorManager.registerListener(listener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
            onDispose { sensorManager.unregisterListener(listener) }
        }
    } else {
        LaunchedEffect(Unit) {
            while (true) {
                isDay = DayNightPolicy.isDayByClock()
                delay(60_000L)
            }
        }
    }
    return isDay
}
