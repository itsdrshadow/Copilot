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
import com.antigravity.copilot.core.fuel.station.FuelStationRepository
import com.antigravity.copilot.core.fuel.station.RefuelMode
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
    private val launcherManager: LauncherManager,
    roadAnomalyManager: com.antigravity.copilot.core.road.RoadAnomalyManager,
    private val fuelStationRepository: FuelStationRepository
) : ViewModel() {

    /** Odometer locked by the dashboard "Reserve light ON" tap, until the next refuel. */
    val reserveMarkerOdometer: StateFlow<Double?> = fuelStationRepository.engineState
        .map { it.pendingReserveOdometer }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Station name handed from the pump-stop prompt to the Add Fuel dialog (null = manual open). */
    private val _refuelDialogStation = MutableStateFlow<String?>(null)
    val refuelDialogStation: StateFlow<String?> = _refuelDialogStation.asStateFlow()

    /** 150–200 m ahead pothole / breaker alert for the flash card. */
    val roadAnomalyAlert: StateFlow<com.antigravity.copilot.core.road.AnomalyAlert?> = roadAnomalyManager.aheadAlert

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
        // The speed-limit engine is fed by DrivingTrackingService on every fix (audit I1), so it
        // keeps working when this screen is not alive; feeding it here too would double-count.
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
        if (!isOpen) _refuelDialogStation.value = null
        _isFuelDialogOpen.value = isOpen
    }

    /** Pump-stop prompt accepted: open Add Fuel pre-filled with the detected station. */
    fun acceptRefuelPrompt() {
        _refuelDialogStation.value = refuelPromptEvent.value?.locationName?.takeIf { it.isNotBlank() }
        fuelRepository.dismissRefuelPrompt()
        _isFuelDialogOpen.value = true
    }

    /** Dashboard "Reserve light ON": lock the odometer now (tap again to undo). */
    fun toggleReserveLight() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            fuelStationRepository.toggleReserveLight(fuelRepository.getEffectiveOdometer())
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
        refuelMode: RefuelMode,
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
        // The station rating log is written by FuelRepository after the fuel_events row, linked
        // to its id (same odometer: typed reading, else the app's), so edits / deletes reach it.
        val confirmedStation = _refuelDialogStation.value != null
        fuelRepository.recordRefuel(
            refuelMode = refuelMode,
            confirmedStation = confirmedStation,
            fullTank = refuelMode == RefuelMode.FULL_TANK,
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
