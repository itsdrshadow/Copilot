package com.antigravity.copilot.core.location

import com.antigravity.copilot.core.logic.GeoMath

/**
 * Pure pump-stop prompt logic (was duplicated inside DrivingTrackingService's 1 s watchdog).
 * All times are monotonic milliseconds (SystemClock.elapsedRealtime()).
 *
 * Flow: stationary >= [checkAfterMs] -> one [Action.CheckStation]; once the station lookup says
 * "fuel station" and the stop has lasted the dwell time -> one [Action.Prompt], subject to a
 * [cooldownMs] / [cooldownRadiusM] cooldown at the same pump. Moving at >= [resumeSpeedKmh] re-arms.
 */
class PumpStopStateMachine(
    private val cooldownMs: Long = 30 * 60 * 1000L,
    private val cooldownRadiusM: Double = 200.0,
    private val checkAfterMs: Long = 3000L,
    private val resumeSpeedKmh: Double = 10.0
) {
    sealed class Action {
        object None : Action()
        data class CheckStation(val lat: Double, val lon: Double) : Action()
        data class Prompt(val stationName: String, val lat: Double, val lon: Double) : Action()
    }

    private var stationarySinceMs = NONE
    private var checked = false
    private var prompted = false
    private var stationName: String? = null
    private var stopLat = 0.0
    private var stopLon = 0.0
    private var lastPromptMs = NONE
    private var lastPromptLat = 0.0
    private var lastPromptLon = 0.0
    private var dwellMs = 30_000L

    val isStopped: Boolean get() = stationarySinceMs != NONE

    fun onTick(
        nowMs: Long,
        speedKmh: Double,
        lat: Double,
        lon: Double,
        enabled: Boolean,
        dwellMs: Long,
        stationarySpeedKmh: Double
    ): Action {
        this.dwellMs = dwellMs
        if (enabled && speedKmh < stationarySpeedKmh) {
            if (stationarySinceMs == NONE) {
                stationarySinceMs = nowMs
                checked = false
                stationName = null
                stopLat = lat
                stopLon = lon
            }
            val stopped = nowMs - stationarySinceMs
            if (stopped >= checkAfterMs && !checked) {
                checked = true
                return Action.CheckStation(lat, lon)
            }
            return maybePrompt(nowMs)
        } else if (speedKmh >= resumeSpeedKmh || !enabled) {
            stationarySinceMs = NONE
            checked = false
            prompted = false
            stationName = null
        }
        return Action.None
    }

    /** Result of the station lookup started by [Action.CheckStation]. */
    fun onStationResult(nowMs: Long, isFuelStation: Boolean, name: String): Action {
        if (stationarySinceMs == NONE) return Action.None // drove off before the lookup returned
        stationName = if (isFuelStation) name else null
        return maybePrompt(nowMs)
    }

    private fun maybePrompt(nowMs: Long): Action {
        val name = stationName ?: return Action.None
        if (prompted || nowMs - stationarySinceMs < dwellMs) return Action.None
        if (isCooldownActive(nowMs)) return Action.None
        prompted = true
        lastPromptMs = nowMs
        lastPromptLat = stopLat
        lastPromptLon = stopLon
        return Action.Prompt(name, stopLat, stopLon)
    }

    private fun isCooldownActive(nowMs: Long): Boolean {
        if (lastPromptMs == NONE) return false
        val dist = GeoMath.haversineM(stopLat, stopLon, lastPromptLat, lastPromptLon)
        return nowMs - lastPromptMs < cooldownMs && dist < cooldownRadiusM
    }

    private companion object { const val NONE = Long.MIN_VALUE }
}

/**
 * Last-known-position write throttle: write at most every [minIntervalMs] unless the car moved
 * [minDistanceM]; [force] (power cut, service stop) always writes.
 */
class PositionSaveThrottle(
    private val minIntervalMs: Long = 30_000L,
    private val minDistanceM: Double = 100.0
) {
    private var lastMs = Long.MIN_VALUE
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun shouldSave(nowMs: Long, lat: Double, lon: Double, force: Boolean = false): Boolean {
        if (lat == 0.0 && lon == 0.0) return false
        val due = force || lastMs == Long.MIN_VALUE || nowMs - lastMs >= minIntervalMs ||
            GeoMath.haversineM(lastLat, lastLon, lat, lon) >= minDistanceM
        if (due) { lastMs = nowMs; lastLat = lat; lastLon = lon }
        return due
    }
}

/**
 * GNSS quality gate. Many head units report a bogus 1 m accuracy, so distance accumulation also
 * requires >= [minSatellites] satellites used in the fix (from GnssStatus) when a status is fresh.
 * No status at all (unsupported HAL) never blocks.
 */
class GnssQualityGate(
    private val minSatellites: Int = 4,
    private val statusMaxAgeMs: Long = 10_000L
) {
    private var lastStatusMs = Long.MIN_VALUE
    var satellitesUsed: Int = -1
        private set

    fun onStatus(nowMs: Long, usedInFix: Int) {
        lastStatusMs = nowMs
        satellitesUsed = usedInFix
    }

    fun isWeak(nowMs: Long): Boolean =
        lastStatusMs != Long.MIN_VALUE && nowMs - lastStatusMs <= statusMaxAgeMs && satellitesUsed in 0 until minSatellites

    /** Mock fixes are dropped outright (spoofing apps / test providers). */
    fun accept(isMock: Boolean): Boolean = !isMock
}
