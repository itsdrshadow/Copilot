package com.antigravity.copilot.core.recovery

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Safe mode after a crash loop (chosen in RecoveryActivity). It is read once at process start
 * and applies to that whole session; the persisted flag is cleared once that session proves
 * stable, so the next start is normal again. Consumers:
 * - DrivingTrackingService: GPS request interval [gpsIntervalMs] (5 s instead of 1 s);
 * - LiveMapView: map render cap forced to the 30 fps battery-saver mode;
 * - UI animations can check [active] to skip non-essential motion.
 */
object SafeMode {
    const val NORMAL_GPS_INTERVAL_MS = 1000L
    const val SAFE_GPS_INTERVAL_MS = 5000L

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        _active.value = CrashGuardian.isSafeModeActive(context)
    }

    fun set(enabled: Boolean) {
        _active.value = enabled
    }

    fun gpsIntervalMs(active: Boolean = _active.value): Long = if (active) SAFE_GPS_INTERVAL_MS else NORMAL_GPS_INTERVAL_MS
}
