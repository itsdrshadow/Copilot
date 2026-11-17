package com.antigravity.copilot.data.repository

import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.domain.repository.FuelLogRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

class FuelLogRepositoryImpl @Inject constructor(
    private val fuel: FuelRepository
) : FuelLogRepository {
    override val fuelState: StateFlow<FuelState> get() = fuel.fuelState
    override val fuelEvents: Flow<List<FuelEventEntity>> get() = fuel.fuelHistory
    override suspend fun getLedgerEventsBetween(startTime: Long, endTime: Long): List<FuelLedgerEntity> =
        fuel.getFuelEventsBetween(startTime, endTime)
    override fun updateFuelEvent(event: FuelEventEntity) = fuel.updateFuelEvent(event)
    override fun deleteFuelEvent(eventId: Long) = fuel.deleteFuelEvent(eventId)
}
