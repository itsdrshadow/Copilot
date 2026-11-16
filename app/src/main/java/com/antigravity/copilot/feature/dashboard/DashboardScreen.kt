package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
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
    onOpenFuelHistory: () -> Unit = {},
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

    val totalLifetimeKm = remember(allTrips, activeTripDistanceKm) {
        allTrips.sumOf { it.distanceMeters } / 1000.0 + activeTripDistanceKm
    }
    val fallbackMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else (if (fuelState.expectedMileageKmpl > 0.0) fuelState.expectedMileageKmpl else 18.0)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.backgroundBrush)
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val isPortrait = screenWidth < screenHeight
        val itemSpacing = AutomotiveDesignTokens.Spacing.extraSmall

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(itemSpacing),
            verticalArrangement = Arrangement.spacedBy(itemSpacing)
        ) {
            if (isPortrait) {
                // ==================== PORTRAIT VERTICAL COCKPIT ====================
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Map Panel (Top 45%)
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.45f)
                            .clip(RoundedCornerShape(AutomotiveDesignTokens.Radii.container))
                    )

                    // Telemetry Area (Bottom 55%)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.55f),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Row 1: Speedometer + Fuel Tank
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
                                allTrips = allTrips,
                                totalLifetimeKm = totalLifetimeKm
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }

                        // Row 2: Current Trip + Music Player / Permanent Clock
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
                                baselineMileage = fallbackMileage
                            )
                            SmartMusicClockCard(
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            } else {
                // ==================== LANDSCAPE AUTOMOTIVE COCKPIT ====================
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Left Pane: Live Map (45%)
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .weight(0.45f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(AutomotiveDesignTokens.Radii.container))
                    )

                    // Right Pane: Telemetry Area (55%)
                    Column(
                        modifier = Modifier
                            .weight(0.55f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Row 1: Speedometer + Fuel Tank
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
                                allTrips = allTrips,
                                totalLifetimeKm = totalLifetimeKm
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }

                        // Row 2: Current Trip + Music Player / Permanent Clock
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
                                baselineMileage = fallbackMileage
                            )
                            SmartMusicClockCard(
                                modifier = Modifier.weight(1f).fillMaxHeight()
                            )
                        }
                    }
                }
            }
            CockpitDock(
                onOpenTrips = { onNavItemSelected(2) },
                onOpenFuel = onOpenFuelHistory,
                onOpenApps = onOpenAppDrawer,
                onOpenSettings = onLaunchSettings
            )
        }
    }
}

@Composable
private fun CockpitDock(
    onOpenTrips: () -> Unit,
    onOpenFuel: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        DockAction("Trips", Icons.Default.History, onOpenTrips, Modifier.weight(1f))
        DockAction("Fuels", Icons.Default.LocalGasStation, onOpenFuel, Modifier.weight(1f))
        DockAction("Apps", Icons.Default.Apps, onOpenApps, Modifier.weight(1f))
        DockAction("Settings", Icons.Default.Settings, onOpenSettings, Modifier.weight(1f))
    }
}

@Composable
private fun DockAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.container)
    val dock = AutomotiveDesignTokens.Dock
    Row(
        modifier = modifier
            .height(dock.height)
            .clip(shape)
            .background(CockpitTheme.colors.cardElevated)
            .border(1.25.dp, CockpitTheme.colors.border, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = dock.pillPaddingHorizontal, vertical = dock.pillPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = CockpitTheme.colors.accent, modifier = Modifier.size(dock.iconSize))
        Text(
            text = label,
            color = CockpitTheme.colors.textPrimary,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = dock.textSize),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
