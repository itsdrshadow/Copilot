package com.antigravity.copilot.core.map.intel

import android.os.SystemClock
import com.antigravity.copilot.core.database.RoadSegmentStatsEntity
import com.antigravity.copilot.core.location.MapRoadObservation
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.TripFuelEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Tracks the road segment the car is on and publishes [callout] for the map bubble.
 *
 * Inputs (main thread):
 * - [onRoad]: the matched road from [OfflineRoadIntelligenceManager] (throttled there to one
 *   `queryRenderedFeatures` per 1.5 s / 25 m). The class is debounced (2 confirmations to change,
 *   2 misses to drop) so junctions and tile edges don't split segments.
 * - [onFix]: every GPS fix. O(1), allocation-free except on a segment change.
 *
 * Moving steps (> [RoadSegmentMath.MIN_MOVING_KMH]) accumulate distance, speed·km and
 * fuel-load·km in memory. They are written to Room only when the segment changes or every
 * [FLUSH_INTERVAL_MS], through [RoadSegmentRepository] off the main thread.
 */
class RoadSegmentTracker(
    private val scope: CoroutineScope,
    private val repo: RoadSegmentRepository
) {
    companion object {
        const val FLUSH_INTERVAL_MS = 20_000L
        const val STATIONARY_HIDE_MS = 30_000L
        /** No road observation for this long (map hidden): stop attributing driving to the old road. */
        const val ROAD_STALE_MS = 10_000L
        const val MAX_STEP_SPEED_KMH = 250.0
        const val MAX_STEP_GAP_MS = 10_000L
        /** Below this much data on the segment the bubble shows the speed-band estimate. */
        const val MIN_DISPLAY_KM = 0.05
        /** A segment's km/L is "solid" only after this much distance on it (and a fuel-log calibration). */
        const val CALIBRATED_MIN_KM = 0.5
        /** Re-check visibility this often: a parked car's GNSS goes quiet, so no fix may come. */
        const val TICK_MS = 5_000L
        /**
         * Keep showing the last callout this long while the road match briefly drops out
         * (junctions, tile edges, a missed query): the bubble no longer blinks off and on.
         */
        const val GAP_HOLD_MS = 20_000L
    }

    private val _callout = MutableStateFlow<RoadCallout?>(null)
    val callout: StateFlow<RoadCallout?> = _callout.asStateFlow()

    private var attached = false
    private var calibrationJob: Job? = null
    private var tickJob: Job? = null

    private val roadClassSticky = StickyValue<String>(confirm = 2, dropAfterMisses = 2)
    private var roadName: String? = null
    private var lastRoadMs = 0L
    private var lastShownMs = 0L

    private var gridLat = Int.MIN_VALUE
    private var gridLon = Int.MIN_VALUE

    // Segment being accumulated.
    private var key: String? = null
    private var keyClass: String? = null
    private var keyName: String? = null
    private var stored: RoadSegmentStatsEntity? = null
    /** Flushed but not yet confirmed by the DB (so the bubble doesn't dip while writing). */
    private var pending: SegmentPass? = null
    private var inFlight = 0

    // Live pass accumulators (primitives: no allocation per fix).
    private var passKm = 0.0
    private var passLoadKm = 0.0
    private var passSpeedKm = 0.0
    private var passSamples = 0
    private var lastFlushMs = 0L

    private var hasPrev = false
    private var prevLat = 0.0
    private var prevLon = 0.0
    private var prevSpeed = 0.0
    private var prevMs = 0L
    private var lastSpeedKmh = 0.0
    private var stationarySinceMs = 0L

    private val roadClass: String? get() = roadClassSticky.value

    fun attach() {
        if (attached) return
        attached = true
        // A new fuel-log calibration re-scales the shown km/L right away.
        calibrationJob = scope.launch { repo.calibration.collect { publish(SystemClock.elapsedRealtime()) } }
        tickJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(TICK_MS)
                if (_callout.value != null) publish(SystemClock.elapsedRealtime())
            }
        }
    }

    fun detach() {
        flush(SystemClock.elapsedRealtime())
        attached = false
        calibrationJob?.cancel()
        calibrationJob = null
        tickJob?.cancel()
        tickJob = null
        roadClassSticky.reset()
        key = null; keyClass = null; stored = null; pending = null
        hasPrev = false
        _callout.value = null
    }

    /** Matched road (null = no match on this evaluation). */
    fun onRoad(obs: MapRoadObservation?) {
        val now = SystemClock.elapsedRealtime()
        val before = roadClass
        roadClassSticky.offer(obs?.roadClass)
        if (obs?.roadClass != null) {
            lastRoadMs = now
            val label = obs.ref ?: obs.name
            if (obs.roadClass == roadClass && label != null) roadName = label
        }
        if (roadClass != before) {
            roadName = obs?.takeIf { it.roadClass == roadClass }?.let { it.ref ?: it.name }
            if (roadClass == null) leaveSegment(now) else enterSegment(now)
        } else if (key != null && keyName == null && roadName != null) {
            keyName = roadName
        }
        publish(now)
    }

    fun onFix(lat: Double, lon: Double, speedKmh: Double) {
        val now = SystemClock.elapsedRealtime()
        if (roadClass != null && now - lastRoadMs > ROAD_STALE_MS) {
            roadClassSticky.reset()
            leaveSegment(now)
        }

        if (hasPrev && key != null) accumulate(lat, lon, speedKmh, now)
        hasPrev = true
        prevLat = lat; prevLon = lon; prevSpeed = speedKmh; prevMs = now
        lastSpeedKmh = speedKmh

        if (speedKmh < RoadSegmentMath.MIN_MOVING_KMH) {
            if (stationarySinceMs == 0L) stationarySinceMs = now
        } else {
            stationarySinceMs = 0L
        }

        val gLat = RoadSegmentMath.gridIndex(lat)
        val gLon = RoadSegmentMath.gridIndex(lon)
        if (gLat != gridLat || gLon != gridLon) {
            gridLat = gLat; gridLon = gLon
            if (roadClass != null) enterSegment(now)
        }

        if (passKm > 0.0 && now - lastFlushMs >= FLUSH_INTERVAL_MS) flush(now)
        publish(now)
    }

    private fun accumulate(lat: Double, lon: Double, speedKmh: Double, now: Long) {
        val dtMs = now - prevMs
        if (dtMs <= 0L || dtMs > MAX_STEP_GAP_MS) return
        val speed = (prevSpeed + speedKmh) / 2.0
        if (speed < RoadSegmentMath.MIN_MOVING_KMH) return
        val km = GeoMath.haversineM(prevLat, prevLon, lat, lon) / 1000.0
        if (km <= 0.0) return
        val dt = dtMs / 1000.0
        if (km / dt * 3600.0 > MAX_STEP_SPEED_KMH) return // GPS jump
        val accel = ((speedKmh - prevSpeed) / 3.6) / dt
        val load = TripFuelEstimator.loadFactor(speed, accel)
        passKm += km
        passLoadKm += km * load
        passSpeedKm += km * speed
        passSamples++
    }

    private fun enterSegment(now: Long) {
        val cls = roadClass ?: return
        if (gridLat == Int.MIN_VALUE) return
        val newKey = RoadSegmentMath.segmentKey(cls, gridLat, gridLon)
        if (newKey == key) return
        flush(now)
        key = newKey
        keyClass = cls
        keyName = roadName
        stored = null
        pending = null
        inFlight = 0
        scope.launch {
            val row = repo.get(newKey)
            if (attached && key == newKey) {
                stored = row
                publish(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun leaveSegment(now: Long) {
        flush(now)
        key = null
        keyClass = null
        stored = null
        pending = null
    }

    private fun flush(now: Long) {
        lastFlushMs = now
        val k = key ?: return
        val cls = keyClass ?: return
        if (passKm <= 0.0) return
        val snap = SegmentPass(passKm, passLoadKm, passSpeedKm, passSamples)
        passKm = 0.0; passLoadKm = 0.0; passSpeedKm = 0.0; passSamples = 0
        pending = pending?.plus(snap) ?: snap
        inFlight++
        val name = keyName
        // ioScope, not the UI scope: the last flush on teardown must still reach the database.
        repo.ioScope.launch {
            val row = repo.mergePass(k, cls, name, snap)
            withContext(Dispatchers.Main) {
                if (!attached || key != k) return@withContext
                if (row != null) stored = row
                if (--inFlight <= 0) { inFlight = 0; pending = null }
                publish(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun publish(now: Long) {
        val k = key
        // Slow fixes, or no fixes at all (parked: GNSS de-duplicates / stops reporting).
        val stationaryTooLong = (stationarySinceMs != 0L && now - stationarySinceMs > STATIONARY_HIDE_MS) ||
            (hasPrev && now - prevMs > STATIONARY_HIDE_MS)
        if (!attached || stationaryTooLong) {
            _callout.value = null
            return
        }
        if (k == null || roadClass == null) {
            // Match gap while moving: hold the last values for a while instead of blinking off.
            if (now - lastShownMs > GAP_HOLD_MS) _callout.value = null
            return
        }
        val cal = repo.calibration.value
        val live = SegmentPass(passKm, passLoadKm, passSpeedKm, passSamples)
        val unsaved = pending?.plus(live) ?: live
        val merged = RoadSegmentMath.merge(stored, k, keyClass ?: return, keyName, unsaved, cal.referenceKmpl, cal.multiplier, 0L)

        val next = if (merged != null && merged.totalDistanceKm >= MIN_DISPLAY_KM) {
            RoadCallout(
                segmentKey = k,
                avgSpeedKmh = merged.avgSpeedKmh.roundToInt(),
                mileageTenths = (merged.calibratedMileageKmpl * 10.0).roundToInt(),
                calibrated = cal.isCalibrated && merged.totalDistanceKm >= CALIBRATED_MIN_KM
            )
        } else if (lastSpeedKmh >= RoadSegmentMath.MIN_MOVING_KMH) {
            // Just entered an unknown segment: speed-band estimate at the current speed.
            val raw = RoadSegmentMath.instantRawKmpl(lastSpeedKmh, cal.referenceKmpl)
            RoadCallout(k, lastSpeedKmh.roundToInt(), (RoadSegmentMath.clampKmpl(raw * cal.multiplier) * 10.0).roundToInt(), calibrated = false)
        } else {
            null
        }
        if (next != null) lastShownMs = now
        _callout.value = next
    }
}
