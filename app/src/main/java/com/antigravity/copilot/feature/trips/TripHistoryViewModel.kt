package com.antigravity.copilot.feature.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.domain.repository.FuelLogRepository
import com.antigravity.copilot.domain.repository.TripHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class TripHistoryViewModel @Inject constructor(
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
}
