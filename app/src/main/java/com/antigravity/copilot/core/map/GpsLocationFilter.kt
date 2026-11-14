package com.antigravity.copilot.core.map

import kotlin.math.*

/**
 * GpsLocationFilter:
 * Professional automotive-grade GPS noise rejection and position filter.
 *
 * Solves:
 * 1. Stationary GPS jitter (random wandering while parked or stopped at traffic lights)
 * 2. Sudden multipath GPS jumps (teleportation glitches)
 * 3. Erratic low-speed bearing rotations (compass jitter)
 * 4. Smooth angular path interpolation without 360-degree wrapping glitches
 */
class GpsLocationFilter {

    data class FilteredState(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speedKmh: Float,
        val isStationary: Boolean,
        val accuracyMeters: Float,
        val timestamp: Long
    )

    private var lastState: FilteredState? = null
    private var lastValidBearing: Float = 0f
    private var lastReportTime: Long = 0L

    companion object {
        const val STATIONARY_SPEED_THRESHOLD_KMH = 2.0f
        const val MIN_ACCURACY_THRESHOLD_METERS = 35.0f
        const val MIN_STATIONARY_MOVEMENT_METERS = 2.5 // Jitter dead-zone when stationary
        const val MAX_VALID_SPEED_KMH = 240.0f
    }

    /**
     * Filters a raw GPS point, applying noise rejection, velocity constraints,
     * and bearing stabilization.
     */
    fun filter(
        rawLat: Double,
        rawLon: Double,
        rawBearing: Float,
        rawSpeedKmh: Float,
        rawAccuracyMeters: Float = 5.0f,
        timestamp: Long = System.currentTimeMillis()
    ): FilteredState {
        // Guard against invalid coordinates
        if (rawLat == 0.0 && rawLon == 0.0) {
            return lastState ?: FilteredState(0.0, 0.0, 0f, 0f, true, 99f, timestamp)
        }

        val previous = lastState
        if (previous == null) {
            val initialState = FilteredState(
                latitude = rawLat,
                longitude = rawLon,
                bearing = if (rawBearing != 0f) rawBearing else 0f,
                speedKmh = rawSpeedKmh.coerceAtLeast(0f),
                isStationary = rawSpeedKmh < STATIONARY_SPEED_THRESHOLD_KMH,
                accuracyMeters = rawAccuracyMeters,
                timestamp = timestamp
            )
            lastState = initialState
            lastValidBearing = initialState.bearing
            lastReportTime = timestamp
            return initialState
        }

        val rawDtSeconds = ((timestamp - lastReportTime) / 1000.0).coerceAtLeast(0.1)
        val isOutageRecovery = rawDtSeconds > 15.0
        val dtSeconds = if (isOutageRecovery) rawDtSeconds else rawDtSeconds.coerceAtMost(10.0)
        val distanceM = calculateDistanceMeters(previous.latitude, previous.longitude, rawLat, rawLon)

        // 1. Multipath glitch rejection: reject impossible jumps (>240 km/h) unconditionally
        val impliedSpeedKmh = (distanceM / dtSeconds) * 3.6
        if (impliedSpeedKmh > MAX_VALID_SPEED_KMH) {
            // Reject sudden high-speed spike unconditionally
            return previous.copy(timestamp = timestamp)
        }

        // 2. Stationary jitter filter:
        // When vehicle speed is below stationary threshold, ignore movements within dead-zone
        val isVehicleStationary = rawSpeedKmh < STATIONARY_SPEED_THRESHOLD_KMH
        val finalLat: Double
        val finalLon: Double

        if (isOutageRecovery && rawAccuracyMeters <= MIN_ACCURACY_THRESHOLD_METERS) {
            // After signal outage / tunnel transit, snap directly to valid fix without low-pass dragging
            finalLat = rawLat
            finalLon = rawLon
        } else if (isVehicleStationary && distanceM < MIN_STATIONARY_MOVEMENT_METERS) {
            // Freeze coordinates to eliminate jitter at stoplights
            finalLat = previous.latitude
            finalLon = previous.longitude
        } else {
            // Apply lightweight low-pass filtering based on GPS accuracy
            val weight = if (rawAccuracyMeters > 20.0f) 0.65 else 0.90
            finalLat = previous.latitude + (rawLat - previous.latitude) * weight
            finalLon = previous.longitude + (rawLon - previous.longitude) * weight
        }

        // 3. Bearing stabilization:
        // GPS bearing is notoriously erratic at < 3 km/h. Only update bearing when moving with speed.
        val finalBearing: Float
        if (rawSpeedKmh >= 3.5f && (rawBearing != 0f || distanceM > 3.0)) {
            val calculatedBearing = if (rawBearing != 0f) {
                rawBearing
            } else {
                calculateBearing(previous.latitude, previous.longitude, finalLat, finalLon)
            }
            // Smooth angular transition (shortest angular distance)
            val shortestDelta = (calculatedBearing - lastValidBearing + 540f) % 360f - 180f
            val smoothedBearing = (lastValidBearing + shortestDelta * 0.45f + 360f) % 360f
            lastValidBearing = smoothedBearing
            finalBearing = smoothedBearing
        } else {
            // Keep previous valid bearing while stopped
            finalBearing = lastValidBearing
        }

        val updatedState = FilteredState(
            latitude = finalLat,
            longitude = finalLon,
            bearing = finalBearing,
            speedKmh = if (isVehicleStationary) 0f else rawSpeedKmh,
            isStationary = isVehicleStationary,
            accuracyMeters = rawAccuracyMeters,
            timestamp = timestamp
        )

        lastState = updatedState
        lastReportTime = timestamp
        return updatedState
    }

    /**
     * Fuses device orientation / compass sensor when vehicle is stationary.
     */
    fun fuseCompassHeading(compassHeading: Float): Float {
        val shortestDelta = (compassHeading - lastValidBearing + 540f) % 360f - 180f
        if (abs(shortestDelta) > 1.2f) {
            val smoothed = (lastValidBearing + shortestDelta * 0.25f + 360f) % 360f
            lastValidBearing = smoothed
            lastState = lastState?.copy(bearing = smoothed)
        }
        return lastValidBearing
    }

    fun reset() {
        lastState = null
        lastValidBearing = 0f
        lastReportTime = 0L
    }

    private fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return 6371000.0 * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        val theta = atan2(y, x)
        return ((Math.toDegrees(theta) + 360.0) % 360.0).toFloat()
    }
}
