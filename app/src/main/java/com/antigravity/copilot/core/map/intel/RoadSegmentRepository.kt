package com.antigravity.copilot.core.map.intel

import android.content.Context
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.RoadSegmentStatsEntity
import com.antigravity.copilot.core.logic.Config
import com.antigravity.copilot.core.logic.fuel.FuelModel
import com.antigravity.copilot.core.logic.fuel.FuelModelState
import com.antigravity.copilot.core.logic.fuel.MileageEstimator
import com.antigravity.copilot.core.logic.fuel.SegmentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fuel-log calibration of the road-segment mileage (persisted in SharedPreferences). */
data class RoadCalibrationState(
    /** Baseline km/L the raw estimates are expressed against (FuelModel default mileage). */
    val referenceKmpl: Double = Config.DEFAULT_MILEAGE_KMPL,
    val multiplier: Double = 1.0,
    val calibrations: Int = 0,
    /** endTimeMs of the newest fuel-log segment already applied. */
    val lastSegmentEndMs: Long = 0L,
    /** Road-tracked driving since that segment: km and the litres the raw model predicts for it. */
    val accKm: Double = 0.0,
    val accPredictedL: Double = 0.0
) {
    val isCalibrated: Boolean get() = calibrations > 0
}

/**
 * Room access for `road_segment_stats` plus the calibration hook.
 *
 * All writes are serialised by one [Mutex] and run on [ioScope] (independent of any UI scope, so
 * the final flush on map teardown completes). Writes happen once per segment exit / ~20 s, never
 * per GPS fix.
 */
class RoadSegmentRepository private constructor(context: Context) {
    companion object {
        private const val TAG = "RoadSegmentRepo"
        private const val PREFS = "road_segment_calibration"

        @Volatile private var instance: RoadSegmentRepository? = null

        fun getInstance(context: Context): RoadSegmentRepository =
            instance ?: synchronized(this) { instance ?: RoadSegmentRepository(context.applicationContext).also { instance = it } }
    }

    private val dao = AppDatabase.getInstance(context).roadSegmentStatsDao()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _calibration = MutableStateFlow(loadCalibration())
    val calibration: StateFlow<RoadCalibrationState> = _calibration.asStateFlow()

    suspend fun get(key: String): RoadSegmentStatsEntity? = withContext(Dispatchers.IO) {
        try { dao.get(key) } catch (e: Exception) { Log.w(TAG, "Read failed: ${e.message}"); null }
    }

