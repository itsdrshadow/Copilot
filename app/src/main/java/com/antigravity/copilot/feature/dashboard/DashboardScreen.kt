package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripVisualState
import com.antigravity.copilot.feature.maps.LiveMapPanel

/**
 * Automotive Cockpit Dashboard:
 * Contemporary dark navy studio aesthetics (#080E1B), unified card design tokens (#111D30, #243750, #35BDF5),
 * and high-contrast precision telemetry layout for 1280x800 infotainment and portrait displays.
 */
@Composable
fun DashboardScreen(
    currentTrip: TripSummary,
    latestPoint: TripPoint?,
    tripTrail: List<TripPoint>,
    fuelState: FuelState,
    allTrips: List<TripEntity>,
    @Suppress("UNUSED_PARAMETER") lastThreeTrips: List<TripEntity> = emptyList(),
    tripVisualState: TripVisualState = TripVisualState(),
    selectedNavIndex: Int,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    onNavItemSelected: (Int) -> Unit,
    onOpenFuelDialog: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenFuelHistory: () -> Unit = {},
    onOpenAppDrawer: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLaunchNavi: () -> Unit = {},
    onLaunchSettings: () -> Unit,
    onToggleFullMap: () -> Unit,
    onCalibrateMidBar: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isSpeedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(latestPoint) {
        isSpeedTimedOut = false
        if (latestPoint != null && latestPoint.speedKmh > 0f) {
            kotlinx.coroutines.delay(10000L)
            isSpeedTimedOut = true
        }
    }
    val currentSpeed = if (isSpeedTimedOut || latestPoint == null) 0f else latestPoint.speedKmh

    // Memoized Lifetime Calculations (Single Source of Truth)
    val isTripActive = currentTrip.status == com.antigravity.copilot.core.model.TripStatus.ACTIVE_TRIP ||
        currentTrip.status == com.antigravity.copilot.core.model.TripStatus.MOVEMENT_CANDIDATE ||
        currentTrip.status == com.antigravity.copilot.core.model.TripStatus.TEMPORARY_STOP

    val activeTripDistanceKm = if (isTripActive) currentTrip.distanceKm else 0.0
    val activeTripDurationMs = if (isTripActive) (currentTrip.drivingDurationMs + currentTrip.stationaryDurationMs) else 0L
    val activeDrivingDurationMs = if (isTripActive) currentTrip.drivingDurationMs else 0L
    val activeTripFuelLiters = if (isTripActive && currentTrip.estimatedFuelConsumedLiters > 0.0) currentTrip.estimatedFuelConsumedLiters else 0.0

    val totalLifetimeKm = remember(allTrips, activeTripDistanceKm) {
        allTrips.sumOf { it.distanceMeters } / 1000.0 + activeTripDistanceKm
    }
    val totalTripsCount = allTrips.size
    val (totalHours, totalRemainingMin) = remember(allTrips, activeTripDurationMs) {
        val totalMs = allTrips.sumOf { it.drivingDurationMs + it.stationaryDurationMs } + activeTripDurationMs
        val totalDurationMin = totalMs / 60000L
        Pair((totalDurationMin / 60).toInt(), (totalDurationMin % 60).toInt())
    }
    val lifetimeAvgSpeed = remember(allTrips, totalLifetimeKm, activeDrivingDurationMs) {
        val totalDrivingMs = allTrips.sumOf { it.drivingDurationMs } + activeDrivingDurationMs
        if (totalDrivingMs > 60000L && totalLifetimeKm > 0.1) {
            val hours = totalDrivingMs / 3600000.0
            totalLifetimeKm / hours
        } else if (allTrips.isNotEmpty()) {
            val valid = allTrips.map { it.avgSpeedKmh }.filter { it > 0 }
            if (valid.isNotEmpty()) valid.average() else 0.0
        } else 0.0
    }
    val fallbackMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else (if (fuelState.expectedMileageKmpl > 0.0) fuelState.expectedMileageKmpl else 18.0)
    val totalLifetimeFuelLiters = remember(allTrips, activeTripFuelLiters, fallbackMileage) {
        allTrips.sumOf {
            if (it.estimatedFuelLiters > 0.0) it.estimatedFuelLiters
            else (it.distanceMeters / 1000.0) / (if (it.calculatedMileageKmpl > 0.0) it.calculatedMileageKmpl else fallbackMileage)
        } + activeTripFuelLiters
    }
    val lifetimeMileage = remember(totalLifetimeKm, totalLifetimeFuelLiters, fallbackMileage) {
        if (totalLifetimeFuelLiters > 0.05) totalLifetimeKm / totalLifetimeFuelLiters else fallbackMileage
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.bg)
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val isPortrait = screenWidth < screenHeight
        val scaling = CockpitTheme.scaling
        val isVeryCompact = screenHeight < 480.dp || (isPortrait && screenWidth < 500.dp) || scaling.mode == com.antigravity.copilot.core.designsystem.UiScaleMode.EXTRA_LARGE
        val itemSpacing = scaling.itemSpacing

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount < -25f) {
                            onOpenAppDrawer()
                        }
                    }
                }
                .padding(if (isVeryCompact) 6.dp else 8.dp)
        ) {
            if (isPortrait) {
                // ==================== PORTRAIT VERTICAL COCKPIT ====================
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Map Panel (Top ~40%)
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.05f)
                            .clip(RoundedCornerShape(if (isVeryCompact) 14.dp else 18.dp))
                    )

                    // 2x2 Telemetry Cards (Bottom ~60%)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1.5f),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Row 1: Speedometer + Fuel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            SpeedometerCluster(
                                speedKmh = currentSpeed,
                                speedLimitInfo = speedLimitInfo,
                                latestPoint = latestPoint,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = true,
                                allTrips = allTrips
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = true
                            )
                        }

                        // Row 2: Trip + Odometer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            CurrentTripCard(
                                currentTrip = currentTrip,
                                tripVisualState = tripVisualState,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = true,
                                baselineMileage = fallbackMileage
                            )
                            OdometerCard(
                                totalLifetimeKm = totalLifetimeKm,
                                totalLifetimeFuelLiters = totalLifetimeFuelLiters,
                                totalTripsCount = totalTripsCount,
                                totalHours = totalHours,
                                totalRemainingMin = totalRemainingMin,
                                lifetimeAvgSpeed = lifetimeAvgSpeed,
                                lifetimeMileage = lifetimeMileage,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = true
                            )
                        }
                    }
                }
            } else {
                // ==================== LANDSCAPE AUTOMOTIVE COCKPIT ====================
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Left Pane: Live Map (~52%)
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .weight(1.08f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(if (isVeryCompact) 14.dp else 18.dp))
                    )

                    // Right Pane: 2x2 Telemetry Cards (~48%)
                    Column(
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Row 1: Speedometer + Fuel
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            SpeedometerCluster(
                                speedKmh = currentSpeed,
                                speedLimitInfo = speedLimitInfo,
                                latestPoint = latestPoint,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = isVeryCompact,
                                allTrips = allTrips
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = isVeryCompact
                            )
                        }

                        // Row 2: Trip + Odometer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            CurrentTripCard(
                                currentTrip = currentTrip,
                                tripVisualState = tripVisualState,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = isVeryCompact,
                                baselineMileage = fallbackMileage
                            )
                            OdometerCard(
                                totalLifetimeKm = totalLifetimeKm,
                                totalLifetimeFuelLiters = totalLifetimeFuelLiters,
                                totalTripsCount = totalTripsCount,
                                totalHours = totalHours,
                                totalRemainingMin = totalRemainingMin,
                                lifetimeAvgSpeed = lifetimeAvgSpeed,
                                lifetimeMileage = lifetimeMileage,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                isVeryCompact = isVeryCompact
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Backward-compatibility alias for PremiumDashboardScreen.
 */
@Composable
fun PremiumDashboardScreen(
    currentTrip: TripSummary,
    latestPoint: TripPoint?,
    tripTrail: List<TripPoint>,
    fuelState: FuelState,
    allTrips: List<TripEntity>,
    lastThreeTrips: List<TripEntity> = emptyList(),
    tripVisualState: TripVisualState = TripVisualState(),
    selectedNavIndex: Int,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    onNavItemSelected: (Int) -> Unit,
    onOpenFuelDialog: () -> Unit,
    onOpenFuelHistory: () -> Unit = {},
    onOpenAppDrawer: () -> Unit,
    onLaunchNavi: () -> Unit = {},
    onLaunchSettings: () -> Unit,
    onToggleFullMap: () -> Unit,
    onCalibrateMidBar: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) = DashboardScreen(
    currentTrip = currentTrip,
    latestPoint = latestPoint,
    tripTrail = tripTrail,
    fuelState = fuelState,
    allTrips = allTrips,
    lastThreeTrips = lastThreeTrips,
    tripVisualState = tripVisualState,
    selectedNavIndex = selectedNavIndex,
    speedLimitInfo = speedLimitInfo,
    onNavItemSelected = onNavItemSelected,
    onOpenFuelDialog = onOpenFuelDialog,
    onOpenFuelHistory = onOpenFuelHistory,
    onOpenAppDrawer = onOpenAppDrawer,
    onLaunchNavi = onLaunchNavi,
    onLaunchSettings = onLaunchSettings,
    onToggleFullMap = onToggleFullMap,
    onCalibrateMidBar = onCalibrateMidBar,
    modifier = modifier
)
