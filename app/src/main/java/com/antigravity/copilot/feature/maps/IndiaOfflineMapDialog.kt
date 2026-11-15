package com.antigravity.copilot.feature.maps

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.util.NumberFormatUtils
import com.antigravity.copilot.core.map.IndiaOfflineMapManager
import com.antigravity.copilot.core.map.OfflineMapManager

@Composable
fun IndiaOfflineMapDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val downloadState by IndiaOfflineMapManager.downloadState.collectAsState()
    val progressPercent by IndiaOfflineMapManager.progressPercent.collectAsState()
    val currentTiles by IndiaOfflineMapManager.progressCurrentTiles.collectAsState()
    val totalTiles by IndiaOfflineMapManager.progressTotalTiles.collectAsState()
    val speedKbs by IndiaOfflineMapManager.downloadSpeedKbs.collectAsState()
    val activeRegionName by IndiaOfflineMapManager.activeRegionName.collectAsState()
    val downloadedRegions by IndiaOfflineMapManager.downloadedRegions.collectAsState()
    val totalStorageBytes by IndiaOfflineMapManager.totalStorageSizeBytes.collectAsState()
    val isCheckingUpdates by IndiaOfflineMapManager.isCheckingUpdates.collectAsState()
    val updateStatusMessage by IndiaOfflineMapManager.updateStatusMessage.collectAsState()

    val isAllIndiaDownloaded = downloadedRegions.containsAll(IndiaOfflineMapManager.INDIA_REGIONS.map { it.id })

    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isVisible = true
    }

    val coroutineScope = rememberCoroutineScope()
    fun handleDismiss() {
        coroutineScope.launch {
            isVisible = false
            delay(220)
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = { handleDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.94f, animationSpec = tween(240, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
                    scaleOut(targetScale = 0.94f, animationSpec = tween(180, easing = FastOutSlowInEasing))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.90f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF070D1A))
                    .border(1.5.dp, Cockpit_Cyan.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ==================== 1. TOP HEADER BAR ====================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Cockpit_Cyan.copy(alpha = 0.15f))
                                .border(1.2.dp, Cockpit_Cyan, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(text = "🇮🇳", fontSize = 22.sp)
                        }
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "India HD Satellite Offline Map System",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 20.sp,
                                        letterSpacing = (-0.5).sp
                                    ),
                                    color = Color.White
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color(0xFF132238))
                                        .border(1.dp, Cockpit_Cyan.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "HD SATELLITE ENGINE",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.5.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = Cockpit_Cyan
                                    )
                                }
                            }
                            Text(
                                text = "Permanent Offline SQLite Storage • Auto-Resumes on Internet • High-Resolution Satellite Hybrid",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Normal
                                ),
                                color = Cockpit_TextSecondary
                            )
                        }
                    }

                    // Close Button
                    IconButton(
                        onClick = { handleDismiss() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF131F36))
                            .border(1.dp, Cockpit_CardBorder, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // ==================== 2. SPLIT 2-COLUMN MAIN CONTENT ====================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // ---------- LEFT COLUMN: OVERVIEW, STORAGE & MASTER ACTIONS (weight: 1.15f) ----------
                    Column(
                        modifier = Modifier
                            .weight(1.15f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Master Status Box
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFF0F1E38), Color(0xFF0A1528))
                                    )
                                )
                                .border(1.2.dp, Cockpit_CardBorder, RoundedCornerShape(16.dp))
                                .padding(18.dp)
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Section label & storage row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "MASTER STATUS",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.Black,
                                            fontSize = 11.sp,
                                            letterSpacing = 1.sp
                                        ),
                                        color = Cockpit_TextSecondary
                                    )

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF0A1C30))
                                            .border(1.dp, Cockpit_Cyan.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text(
                                            text = "STORAGE: ${OfflineMapManager.formatCacheSize(totalStorageBytes)}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 11.sp,
                                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                            ),
                                            color = Cockpit_Cyan
                                        )
                                    }
                                }

                                // State Headline with Status Indicator Dot
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val (statusText, statusColor) = when (downloadState) {
                                        IndiaOfflineMapManager.DownloadState.DOWNLOADING -> Pair("Downloading $activeRegionName...", Cockpit_Cyan)
                                        IndiaOfflineMapManager.DownloadState.PAUSED_NETWORK_LOST -> Pair("Paused (Waiting for Connection)", Cockpit_AmberWarning)
                                        IndiaOfflineMapManager.DownloadState.PAUSED_USER -> Pair("Download Paused", Cockpit_AmberWarning)
                                        IndiaOfflineMapManager.DownloadState.COMPLETED -> Pair("All-India Offline Maps Ready", Cockpit_GreenEco)
                                        IndiaOfflineMapManager.DownloadState.UPDATE_AVAILABLE -> Pair("Map Update Available", Color(0xFFC084FC))
                                        IndiaOfflineMapManager.DownloadState.UPDATING -> Pair("Updating Map Data in Background...", Cockpit_Cyan)
                                        IndiaOfflineMapManager.DownloadState.ERROR -> Pair("Download Error (Will Auto-Retry)", Cockpit_RedAlert)
                                        IndiaOfflineMapManager.DownloadState.IDLE -> {
                                            if (isAllIndiaDownloaded) Pair("Offline Maps Complete (100% Usable Offline)", Cockpit_GreenEco)
                                            else Pair("Ready to Download India Offline Map", Color.White)
                                        }
                                    }

                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(statusColor)
                                    )
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp
                                        ),
                                        color = statusColor
                                    )
                                }

                                // Progress bar & telemetry when active
                                if (downloadState == IndiaOfflineMapManager.DownloadState.DOWNLOADING ||
                                    downloadState == IndiaOfflineMapManager.DownloadState.PAUSED_NETWORK_LOST ||
                                    downloadState == IndiaOfflineMapManager.DownloadState.PAUSED_USER
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        LinearProgressIndicator(
                                            progress = { progressPercent / 100f },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(8.dp)
                                                .clip(RoundedCornerShape(4.dp)),
                                            color = Cockpit_Cyan,
                                            trackColor = Color(0xFF162544)
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = "$currentTiles / $totalTiles tiles ($progressPercent%)",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.5.sp,
                                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                ),
                                                color = Color.White
                                            )
                                            Text(
                                                text = "${NumberFormatUtils.formatPrecision(speedKbs, 2)} KB/s",
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontSize = 11.5.sp,
                                                    fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                                                ),
                                                color = Cockpit_TextSecondary
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        text = if (isAllIndiaDownloaded)
                                            "All national highways, arterial expressways and major cities are cached permanently in local SQLite storage. Maps work seamlessly with full clarity without cellular coverage."
                                        else
                                            "Download the complete India offline map pack to travel anywhere across states and national highways without cellular connection.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp,
                                            lineHeight = 17.sp
                                        ),
                                        color = Cockpit_TextSecondary
                                    )
                                }

                                // Master Action Buttons
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // Primary Button
                                    Button(
                                        onClick = {
                                            when (downloadState) {
                                                IndiaOfflineMapManager.DownloadState.DOWNLOADING -> IndiaOfflineMapManager.pauseDownload(context)
                                                IndiaOfflineMapManager.DownloadState.PAUSED_USER,
                                                IndiaOfflineMapManager.DownloadState.PAUSED_NETWORK_LOST -> IndiaOfflineMapManager.resumeDownload(context)
                                                else -> IndiaOfflineMapManager.startDownload(context, "ALL_INDIA")
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(46.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (downloadState == IndiaOfflineMapManager.DownloadState.DOWNLOADING) Cockpit_AmberWarning else Cockpit_Cyan
                                        )
                                    ) {
                                        val buttonText = when (downloadState) {
                                            IndiaOfflineMapManager.DownloadState.DOWNLOADING -> "PAUSE DOWNLOAD"
                                            IndiaOfflineMapManager.DownloadState.PAUSED_USER,
                                            IndiaOfflineMapManager.DownloadState.PAUSED_NETWORK_LOST -> "RESUME DOWNLOAD"
                                            IndiaOfflineMapManager.DownloadState.COMPLETED -> "RE-CHECK & VERIFY MAP"
                                            else -> "DOWNLOAD COMPLETE INDIA MAP"
                                        }
                                        Text(
                                            text = buttonText,
                                            style = MaterialTheme.typography.labelLarge.copy(
                                                fontWeight = FontWeight.Black,
                                                fontSize = 12.5.sp,
                                                letterSpacing = 0.5.sp,
                                                color = Color.Black
                                            )
                                        )
                                    }

                                    // Check Updates Button
                                    OutlinedButton(
                                        onClick = { IndiaOfflineMapManager.checkForUpdates(context) },
                                        enabled = !isCheckingUpdates,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(44.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        border = ButtonDefaults.outlinedButtonBorder.copy(
                                            brush = Brush.horizontalGradient(listOf(Cockpit_CardBorder, Cockpit_Cyan.copy(alpha = 0.5f)))
                                        )
                                    ) {
                                        if (isCheckingUpdates) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Cockpit_Cyan
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "CHECKING FOR UPDATES...",
                                                style = MaterialTheme.typography.labelMedium.copy(
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    letterSpacing = 0.5.sp,
                                                    color = Cockpit_Cyan
                                                )
                                            )
                                        } else {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Refresh,
                                                    contentDescription = "Check Updates",
                                                    tint = Cockpit_Cyan,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "CHECK FOR MAP UPDATES",
                                                    style = MaterialTheme.typography.labelMedium.copy(
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        letterSpacing = 0.5.sp,
                                                        color = Color.White
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    // Update feedback message pill
                                    if (!updateStatusMessage.isNullOrBlank()) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF0C192E))
                                                .border(1.dp, Cockpit_Cyan.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = null,
                                                    tint = Cockpit_Cyan,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = updateStatusMessage ?: "",
                                                    style = MaterialTheme.typography.labelSmall.copy(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Medium
                                                    ),
                                                    color = Color(0xFFE2E8F0)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Reliability & Offline MBTiles Import Card
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF091222))
                                .border(1.dp, Cockpit_CardBorder.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Top,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SdCard,
                                    contentDescription = "Offline Archive",
                                    tint = Cockpit_Cyan,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .padding(top = 1.dp)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(
                                        text = "Whole India Offline Archive (.mbtiles / .sqlite)",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        ),
                                        color = Color.White
                                    )
                                    Text(
                                        text = "You can also copy pre-downloaded nationwide Satellite MBTiles or SQLite archives into the '/osmdroid/' folder. Auto-detected and mounted instantly without downloading over mobile internet.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 10.5.sp,
                                            lineHeight = 14.5.sp
                                        ),
                                        color = Cockpit_TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // ---------- RIGHT COLUMN: REGIONAL HIGHWAY & ARTERIAL PACKS (weight: 1.45f) ----------
                    Column(
                        modifier = Modifier
                            .weight(1.45f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "REGIONAL HIGHWAY & ARTERIAL PACKS",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 11.sp,
                                    letterSpacing = 1.sp
                                ),
                                color = Cockpit_TextSecondary
                            )

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF101E36))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "${downloadedRegions.size} of ${IndiaOfflineMapManager.INDIA_REGIONS.size} INSTALLED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = if (downloadedRegions.size == IndiaOfflineMapManager.INDIA_REGIONS.size) Cockpit_GreenEco else Cockpit_Cyan
                                )
                            }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(IndiaOfflineMapManager.INDIA_REGIONS) { region ->
                                val isDownloaded = downloadedRegions.contains(region.id)
                                val isCurrentlyDownloading = downloadState == IndiaOfflineMapManager.DownloadState.DOWNLOADING && activeRegionName == region.name

                                RegionalPackCard(
                                    region = region,
                                    isDownloaded = isDownloaded,
                                    isCurrentlyDownloading = isCurrentlyDownloading,
                                    onDownload = {
                                        IndiaOfflineMapManager.startDownload(context, region.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun RegionalPackCard(
    region: IndiaOfflineMapManager.RegionPack,
    isDownloaded: Boolean,
    isCurrentlyDownloading: Boolean,
    onDownload: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0C1424))
            .border(
                1.dp,
                if (isDownloaded) Cockpit_GreenEco.copy(alpha = 0.4f) else Cockpit_CardBorder,
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = region.name,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp
                        ),
                        color = Color.White
                    )

                    // Zoom Pill Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF162544))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Z${region.minZoom}-${region.maxZoom}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Cockpit_Cyan
                        )
                    }

                    // Estimated Size Pill Badge
                    val sizeLabel = if (region.estimatedSizeMb >= 1000) {
                        "~${NumberFormatUtils.formatPrecision(region.estimatedSizeMb / 1024.0, 1)} GB"
                    } else {
                        "~${region.estimatedSizeMb} MB"
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF162544))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = sizeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp,
                                fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily, fontFeatureSettings = "tnum"
                            ),
                            color = Cockpit_TextSecondary
                        )
                    }
                }

                Text(
                    text = region.description,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp
                    ),
                    color = Cockpit_TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isDownloaded) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Cockpit_GreenEco.copy(alpha = 0.15f))
                        .border(1.dp, Cockpit_GreenEco.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Installed",
                            tint = Cockpit_GreenEco,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "INSTALLED",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 10.sp,
                                letterSpacing = 0.5.sp
                            ),
                            color = Cockpit_GreenEco
                        )
                    }
                }
            } else if (isCurrentlyDownloading) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Cockpit_Cyan.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Cockpit_Cyan,
                        strokeWidth = 2.5.dp
                    )
                }
            } else {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF14223A))
                        .border(1.dp, Cockpit_Cyan.copy(alpha = 0.35f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Download Region",
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
