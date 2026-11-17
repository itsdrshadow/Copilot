package com.antigravity.copilot.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.trip.TripGeofenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeofenceSettingsViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val geofenceManager: TripGeofenceManager
) : ViewModel() {

    val geofences: StateFlow<List<TripGeofenceEntity>> = geofenceManager.geofences

    /** Last known device position (lat, lon), captured once when the screen opens. */
    val lastKnownPosition: Pair<Double, Double> = OfflineMapManager.getLastKnownPosition(context)

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun distanceMeters(geofence: TripGeofenceEntity): Float =
        geofenceManager.calculateDistance(geofence, lastKnownPosition.first, lastKnownPosition.second)

    fun toggle(id: Long, enabled: Boolean) {
        viewModelScope.launch { geofenceManager.toggleGeofence(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            geofenceManager.deleteGeofence(id)
            _messages.send("Geofence deleted")
        }
    }

    /** Adds a new geofence, or updates [existing]; [onSaved] runs only on success. */
    fun save(
        existing: TripGeofenceEntity?,
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val result = if (existing != null) {
                geofenceManager.updateGeofence(
                    existing.copy(name = name, latitude = latitude, longitude = longitude, radiusMeters = radiusMeters)
                )
            } else {
                geofenceManager.addGeofence(name, latitude, longitude, radiusMeters).map { }
            }
            if (result.isSuccess) {
                _messages.send("Geofence saved successfully!")
                onSaved()
            } else {
                _messages.send(result.exceptionOrNull()?.message ?: "Failed to save geofence")
            }
        }
    }
}
