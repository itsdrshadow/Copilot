package com.antigravity.copilot.core.fuel.station

import android.content.Context
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.road.RoadAnomalyMath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fuel stations + refuel logs (Room) and the station quality layer on top of the existing fuel
 * ledger. Every write replays [HybridMileageEngine] over the full history and rewrites only the
 * rows whose status / mileage / rating changed.
 */
class FuelStationRepository private constructor(context: Context) {
    private val db = AppDatabase.getInstance(context)
    private val stationDao = db.fuelStationDao()
    private val logDao = db.refuelLogDao()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val stations: Flow<List<FuelStationEntity>> = stationDao.observeAll()

    data class EngineState(
        val pendingReserveOdometer: Double? = null,
        val openAnchorMode: RefuelMode? = null,
        val trendKmpl: Double? = null,
        /** Lifetime km/L over all completed cycles and the km behind it (null = none yet). */
        val lifetimeKmpl: Double? = null,
        val lifetimeKm: Double = 0.0
    )

    /** Set by FuelRepository: receives (lifetime km/L, km behind it) after every replay. */
    @Volatile var onLifetimeMileage: ((Double, Double) -> Unit)? = null

    private val _engineState = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    init {
        scope.launch { runCatching { mutex.withLock { reevaluateLocked() } } }
    }

    /** Nearest saved station within [radiusM] (bounding box, then exact distance). */
    suspend fun findStationNear(lat: Double, lon: Double, radiusM: Double = STATION_MATCH_RADIUS_M): FuelStationEntity? {
        if (lat == 0.0 && lon == 0.0) return null
        val box = RoadAnomalyMath.boundingBox(lat, lon, radiusM)
        return stationDao.inBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
            .map { it to RoadAnomalyMath.distanceM(lat, lon, it.latitude, it.longitude) }
            .filter { it.second <= radiusM }
            .minByOrNull { it.second }?.first
    }

    /** Stations inside a bounding box of [radiusM] (for the proximity bubble). */
    suspend fun stationsAround(lat: Double, lon: Double, radiusM: Double): List<FuelStationEntity> {
        val box = RoadAnomalyMath.boundingBox(lat, lon, radiusM)
        return stationDao.inBox(box.minLat, box.maxLat, box.minLon, box.maxLon)
    }

    /**
     * Logs a refuel. A station row is created/updated only when the car is at a confirmed pump:
     * a saved station or a map fuel POI within 40 m, or [confirmedStation] (the stop prompt fired).
     * A manual entry at home still joins the mileage chain, just without a station.
     */
    suspend fun recordRefuel(
        stationName: String,
        lat: Double,
        lon: Double,
        liters: Double,
        rate: Double,
        mode: RefuelMode,
        odometerKm: Double,
        confirmedStation: Boolean
    ) = mutex.withLock {
        try {
            val stationId = resolveStationLocked(stationName, lat, lon, rate, confirmedStation)
            logDao.insert(
                RefuelLogEntity(
                    stationId = stationId,
                    liters = liters,
                    rate = rate,
                    refuelMode = mode.name,
                    odometerAtRefuel = odometerKm
                )
            )
            reevaluateLocked()
        } catch (e: Exception) {
            Log.w(TAG, "recordRefuel: ${e.message}")
        }
    }

    /**
     * Dashboard "Reserve light ON": locks the odometer now. Tapping again before the next refuel
     * undoes it. Returns true when a marker is now set.
     */
    suspend fun toggleReserveLight(odometerKm: Double): Boolean = mutex.withLock {
        val logs = logDao.getAllChronological()
        val last = logs.lastOrNull()
        val set = if (last != null && last.mode == RefuelMode.RESERVE_MARKER) {
            logDao.deleteById(last.logId)
            false
        } else {
            logDao.insert(
                RefuelLogEntity(
                    liters = 0.0, rate = 0.0, refuelMode = RefuelMode.RESERVE_MARKER.name,
                    odometerAtRefuel = odometerKm, status = RefuelLogStatus.COMPLETED.name
                )
            )
            true
        }
        reevaluateLocked()
        set
    }

