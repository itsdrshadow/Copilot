package com.antigravity.copilot.feature.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.model.AppDrawerItem
import kotlinx.coroutines.launch

/**
 * Automotive 3-column x 4-row App Drawer Screen with horizontal pagination.
 * Replaces legacy dialog app drawer with responsive full-screen automotive layout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerScreen(
    apps: List<AppDrawerItem>,
    modifier: Modifier = Modifier,
    onAppClick: (AppDrawerItem) -> Unit,
    onBackToHome: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenFuel: () -> Unit = {}
) {
    BackHandler(onBack = onBackToHome)

    var searchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(apps, searchQuery) {
        if (searchQuery.isBlank()) {
            apps
        } else {
            val q = searchQuery.trim().lowercase()
            apps.filter { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
    }

    val pageChunks = remember(filteredApps) {
        LauncherManager.paginate(filteredApps, LauncherManager.APPS_PER_PAGE)
    }
    val totalPages = if (pageChunks.isEmpty()) 1 else pageChunks.size
    val pagerState = rememberPagerState(initialPage = 0) { totalPages }
    val coroutineScope = rememberCoroutineScope()

    // Detect right swipe on page 0 to return to Home Screen
    val swipeBackModifier = Modifier.pointerInput(pagerState.currentPage) {
        detectHorizontalDragGestures { _, dragAmount ->
            if (dragAmount > 35f && pagerState.currentPage == 0) {
                onBackToHome()
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.bg)
            .then(swipeBackModifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        val isVeryCompact = maxHeight < 480.dp

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==================== 1. TOP HEADER & SEARCH ====================
            AppDrawerHeader(
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                pageIndex = pagerState.currentPage,
                totalPages = totalPages,
                totalAppsCount = filteredApps.size,
                onBackToHome = onBackToHome,
                onOpenSettings = onOpenSettings,
                onOpenTrips = onOpenTrips,
                onOpenFuel = onOpenFuel,
                isVeryCompact = isVeryCompact
            )

            Spacer(modifier = Modifier.height(if (isVeryCompact) 4.dp else 8.dp))

            // ==================== 2. 3x4 GRID OR EMPTY STATE ====================
            if (filteredApps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = CockpitTheme.colors.textSecondary,
                            modifier = Modifier.size(if (isVeryCompact) 36.dp else 48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) "No applications match \"$searchQuery\"" else "No applications installed",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = CockpitTheme.colors.textSecondary
                        )
                        if (searchQuery.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Button(
                                onClick = { searchQuery = "" },
                                colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accentSoft),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Clear Search", color = CockpitTheme.colors.accent, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) { page ->
                    val pageApps = if (page < pageChunks.size) pageChunks[page] else emptyList()
                    AppDrawer3x4Grid(
                        apps = pageApps,
                        modifier = Modifier.fillMaxSize(),
                        onAppClick = onAppClick
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (isVeryCompact) 4.dp else 6.dp))

            // ==================== 3. PAGE INDICATOR DOTS ====================
            if (totalPages > 1) {
                AppDrawerPageIndicator(
                    currentPage = pagerState.currentPage,
                    totalPages = totalPages,
                    onPageSelected = { targetPage ->
                        coroutineScope.launch {
                            pagerState.animateScrollToPage(targetPage)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Renders a strict 3-column x 4-row grid of apps (12 cells total).
 * Empty spacer cells guarantee consistent alignment on partially filled pages.
 */
