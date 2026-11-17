package com.antigravity.copilot.data.repository

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.trip.TripRepository
import com.antigravity.copilot.domain.repository.TripHistoryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class TripHistoryRepositoryImpl @Inject constructor(
    private val trips: TripRepository
) : TripHistoryRepository {
    override val completedTrips: Flow<List<TripEntity>> get() = trips.qualifyingTrips
    override suspend fun getPoints(tripId: Long): List<TripPointEntity> = trips.getPointsForTrip(tripId)
    override suspend fun getTransitions(tripId: Long, startTime: Long, endTime: Long): List<VehicleStateTransitionEntity> =
        trips.getTransitionsForTrip(tripId, startTime, endTime)
    override fun deleteTrip(tripId: Long) = trips.deleteTrip(tripId)
}
