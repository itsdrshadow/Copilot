package com.antigravity.copilot.di

import com.antigravity.copilot.data.repository.FuelLogRepositoryImpl
import com.antigravity.copilot.data.repository.TripHistoryRepositoryImpl
import com.antigravity.copilot.domain.repository.FuelLogRepository
import com.antigravity.copilot.domain.repository.TripHistoryRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds abstract fun bindTripHistoryRepository(impl: TripHistoryRepositoryImpl): TripHistoryRepository
    @Binds abstract fun bindFuelLogRepository(impl: FuelLogRepositoryImpl): FuelLogRepository
}