@Composable
fun AppDrawer3x4Grid(
    apps: List<AppDrawerItem>,
    modifier: Modifier = Modifier,
    onAppClick: (AppDrawerItem) -> Unit
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (rowIndex in 0 until LauncherManager.GRID_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (colIndex in 0 until LauncherManager.GRID_COLUMNS) {
                    val itemIndex = rowIndex * LauncherManager.GRID_COLUMNS + colIndex
                    if (itemIndex < apps.size) {
                        val app = apps[itemIndex]
                        AppGridItem(
                            app = app,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            onClick = { onAppClick(app) }
                        )
                    } else {
                        // Empty placeholder cell preserves the 3-column x 4-row grid structure
                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

/**
 * Driver-safe touch card for head-unit interaction.
 * Dynamically switches between horizontal Row (landscape) and vertical Column (portrait/narrow).
 */
@Composable
fun AppGridItem(
    app: AppDrawerItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val imageBitmap = remember(app.packageName, app.activityName) {
        app.iconBitmap?.asImageBitmap()
    }

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitTheme.colors.card)
            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        val isNarrowCell = maxWidth < 160.dp
        val isShortCell = maxHeight < 85.dp
        val iconSize = if (isShortCell || isNarrowCell) 40.dp else 48.dp
        val labelFontSize = if (isShortCell) 13.sp else 15.sp
        val labelLineHeight = if (isShortCell) 16.sp else 18.sp

        if (isNarrowCell) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(10.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(10.dp))
                            .background(CockpitTheme.colors.cardElevated),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = CockpitTheme.colors.accent
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.5.sp,
                        textAlign = TextAlign.Center
                    ),
                    color = CockpitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (imageBitmap != null) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = app.label,
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = app.label.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                            color = CockpitTheme.colors.accent
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = labelFontSize,
                            lineHeight = labelLineHeight
                        ),
                        color = CockpitTheme.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Top header of the App Drawer with Cockpit return, app counts, search and shortcuts.
 */
@Composable
fun AppDrawerHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pageIndex: Int,
    totalPages: Int,
    totalAppsCount: Int,
    onBackToHome: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenTrips: () -> Unit = {},
    onOpenFuel: () -> Unit = {},
    isVeryCompact: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Back/Home navigation button & Title with app count badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onBackToHome,
                modifier = Modifier
                    .size(if (isVeryCompact) 36.dp else 42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    imageVector = Icons.Default.Dashboard,
                    contentDescription = "Back to Cockpit",
                    tint = CockpitTheme.colors.accent,
                    modifier = Modifier.size(if (isVeryCompact) 18.dp else 22.dp)
                )
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "All Applications",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = if (isVeryCompact) 16.sp else 19.sp
                        ),
                        color = CockpitTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$totalAppsCount apps",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            ),
                            color = CockpitTheme.colors.accent
                        )
                    }
                }
                if (totalPages > 1) {
                    Text(
                        text = "Page ${pageIndex + 1} of $totalPages • Swipe left/right",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = CockpitTheme.colors.textSecondary
                    )
                }
            }
        }

        // Center / Right: Search & Quick Navigation Shortcuts
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = {
                    Text(
                        "Search apps...",
                        color = CockpitTheme.colors.textDisabled,
                        fontSize = if (isVeryCompact) 11.sp else 12.5.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = null,
                        tint = CockpitTheme.colors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(20.dp)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = CockpitTheme.colors.textSecondary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .width(if (isVeryCompact) 180.dp else 230.dp)
                    .height(if (isVeryCompact) 38.dp else 42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = CockpitTheme.colors.textPrimary,
                    unfocusedTextColor = CockpitTheme.colors.textPrimary,
                    focusedBorderColor = CockpitTheme.colors.accent,
                    unfocusedBorderColor = CockpitTheme.colors.border,
                    focusedContainerColor = CockpitTheme.colors.cardElevated,
                    unfocusedContainerColor = CockpitTheme.colors.card
                )
            )

            // Trips Shortcut
            IconButton(
                onClick = onOpenTrips,
                modifier = Modifier
                    .size(if (isVeryCompact) 36.dp else 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Trip History",
                    tint = CockpitTheme.colors.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Fuel Log Shortcut
            IconButton(
                onClick = onOpenFuel,
                modifier = Modifier
                    .size(if (isVeryCompact) 36.dp else 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    Icons.Default.LocalGasStation,
                    contentDescription = "Fuel Log",
                    tint = CockpitTheme.colors.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }

            // Settings Shortcut
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(if (isVeryCompact) 36.dp else 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(10.dp))
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = CockpitTheme.colors.textSecondary,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
    }
}

/**
 * Animated glowing page indicator dots for automotive infotainment.
 */
@Composable
fun AppDrawerPageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier,
    onPageSelected: ((Int) -> Unit)? = null
) {
    if (totalPages <= 1) return

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (page in 0 until totalPages) {
            val isSelected = page == currentPage
            val dotWidth by animateDpAsState(
                targetValue = if (isSelected) 24.dp else 8.dp,
                animationSpec = tween(220),
                label = "AppDrawerDotWidth"
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(dotWidth)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (isSelected) CockpitTheme.colors.accent
                        else CockpitTheme.colors.textSecondary.copy(alpha = 0.35f)
                    )
                    .clickable(enabled = onPageSelected != null) {
                        onPageSelected?.invoke(page)
                    }
            )
        }
    }
}

/**
 * Backward compatibility aliases.
 */
@Composable
fun AppDrawerCard(
    app: AppDrawerItem,
    isCompact: Boolean = false,
    onClick: () -> Unit
) = AppGridItem(app = app, onClick = onClick)

@Composable
fun AppDrawerDialog(
    apps: List<AppDrawerItem>,
    onAppClick: (AppDrawerItem) -> Unit,
    onDismiss: () -> Unit
) = AppDrawerScreen(
    apps = apps,
    onAppClick = onAppClick,
    onBackToHome = onDismiss
)
