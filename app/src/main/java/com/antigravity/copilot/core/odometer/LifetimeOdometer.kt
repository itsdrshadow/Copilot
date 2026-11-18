package com.antigravity.copilot.core.odometer

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import com.antigravity.copilot.core.logic.LifetimeDistanceCounter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service-owned persistent lifetime distance (audit G1/G2/G3). DrivingTrackingService feeds every
 * accepted odometer segment and bridge; the UI and the fuel model only observe [totalKm]. It keeps
 * counting while another app is in front or the activity is gone, never drops sub-1 km trips and
 * never goes backwards when a trip is deleted. Seeded once from the old source (qualifying trips +
 * active trip) so the odometer does not jump on upgrade.
 */
object LifetimeOdometer {
    private const val PREFS = "lifetime_odometer_prefs"
    private const val KEY_TOTAL_KM_BITS = "total_km_bits"
    private const val KEY_SEEDED = "seeded_v1"

    private val _totalKm = MutableStateFlow(0.0)
    val totalKm: StateFlow<Double> = _totalKm.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null
    private var counter = LifetimeDistanceCounter()

    val isSeeded: Boolean get() = counter.isSeeded

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val km = if (p.contains(KEY_TOTAL_KM_BITS)) java.lang.Double.longBitsToDouble(p.getLong(KEY_TOTAL_KM_BITS, 0L)) else 0.0
        counter = LifetimeDistanceCounter(if (km.isFinite()) km else 0.0, p.getBoolean(KEY_SEEDED, false))
        publish()
    }

    /** Backup restore wrote new prefs: reload them into memory. */
    @Synchronized
    fun reloadFromPrefs(context: Context) {
        prefs = null
        init(context)
    }

    @Synchronized
    fun addMeters(meters: Double) {
        val now = SystemClock.elapsedRealtime()
        val persist = counter.addMeters(meters, now)
        if (meters > 0.0 && counter.isSeeded) publish()
        if (persist) persist(now, sync = false)
    }

    /** One-time seed (no-op once seeded). */
    @Synchronized
    fun seedIfNeeded(seedKm: Double) {
        if (counter.isSeeded) return
        counter.seed(seedKm)
        publish()
        persist(SystemClock.elapsedRealtime(), sync = true)
    }

    /** Backup restore / factory reset. */
    @Synchronized
    fun resetTo(km: Double) {
        counter.reset(km)
        publish()
        persist(SystemClock.elapsedRealtime(), sync = true)
    }

    /** Power cut / service stop: write the latest value synchronously (atomic prefs commit). */
    @Synchronized
    fun flush() {
        if (counter.isSeeded) persist(SystemClock.elapsedRealtime(), sync = true)
    }

    private fun publish() {
        val km = counter.totalKm
        _totalKm.value = km
        OdometerManager.syncLifetimeKm(km)
    }

    private fun persist(nowMs: Long, sync: Boolean) {
        val p = prefs ?: return
        val editor = p.edit()
            .putLong(KEY_TOTAL_KM_BITS, java.lang.Double.doubleToRawLongBits(counter.totalKm))
            .putBoolean(KEY_SEEDED, counter.isSeeded)
        if (sync) editor.commit() else editor.apply()
        counter.markPersisted(nowMs)
    }
}
