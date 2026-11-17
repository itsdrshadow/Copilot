package com.antigravity.copilot.di

import android.content.Context
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.location.SpeedLimitEngine
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.trip.TripRepository
import com.antigravity.copilot.core.trip.VehicleStateManager
import com.antigravity.copilot.feature.launcher.LauncherManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges the existing process-wide singletons into the Hilt graph.
 *
 * `DrivingTrackingService`, broadcast receivers and workers still reach these through
 * `getInstance()`, so Hilt must hand out the very same instances rather than new ones.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getInstance(context)

    @Provides @Singleton
    fun provideTripRepository(@ApplicationContext context: Context): TripRepository = TripRepository.getInstance(context)

    @Provides @Singleton
    fun provideFuelRepository(@ApplicationContext context: Context): FuelRepository = FuelRepository.getInstance(context)

    @Provides @Singleton
    fun provideSpeedLimitEngine(@ApplicationContext context: Context): SpeedLimitEngine = SpeedLimitEngine.getInstance(context)

    @Provides @Singleton
    fun provideTripGeofenceManager(@ApplicationContext context: Context): TripGeofenceManager =
        TripGeofenceManager.getInstance(context)

    @Provides @Singleton
    fun provideVehicleStateManager(@ApplicationContext context: Context): VehicleStateManager =
        VehicleStateManager.getInstance(context)

    @Provides
    fun provideLauncherManager(@ApplicationContext context: Context): LauncherManager = LauncherManager(context)
}
