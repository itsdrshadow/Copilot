package com.antigravity.drivingreport.core.trip

import android.util.Log

data class GeofenceDiagnosticEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val geofenceId: Long? = null,
    val geofenceName: String? = null,
    val radiusMeters: Float? = null,
    val distanceMeters: Float? = null,
    val locationAccuracy: Float = 0f,
    val previousState: String = "",
    val currentState: String = "",
    val eventType: String = "",
    val reason: String = ""
)

object GeofenceDiagnostics {
    private const val TAG = "GeofenceLifecycle"
    private const val MAX_LOG_SIZE = 100
    private val _logs = mutableListOf<GeofenceDiagnosticEvent>()
    val logs: List<GeofenceDiagnosticEvent> get() = synchronized(_logs) { ArrayList(_logs) }

    fun log(
        eventType: String,
        previousState: String,
        currentState: String,
        geofenceId: Long? = null,
        geofenceName: String? = null,
        radiusMeters: Float? = null,
        distanceMeters: Float? = null,
        accuracy: Float = 0f,
        reason: String = ""
    ) {
        val entry = GeofenceDiagnosticEvent(
            timestamp = System.currentTimeMillis(),
            geofenceId = geofenceId,
            geofenceName = geofenceName,
            radiusMeters = radiusMeters,
            distanceMeters = distanceMeters,
            locationAccuracy = accuracy,
            previousState = previousState,
            currentState = currentState,
            eventType = eventType,
            reason = reason
        )
        synchronized(_logs) {
            _logs.add(entry)
            if (_logs.size > MAX_LOG_SIZE) {
                _logs.removeAt(0)
            }
        }
        Log.d(TAG, "[$eventType] state: $previousState -> $currentState | gf: $geofenceName (id=$geofenceId, r=${radiusMeters}m, d=${distanceMeters}m) | acc=${accuracy}m | $reason")
    }

    fun clear() {
        synchronized(_logs) {
            _logs.clear()
        }
    }
}