    /** Merge one pass into its row and feed the calibration accumulator. Returns the stored row. */
    suspend fun mergePass(key: String, roadClass: String, roadName: String?, pass: SegmentPass): RoadSegmentStatsEntity? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val cal = _calibration.value
                    val merged = RoadSegmentMath.merge(
                        dao.get(key), key, roadClass, roadName, pass, cal.referenceKmpl, cal.multiplier, System.currentTimeMillis()
                    ) ?: return@withLock null
                    dao.upsert(merged)
                    saveCalibration(
                        cal.copy(accKm = cal.accKm + pass.km, accPredictedL = cal.accPredictedL + pass.loadKm / cal.referenceKmpl)
                    )
                    merged
                } catch (e: Exception) {
                    Log.w(TAG, "Segment write failed: ${e.message}")
                    null
                }
            }
        }

    /**
     * Calibration hook, called by FuelRepository whenever the fuel model is persisted.
     *
     * 1. Baseline km/L changed (Settings): raw rows and the multiplier are rescaled in opposite
     *    directions, so calibrated values are unchanged (uncalibrated ones follow the new baseline).
     * 2. A new valid tank-to-tank (or gauge-to-gauge) segment arrived:
     *    `multiplier = actual km/L ÷ predicted km/L`, where predicted comes from the road-tracked
     *    driving since the previous segment. If too little of that driving was tracked (map hidden,
     *    first install), the model's learned mileage is compared with the baseline instead.
     *    Every stored row's calibrated km/L is then recomputed in one UPDATE.
     */
    fun onFuelModelUpdated(model: FuelModelState) {
        ioScope.launch {
            mutex.withLock {
                try {
                    applyFuelModel(model)
                } catch (e: Exception) {
                    Log.w(TAG, "Calibration update failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun applyFuelModel(model: FuelModelState) {
        var cal = _calibration.value
        val newRef = RoadSegmentMath.clampKmpl(model.defaultKmpl)
        var rawFactor = 1.0
        if (kotlin.math.abs(newRef - cal.referenceKmpl) > 1e-6) {
            rawFactor = newRef / cal.referenceKmpl
            cal = cal.copy(
                referenceKmpl = newRef,
                multiplier = if (cal.isCalibrated) cal.multiplier / rawFactor else 1.0,
                accPredictedL = cal.accPredictedL / rawFactor
            )
        }

        val fresh = model.segments.filter { it.endTimeMs > cal.lastSegmentEndMs && MileageEstimator.isValid(it) }
        if (fresh.isNotEmpty()) {
            val latest = fresh.maxBy { it.endTimeMs }
            val tracked = cal.accKm >= RoadSegmentMath.MIN_CALIBRATION_KM &&
                cal.accKm >= latest.km * RoadSegmentMath.MIN_CALIBRATION_COVERAGE && cal.accPredictedL > 0.0
            val (actual, predicted) = if (tracked) {
                latest.kmpl to cal.accKm / cal.accPredictedL
            } else {
                FuelModel.mileage(model, System.currentTimeMillis()).kmpl to cal.referenceKmpl
            }
            val m = RoadSegmentMath.updatedMultiplier(
                cal.multiplier, cal.calibrations, actual, predicted, latest.type == SegmentType.FULL_TO_FULL
            )
            Log.i(TAG, "Calibrated: actual=%.2f predicted=%.2f tracked=%s multiplier %.3f -> %.3f".format(actual, predicted, tracked, cal.multiplier, m))
            cal = cal.copy(
                multiplier = m,
                calibrations = cal.calibrations + 1,
                lastSegmentEndMs = fresh.maxOf { it.endTimeMs },
                accKm = 0.0,
                accPredictedL = 0.0
            )
        } else if (cal.lastSegmentEndMs == 0L && model.segments.isNotEmpty()) {
            // Only invalid history: start the accumulator from now on.
            cal = cal.copy(lastSegmentEndMs = model.segments.maxOf { it.endTimeMs })
        }

        if (cal == _calibration.value) return
        val rescale = rawFactor != 1.0 || cal.multiplier != _calibration.value.multiplier
        if (rescale) dao.rescaleAll(rawFactor, cal.multiplier)
        saveCalibration(cal)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            dao.clearAll()
            saveCalibration(RoadCalibrationState())
        }
    }

    // ---------------- Persistence ----------------

    private fun loadCalibration(): RoadCalibrationState = try {
        RoadCalibrationState(
            referenceKmpl = prefs.getDouble("ref", Config.DEFAULT_MILEAGE_KMPL),
            multiplier = prefs.getDouble("mult", 1.0),
            calibrations = prefs.getInt("count", 0),
            lastSegmentEndMs = prefs.getLong("lastSegEnd", 0L),
            accKm = prefs.getDouble("accKm", 0.0),
            accPredictedL = prefs.getDouble("accL", 0.0)
        )
    } catch (_: Exception) {
        RoadCalibrationState()
    }

    private fun saveCalibration(c: RoadCalibrationState) {
        _calibration.value = c
        prefs.edit()
            .putDouble("ref", c.referenceKmpl)
            .putDouble("mult", c.multiplier)
            .putInt("count", c.calibrations)
            .putLong("lastSegEnd", c.lastSegmentEndMs)
            .putDouble("accKm", c.accKm)
            .putDouble("accL", c.accPredictedL)
            .apply()
    }

    private fun android.content.SharedPreferences.getDouble(key: String, def: Double): Double =
        if (contains(key)) Double.fromBits(getLong(key, 0L)) else def

    private fun android.content.SharedPreferences.Editor.putDouble(key: String, v: Double) = putLong(key, v.toRawBits())
}
