package com.antigravity.copilot.core.trip

import android.content.Context
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.PersistentDataManager
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.location.LocationNameResolver
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.model.VehicleStateMetrics
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TripVisualLifecycleEvent {
    NONE,
    STARTED,
    COMPLETED
}

data class TripVisualState(
    val event: TripVisualLifecycleEvent = TripVisualLifecycleEvent.NONE,
    val eventTimestamp: Long = 0L,
    val tripId: Long = 0L
)

/**
 * Zero-allocation thread-safe immutable view over the active trail points.
 * Implements java.util.AbstractList and RandomAccess with O(1) indexed access and O(1) StateFlow equality check.
 * Strictly throws UnsupportedOperationException on mutating operations, and safely guards
 * against backing list clearance across trip lifecycle transitions.
 */
class SnapshotTrailList(
    private val backing: ArrayList<TripPoint>,
    val version: Long,
    override val size: Int
) : java.util.AbstractList<TripPoint>(), RandomAccess {

    override fun get(index: Int): TripPoint {
        if (index < 0 || index >= size) {
            throw IndexOutOfBoundsException("Index: $index, Size: $size")
        }
        return synchronized(backing) {
            if (index < backing.size) {
                backing[index]
            } else {
                // Backing list was cleared/truncated after snapshot created - this indicates a race condition
                // Throw instead of returning dummy (0,0) point which would render incorrectly on map
                throw IndexOutOfBoundsException("Snapshot stale: backing size ${backing.size} < snapshot size $size. Trip likely completed.")
            }
        }
    }

    override fun add(index: Int, element: TripPoint) {
        throw UnsupportedOperationException("SnapshotTrailList is an unmodifiable view")
    }

    override fun removeAt(index: Int): TripPoint {
        throw UnsupportedOperationException("SnapshotTrailList is an unmodifiable view")
    }

    override fun set(index: Int, element: TripPoint): TripPoint {
        throw UnsupportedOperationException("SnapshotTrailList is an unmodifiable view")
    }

    override fun clear() {
        throw UnsupportedOperationException("SnapshotTrailList is an unmodifiable view")
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotTrailList) return false
        return this.version == other.version && this.size == other.size
    }

    override fun hashCode(): Int = version.hashCode()
}

class TripRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getInstance(context)
    private val tripDao = database.tripDao()
    private val vehicleTransitionDao = database.vehicleStateDao()
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in TripRepository coroutine scope: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + coroutineExceptionHandler)

    @Volatile
    private var activeTripInsertionJob: Job? = null

    // Real initial state: 0 distance, 0 duration, 0 avg speed, 100 driver score
    private val _currentTrip = MutableStateFlow(
        TripSummary(
            id = 0,
            startTime = 0L,
            distanceMeters = 0.0,
            drivingDurationMs = 0,
            stationaryDurationMs = 0,
            avgSpeedKmh = 0.0,
            maxSpeedKmh = 0.0,
            driverScore = 100,
            status = TripStatus.IDLE
        )
    )
    val currentTrip: StateFlow<TripSummary> = _currentTrip.asStateFlow()

    private val visualPrefs = appContext.getSharedPreferences("trip_visual_lifecycle_prefs", Context.MODE_PRIVATE)
    private val lastCompletedTripPrefs = appContext.getSharedPreferences("last_completed_trip_prefs", Context.MODE_PRIVATE)

    private val _lastCompletedTripId = MutableStateFlow<Long?>(
        lastCompletedTripPrefs.getLong("last_completed_trip_id", -1L).takeIf { it > 0 }
    )
    val lastCompletedTripId: StateFlow<Long?> = _lastCompletedTripId.asStateFlow()

    private val _lastCompletedTrip = MutableStateFlow<TripEntity?>(null)
    val lastCompletedTrip: StateFlow<TripEntity?> = _lastCompletedTrip.asStateFlow()

    /** Learned km/L from the fuel model (set by the tracking service). */
    @Volatile
    var learnedMileageProvider: (() -> Double)? = null

    /**
     * Phase 7: distribute the trip's tank-model fuel (km / learned km/L, idle excluded) over
     * ~40 m road cells weighted by driving load, and merge into the per-road aggregates.
     */
    private suspend fun ingestRoadMileage(tripId: Long, tripKm: Double) {
        if (tripKm < com.antigravity.copilot.core.logic.Config.ROAD_MIN_TRIP_KM) return
        val kmpl = learnedMileageProvider?.invoke()?.takeIf { it > 0.0 } ?: getEffectiveMileageFallback()
        val samples = tripDao.getPointsForTrip(tripId).map {
            com.antigravity.copilot.core.logic.RoadSample(it.latitude, it.longitude, it.speedKmh.toDouble(), it.timestamp)
        }
        val contributions = com.antigravity.copilot.core.logic.RoadMileage.distributeTrip(samples, tripKm / kmpl)
        if (contributions.isEmpty()) return
        val dao = database.roadCellDao()
        val existing = contributions.map { it.key }.chunked(500).flatMap { dao.getByKeys(it) }.associate {
            it.cellKey to com.antigravity.copilot.core.logic.RoadCellStats(it.cellKey, it.latitude, it.longitude, it.km, it.litres, it.passes, it.speedKm)
        }
        val merged = com.antigravity.copilot.core.logic.RoadMileage.aggregate(existing, contributions)
        dao.upsertAll(merged.map {
            com.antigravity.copilot.core.database.RoadCellEntity(it.key, it.lat, it.lon, it.km, it.litres, it.passes, it.speedKm)
        })
    }

    private fun getEffectiveMileageFallback(): Double {
        return try {
            val prefs = appContext.getSharedPreferences("driving_report_fuel_prefs", Context.MODE_PRIVATE)
            val currentMileage = prefs.getFloat("current_mileage", 18.0f).toDouble()
            val baselineMileage = prefs.getFloat("baseline_vehicle_mileage", 18.0f).toDouble()
            if (currentMileage > 0.0) currentMileage else if (baselineMileage > 0.0) baselineMileage else 18.0
        } catch (_: Exception) {
            18.0
        }
    }
    private val _tripVisualState = MutableStateFlow(
        TripVisualState(
            event = try {
                TripVisualLifecycleEvent.valueOf(visualPrefs.getString("last_event_type", "NONE") ?: "NONE")
            } catch (_: Exception) {
                TripVisualLifecycleEvent.NONE
            },
            eventTimestamp = visualPrefs.getLong("last_event_timestamp", 0L),
            tripId = visualPrefs.getLong("last_event_trip_id", 0L)
        )
    )
    val tripVisualState: StateFlow<TripVisualState> = _tripVisualState.asStateFlow()

    fun updateVisualLifecycle(event: TripVisualLifecycleEvent, timestamp: Long, tripId: Long) {
        visualPrefs.edit()
            .putString("last_event_type", event.name)
            .putLong("last_event_timestamp", timestamp)
            .putLong("last_event_trip_id", tripId)
            .apply()
        _tripVisualState.value = TripVisualState(event, timestamp, tripId)
    }

    private val _latestPoint = MutableStateFlow<TripPoint?>(null)
    val latestPoint: StateFlow<TripPoint?> = _latestPoint.asStateFlow()

    private val trailLock = Any()
    @Volatile
    private var activeTrailPoints = ArrayList<TripPoint>()
    private var trailVersion: Long = 0L
    private val _tripTrail = MutableStateFlow<List<TripPoint>>(emptyList())
    val tripTrail: StateFlow<List<TripPoint>> = _tripTrail.asStateFlow()

    val qualifyingTrips = tripDao.getAllHistoricalTrips()
    val lastThreeTrips = tripDao.getLastThreeTrips()
    val vehicleStateMetrics: StateFlow<VehicleStateMetrics> = VehicleStateManager.getInstance(context).metrics

    init {
        // Seed initial position with last known location (Chhattisgarh / actual GPS) so map never starts with null or New Delhi
        try {
            val (initLat, initLon) = com.antigravity.copilot.core.map.OfflineMapManager.getLastKnownPosition(appContext)
            _latestPoint.value = TripPoint(
                tripId = 0,
                latitude = initLat,
                longitude = initLon,
                altitude = 0.0,
                speedKmh = 0f,
                bearing = 0f,
                accuracy = 10f,
                timestamp = System.currentTimeMillis()
            )
        } catch (_: Exception) {}

        reloadFromDatabase()
    }

    fun reloadFromDatabase() {
        scope.launch {
            try {
                // Seamless multi-day trip recovery: continue ongoing journey across power cycles
                val activeTrip = tripDao.getActiveTrip()
                if (activeTrip != null && activeTrip.endTime <= 0L) {
                    val restoredStatus = try {
                        TripStatus.valueOf(activeTrip.status)
                    } catch (e: Exception) {
                        TripStatus.ACTIVE_TRIP
                    }
                    _currentTrip.value = TripSummary(
                        id = activeTrip.id,
                        startTime = activeTrip.startTime,
                        distanceMeters = activeTrip.distanceMeters,
                        drivingDurationMs = activeTrip.drivingDurationMs,
                        stationaryDurationMs = activeTrip.stationaryDurationMs,
                        avgSpeedKmh = activeTrip.avgSpeedKmh,
                        maxSpeedKmh = activeTrip.maxSpeedKmh,
                        calculatedMileageKmpl = if (activeTrip.calculatedMileageKmpl > 0) activeTrip.calculatedMileageKmpl else getEffectiveMileageFallback(),
                        startLocationName = activeTrip.startLocationName,
                        endLocationName = activeTrip.endLocationName,
                        status = restoredStatus
                    )
                    val pts = tripDao.getPointsForTrip(activeTrip.id)
                    val mapped = pts.map {
                        TripPoint(
                            tripId = it.tripId,
                            latitude = it.latitude,
                            longitude = it.longitude,
                            altitude = it.altitude,
                            speedKmh = it.speedKmh,
                            bearing = it.bearing,
                            accuracy = it.accuracy,
                            timestamp = it.timestamp
                        )
                    }
                    synchronized(trailLock) {
                        activeTrailPoints = ArrayList(mapped)
                    }
                    trailVersion++
                    _tripTrail.value = mapped
                } else {
                    // Trip was already completed or no active trip: reset current trip to empty to avoid double-counting with historical list
                    _currentTrip.value = TripSummary.empty()
                    synchronized(trailLock) {
                        activeTrailPoints = ArrayList()
                    }
                    trailVersion++
                    _tripTrail.value = emptyList()
                }

                // Repair & resolve missing mileage and location names for existing trips
                val allTrips = tripDao.getAllTrips()
                for (t in allTrips) {
                    var updated = t
                    var changed = false

                    if (updated.calculatedMileageKmpl <= 0.0) {
                        val kmpl = if (updated.estimatedFuelLiters > 0.0) {
                            (updated.distanceMeters / 1000.0) / updated.estimatedFuelLiters
                        } else {
                            getEffectiveMileageFallback()
                        }
                        updated = updated.copy(calculatedMileageKmpl = kmpl)
                        changed = true
                    }

                    if (updated.estimatedFuelLiters <= 0.0 && updated.distanceMeters > 0.0) {
                        val kmpl = if (updated.calculatedMileageKmpl > 0.0) updated.calculatedMileageKmpl else getEffectiveMileageFallback()
                        val fuel = (updated.distanceMeters / 1000.0) / kmpl
                        updated = updated.copy(estimatedFuelLiters = fuel)
                        changed = true
                    }

                    if (updated.startLocationName.isBlank() || updated.startLocationName == "Trip Origin" ||
                        updated.endLocationName.isBlank() || updated.endLocationName == "Trip Destination"
                    ) {
                        val pts = tripDao.getPointsForTrip(t.id)
                        if (pts.isNotEmpty()) {
                            val sName = LocationNameResolver.resolveTripLocation(appContext, pts.first().latitude, pts.first().longitude)
                            val eName = LocationNameResolver.resolveTripLocation(appContext, pts.last().latitude, pts.last().longitude)
                            updated = updated.copy(
                                startLocationName = if (sName.isNotBlank() && !sName.contains("Station")) sName else "Pusaur, Raigarh",
                                endLocationName = if (eName.isNotBlank() && !eName.contains("Station")) eName else "Raigarh Road"
                            )
                            changed = true
                        }
                    }

                    if (changed) {
                        tripDao.updateTrip(updated)
                    }
                }

                // Restore persistent pointer to last completed trip
                var lastId = lastCompletedTripPrefs.getLong("last_completed_trip_id", -1L)
                var lastTrip: TripEntity? = null
                if (lastId > 0L) {
                    lastTrip = tripDao.getTripById(lastId)
                }
                if (lastTrip == null) {
                    lastTrip = tripDao.getLastCompletedTrip()
                    if (lastTrip != null) {
                        lastId = lastTrip.id
                        lastCompletedTripPrefs.edit().putLong("last_completed_trip_id", lastId).apply()
                    }
                }
                if (lastId > 0L && lastTrip != null) {
                    _lastCompletedTripId.value = lastId
                    _lastCompletedTrip.value = lastTrip
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database operation failed in reloadFromDatabase: ${e.message}", e)
            }
        }
    }

    fun getLastCompletedTripId(): Long {
        return lastCompletedTripPrefs.getLong("last_completed_trip_id", -1L)
    }

    suspend fun getLastCompletedTrip(): TripEntity? {
        val cached = _lastCompletedTrip.value
        if (cached != null) return cached
        val lastId = getLastCompletedTripId()
        if (lastId > 0L) {
            val trip = tripDao.getTripById(lastId)
            if (trip != null) {
                _lastCompletedTrip.value = trip
                return trip
            }
        }
        val fallback = tripDao.getLastCompletedTrip()
        if (fallback != null) {
            setLastCompletedTripId(fallback.id)
            _lastCompletedTrip.value = fallback
        }
        return fallback
    }

    suspend fun getTripById(tripId: Long): TripEntity? {
        return tripDao.getTripById(tripId)
    }

    suspend fun getAllHistoricalTripsList(): List<TripEntity> {
        return tripDao.getAllTrips()
    }

    suspend fun getActiveTripFromDb(): TripEntity? {
        return tripDao.getActiveTrip()
    }

    fun setLastCompletedTripId(tripId: Long) {
        lastCompletedTripPrefs.edit().putLong("last_completed_trip_id", tripId).apply()
        _lastCompletedTripId.value = tripId
        scope.launch {
            _lastCompletedTrip.value = tripDao.getTripById(tripId)
        }
    }

    fun updateTripOrigin(tripId: Long, originName: String) {
        if (originName.isBlank()) return
        if (_currentTrip.value.id == tripId) {
            _currentTrip.value = _currentTrip.value.copy(startLocationName = originName)
        }
        scope.launch {
            try {
                activeTripInsertionJob?.join()
                val trip = tripDao.getTripById(tripId) ?: return@launch
                tripDao.updateTrip(trip.copy(startLocationName = originName))
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating trip origin in Room DB: ${e.message}", e)
            }
        }
    }

    fun updateTripDestination(tripId: Long, destinationName: String) {
        if (destinationName.isBlank()) return
        if (_currentTrip.value.id == tripId) {
            _currentTrip.value = _currentTrip.value.copy(endLocationName = destinationName)
        }
        scope.launch {
            try {
                activeTripInsertionJob?.join()
                val trip = tripDao.getTripById(tripId) ?: return@launch
                tripDao.updateTrip(trip.copy(endLocationName = destinationName))
            } catch (e: Exception) {
                Log.e(TAG, "Failed updating trip destination in Room DB: ${e.message}", e)
            }
        }
    }

    fun attachDeparturePoints(tripId: Long, points: List<TripPoint>): Job {
        if (points.isEmpty()) return Job().apply { complete() }
        val mappedPoints = points.map { if (it.tripId == tripId) it else it.copy(tripId = tripId) }

        // Update in-memory trail points immediately so Map and UI render the continuous pre-trip route without waiting for DB I/O
        val snapshot = synchronized(trailLock) {
            synchronized(activeTrailPoints) {
                val existingTimestamps = activeTrailPoints.mapTo(HashSet()) { it.timestamp }
                val toPrepend = mappedPoints.filter { it.timestamp !in existingTimestamps }
                activeTrailPoints.addAll(0, toPrepend)
            }
            trailVersion++
            SnapshotTrailList(activeTrailPoints, trailVersion, activeTrailPoints.size)
        }
        _tripTrail.value = snapshot

        if (_currentTrip.value.id == tripId && _currentTrip.value.distanceMeters == 0.0) {
            var departureDist = 0.0
            for (i in 0 until (mappedPoints.size - 1)) {
                val p1 = mappedPoints[i]
                val p2 = mappedPoints[i + 1]
                departureDist += TripDetectionEngine.computeGeodesicDistance(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }
            if (departureDist > 0.0) {
                _currentTrip.value = _currentTrip.value.copy(distanceMeters = departureDist)
            }
        }

        return scope.launch {
            try {
                // Strictly await parent TripEntity insertion in SQLite to prevent SQLiteConstraintException
                activeTripInsertionJob?.join()

                // Safety check: ensure parent trip row exists before inserting points
                val tripExists = tripDao.getTripById(tripId) != null
                if (!tripExists) {
                    tripDao.insertTrip(
                        TripEntity(
                            id = tripId,
                            startTime = tripId,
                            startLocationName = "Trip Origin",
                            status = TripStatus.ACTIVE_TRIP.name
                        )
                    )
                }

                val entities = mappedPoints.map { pt ->
                    TripPointEntity(
                        tripId = tripId,
                        latitude = pt.latitude,
                        longitude = pt.longitude,
                        altitude = pt.altitude,
                        speedKmh = pt.speedKmh,
                        bearing = pt.bearing,
                        accuracy = pt.accuracy,
                        timestamp = pt.timestamp
                    )
                }
                tripDao.insertPoints(entities)
            } catch (e: Exception) {
                Log.e(TAG, "Failed attaching departure points in Room DB: ${e.message}", e)
            }
        }
    }

    suspend fun getPointsForTrip(tripId: Long): List<TripPointEntity> {
        return tripDao.getPointsForTrip(tripId)
    }

    suspend fun getTransitionsForTrip(tripId: Long, startTime: Long = 0L, endTime: Long = 0L): List<com.antigravity.copilot.core.database.VehicleStateTransitionEntity> {
        val byTripId = database.vehicleStateDao().getTransitionsForTrip(tripId)
        if (byTripId.isNotEmpty()) return byTripId
        if (startTime > 0L && endTime >= startTime) {
            return database.vehicleStateDao().getTransitionsBetween(startTime, endTime)
        }
        return emptyList()
    }

    private val pendingPoints = ConcurrentLinkedQueue<TripPointEntity>()
    private var lastDbFlushTime = 0L

    fun onLocationReceived(point: TripPoint) {
        _latestPoint.value = point
    }

    fun onTripStarted(tripId: Long): Job {
        updateVisualLifecycle(TripVisualLifecycleEvent.STARTED, System.currentTimeMillis(), tripId)
        val summary = TripSummary(
            id = tripId,
            startTime = tripId,
            status = TripStatus.ACTIVE_TRIP
        )
        _currentTrip.value = summary
        
        // Allocate a fresh ArrayList instance so previous trip snapshots retain their backing intact
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList()

        // Initialize flush timer to trip start time so point #1 does not flush immediately
        lastDbFlushTime = System.currentTimeMillis()

        val job = scope.launch {
            try {
                tripDao.insertTrip(
                    TripEntity(
                        id = tripId,
                        startTime = tripId,
                        startLocationName = "Start Location",
                        endLocationName = "",
                        status = TripStatus.ACTIVE_TRIP.name,
                        isQualifying = false
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed inserting trip onTripStarted: ${e.message}", e)
            }
        }
        activeTripInsertionJob = job
        return job
    }

    fun onTripUpdated(summary: TripSummary, point: TripPoint) {
        _currentTrip.value = summary
        _latestPoint.value = point
        val isDuplicate: Boolean
        val snapshot = synchronized(trailLock) {
            synchronized(activeTrailPoints) {
                val lastPt = activeTrailPoints.lastOrNull()
                isDuplicate = lastPt != null &&
                        lastPt.timestamp == point.timestamp &&
                        lastPt.latitude == point.latitude &&
                        lastPt.longitude == point.longitude
                if (!isDuplicate) {
                    activeTrailPoints.add(point)
                }
            }
            trailVersion++
            SnapshotTrailList(activeTrailPoints, trailVersion, activeTrailPoints.size)
        }
        _tripTrail.value = snapshot

        if (isDuplicate) return

        val pointEntity = TripPointEntity(
            tripId = summary.id,
            latitude = point.latitude,
            longitude = point.longitude,
            altitude = point.altitude,
            speedKmh = point.speedKmh,
            bearing = point.bearing,
            accuracy = point.accuracy,
            timestamp = point.timestamp
        )

        pendingPoints.add(pointEntity)
        val now = System.currentTimeMillis()

        if (now - lastDbFlushTime > 4000 || pendingPoints.size >= 20) {
            val toFlush = mutableListOf<TripPointEntity>()
            while (true) {
                val pt = pendingPoints.poll() ?: break
                toFlush.add(pt)
            }
            lastDbFlushTime = now

            if (toFlush.isNotEmpty()) {
                scope.launch {
                    try {
                        // Strictly await parent TripEntity insertion before inserting child telemetry points
                        activeTripInsertionJob?.join()

                        // Safety verification: ensure parent trip row exists in SQLite
                        val tripExists = tripDao.getTripById(summary.id) != null
                        if (!tripExists) {
                            tripDao.insertTrip(
                                TripEntity(
                                    id = summary.id,
                                    startTime = summary.startTime,
                                    startLocationName = summary.startLocationName.ifEmpty { "Start Location" },
                                    endLocationName = "",
                                    status = TripStatus.ACTIVE_TRIP.name,
                                    isQualifying = false
                                )
                            )
                        }

                        val existingTrip = tripDao.getTripById(summary.id)
                        val effectiveStartName = summary.startLocationName.ifEmpty { existingTrip?.startLocationName?.ifEmpty { "Trip Origin" } ?: "Trip Origin" }
                        val effectiveEndName = summary.endLocationName.ifEmpty { existingTrip?.endLocationName ?: "" }

                        tripDao.insertPoints(toFlush)
                        tripDao.updateTrip(
                            TripEntity(
                                id = summary.id,
                                startTime = summary.startTime,
                                endTime = summary.endTime,
                                distanceMeters = summary.distanceMeters,
                                drivingDurationMs = summary.drivingDurationMs,
                                stationaryDurationMs = summary.stationaryDurationMs,
                                avgSpeedKmh = summary.avgSpeedKmh,
                                maxSpeedKmh = summary.maxSpeedKmh,
                                estimatedFuelLiters = summary.estimatedFuelConsumedLiters,
                                calculatedMileageKmpl = if (summary.calculatedMileageKmpl > 0) summary.calculatedMileageKmpl else getEffectiveMileageFallback(),
                                driverScore = summary.driverScore,
                                brakeCount = summary.brakeCount,
                                accelCount = summary.accelCount,
                                bestMileageKmpl = summary.bestMileageKmpl,
                                startLocationName = effectiveStartName,
                                endLocationName = effectiveEndName,
                                status = summary.status.name,
                                isQualifying = summary.distanceMeters >= 1000.0
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed updating trip in Room DB: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun persistActiveTrip(summary: TripSummary): Job {
        _currentTrip.value = summary
        return scope.launch {
            try {
                activeTripInsertionJob?.join()
                val toFlush = mutableListOf<TripPointEntity>()
                while (true) {
                    val pt = pendingPoints.poll() ?: break
                    toFlush.add(pt)
                }
                if (toFlush.isNotEmpty()) {
                    tripDao.insertPoints(toFlush)
                }

                val tripExists = tripDao.getTripById(summary.id) != null
                if (!tripExists) {
                    tripDao.insertTrip(
                        TripEntity(
                            id = summary.id,
                            startTime = summary.startTime,
                            startLocationName = summary.startLocationName.ifEmpty { "Start Location" },
                            endLocationName = "",
                            status = summary.status.name,
                            isQualifying = summary.distanceMeters >= 1000.0
                        )
                    )
                }

                val existingTrip = tripDao.getTripById(summary.id)
                val effectiveStartName = summary.startLocationName.ifEmpty { existingTrip?.startLocationName?.ifEmpty { "Trip Origin" } ?: "Trip Origin" }
                val effectiveEndName = summary.endLocationName.ifEmpty { existingTrip?.endLocationName ?: "" }

                tripDao.updateTrip(
                    TripEntity(
                        id = summary.id,
                        startTime = summary.startTime,
                        endTime = summary.endTime,
                        distanceMeters = summary.distanceMeters,
                        drivingDurationMs = summary.drivingDurationMs,
                        stationaryDurationMs = summary.stationaryDurationMs,
                        avgSpeedKmh = summary.avgSpeedKmh,
                        maxSpeedKmh = summary.maxSpeedKmh,
                        estimatedFuelLiters = summary.estimatedFuelConsumedLiters,
                        calculatedMileageKmpl = if (summary.calculatedMileageKmpl > 0) summary.calculatedMileageKmpl else getEffectiveMileageFallback(),
                        driverScore = summary.driverScore,
                        brakeCount = summary.brakeCount,
                        accelCount = summary.accelCount,
                        bestMileageKmpl = summary.bestMileageKmpl,
                        startLocationName = effectiveStartName,
                        endLocationName = effectiveEndName,
                        status = summary.status.name,
                        isQualifying = summary.distanceMeters >= 1000.0
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed persisting active trip in Room DB: ${e.message}", e)
            }
        }
    }

    fun onTripCompleted(summary: TripSummary) {
        updateVisualLifecycle(TripVisualLifecycleEvent.COMPLETED, System.currentTimeMillis(), summary.id)
        _currentTrip.value = TripSummary.empty()
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList() // Immediately clear active route trail line when trip ends

        scope.launch {
            try {
                // Strictly await parent TripEntity insertion before flushing remaining points
                activeTripInsertionJob?.join()

                val toFlush = mutableListOf<TripPointEntity>()
                while (true) {
                    val pt = pendingPoints.poll() ?: break
                    toFlush.add(pt)
                }
                if (toFlush.isNotEmpty()) {
                    tripDao.insertPoints(toFlush)
                }
                val existingTrip = tripDao.getTripById(summary.id)
                val effectiveStartName = summary.startLocationName.ifEmpty { existingTrip?.startLocationName?.ifEmpty { "Trip Origin" } ?: "Trip Origin" }
                val effectiveEndName = summary.endLocationName.ifEmpty { existingTrip?.endLocationName?.ifEmpty { "Trip Destination" } ?: "Trip Destination" }

                val completedTrip = TripEntity(
                    id = summary.id,
                    startTime = summary.startTime,
                    endTime = summary.endTime,
                    distanceMeters = summary.distanceMeters,
                    drivingDurationMs = summary.drivingDurationMs,
                    stationaryDurationMs = summary.stationaryDurationMs,
                    avgSpeedKmh = summary.avgSpeedKmh,
                    maxSpeedKmh = summary.maxSpeedKmh,
                    estimatedFuelLiters = summary.estimatedFuelConsumedLiters,
                    calculatedMileageKmpl = if (summary.calculatedMileageKmpl > 0) summary.calculatedMileageKmpl else getEffectiveMileageFallback(),
                    driverScore = summary.driverScore,
                    brakeCount = summary.brakeCount,
                    accelCount = summary.accelCount,
                    bestMileageKmpl = summary.bestMileageKmpl,
                    startLocationName = effectiveStartName,
                    endLocationName = effectiveEndName,
                    status = TripStatus.COMPLETED.name,
                    isQualifying = summary.distanceMeters >= 1000.0
                )
                tripDao.updateTrip(completedTrip)
                try {
                    ingestRoadMileage(summary.id, summary.distanceMeters / 1000.0)
                } catch (e: Exception) {
                    Log.e(TAG, "Per-road mileage ingestion failed: ${e.message}", e)
                }

                // Atomically update persistent pointer to this newly completed trip
                lastCompletedTripPrefs.edit().putLong("last_completed_trip_id", summary.id).apply()
                _lastCompletedTripId.value = summary.id
                _lastCompletedTrip.value = completedTrip

                PersistentDataManager.backupAsync(appContext, force = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed completing trip in Room DB: ${e.message}", e)
            }
        }
    }

    fun onTripDiscarded(tripId: Long) {
        _currentTrip.value = TripSummary(id = 0, startTime = 0L, status = TripStatus.IDLE)
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList()
        pendingPoints.clear()

        scope.launch {
            try {
                tripDao.deletePointsForTrip(tripId)
                vehicleTransitionDao.deleteTransitionsForTrip(tripId)
                tripDao.deleteTrip(tripId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed discarding trip in Room DB: ${e.message}", e)
            }
        }
    }

    fun deleteTrip(tripId: Long) {
        scope.launch {
            try {
                tripDao.deletePointsForTrip(tripId)
                vehicleTransitionDao.deleteTransitionsForTrip(tripId)
                tripDao.deleteTrip(tripId)
                if (getLastCompletedTripId() == tripId) {
                    val nextLatest = tripDao.getLastCompletedTrip()
                    if (nextLatest != null) {
                        setLastCompletedTripId(nextLatest.id)
                    } else {
                        lastCompletedTripPrefs.edit().remove("last_completed_trip_id").apply()
                        _lastCompletedTripId.value = null
                        _lastCompletedTrip.value = null
                    }
                }
                PersistentDataManager.backupAsync(appContext, force = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting trip in Room DB: ${e.message}", e)
            }
        }
    }

    fun resetAllData() {
        updateVisualLifecycle(TripVisualLifecycleEvent.NONE, 0L, 0L)
        try {
            visualPrefs.edit().clear().apply()
            lastCompletedTripPrefs.edit().clear().apply()
            _lastCompletedTripId.value = null
            _lastCompletedTrip.value = null
        } catch (_: Exception) {}
        _currentTrip.value = TripSummary(id = 0, startTime = 0L, status = TripStatus.IDLE)
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList()
        pendingPoints.clear()

        scope.launch {
            try {
                tripDao.clearAllTripPoints()
                tripDao.clearAllTrips()
                PersistentDataManager.clearBackup(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Failed resetting trip data in Room DB: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val TAG = "TripRepository"

        @Volatile
        private var INSTANCE: TripRepository? = null

        fun getInstance(context: Context): TripRepository {
            return INSTANCE ?: synchronized(this) {
                val inst = TripRepository(context.applicationContext)
                INSTANCE = inst
                inst
            }
        }
    }
}
