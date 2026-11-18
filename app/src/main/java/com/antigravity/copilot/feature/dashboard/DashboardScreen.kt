package com.antigravity.copilot.feature.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import com.antigravity.copilot.core.designsystem.CopilotWindowClass
import com.antigravity.copilot.core.designsystem.LocalCopilotWindowClass
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
 * Drive screen, adapted to [LocalCopilotWindowClass]:
 * - Expanded (head unit): map left half, 2x2 card grid right half
 *   (Speedometer | Fuel on top, Current trip | Music + clock below).
 * - Medium: map 60 % | one scrolling column of the same cards 40 %.
 * - Compact (phone): full-bleed map with a collapsible card sheet along the bottom.
 * The map has no trip overlay; trip stats live in the Current trip card.
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
    reserveMarkerOdometer: Double? = null,
    onToggleReserveLight: () -> Unit = {},
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

    val windowClass = LocalCopilotWindowClass.current
    val tripDistanceKm = if (currentTrip.isActive() || currentTrip.isCompleted()) currentTrip.distanceKm else null

    // Each card is defined once and placed by whichever layout the window class picks.
    val cards: List<Pair<String, @Composable (Modifier) -> Unit>> = listOf(
        "speed" to { m ->
            LiveSpeedometer(
                latestPoint = latestPoint,
                speedLimitInfo = speedLimitInfo,
                modifier = m,
                allTrips = allTrips,
                totalLifetimeKm = totalLifetimeKm,
                tripDistanceKm = tripDistanceKm
            )
        },
        "fuel" to { m ->
            FuelTankCard(
                fuelState = fuelState,
                onClick = onOpenFuelDialog,
                onCalibrateGauge = onCalibrateMidBar,
                onDismissNotice = onDismissFuelNotice,
                reserveMarkerOdometer = reserveMarkerOdometer,
                onToggleReserveLight = onToggleReserveLight,
                modifier = m
            )
        },
        "trip" to { m ->
            CurrentTripCard(
                currentTrip = currentTrip,
                tripVisualState = tripVisualState,
                modifier = m,
                baselineMileage = fallbackMileage
            )
        },
        "music" to { m -> SmartMusicClockCard(modifier = m) }
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .cockpitBackground()
    ) {
        val compact = windowClass == CopilotWindowClass.Compact
        val split = !isMapFullScreen && !compact
        // Compact and full-screen maps are edge to edge; split layouts keep the card gutter.
        val gap = if (split) Raahi.Gap else 0.dp
        val innerWidth = maxWidth - gap * 2
        val innerHeight = maxHeight - gap * 2
        val mapFraction = if (windowClass == CopilotWindowClass.Medium) 0.6f else 0.5f
        val mapWidth = if (split) (innerWidth - gap) * mapFraction else innerWidth
        val panelWidth = innerWidth - gap - mapWidth
        var sheetHeightPx by remember { mutableIntStateOf(0) }
        val showSheet = compact && !isMapFullScreen

        // Always the first child of this Box in every layout, so the MapView is never recreated:
        // a window-class change only changes its size modifiers.
        val mapShape = if (split) Raahi.CardShape else RectangleShape
        LiveMapPanel(
            latestPoint = latestPoint,
            trail = tripTrail,
            speedLimitInfo = speedLimitInfo,
            isFullScreen = isMapFullScreen,
            isCovered = isCovered,
            onToggleFullScreen = onToggleFullMap,
            controlsBottomInsetPx = { if (showSheet) sheetHeightPx else 0 },
            modifier = Modifier
                .padding(gap)
                .width(mapWidth)
                .fillMaxHeight()
                .clip(mapShape)
                .then(if (split) Modifier.border(Raahi.Border, CockpitTheme.colors.border, mapShape) else Modifier)
        )

        if (split) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(gap)
                    .width(panelWidth)
                    .fillMaxHeight()
            ) {
                if (windowClass == CopilotWindowClass.Medium) {
                    MediumTelemetryColumn(cards = cards, panelHeight = innerHeight)
                } else {
                    ExpandedCardGrid(cards = cards, panelHeight = innerHeight)
                }
            }
        }

        if (showSheet) {
            CompactCockpitSheet(
                cards = cards,
                cardWidth = (maxWidth - Raahi.Gap * 2) * 0.88f,
                cardHeight = (maxHeight * 0.5f).coerceIn(180.dp, 340.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { sheetHeightPx = it.height }
            )
        }
    }
}

/**
 * Expanded (head unit): the 2x2 grid (Speedometer | Fuel, Current trip | Music). Short panels
 * (small phones in landscape, or a large font scale) scroll instead of squeezing cards until text clips.
 */
@Composable
private fun ExpandedCardGrid(cards: List<Pair<String, @Composable (Modifier) -> Unit>>, panelHeight: Dp) {
    val roomy = panelHeight >= 540.dp
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (roomy) Modifier else Modifier.verticalScroll(rememberScrollState())),
        verticalArrangement = Arrangement.spacedBy(Raahi.Gap)
    ) {
        cards.chunked(2).forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (roomy) Modifier.weight(1f) else Modifier.height(320.dp)),
                horizontalArrangement = Arrangement.spacedBy(Raahi.Gap)
            ) {
                row.forEach { (_, card) -> card(Modifier.weight(1f).fillMaxHeight()) }
            }
        }
    }
}

/** Medium (small tablet / split-screen): one scrolling column, two cards per panel height. */
@Composable
private fun MediumTelemetryColumn(cards: List<Pair<String, @Composable (Modifier) -> Unit>>, panelHeight: Dp) {
    val cardHeight = ((panelHeight - Raahi.Gap) / 2).coerceAtLeast(320.dp)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Raahi.Gap)
    ) {
        cards.forEach { (_, card) -> card(Modifier.fillMaxWidth().height(cardHeight)) }
    }
}

/**
 * Compact (phone): a collapsible sheet over the bottom of the full-bleed map. The cards sit in a
 * snapping row, each ~88 % of the width so the next one peeks in. Collapsing it frees the map.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactCockpitSheet(
    cards: List<Pair<String, @Composable (Modifier) -> Unit>>,
    cardWidth: Dp,
    cardHeight: Dp,
    modifier: Modifier = Modifier
) {
    val c = CockpitTheme.colors
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Raahi.Gap),
        verticalArrangement = Arrangement.spacedBy(Raahi.Gap)
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .heightIn(min = 48.dp)
                .clip(Raahi.PillShape)
                .background(c.rail)
                .border(Raahi.Border, c.border, Raahi.PillShape)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = c.textSecondary
            )
            Text(
                text = if (expanded) "Hide cockpit" else "Show cockpit",
                style = CockpitTheme.typography.labelLarge,
                color = c.textPrimary
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val listState = rememberLazyListState()
            LazyRow(
                state = listState,
                flingBehavior = rememberSnapFlingBehavior(listState),
                horizontalArrangement = Arrangement.spacedBy(Raahi.Gap),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
            ) {
                items(cards, key = { it.first }) { (_, card) ->
                    card(Modifier.width(cardWidth).fillMaxHeight())
                }
            }
        }
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
