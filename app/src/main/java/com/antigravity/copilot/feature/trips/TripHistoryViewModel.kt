package com.antigravity.copilot.feature.trips

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.domain.repository.FuelLogRepository
import com.antigravity.copilot.domain.repository.TripHistoryRepository
import com.antigravity.copilot.core.location.LocationNameResolver
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.trip.TripGeofenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tripHistory: TripHistoryRepository,
    private val fuelLog: FuelLogRepository
) : ViewModel() {

    val trips: StateFlow<List<TripEntity>> = tripHistory.completedTrips
        // Eager: loaded at startup (warmed by MainAppScreen) so the screen opens with data.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val fuelPricePerLiter: StateFlow<Double> = fuelLog.fuelState
        .map { it.fuelPricePerLiter }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), fuelLog.fuelState.value.fuelPricePerLiter)

    suspend fun loadPoints(tripId: Long): List<TripPointEntity> = tripHistory.getPoints(tripId)

    suspend fun loadTransitions(tripId: Long, startTime: Long, endTime: Long): List<VehicleStateTransitionEntity> =
        tripHistory.getTransitions(tripId, startTime, endTime)

    suspend fun loadFuelEvents(startTime: Long, endTime: Long): List<FuelLedgerEntity> =
        fuelLog.getLedgerEventsBetween(startTime, endTime)

    fun deleteTrip(tripId: Long) = tripHistory.deleteTrip(tripId)

    // ---- Round-trip names ----
    // Geofences make most trips "Home → Home"; name them after the farthest point reached.
    private val viaPrefs = appContext.getSharedPreferences("trip_via_names", Context.MODE_PRIVATE)
    private val _viaNames = MutableStateFlow(
        viaPrefs.all.mapNotNull { (k, v) -> k.toLongOrNull()?.let { id -> (v as? String)?.let { id to it } } }.toMap()
    )

    /** Trip id → turnaround place ("" = local loop). Missing = not resolved yet. */
    val viaNames: StateFlow<Map<Long, String>> = _viaNames.asStateFlow()
    private val viaAttempted = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            trips.collect { list -> resolveViaNames(list) }
        }
    }

    private suspend fun resolveViaNames(list: List<TripEntity>) = withContext(Dispatchers.IO) {
        val geofences = TripGeofenceManager.getInstance(appContext)
        for (trip in list) {
            if (!isRoundTrip(trip) || trip.id in _viaNames.value || !viaAttempted.add(trip.id)) continue
            val points = runCatching { tripHistory.getPoints(trip.id) }.getOrDefault(emptyList())
                .filter { it.latitude != 0.0 || it.longitude != 0.0 }
            if (points.size < 2) continue
            val origin = points.first()
            val far = points.maxByOrNull { GeoMath.haversineM(origin.latitude, origin.longitude, it.latitude, it.longitude) } ?: continue
            val reachM = GeoMath.haversineM(origin.latitude, origin.longitude, far.latitude, far.longitude)
            val name: String? = if (reachM < LOCAL_LOOP_RADIUS_M) {
                ""
            } else {
                geofences.findGeofenceContaining(far.latitude, far.longitude, onlyEnabled = false)?.name
                    ?: LocationNameResolver.resolvePlaceNameOrNull(appContext, far.latitude, far.longitude)?.let(::shortPlaceName)
            }
            // Unresolved (offline): try again next time the screen opens.
            if (name == null) continue
            val clean = if (name.equals(trip.startLocationName.trim(), ignoreCase = true)) "" else name
            viaPrefs.edit().putString(trip.id.toString(), clean).apply()
            _viaNames.update { it + (trip.id to clean) }
        }
    }

    private companion object {
        /** A round trip that never got farther than this from its start is a "local loop". */
        const val LOCAL_LOOP_RADIUS_M = 300.0
    }
}
