package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripVisualState
import com.antigravity.copilot.feature.maps.LiveMapPanel
import java.text.SimpleDateFormat
import java.util.*

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
        val isVeryCompact = screenHeight < 480.dp || (isPortrait && screenWidth < 500.dp)
        val itemSpacing = if (isVeryCompact) 6.dp else 8.dp
        val dockHeight = if (isVeryCompact) 54.dp else 78.dp
        val isNarrow = screenWidth < 700.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isVeryCompact) 6.dp else 8.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (isPortrait) {
                // ==================== PORTRAIT VERTICAL COCKPIT ====================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(itemSpacing)
                ) {
                    // Map Panel (Top ~38%)
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

                    // 2x2 Telemetry Cards
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
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

            Spacer(Modifier.height(if (isVeryCompact) 4.dp else 6.dp))

            // ==================== PREMIUM BOTTOM ACTION DOCK ====================
            PremiumBottomDock(
                latestPoint = latestPoint,
                selectedTab = selectedNavIndex,
                onSelectCockpit = { onNavItemSelected(0) },
                onSelectMap = onToggleFullMap,
                onSelectTrips = { onNavItemSelected(2) },
                onSelectFuelHistory = { onNavItemSelected(3) },
                onOpenApps = onOpenAppDrawer,
                onOpenSettings = onLaunchSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(dockHeight),
                isVeryCompact = isVeryCompact,
                isNarrow = isNarrow
            )
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

@Composable
private fun PremiumBottomDock(
    @Suppress("UNUSED_PARAMETER") latestPoint: TripPoint?,
    selectedTab: Int,
    onSelectCockpit: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onSelectMap: () -> Unit = {},
    onSelectTrips: () -> Unit,
    onSelectFuelHistory: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isVeryCompact: Boolean = false,
    isNarrow: Boolean = false
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (isVeryCompact) 14.dp else 22.dp))
            .background(CockpitTheme.colors.card)
            .border(1.25.dp, CockpitTheme.colors.border, RoundedCornerShape(if (isVeryCompact) 14.dp else 22.dp))
            .padding(horizontal = if (isVeryCompact) 10.dp else 16.dp, vertical = if (isVeryCompact) 4.dp else 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Navigation items
        Row(
            modifier = if (isNarrow) Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()) else Modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isVeryCompact) 6.dp else 10.dp)
        ) {
            PremiumDockItem(
                icon = Icons.Default.Dashboard,
                label = "Cockpit",
                isSelected = selectedTab == 0,
                onClick = onSelectCockpit,
                isVeryCompact = isVeryCompact
            )
            PremiumDockItem(
                icon = Icons.Default.History,
                label = "Trip History",
                isSelected = selectedTab == 2,
                onClick = onSelectTrips,
                isVeryCompact = isVeryCompact
            )
            PremiumDockItem(
                icon = Icons.Default.LocalGasStation,
                label = "Fuel History",
                isSelected = selectedTab == 3,
                onClick = onSelectFuelHistory,
                isVeryCompact = isVeryCompact
            )
            PremiumDockItem(
                icon = Icons.Default.Apps,
                label = "All Apps",
                isSelected = false,
                onClick = onOpenApps,
                isVeryCompact = isVeryCompact
            )
            PremiumDockItem(
                icon = Icons.Default.Settings,
                label = "Settings",
                isSelected = false,
                onClick = onOpenSettings,
                isVeryCompact = isVeryCompact
            )
        }

        // Center Media Playback Pill (Local offline media player controls)
        val appSettings by com.antigravity.copilot.core.settings.AppSettingsManager.settingsState.collectAsState()
        if (appSettings.mediaPillEnabled) {
            MediaPlaybackPill(
                isVeryCompact = isVeryCompact,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // Right Telemetry: Clock (Self-contained 1-second tick to prevent parent recomposition)
        PremiumDockClock(isVeryCompact = isVeryCompact)
    }
}

@Composable
private fun PremiumDockClock(isVeryCompact: Boolean) {
    val currentTime = remember { mutableStateOf(getFormattedTime()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            currentTime.value = getFormattedTime()
        }
    }
    Text(
        text = currentTime.value,
        style = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            fontSize = if (isVeryCompact) 16.sp else 23.sp,
            letterSpacing = (-0.5).sp
        ),
        color = CockpitTheme.colors.textPrimary,
        modifier = Modifier.padding(end = if (isVeryCompact) 4.dp else 8.dp)
    )
}

@Composable
private fun PremiumDockItem(
    icon: ImageVector,
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    isVeryCompact: Boolean = false,
    accentColor: Color = CockpitTheme.colors.accent,
    badgeCount: Int = 0
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) CockpitTheme.colors.accentSoft else Color.Transparent,
        animationSpec = tween(200),
        label = "PremiumDockItemBg"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) accentColor else CockpitTheme.colors.textSecondary,
        animationSpec = tween(200),
        label = "PremiumDockItemContent"
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(if (isVeryCompact) 8.dp else 12.dp))
            .background(bgColor)
            .border(
                width = 1.2.dp,
                color = if (isSelected) accentColor.copy(alpha = 0.45f) else Color.Transparent,
                shape = RoundedCornerShape(if (isVeryCompact) 8.dp else 12.dp)
            )
            .clickable { onClick() }
            .padding(
                horizontal = if (isVeryCompact) 10.dp else 16.dp,
                vertical = if (isVeryCompact) 6.dp else 11.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (isVeryCompact) 5.dp else 8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = contentColor,
            modifier = Modifier.size(if (isVeryCompact) 16.dp else 24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                fontSize = if (isVeryCompact) 10.5.sp else 16.sp
            ),
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // Badge for due services
        if (badgeCount > 0) {
            Box(
                modifier = Modifier
                    .size(if (isVeryCompact) 16.dp else 20.dp)
                    .clip(CircleShape)
                    .background(CockpitTheme.colors.critical)
                    .padding(start = if (isVeryCompact) 2.dp else 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeCount.toString(),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        fontSize = if (isVeryCompact) 8.sp else 10.sp
                    ),
                    color = CockpitTheme.colors.onAccent
                )
            }
        }
    }
}

private fun getFormattedTime(): String {
    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
    return sdf.format(Date())
}
