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
import kotlinx.coroutines.sync.withLock

/**
 * Self-healing road quality system: jerk → spatially de-duplicated Room row; smooth passes decay
 * confidence until the row is deleted; nearest anomaly up to 500 m ahead in a ±30° cone is exposed
 * as [aheadAlert]. State lives on one serial dispatcher; Room calls suspend, so read-modify-write
 * of rows (merge / decay) additionally holds [rowLock] to stop two hits both inserting.
 */
class RoadAnomalyManager private constructor(context: Context) {
    private val dao = AppDatabase.getInstance(context).roadAnomalyDao()
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + serial)
    private val rowLock = kotlinx.coroutines.sync.Mutex()

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

    /**
     * Debug drive simulator only: records a jolt as if the accelerometer had produced [peakMss] at
     * this spot, through the same merge / confidence path as a real hit.
     */
    fun debugInjectHit(lat: Double, lon: Double, peakMss: Float, headingDeg: Float) {
        val severity = RoadAnomalyMath.classify(peakMss) ?: return
        scope.launch { record(JerkHit(lat, lon, peakMss, severity, headingDeg, SystemClock.elapsedRealtime())) }
    }

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

    /** Last hit (elapsed ms, peak) per anomaly id: separates passes and pairs axle hits. */
    private val lastHit = HashMap<Long, Pair<Long, Float>>()

    private suspend fun record(hit: JerkHit) = rowLock.withLock {
        try {
            val box = RoadAnomalyMath.boundingBox(hit.latitude, hit.longitude, RoadAnomalyConfig.DEDUP_RADIUS_M)
            val existing = dao.inBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
                // Direction-aware dedup (K2): the opposite carriageway keeps its own record.
                .filter { RoadAnomalyPattern.canMerge(it.headingDeg, hit.headingDeg) }
                .map { it to RoadAnomalyMath.distanceM(hit.latitude, hit.longitude, it.latitude, it.longitude) }
                .filter { it.second <= RoadAnomalyConfig.DEDUP_RADIUS_M }
                .minByOrNull { it.second }?.first
            if (existing != null) {
                val maxZ = maxOf(existing.maxZForce, hit.peakMss)
                val previous = lastHit[existing.id]
                // Same pass (e.g. the rear axle a moment after the front one) is not a confirmation.
                val separatePass = RoadAnomalyPattern.isSeparatePass(previous?.first, hit.elapsedMs)
                val kind = if (previous != null && hit.elapsedMs - previous.first <= RoadAnomalyPattern.AXLE_PAIR_MAX_MS) {
                    RoadAnomalyPattern.classifyPair(previous.second, hit.peakMss, hit.elapsedMs - previous.first).name
                } else existing.kind
                dao.update(
                    existing.copy(
                        latitude = RoadAnomalyMath.smooth(existing.latitude, hit.latitude),
                        longitude = RoadAnomalyMath.smooth(existing.longitude, hit.longitude),
                        maxZForce = maxZ,
                        severity = (RoadAnomalyMath.classify(maxZ) ?: existing.severityEnum).name,
                        confidenceScore = if (separatePass) (existing.confidenceScore + 1).coerceAtMost(RoadAnomalyConfig.MAX_CONFIDENCE) else existing.confidenceScore,
                        lastUpdated = System.currentTimeMillis(),
                        kind = kind
                    )
                )
                lastHit[existing.id] = hit.elapsedMs to hit.peakMss
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
                lastHit[id] = hit.elapsedMs to hit.peakMss
                passTracker.onJerk(id, hit.elapsedMs)
                Log.i(TAG, "New anomaly $id (${hit.severity}, ${"%.1f".format(hit.peakMss)} m/s²)")
            }
            nearbyDirty = true
        } catch (e: Exception) {
            Log.w(TAG, "record: ${e.message}")
        }
    }

    private suspend fun decay(id: Long) = rowLock.withLock {
        val row = dao.getById(id) ?: return@withLock
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
