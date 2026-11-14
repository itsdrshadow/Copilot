package com.antigravity.drivingreport

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.antigravity.drivingreport.core.database.FuelEventEntity
import com.antigravity.drivingreport.core.database.FuelLedgerEntity
import com.antigravity.drivingreport.core.database.TripEntity
import com.antigravity.drivingreport.core.database.TripPointEntity
import com.antigravity.drivingreport.core.fuel.FuelRepository
import com.antigravity.drivingreport.core.fuel.intelligence.MissedRefuelDetectionResult
import com.antigravity.drivingreport.core.location.SpeedLimitEngine
import com.antigravity.drivingreport.core.location.SpeedLimitInfo
import com.antigravity.drivingreport.core.model.AppDrawerItem
import com.antigravity.drivingreport.core.model.FuelState
import com.antigravity.drivingreport.core.model.TripPoint
import com.antigravity.drivingreport.core.model.TripSummary
import com.antigravity.drivingreport.core.trip.TripRepository
import com.antigravity.drivingreport.feature.launcher.LauncherManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MainViewModel - Centralized state holder for Automotive Cockpit & Launcher.
 * Follows modern Android Architecture guidelines (UDF: Unidirectional Data Flow).
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val tripRepository = TripRepository.getInstance(application)
    private val fuelRepository = FuelRepository.getInstance(application)
    private val speedLimitEngine = SpeedLimitEngine.getInstance()
    private val launcherManager = LauncherManager(application)

    // Core Domain Streams
    val currentTrip: StateFlow<TripSummary> = tripRepository.currentTrip
    val tripVisualState = tripRepository.tripVisualState
    val latestPoint: StateFlow<TripPoint?> = tripRepository.latestPoint
    val tripTrail: StateFlow<List<TripPoint>> = tripRepository.tripTrail
    val qualifyingTrips: Flow<List<TripEntity>> = tripRepository.qualifyingTrips
    val lastThreeTrips: Flow<List<TripEntity>> = tripRepository.lastThreeTrips
    val fuelState: StateFlow<FuelState> = fuelRepository.fuelState
    val fuelHistory: Flow<List<FuelEventEntity>> = fuelRepository.fuelHistory
    val fuelLedgerHistory: Flow<List<FuelLedgerEntity>> = fuelRepository.fuelLedgerHistory
    val provisionalInferredEvents: Flow<List<FuelLedgerEntity>> = fuelRepository.provisionalInferredEvents
    val refuelPromptEvent: StateFlow<FuelRepository.RefuelPromptData?> = fuelRepository.refuelPromptEvent
    val missedRefuelAlert: StateFlow<MissedRefuelDetectionResult?> = fuelRepository.missedRefuelAlert
    val speedLimitInfo: StateFlow<SpeedLimitInfo> = speedLimitEngine.speedLimitInfo
    val vehicleStateMetrics = tripRepository.vehicleStateMetrics

    // UI Navigation & Dialog States
    private val _selectedTab = MutableStateFlow(0) // 0: Cockpit, 1: Full Map, 2: Trips, 3: Fuel Records
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isAppDrawerOpen = MutableStateFlow(false)
    val isAppDrawerOpen: StateFlow<Boolean> = _isAppDrawerOpen.asStateFlow()

    private val _isFuelDialogOpen = MutableStateFlow(false)
    val isFuelDialogOpen: StateFlow<Boolean> = _isFuelDialogOpen.asStateFlow()

    private val _isFuelHistoryOpen = MutableStateFlow(false)
    val isFuelHistoryOpen: StateFlow<Boolean> = _isFuelHistoryOpen.asStateFlow()

    private val _isSettingsDialogOpen = MutableStateFlow(false)
    val isSettingsDialogOpen: StateFlow<Boolean> = _isSettingsDialogOpen.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppDrawerItem>>(emptyList())
    val installedApps: StateFlow<List<AppDrawerItem>> = _installedApps.asStateFlow()

    init {
        // Observe latest location point for real-time speed limit updates
        viewModelScope.launch {
            latestPoint.collect { point ->
                speedLimitEngine.updateTelemetry(point)
            }
        }
        loadInstalledApps()
    }

    fun selectTab(tabIndex: Int) {
        _selectedTab.value = tabIndex
    }

    fun setAppDrawerOpen(isOpen: Boolean) {
        _isAppDrawerOpen.value = isOpen
        if (isOpen && _installedApps.value.isEmpty()) {
            loadInstalledApps()
        }
    }

    fun setFuelDialogOpen(isOpen: Boolean) {
        _isFuelDialogOpen.value = isOpen
    }

    fun setFuelHistoryOpen(isOpen: Boolean) {
        _isFuelHistoryOpen.value = isOpen
        if (isOpen) {
            selectTab(3)
        } else if (_selectedTab.value == 3) {
            selectTab(0)
        }
    }

    fun setSettingsDialogOpen(isOpen: Boolean) {
        _isSettingsDialogOpen.value = isOpen
    }

    fun loadInstalledApps() {
        viewModelScope.launch {
            _installedApps.value = launcherManager.getInstalledApps()
        }
    }

    fun launchApp(packageName: String, activityName: String) {
        launcherManager.launchApp(packageName, activityName)
    }

    fun launchNavigation() {
        launcherManager.launchNavigation()
    }

    fun syncOdometer(recordedDistanceKm: Double) {
        fuelRepository.syncOdometerDistance(recordedDistanceKm)
    }

    fun recordRefuel(
        litersAdded: Double,
        midBarObserved: Int,
        pricePerLiter: Double,
        totalCost: Double,
        odometerKm: Double,
        locationName: String,
        latitude: Double,
        longitude: Double,
        recordedLifetimeKm: Double
    ) {
        fuelRepository.recordRefuel(
            litersAdded = litersAdded,
            midBarObserved = midBarObserved,
            pricePerLiter = pricePerLiter,
            totalCost = totalCost,
            odometerKm = odometerKm,
            locationName = locationName,
            latitude = latitude,
            longitude = longitude,
            recordedLifetimeKm = recordedLifetimeKm
        )
    }

    fun calibrateMidBar(bar: Int) {
        fuelRepository.calibrateMidBar(bar)
    }

    fun calibrateOdometer(odometerKm: Double, recordedLifetimeKm: Double) {
        fuelRepository.calibrateOdometer(odometerKm, recordedLifetimeKm)
    }

    fun updateFuelEvent(updatedEvent: FuelEventEntity) {
        fuelRepository.updateFuelEvent(updatedEvent)
    }

    fun deleteFuelEvent(eventId: Long) {
        fuelRepository.deleteFuelEvent(eventId)
    }

    fun deleteTrip(tripId: Long) {
        tripRepository.deleteTrip(tripId)
    }

    fun updateVehicleConfig(designCapacity: Double, defaultMileage: Double) {
        fuelRepository.updateVehicleConfig(designCapacity, defaultMileage)
    }

    fun dismissRefuelPrompt() {
        fuelRepository.dismissRefuelPrompt()
    }

    fun dismissMissedRefuelAlert() {
        fuelRepository.dismissMissedRefuelAlert()
    }

    fun resolveInferredRefuel(
        inferredEventId: Long,
        confirmedLiters: Double,
        pricePerLiter: Double,
        totalAmount: Double,
        fuelStation: String = ""
    ) {
        fuelRepository.resolveInferredRefuel(inferredEventId, confirmedLiters, pricePerLiter, totalAmount, fuelStation)
    }

    suspend fun getPointsForTrip(tripId: Long): List<TripPointEntity> {
        return tripRepository.getPointsForTrip(tripId)
    }

    suspend fun getTransitionsForTrip(tripId: Long, startTime: Long = 0L, endTime: Long = 0L): List<com.antigravity.drivingreport.core.database.VehicleStateTransitionEntity> {
        return tripRepository.getTransitionsForTrip(tripId, startTime, endTime)
    }

    suspend fun getFuelEventsForTrip(startTime: Long, endTime: Long): List<com.antigravity.drivingreport.core.database.FuelLedgerEntity> {
        return fuelRepository.getFuelEventsBetween(startTime, endTime)
    }

    fun updateVehicleConfiguration(tankCapacity: Double, expectedMileage: Double) {
        fuelRepository.updateVehicleConfiguration(tankCapacity, expectedMileage)
    }

    fun resetAllData() {
        viewModelScope.launch {
            com.antigravity.drivingreport.core.persistence.FullFactoryResetManager.executeFactoryReset(getApplication())
        }
    }
}
