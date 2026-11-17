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
 * Raahi Drive screen: map in the centre, ~372 dp panel on the right with the trip pill + clock,
 * Speed card, Fuel card and Trip strip. Same data and callbacks as before; the trip card and
 * music player are one tap away (trip pill / clock pill). Navigation lives in the left rail.
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
    var showTripDetails by remember { mutableStateOf(false) }
    var showMedia by remember { mutableStateOf(false) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cockpitBackground()
    ) {
        val gap = if (isMapFullScreen) 0.dp else Raahi.Gap
        val panelWidth = minOf(Raahi.RightPanelWidth, maxWidth * 0.5f)
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
                        onOpenTripDetails = { showTripDetails = true },
                        onOpenMedia = { showMedia = true }
                    )
                    LiveSpeedometer(
                        latestPoint = latestPoint,
                        speedLimitInfo = speedLimitInfo,
                        modifier = if (roomy) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(250.dp),
                        allTrips = allTrips,
                        totalLifetimeKm = totalLifetimeKm
                    )
                    FuelTankCard(
                        fuelState = fuelState,
                        onClick = onOpenFuelDialog,
                        onCalibrateGauge = onCalibrateMidBar,
                        onDismissNotice = onDismissFuelNotice,
                        modifier = Modifier.fillMaxWidth()
                    )
                    DriveTripStrip(
                        currentTrip = currentTrip,
                        totalLifetimeKm = totalLifetimeKm,
                        onOpenTripDetails = { showTripDetails = true }
                    )
                }
            }
        }
    }

    if (showTripDetails) {
        TripDetailsDialog(
            currentTrip = currentTrip,
            tripVisualState = tripVisualState,
            baselineMileage = fallbackMileage,
            onDismiss = { showTripDetails = false }
        )
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