    private suspend fun resolveStationLocked(name: String, lat: Double, lon: Double, rate: Double, confirmed: Boolean): Long? {
        if (lat == 0.0 && lon == 0.0) return null
        val known = findStationNear(lat, lon)
        val cleanName = name.trim()
        if (known != null) {
            stationDao.update(
                known.copy(
                    // The driver's edit of the name is kept permanently.
                    name = cleanName.ifBlank { known.name },
                    lastFuelRate = if (rate > 0.0) rate else known.lastFuelRate,
                    visitCount = known.visitCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return known.stationId
        }
        val poi = FuelPoiCache.nearest(lat, lon, STATION_MATCH_RADIUS_M)
        if (poi == null && !confirmed) return null
        val pinLat = poi?.latitude ?: lat
        val pinLon = poi?.longitude ?: lon
        return stationDao.insert(
            FuelStationEntity(
                name = cleanName.ifBlank { poi?.name ?: "Fuel Station" },
                latitude = pinLat,
                longitude = pinLon,
                lastFuelRate = rate,
                visitCount = 1
            )
        )
    }

    private suspend fun reevaluateLocked() {
        val logs = logDao.getAllChronological()
        val result = HybridMileageEngine.evaluate(
            logs.map { HybridMileageEngine.Entry(it.logId, it.stationId, it.mode, it.liters, it.odometerAtRefuel) }
        )
        val changedLogs = logs.mapNotNull { l ->
            val o = result.outcomes[l.logId] ?: return@mapNotNull null
            if (o.status.name == l.status && o.mileage == l.calculatedMileage) null
            else l.copy(status = o.status.name, calculatedMileage = o.mileage)
        }
        if (changedLogs.isNotEmpty()) logDao.updateAll(changedLogs)

        val averages = HybridMileageEngine.stationAverages(result.cycles)
        val avgRates = logs.filter { it.stationId != null && it.rate > 0.0 && it.mode != RefuelMode.RESERVE_MARKER }
            .groupBy { it.stationId!! }
            .mapValues { (_, l) -> Math.round(l.map { it.rate }.average() * 100.0) / 100.0 }
        val changedStations = stationDao.getAll().mapNotNull { s ->
            val (avg, count) = averages[s.stationId] ?: (0.0 to 0)
            val quality = HybridMileageEngine.rate(avg).name
            val avgRate = avgRates[s.stationId] ?: s.lastFuelRate
            if (avg == s.averageMileage && quality == s.qualityRating && count == s.ratedCycles && avgRate == s.avgFuelRate) null
            else s.copy(averageMileage = avg, qualityRating = quality, ratedCycles = count, avgFuelRate = avgRate)
        }
        if (changedStations.isNotEmpty()) stationDao.updateAll(changedStations)

        val lifetime = HybridMileageEngine.lifetime(result.cycles)
        _engineState.value = EngineState(
            result.pendingReserveOdometer, result.openAnchorMode, result.trendKmpl,
            lifetimeKmpl = lifetime?.first, lifetimeKm = lifetime?.second ?: 0.0
        )
        // The tank / range / Fuel-card mileage is the lifetime figure: hand it to the fuel model.
        onLifetimeMileage?.invoke(lifetime?.first ?: 0.0, lifetime?.second ?: 0.0)
    }

    // ---- backup / reset ----
    suspend fun exportStations() = stationDao.getAll()
    suspend fun exportLogs() = logDao.getAllChronological()

    suspend fun restore(stations: List<FuelStationEntity>?, logs: List<RefuelLogEntity>?) = mutex.withLock {
        if (stations != null) { stationDao.clearAll(); stationDao.insertAll(stations) }
        if (logs != null) { logDao.clearAll(); logDao.insertAll(logs) }
        reevaluateLocked()
    }

    suspend fun clearAll() = mutex.withLock {
        stationDao.clearAll()
        logDao.clearAll()
        _engineState.value = EngineState()
    }

    companion object {
        private const val TAG = "FuelStationRepository"
        const val STATION_MATCH_RADIUS_M = 40.0

        @Volatile private var instance: FuelStationRepository? = null
        fun getInstance(context: Context): FuelStationRepository =
            instance ?: synchronized(this) {
                instance ?: FuelStationRepository(context.applicationContext).also { instance = it }
            }
    }
}
