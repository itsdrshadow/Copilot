package com.antigravity.copilot.domain.repository

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import kotlinx.coroutines.flow.Flow

/** Read/delete access to completed trips, as needed by the trip history feature. */
interface TripHistoryRepository {
    val completedTrips: Flow<List<TripEntity>>
    suspend fun getPoints(tripId: Long): List<TripPointEntity>
    suspend fun getTransitions(tripId: Long, startTime: Long, endTime: Long): List<VehicleStateTransitionEntity>
    fun deleteTrip(tripId: Long)
}
