package com.antigravity.copilot.feature.launcher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
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
    onBackToHome: () -> Unit
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.backgroundBrush)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
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
                onBackToHome = onBackToHome
            )

            Spacer(modifier = Modifier.height(8.dp))

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
                            modifier = Modifier.size(48.dp)
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

            Spacer(modifier = Modifier.height(6.dp))

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
 * Renders a strict 6-column x 5-row grid of apps (30 cells total).
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
        verticalArrangement = Arrangement.spacedBy(AutomotiveDesignTokens.AppDrawer.gridVerticalSpacing)
    ) {
        for (rowIndex in 0 until LauncherManager.GRID_ROWS) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(AutomotiveDesignTokens.AppDrawer.gridHorizontalSpacing)
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
                        // Empty placeholder cell preserves the 6-column x 5-row grid structure
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
 * Clean 1x1 automotive app icon without heavy card background.
 * Full-cell clickable tile with tactile bounce animation & ripple feedback.
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
    val iconSize = 58.dp
    val labelSize = 14.5.sp

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1.0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "AppItemScale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .background(if (isPressed) CockpitTheme.colors.cardElevated.copy(alpha = 0.8f) else Color.Transparent)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = true, color = CockpitTheme.colors.accent),
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Icon
            if (imageBitmap != null) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(iconSize)
                        .clip(RoundedCornerShape(14.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(iconSize)
                        .clip(RoundedCornerShape(14.dp))
                        .background(CockpitTheme.colors.cardElevated)
                        .border(1.2.dp, CockpitTheme.colors.border, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = app.label.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 24.sp
                        ),
                        color = CockpitTheme.colors.accent
                    )
                }
            }

            Spacer(modifier = Modifier.height(5.dp))

            // App Label underneath the icon
            Text(
                text = app.label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = labelSize,
                    lineHeight = 17.sp
                ),
                color = CockpitTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 140.dp)
            )
        }
    }
}

/**
 * Top dock bar of the App Drawer with larger layout and clear Icon + Text action buttons.
 */
@Composable
fun AppDrawerHeader(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    pageIndex: Int,
    totalPages: Int,
    totalAppsCount: Int,
    onBackToHome: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CockpitTheme.colors.dockBrush)
            .border(1.2.dp, CockpitTheme.colors.dockBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: Round back button + Title & app count
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            IconButton(
                onClick = onBackToHome,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CockpitTheme.colors.cardElevated)
                    .border(1.2.dp, CockpitTheme.colors.border, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = CockpitTheme.colors.textPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "All Applications",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        ),
                        color = CockpitTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$totalAppsCount apps",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp
                            ),
                            color = CockpitTheme.colors.accent
                        )
                    }
                }
                if (totalPages > 1) {
                    Text(
                        text = "Page ${pageIndex + 1} of $totalPages • Swipe left/right",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = CockpitTheme.colors.textSecondary
                    )
                }
            }
        }

        // Right: Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = {
                Text(
                    "Search apps...",
                    color = CockpitTheme.colors.textDisabled,
                    fontSize = 14.5.sp
                )
            },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = CockpitTheme.colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = CockpitTheme.colors.textSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .width(260.dp)
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = CockpitTheme.colors.textPrimary,
                unfocusedTextColor = CockpitTheme.colors.textPrimary,
                focusedBorderColor = CockpitTheme.colors.accent,
                unfocusedBorderColor = CockpitTheme.colors.border,
                focusedContainerColor = CockpitTheme.colors.cardElevated,
                unfocusedContainerColor = CockpitTheme.colors.cardElevated
            )
        )
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
