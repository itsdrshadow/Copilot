package com.antigravity.drivingreport.core.trip

import android.content.Context
import com.antigravity.drivingreport.core.database.AppDatabase
import com.antigravity.drivingreport.core.database.TripGeofenceEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Abstraction for spatial geofence queries, enabling pure JVM unit testing.
 */
interface GeofenceProvider {
    fun calculateDistance(geofence: TripGeofenceEntity, lat: Double, lon: Double): Float
    fun findGeofenceContaining(lat: Double, lon: Double, onlyEnabled: Boolean = true): TripGeofenceEntity?
}

/**
 * Manages spatial geofence boundaries for the automatic trip lifecycle.
 * Validates, persists, and monitors geographic zones across application restarts.
 */
class TripGeofenceManager private constructor(context: Context) : GeofenceProvider {

    private val database = AppDatabase.getInstance(context)
    private val dao = database.tripGeofenceDao()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _geofences = MutableStateFlow<List<TripGeofenceEntity>>(emptyList())
    val geofences: StateFlow<List<TripGeofenceEntity>> = _geofences.asStateFlow()

    init {
        scope.launch {
            dao.getAllGeofences().collect { list ->
                _geofences.value = list
            }
        }
    }

    companion object {
        const val MIN_RADIUS_METERS = 50.0f
        const val MAX_RADIUS_METERS = 2000.0f
        const val DEFAULT_RADIUS_METERS = 200.0f

        @Volatile
        private var INSTANCE: TripGeofenceManager? = null

        fun getInstance(context: Context): TripGeofenceManager {
            return INSTANCE ?: synchronized(this) {
                val instance = TripGeofenceManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    suspend fun addGeofence(
        name: String,
        latitude: Double,
        longitude: Double,
        radiusMeters: Float = com.antigravity.drivingreport.core.settings.AppSettingsManager.settingsState.value.defaultGeofenceRadiusMeters
    ): Result<Long> {
        val trimmedName = name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Geofence name cannot be empty."))
        }
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            return Result.failure(IllegalArgumentException("Invalid GPS coordinates."))
        }
        val clampedRadius = radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)

        val entity = TripGeofenceEntity(
            name = trimmedName,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = clampedRadius,
            isEnabled = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val id = dao.insertGeofence(entity)
        return Result.success(id)
    }

    suspend fun updateGeofence(geofence: TripGeofenceEntity): Result<Unit> {
        val trimmedName = geofence.name.trim()
        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Geofence name cannot be empty."))
        }
        if (geofence.latitude !in -90.0..90.0 || geofence.longitude !in -180.0..180.0) {
            return Result.failure(IllegalArgumentException("Invalid GPS coordinates."))
        }
        val clampedRadius = geofence.radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_RADIUS_METERS)

        val updated = geofence.copy(
            name = trimmedName,
            radiusMeters = clampedRadius,
            updatedAt = System.currentTimeMillis()
        )
        dao.updateGeofence(updated)
        return Result.success(Unit)
    }

    suspend fun toggleGeofence(id: Long, isEnabled: Boolean) {
        val existing = dao.getGeofenceById(id) ?: return
        dao.updateGeofence(existing.copy(isEnabled = isEnabled, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteGeofence(id: Long) {
        dao.deleteGeofenceById(id)
    }

    override fun calculateDistance(geofence: TripGeofenceEntity, lat: Double, lon: Double): Float {
        return TripDetectionEngine.computeGeodesicDistance(lat, lon, geofence.latitude, geofence.longitude).toFloat()
    }

    override fun findGeofenceContaining(lat: Double, lon: Double, onlyEnabled: Boolean): TripGeofenceEntity? {
        val candidateList = if (onlyEnabled) {
            _geofences.value.filter { it.isEnabled }
        } else {
            _geofences.value
        }

        for (gf in candidateList) {
            val dist = calculateDistance(gf, lat, lon)
            if (dist <= gf.radiusMeters) {
                return gf
            }
        }
        return null
    }

    suspend fun clearAllGeofences() {
        dao.clearAllGeofences()
        _geofences.value = emptyList()
    }

    fun resetAllData() {
        _geofences.value = emptyList()
        scope.launch {
            dao.clearAllGeofences()
        }
    }

    fun getNearestGeofence(lat: Double, lon: Double): Pair<TripGeofenceEntity, Float>? {
        val list = _geofences.value
        if (list.isEmpty()) return null

        var nearest: TripGeofenceEntity? = null
        var minDistance = Float.MAX_VALUE

        for (gf in list) {
            val dist = calculateDistance(gf, lat, lon)
            if (dist < minDistance) {
                minDistance = dist
                nearest = gf
            }
        }
        return if (nearest != null) Pair(nearest, minDistance) else null
    }
}
