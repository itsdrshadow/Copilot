package com.antigravity.copilot.di

import com.antigravity.copilot.data.repository.BackupRepositoryImpl
import com.antigravity.copilot.data.repository.FuelLogRepositoryImpl
import com.antigravity.copilot.data.repository.SettingsRepositoryImpl
import com.antigravity.copilot.data.repository.TripHistoryRepositoryImpl
import com.antigravity.copilot.domain.repository.BackupRepository
import com.antigravity.copilot.domain.repository.FuelLogRepository
import com.antigravity.copilot.domain.repository.SettingsRepository
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
    @Binds abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository
}
