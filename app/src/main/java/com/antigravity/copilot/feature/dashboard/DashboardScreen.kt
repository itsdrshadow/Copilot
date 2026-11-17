package com.antigravity.copilot.feature.dashboard

import com.antigravity.copilot.core.designsystem.components.liquidGlassPill
import com.antigravity.copilot.core.designsystem.components.liquidGlass
import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.unit.sp
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
    /**
     * Location is passed as providers, not values: the 5 Hz GPS stream is then read only by the
     * leaves that show it (map controller, speedometer), never recomposing this whole screen.
     */
    latestPoint: () -> TripPoint?,
    tripTrail: () -> List<TripPoint>,
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
    onCalibrateMidBar: (Double) -> Unit = {},
    onDismissFuelNotice: () -> Unit = {},
    isMapFullScreen: Boolean = false,
    isCovered: Boolean = false,
    modifier: Modifier = Modifier
) {
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
            .cockpitBackground()
    ) {
        val screenHeight = maxHeight
        val screenWidth = maxWidth
        val isPortrait = screenWidth < screenHeight
        val itemSpacing = if (isMapFullScreen) 0.dp else AutomotiveDesignTokens.Spacing.extraSmall
        val mapShape = if (isMapFullScreen) androidx.compose.ui.graphics.RectangleShape else RoundedCornerShape(AutomotiveDesignTokens.Radii.container)

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
                    // Map Panel (Top 50%, or everything in full-screen map mode). It stays in this
                    // composition slot in both modes so the MapView is never recreated.
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        isFullScreen = isMapFullScreen,
                        isCovered = isCovered,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(if (isMapFullScreen) 1f else 0.50f)
                            .clip(mapShape)
                    )

                    // Telemetry Area (Bottom 50%)
                    if (!isMapFullScreen) Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.50f),
                        verticalArrangement = Arrangement.spacedBy(itemSpacing)
                    ) {
                        // Row 1: Speedometer + Fuel Tank
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(itemSpacing)
                        ) {
                            LiveSpeedometer(
                                latestPoint = latestPoint,
                                speedLimitInfo = speedLimitInfo,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allTrips = allTrips,
                                totalLifetimeKm = totalLifetimeKm
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                onDismissNotice = onDismissFuelNotice,
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
                    // Left Pane: Live Map (50%, or everything in full-screen map mode). Same
                    // composition slot in both modes so the MapView is never recreated.
                    LiveMapPanel(
                        latestPoint = latestPoint,
                        trail = tripTrail,
                        speedLimitInfo = speedLimitInfo,
                        isFullScreen = isMapFullScreen,
                        isCovered = isCovered,
                        onToggleFullScreen = onToggleFullMap,
                        modifier = Modifier
                            .weight(if (isMapFullScreen) 1f else 0.50f)
                            .fillMaxHeight()
                            .clip(mapShape)
                    )

                    // Right Pane: Telemetry Area (50%)
                    if (!isMapFullScreen) Column(
                        modifier = Modifier
                            .weight(0.50f)
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
                            LiveSpeedometer(
                                latestPoint = latestPoint,
                                speedLimitInfo = speedLimitInfo,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                allTrips = allTrips,
                                totalLifetimeKm = totalLifetimeKm
                            )
                            FuelTankCard(
                                fuelState = fuelState,
                                onClick = onOpenFuelDialog,
                                onCalibrateGauge = onCalibrateMidBar,
                                onDismissNotice = onDismissFuelNotice,
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
            if (!isMapFullScreen) CockpitDock(
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
    val dockContainerShape = RoundedCornerShape(AutomotiveDesignTokens.Radii.container + 6.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (CockpitTheme.isGlass) Modifier.liquidGlass(dockContainerShape)
                else Modifier.cockpitSurface(CockpitTheme.colors.dockBrush, CockpitTheme.colors.dockBorder, dockContainerShape, borderWidth = 1.25.dp)
            )
            .padding(6.dp)
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
}

@Composable
private fun DockAction(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(AutomotiveDesignTokens.Radii.container)
    val dock = AutomotiveDesignTokens.Dock
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "DockActionScale"
    )

    val dockActionBg = if (isPressed) {
        androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accentSoft)
        )
    } else {
        CockpitTheme.colors.dockActionBrush
    }

    Row(
        modifier = modifier
            .height(dock.height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (CockpitTheme.isGlass) Modifier.liquidGlassPill(shape, CockpitTheme.colors.accent, active = isPressed)
                else Modifier.cockpitSurface(dockActionBg, if (isPressed) CockpitTheme.colors.accent else CockpitTheme.colors.border.copy(alpha = 0.45f), shape, borderWidth = 1.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = true, color = CockpitTheme.colors.accent),
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = dock.pillPaddingHorizontal, vertical = dock.pillPaddingVertical),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.5f), CircleShape, borderWidth = 1.2.dp),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = CockpitTheme.colors.accent,
                modifier = Modifier.size(dock.iconSize)
            )
        }
        Text(
            text = label,
            color = CockpitTheme.colors.textPrimary,
            style = CockpitTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Speedometer leaf that owns the location read, so a GPS tick recomposes only this subtree.
 * The 10 s timeout zeroes the speed when fixes stop arriving while moving.
 */
@Composable
private fun LiveSpeedometer(
    latestPoint: () -> TripPoint?,
    speedLimitInfo: SpeedLimitInfo,
    modifier: Modifier,
    allTrips: List<TripEntity>,
    totalLifetimeKm: Double
) {
    val point = latestPoint()
    var isSpeedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(point) {
        isSpeedTimedOut = false
        if (point != null && point.speedKmh > 0f) {
            kotlinx.coroutines.delay(10000L)
            isSpeedTimedOut = true
        }
    }
    SpeedometerCluster(
        speedKmh = if (isSpeedTimedOut || point == null) 0f else point.speedKmh,
        speedLimitInfo = speedLimitInfo,
        latestPoint = point,
        modifier = modifier,
        allTrips = allTrips,
        totalLifetimeKm = totalLifetimeKm
    )
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
    onCalibrateMidBar: (Double) -> Unit = {},
    onDismissFuelNotice: () -> Unit = {},
    modifier: Modifier = Modifier
) = DashboardScreen(
    currentTrip = currentTrip,
    latestPoint = { latestPoint },
    tripTrail = { tripTrail },
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
    onDismissFuelNotice = onDismissFuelNotice,
    modifier = modifier
)
