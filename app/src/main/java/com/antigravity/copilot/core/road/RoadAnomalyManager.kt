package com.antigravity.copilot.core.road

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.model.TripPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Self-healing road quality system: jerk → spatially de-duplicated Room row; smooth passes decay
 * confidence until the row is deleted; nearest anomaly 150–200 m ahead in a ±30° cone is exposed
 * as [aheadAlert]. All state is mutated on one serial dispatcher, so no locks are needed.
 */
class RoadAnomalyManager private constructor(context: Context) {
    private val dao = AppDatabase.getInstance(context).roadAnomalyDao()
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val passTracker = AnomalyPassTracker()
    private val alertTracker = AheadAlertTracker()

    private var nearby: List<RoadAnomalyEntity> = emptyList()
    private var nearbyLat = Double.NaN
    private var nearbyLon = Double.NaN
    private var nearbyDirty = true

    /** All anomalies, for the map layer. Re-emits on every Room change. */
    val anomalies: Flow<List<RoadAnomalyEntity>> = dao.observeAll()

    private val _aheadAlert = MutableStateFlow<AnomalyAlert?>(null)
    val aheadAlert: StateFlow<AnomalyAlert?> = _aheadAlert.asStateFlow()

    private val detector = RoadAnomalyDetector(context) { hit -> scope.launch { record(hit) } }

    fun start() = detector.start()
    fun stop() {
        detector.stop()
        scope.launch { alertTracker.reset(); _aheadAlert.value = null }
    }
    fun onWake() = detector.rearm()

    /** Feed every real GPS fix (from the tracking service). */
    fun onLocation(p: TripPoint) {
        detector.updateFix(p.latitude, p.longitude, p.speedKmh, p.bearing, p.accuracy)
        scope.launch { process(p) }
    }

    private suspend fun process(p: TripPoint) {
        try {
            val now = SystemClock.elapsedRealtime()
            refreshNearbyIfNeeded(p.latitude, p.longitude)
            val heading = p.bearing.toDouble()

            val accurate = p.accuracy <= RoadAnomalyConfig.MAX_ACCURACY_M
            if (accurate) {
                for (id in passTracker.onFix(now, p.latitude, p.longitude, heading, p.speedKmh, nearby)) {
                    decay(id)
                }
            }
            _aheadAlert.value = alertTracker.update(now, p.latitude, p.longitude, heading, p.speedKmh, nearby)
        } catch (e: Exception) {
            Log.w(TAG, "process: ${e.message}")
        }
    }

    private suspend fun record(hit: JerkHit) {
        try {
            val box = RoadAnomalyMath.boundingBox(hit.latitude, hit.longitude, RoadAnomalyConfig.DEDUP_RADIUS_M)
            val existing = dao.inBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
                .map { it to RoadAnomalyMath.distanceM(hit.latitude, hit.longitude, it.latitude, it.longitude) }
                .filter { it.second <= RoadAnomalyConfig.DEDUP_RADIUS_M }
                .minByOrNull { it.second }?.first
            if (existing != null) {
                val maxZ = maxOf(existing.maxZForce, hit.peakMss)
                dao.update(
                    existing.copy(
                        latitude = RoadAnomalyMath.smooth(existing.latitude, hit.latitude),
                        longitude = RoadAnomalyMath.smooth(existing.longitude, hit.longitude),
                        maxZForce = maxZ,
                        severity = (RoadAnomalyMath.classify(maxZ) ?: existing.severityEnum).name,
                        confidenceScore = (existing.confidenceScore + 1).coerceAtMost(RoadAnomalyConfig.MAX_CONFIDENCE),
                        lastUpdated = System.currentTimeMillis()
                    )
                )
                passTracker.onJerk(existing.id, hit.elapsedMs)
                Log.i(TAG, "Re-confirmed anomaly ${existing.id} (${hit.severity}, ${"%.1f".format(hit.peakMss)} m/s²)")
            } else {
                val id = dao.insert(
                    RoadAnomalyEntity(
                        latitude = hit.latitude,
                        longitude = hit.longitude,
                        maxZForce = hit.peakMss,
                        severity = hit.severity.name,
                        headingDeg = hit.headingDeg
                    )
                )
                passTracker.onJerk(id, hit.elapsedMs)
                Log.i(TAG, "New anomaly $id (${hit.severity}, ${"%.1f".format(hit.peakMss)} m/s²)")
            }
            nearbyDirty = true
        } catch (e: Exception) {
            Log.w(TAG, "record: ${e.message}")
        }
    }

    private suspend fun decay(id: Long) {
        val row = dao.getById(id) ?: return
        val next = row.confidenceScore - 1
        if (next <= 0) {
            dao.deleteById(id)
            passTracker.forget(id)
            Log.i(TAG, "Anomaly $id healed and removed")
        } else {
            dao.update(row.copy(confidenceScore = next, lastUpdated = System.currentTimeMillis()))
        }
        nearbyDirty = true
    }

    private suspend fun refreshNearbyIfNeeded(lat: Double, lon: Double) {
        val moved = nearbyLat.isNaN() ||
            RoadAnomalyMath.distanceM(lat, lon, nearbyLat, nearbyLon) > RoadAnomalyConfig.NEARBY_REFRESH_MOVE_M
        if (!moved && !nearbyDirty) return
        val box = RoadAnomalyMath.boundingBox(lat, lon, RoadAnomalyConfig.NEARBY_RADIUS_M)
        nearby = dao.inBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
        nearbyLat = lat; nearbyLon = lon; nearbyDirty = false
    }

    companion object {
        private const val TAG = "RoadAnomalyManager"
        @Volatile private var instance: RoadAnomalyManager? = null
        fun getInstance(context: Context): RoadAnomalyManager =
            instance ?: synchronized(this) {
                instance ?: RoadAnomalyManager(context.applicationContext).also { instance = it }
            }
    }
}
