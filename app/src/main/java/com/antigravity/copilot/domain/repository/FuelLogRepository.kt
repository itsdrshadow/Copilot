package com.antigravity.copilot.domain.repository

import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.model.FuelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Fuel log access for fuel history and trip-replay features. */
interface FuelLogRepository {
    val fuelState: StateFlow<FuelState>
    val fuelEvents: Flow<List<FuelEventEntity>>
    suspend fun getLedgerEventsBetween(startTime: Long, endTime: Long): List<FuelLedgerEntity>
    fun updateFuelEvent(event: FuelEventEntity)
    fun deleteFuelEvent(eventId: Long)
}
