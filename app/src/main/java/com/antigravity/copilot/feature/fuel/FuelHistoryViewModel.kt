package com.antigravity.copilot.feature.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.domain.repository.FuelLogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FuelHistoryViewModel @Inject constructor(
    private val fuelLog: FuelLogRepository
) : ViewModel() {

    val fuelEvents: StateFlow<List<FuelEventEntity>> = fuelLog.fuelEvents
        // Eager: loaded at startup (warmed by MainAppScreen) so the screen opens with data.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun updateEvent(event: FuelEventEntity) = fuelLog.updateFuelEvent(event)

    fun deleteEvent(eventId: Long) = fuelLog.deleteFuelEvent(eventId)
}
