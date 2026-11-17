package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.components.Raahi
import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import com.antigravity.copilot.core.location.SpeedLimitInfo
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.TripVisualState
import com.antigravity.copilot.feature.maps.LiveMapPanel

/**
 * Raahi Drive screen: map in the centre, ~416 dp panel on the right with the trip pill (trip
 * time) + clock, Speed card (dial, distance, odometer) and Fuel card. Trip stats rotate on the map (top-right); the music
 * player is one tap away (clock pill). Navigation lives in the left rail.
 */
@Composable
fun DashboardScreen(
    currentTrip: TripSummary,
    /**
     * Location is passed as providers, not values: the GPS stream is then read only by the leaves
     * that show it (map controller, speed card), never recomposing this whole screen.
     */
    latestPoint: () -> TripPoint?,
    tripTrail: () -> List<TripPoint>,
    fuelState: FuelState,
    allTrips: List<TripEntity>,
    @Suppress("UNUSED_PARAMETER") lastThreeTrips: List<TripEntity> = emptyList(),
    tripVisualState: TripVisualState = TripVisualState(),
    @Suppress("UNUSED_PARAMETER") selectedNavIndex: Int,
    speedLimitInfo: SpeedLimitInfo = SpeedLimitInfo(),
    @Suppress("UNUSED_PARAMETER") onNavItemSelected: (Int) -> Unit,
    onOpenFuelDialog: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenFuelHistory: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenAppDrawer: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onLaunchNavi: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onLaunchSettings: () -> Unit,
    onToggleFullMap: () -> Unit,
    onCalibrateMidBar: (Double) -> Unit = {},
    onDismissFuelNotice: () -> Unit = {},
    isMapFullScreen: Boolean = false,
    isCovered: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeTripDistanceKm = if (currentTrip.isActive()) currentTrip.distanceKm else 0.0
    val totalLifetimeKm = remember(allTrips, activeTripDistanceKm) {
        allTrips.sumOf { it.distanceMeters } / 1000.0 + activeTripDistanceKm
    }
    val fallbackMileage = when {
        fuelState.currentMileageKmpl > 0.0 -> fuelState.currentMileageKmpl
        fuelState.expectedMileageKmpl > 0.0 -> fuelState.expectedMileageKmpl
        else -> 18.0
    }
    var showMedia by remember { mutableStateOf(false) }
    // The map reads the trip through these, so trip updates recompose only the ticker chip.
    val tripState = rememberUpdatedState(currentTrip)
    val mileageState = rememberUpdatedState(fallbackMileage)
    val tripTicker: @Composable () -> Unit = remember {
        { TripMetricTicker(trip = { tripState.value }, baselineMileage = { mileageState.value }) }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cockpitBackground()
    ) {
        val gap = if (isMapFullScreen) 0.dp else Raahi.Gap
        // Wider right panel (map a little smaller); never more than ~42% of the screen.
        val panelWidth = minOf(Raahi.RightPanelWidth, maxWidth * 0.42f).coerceAtLeast(minOf(340.dp, maxWidth * 0.5f))
        // Short screens (small phones in landscape, or a large font scale): the panel scrolls
        // instead of squeezing cards until text clips.
        val roomy = maxHeight >= 560.dp

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(gap),
            horizontalArrangement = Arrangement.spacedBy(gap)
        ) {
            // Same composition slot in both modes, so the MapView is never recreated.
            val mapShape = if (isMapFullScreen) RectangleShape else Raahi.CardShape
            LiveMapPanel(
                latestPoint = latestPoint,
                trail = tripTrail,
                speedLimitInfo = speedLimitInfo,
                isFullScreen = isMapFullScreen,
                isCovered = isCovered,
                onToggleFullScreen = onToggleFullMap,
                topOverlay = tripTicker,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(mapShape)
                    .then(if (isMapFullScreen) Modifier else Modifier.border(Raahi.Border, CockpitTheme.colors.border, mapShape))
            )

            if (!isMapFullScreen) {
                Column(
                    modifier = Modifier
                        .width(panelWidth)
                        .fillMaxHeight()
                        .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())),
                    verticalArrangement = Arrangement.spacedBy(Raahi.Gap)
                ) {
                    DriveTopRow(
                        currentTrip = currentTrip,
                        tripVisualState = tripVisualState,
                        onOpenMedia = { showMedia = true }
                    )
                    LiveSpeedometer(
                        latestPoint = latestPoint,
                        speedLimitInfo = speedLimitInfo,
                        // Speed and Fuel share the height ~55 / 45.
                        modifier = if (roomy) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(300.dp),
                        allTrips = allTrips,
                        totalLifetimeKm = totalLifetimeKm,
                        tripDistanceKm = if (currentTrip.isActive() || currentTrip.isCompleted()) currentTrip.distanceKm else null
                    )
                    FuelTankCard(
                        fuelState = fuelState,
                        onClick = onOpenFuelDialog,
                        onCalibrateGauge = onCalibrateMidBar,
                        onDismissNotice = onDismissFuelNotice,
                        modifier = if (roomy) Modifier.fillMaxWidth().weight(0.82f) else Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    if (showMedia) {
        MediaDialog(onDismiss = { showMedia = false })
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
    totalLifetimeKm: Double,
    tripDistanceKm: Double?
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
        totalLifetimeKm = totalLifetimeKm,
        tripDistanceKm = tripDistanceKm
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
