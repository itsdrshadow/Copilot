package com.antigravity.copilot.core.odometer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * OdometerManager:
 * Enterprise Automotive Odometer tracking and persistence.
 *
 * Guarantees:
 * 1. Accumulates total vehicle distance continuously from validated GPS trip telemetry.
 * 2. Never resets when a new trip starts.
 * 3. Persists initial dash odometer reading and live state across app restarts.
 * 4. Tracks live distance delta added since the last odometer baseline update (e.g. +12.4 km).
 * 5. Resets the distance delta only after a new odometer baseline is saved.
 */
object OdometerManager {
    private const val PREFS_NAME = "driving_report_odometer_prefs"
    private const val KEY_BASELINE_ODOMETER_KM = "baseline_odometer_km"
    private const val KEY_BASELINE_LIFETIME_AT_UPDATE = "baseline_lifetime_km_at_update"
    private const val KEY_LATEST_LIFETIME_KM = "latest_lifetime_km"
    private const val KEY_IS_MANUALLY_VERIFIED = "is_manually_verified"

    data class OdometerState(
        val baselineOdometerKm: Double = 0.0,
        val baselineLifetimeKmAtUpdate: Double = 0.0,
        val isManuallyVerified: Boolean = false
    )

    private val _odometerConfig = MutableStateFlow(OdometerState())
    val odometerConfig: StateFlow<OdometerState> = _odometerConfig.asStateFlow()

    @Volatile
    private var _isInitialized = false
    val isInitialized: Boolean get() = _isInitialized
    @Volatile
    private var latestLifetimeKm: Double = 0.0

    fun init(context: Context) {
        if (isInitialized) return
        reloadFromPreferences(context)
        _isInitialized = true
    }

    fun reloadFromPreferences(context: Context) {
        val prefs = getPrefs(context)
        val baseline = if (prefs.contains(KEY_BASELINE_ODOMETER_KM + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_ODOMETER_KM + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_ODOMETER_KM, 0.0f).toDouble()
        }
        val baselineLifetime = if (prefs.contains(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, 0.0f).toDouble()
        }
        val latestLifetime = if (prefs.contains(KEY_LATEST_LIFETIME_KM + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LATEST_LIFETIME_KM + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_LATEST_LIFETIME_KM, 0.0f).toDouble()
        }
        latestLifetimeKm = latestLifetime
        val verified = prefs.getBoolean(KEY_IS_MANUALLY_VERIFIED, false)
        _odometerConfig.value = OdometerState(baseline, baselineLifetime, verified)
    }

    fun syncLifetimeKm(km: Double, context: Context? = null) {
        latestLifetimeKm = km.coerceAtLeast(0.0)
        context?.let { persistLatestLifetimeKm(it) }
    }

    private fun persistLatestLifetimeKm(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_LATEST_LIFETIME_KM + "_bits", java.lang.Double.doubleToRawLongBits(latestLifetimeKm))
            .putFloat(KEY_LATEST_LIFETIME_KM, latestLifetimeKm.toFloat())
            .apply()
    }

    fun getEffectiveOdometer(): Double {
        return calculateLiveOdometer(latestLifetimeKm)
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Manually configure the initial vehicle dashboard odometer reading.
     * Records the snapshot of currentTotalLifetimeKm to measure added distance delta.
     */
    fun setBaselineOdometer(context: Context, readingKm: Double, currentTotalLifetimeKm: Double = 0.0) {
        val safeReading = readingKm.coerceAtLeast(0.0)
        val safeLifetime = currentTotalLifetimeKm.coerceAtLeast(0.0)
        latestLifetimeKm = safeLifetime
        getPrefs(context).edit()
            .putLong(KEY_BASELINE_ODOMETER_KM + "_bits", java.lang.Double.doubleToRawLongBits(safeReading))
            .putLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", java.lang.Double.doubleToRawLongBits(safeLifetime))
            .putLong(KEY_LATEST_LIFETIME_KM + "_bits", java.lang.Double.doubleToRawLongBits(safeLifetime))
            .putFloat(KEY_BASELINE_ODOMETER_KM, safeReading.toFloat())
            .putFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, safeLifetime.toFloat())
            .putFloat(KEY_LATEST_LIFETIME_KM, safeLifetime.toFloat())
            .putBoolean(KEY_IS_MANUALLY_VERIFIED, true)
            .apply()

        _odometerConfig.value = OdometerState(
            baselineOdometerKm = safeReading,
            baselineLifetimeKmAtUpdate = safeLifetime,
            isManuallyVerified = true
        )
    }

    /**
     * Calculates distance added since the last odometer baseline update.
     * Example: +12.4 km.
     */
    fun calculateDeltaKm(totalLifetimeKm: Double): Double {
        val delta = totalLifetimeKm - _odometerConfig.value.baselineLifetimeKmAtUpdate
        return delta.coerceAtLeast(0.0)
    }

    /**
     * Computes the live active odometer value combining the initial configured baseline
     * and the verified distance added since last baseline update.
     */
    fun calculateLiveOdometer(totalLifetimeKm: Double): Double {
        val delta = calculateDeltaKm(totalLifetimeKm)
        return (_odometerConfig.value.baselineOdometerKm + delta).coerceAtLeast(0.0)
    }

    fun resetAllData(context: Context) {
        getPrefs(context).edit().clear().apply()
        latestLifetimeKm = 0.0
        _odometerConfig.value = OdometerState()
    }
}
