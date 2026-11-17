package com.antigravity.copilot

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.lifecycle.viewModelScope
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.fuel.intelligence.MissedRefuelDetectionResult
import com.antigravity.copilot.core.location.SpeedLimitEngine
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.model.AppDrawerItem
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripRepository
import com.antigravity.copilot.feature.launcher.LauncherManager
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MainViewModel - Centralized state holder for Automotive Cockpit & Launcher.
 * Follows modern Android Architecture guidelines (UDF: Unidirectional Data Flow).
 */
private const val UI_TELEMETRY_INTERVAL_MS = 200L

/** Emit immediately, then at most once per [periodMs], always delivering the latest value. */
internal fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> =
    conflate().transform {
        emit(it)
        kotlinx.coroutines.delay(periodMs)
    }

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val tripRepository: TripRepository,
    private val fuelRepository: FuelRepository,
    private val speedLimitEngine: SpeedLimitEngine,
    private val launcherManager: LauncherManager
) : ViewModel() {

    // Core Domain Streams
    val currentTrip: StateFlow<TripSummary> = tripRepository.currentTrip
    val tripVisualState = tripRepository.tripVisualState
    /**
     * UI-facing position, capped at [UI_TELEMETRY_INTERVAL_MS] (5 Hz) so a burst of fused/raw GPS
     * fixes can never cause a recomposition storm. Latest value always wins (conflated).
     */
    val latestPoint: StateFlow<TripPoint?> = tripRepository.latestPoint
        .throttleLatest(UI_TELEMETRY_INTERVAL_MS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), tripRepository.latestPoint.value)
    val tripTrail: StateFlow<List<TripPoint>> = tripRepository.tripTrail
    val qualifyingTrips: Flow<List<TripEntity>> = tripRepository.qualifyingTrips
    val lastThreeTrips: Flow<List<TripEntity>> = tripRepository.lastThreeTrips
    val fuelState: StateFlow<FuelState> = fuelRepository.fuelState
    val fuelLedgerHistory: Flow<List<FuelLedgerEntity>> = fuelRepository.fuelLedgerHistory
    val provisionalInferredEvents: Flow<List<FuelLedgerEntity>> = fuelRepository.provisionalInferredEvents
    val refuelPromptEvent: StateFlow<FuelRepository.RefuelPromptData?> = fuelRepository.refuelPromptEvent
    val missedRefuelAlert: StateFlow<MissedRefuelDetectionResult?> = fuelRepository.missedRefuelAlert
    val speedLimitInfo: StateFlow<SpeedLimitInfo> = speedLimitEngine.speedLimitInfo
    val vehicleStateMetrics = tripRepository.vehicleStateMetrics
    val lastCompletedTrip: StateFlow<TripEntity?> = tripRepository.lastCompletedTrip
    val lastCompletedTripId: StateFlow<Long?> = tripRepository.lastCompletedTripId

    // UI Navigation & Dialog States
    private val _selectedTab = MutableStateFlow(0) // 0: Cockpit, 1: Full Map, 2: Trips, 3: Fuel Records
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _isAppDrawerOpen = MutableStateFlow(false)
    val isAppDrawerOpen: StateFlow<Boolean> = _isAppDrawerOpen.asStateFlow()

    private val _isFuelDialogOpen = MutableStateFlow(false)
    val isFuelDialogOpen: StateFlow<Boolean> = _isFuelDialogOpen.asStateFlow()

    private val _isSettingsDialogOpen = MutableStateFlow(false)
    val isSettingsDialogOpen: StateFlow<Boolean> = _isSettingsDialogOpen.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppDrawerItem>>(emptyList())
    val installedApps: StateFlow<List<AppDrawerItem>> = _installedApps.asStateFlow()

    init {
        // Observe latest location point for real-time speed limit updates
        viewModelScope.launch {
            // Speed-limit engine needs every fix: read the unthrottled repository stream.
            tripRepository.latestPoint.collect { point ->
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
        fullTank: Boolean,
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
            fullTank = fullTank,
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

    /** Gauge calibration popup (half points allowed). */
    fun calibrateGaugePoint(point: Double) {
        fuelRepository.calibrateMidBar(point)
    }

    fun updateVehicleConfig(designCapacity: Double, defaultMileage: Double) {
        fuelRepository.updateVehicleConfig(designCapacity, defaultMileage)
    }

    fun dismissFuelNotice() {
        fuelRepository.dismissFuelNotice()
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

    fun updateVehicleConfiguration(tankCapacity: Double, expectedMileage: Double) {
        fuelRepository.updateVehicleConfiguration(tankCapacity, expectedMileage)
    }

    fun resetAllData() {
        viewModelScope.launch {
            com.antigravity.copilot.core.persistence.FullFactoryResetManager.executeFactoryReset(appContext)
        }
    }
}
