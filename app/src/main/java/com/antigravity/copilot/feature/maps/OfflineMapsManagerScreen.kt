package com.antigravity.copilot.feature.maps

import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.designsystem.colors.*
import com.antigravity.copilot.core.map.OfflineMapManager

@Composable
fun OfflineMapsManagerDialog(
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f))
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            OfflineMapsManagerContent(onClose = onDismissRequest)
        }
    }
}

@Composable
fun OfflineMapsManagerContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OfflineMapsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val activePkgId by viewModel.activePackageId.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val downloadSpeedMbs by viewModel.downloadSpeedMbs.collectAsStateWithLifecycle()
    val installedPackages by viewModel.installedPackages.collectAsStateWithLifecycle()
    val currentGpsRegion by viewModel.currentGpsRegion.collectAsStateWithLifecycle()
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val usbImportProgressText by viewModel.usbImportProgress.collectAsStateWithLifecycle()
    // Storage Access Framework picker for the India .pmtiles file (no storage permission needed)
    val importMapLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importMapFile(uri) }
    val isImportingUsb = usbImportProgressText != null

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    var editingPackageForUrl by remember { mutableStateOf<OfflineMapManager.OfflinePackage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.95f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, Cockpit_Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
        color = Color(0xFF0F141C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Cockpit_Cyan.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, Cockpit_Cyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = Cockpit_Cyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "OFFLINE MAPS MANAGER",
                            fontFamily = com.antigravity.copilot.core.designsystem.typography.GoogleSansFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp,
                            color = Color.White
                        )
                        Text(
                            text = "India PMTiles vector map, 100% offline (Max 15 GB Budget)",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                // Close Button (Min 56dp touch area)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2633))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Budget Gauge Bar
            StorageBudgetCard(storageInfo = storageInfo)

            Spacer(modifier = Modifier.height(10.dp))

            // Live GPS Detected Region Pill Banner
            CurrentGpsRegionBanner(currentGpsRegion = currentGpsRegion)

            Spacer(modifier = Modifier.height(10.dp))

            // Active Download Progress Card
            AnimatedVisibility(
                visible = downloadState == OfflineMapManager.DownloadState.DOWNLOADING ||
                        downloadState == OfflineMapManager.DownloadState.PAUSED_USER
            ) {
                ActiveDownloadCard(
                    packageTitle = installedPackages.find { it.id == activePkgId }?.title ?: "Offline Package",
                    downloadState = downloadState,
                    progress = downloadProgress,
                    speedMbs = downloadSpeedMbs,
                    onPause = { activePkgId?.let { viewModel.pauseDownload(it) } },
                    onResume = { activePkgId?.let { viewModel.resumeDownload(it) } },
                    onCancel = { activePkgId?.let { viewModel.cancelDownload(it) } }
                )
            }

            // Download Error Alert Card
            AnimatedVisibility(
                visible = downloadState == OfflineMapManager.DownloadState.ERROR
            ) {
                DownloadErrorCard(
                    errorMessage = statusMessage,
                    onDismiss = { viewModel.clearDownloadError() },
                    onRetry = { activePkgId?.let { viewModel.startDownload(it) } },
                    onImportUsb = {
                        viewModel.importFromUsb("Scanning USB drive & storage...")
                    }
                )
            }

            // USB / SD Card Import Progress
            if (isImportingUsb && usbImportProgressText != null) {
                Surface(
                    color = Color(0xFF16222F),
                    shape = CopilotShapes.R12,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Cockpit_Cyan,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = usbImportProgressText ?: "Importing...",
                            color = Cockpit_Cyan,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Package List (Regional + National)
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(installedPackages) { pkg ->
                    val isCurrentGpsMatch = currentGpsRegion?.id == pkg.id
                    PackageItemRow(
                        pkg = pkg,
                        isCurrentGpsMatch = isCurrentGpsMatch,
                        isCurrentlyDownloading = downloadState == OfflineMapManager.DownloadState.DOWNLOADING && activePkgId == pkg.id,
                        onDownload = {
                            Toast.makeText(context, "Starting download for ${pkg.title}", Toast.LENGTH_SHORT).show()
                            viewModel.startDownload(pkg.id)
                        },
                        onDelete = { viewModel.deletePackage(pkg.id) },
                        onUpdate = { viewModel.startDownload(pkg.id) },
                        onEditUrl = { editingPackageForUrl = pkg }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Actions Bar (USB Import + Check Updates)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Import map file (.pmtiles) via Storage Access Framework
                Button(
                    onClick = {
                        importMapLauncher.launch(arrayOf("application/octet-stream", "application/vnd.pmtiles", "*/*"))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D3D)),
                    shape = CopilotShapes.R12
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "IMPORT MAP FILE",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }

                // Check for Updates Button
                Button(
                    onClick = {
                        viewModel.checkForUpdates()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan.copy(alpha = 0.2f)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Cockpit_Cyan)),
                    shape = CopilotShapes.R12
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CHECK FOR UPDATES",
                        fontWeight = FontWeight.Bold,
                        color = Cockpit_Cyan,
                        fontSize = 13.sp
                    )
                }
            }

            // Custom Download URL Editor Dialog
            editingPackageForUrl?.let { pkg ->
                CustomUrlDialog(
                    pkg = pkg,
                    initialUrl = viewModel.effectiveDownloadUrl(pkg),
                    onSave = { newUrl ->
                        viewModel.setCustomDownloadUrl(pkg.id, newUrl)
                        editingPackageForUrl = null
                        Toast.makeText(context, "Download URL updated for ${pkg.title}", Toast.LENGTH_SHORT).show()
                    },
                    onReset = {
                        viewModel.setCustomDownloadUrl(pkg.id, null)
                        editingPackageForUrl = null
                        Toast.makeText(context, "Reset to default URL", Toast.LENGTH_SHORT).show()
                    },
                    onDismiss = { editingPackageForUrl = null }
                )
            }
        }
    }
}

@Composable
private fun CurrentGpsRegionBanner(currentGpsRegion: OfflineMapManager.OfflinePackage?) {
    Surface(
        color = Color(0xFF131D2A),
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.GpsFixed,
                contentDescription = null,
                tint = Cockpit_Cyan,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "CURRENT GPS REGION: ",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                    Text(
                        text = currentGpsRegion?.title ?: "Detecting Location...",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Cockpit_Cyan
                    )
                }
                if (!currentGpsRegion?.coveredStates.isNullOrBlank()) {
                    Text(
                        text = currentGpsRegion?.coveredStates ?: "",
                        fontSize = 10.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageBudgetCard(storageInfo: OfflineMapManager.StorageBudgetInfo) {
    val totalGb = storageInfo.totalDeviceBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val freeGb = storageInfo.freeDeviceBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val usedOfflineGb = storageInfo.offlineMapsUsedBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val offlineRatio = if (totalGb > 0) (storageInfo.offlineMapsUsedBytes.toFloat() / storageInfo.totalDeviceBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        color = Palette.Ink850,
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (storageInfo.isLowStorageWarning) Cockpit_RedAlert else Color(0xFF222B3A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = if (storageInfo.isLowStorageWarning) Cockpit_RedAlert else Cockpit_Cyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEVICE STORAGE BUDGET",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = String.format("%.1f GB Free of %.1f GB (Maps: %.2f GB)", freeGb, totalGb, usedOfflineGb),
                    fontSize = 12.sp,
                    color = if (storageInfo.isLowStorageWarning) Cockpit_RedAlert else Cockpit_Cyan,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Multi-segment storage bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0xFF222C3A))
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(offlineRatio)
                            .background(Cockpit_Cyan)
                    )
                }
            }

            if (storageInfo.isLowStorageWarning) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Cockpit_RedAlert,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "WARNING: Free storage below 2.0 GB. Clean up unused regional maps.",
                        fontSize = 11.sp,
                        color = Cockpit_RedAlert,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    packageTitle: String,
    downloadState: OfflineMapManager.DownloadState,
    progress: Float,
    speedMbs: Double,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        color = Color(0xFF132030),
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = packageTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    text = when (downloadState) {
                        OfflineMapManager.DownloadState.PAUSED_USER -> "Paused"
                        else -> String.format("%.1f%% (%.1f MB/s)", progress * 100f, speedMbs)
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cockpit_Cyan
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CopilotShapes.R4),
                color = Cockpit_Cyan,
                trackColor = Color(0xFF1C2B3C)
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(48.dp),
                    shape = CopilotShapes.R8,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_RedAlert)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) onPause else onResume,
                    modifier = Modifier.height(48.dp),
                    shape = CopilotShapes.R8,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) Cockpit_AmberWarning else Cockpit_Cyan
                    )
                ) {
                    Icon(
                        imageVector = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) "PAUSE" else "RESUME",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItemRow(
    pkg: OfflineMapManager.OfflinePackage,
    isCurrentGpsMatch: Boolean,
    isCurrentlyDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit,
    onEditUrl: () -> Unit
) {
    Surface(
        color = if (isCurrentGpsMatch && !pkg.isInstalled) Color(0xFF122233) else Color(0xFF161C26),
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrentGpsMatch) Cockpit_Cyan.copy(alpha = 0.6f) else Color(0xFF222B38)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pkg.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    if (isCurrentGpsMatch) {
                        Surface(
                            color = Cockpit_Cyan.copy(alpha = 0.25f),
                            shape = CopilotShapes.R6
                        ) {
                            Text(
                                text = "★ GPS REGION",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Black,
                                color = Cockpit_Cyan,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Status Badge
                    Surface(
                        color = if (pkg.isInstalled) Cockpit_GreenEco.copy(alpha = 0.2f) else Color(0xFF2A3442),
                        shape = CopilotShapes.R6
                    ) {
                        Text(
                            text = if (pkg.isInstalled) "INSTALLED" else "NOT INSTALLED",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pkg.isInstalled) Cockpit_GreenEco else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                if (pkg.coveredStates.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pkg.coveredStates,
                        fontSize = 11.sp,
                        color = Cockpit_Cyan.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                val sizeText = if (pkg.estimatedSizeBytes >= 1024L * 1024L * 1024L) {
                    "~${String.format("%.1f", pkg.estimatedSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))} GB"
                } else {
                    "${pkg.estimatedSizeBytes / 1024} KB"
                }
                Text(
                    text = "${pkg.description} • $sizeText",
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Large Touch Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                // Link / Custom URL button
                IconButton(
                    onClick = onEditUrl,
                    modifier = Modifier
                        .size(44.dp)
                        .background(Color(0xFF1E2836), CopilotShapes.R8)
                ) {
                    Icon(
                        imageVector = Icons.Default.Link,
                        contentDescription = "Configure URL",
                        tint = Cockpit_Cyan.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                if (pkg.isInstalled) {
                    OutlinedButton(
                        onClick = onUpdate,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_Cyan)
                    ) {
                        Text("UPDATE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }

                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_RedAlert)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("DELETE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isCurrentlyDownloading,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCurrentGpsMatch) Cockpit_Cyan else Color(0xFF1E2F42)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = if (isCurrentGpsMatch) Color.Black else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "DOWNLOAD",
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrentGpsMatch) Color.Black else Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadErrorCard(
    errorMessage: String,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onImportUsb: () -> Unit
) {
    Surface(
        color = Color(0xFF281116),
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, Cockpit_RedAlert),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Cockpit_RedAlert,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "DOWNLOAD FAILED / ERROR",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = Cockpit_RedAlert
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = errorMessage,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                    shape = CopilotShapes.R8,
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("RETRY", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = onImportUsb,
                    shape = CopilotShapes.R8,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_Cyan),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(Cockpit_Cyan)
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("IMPORT FROM USB / STORAGE", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun CustomUrlDialog(
    pkg: OfflineMapManager.OfflinePackage,
    initialUrl: String,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(initialUrl) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = CopilotShapes.R16,
            color = Color(0xFF131B26),
            border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Configure Server URL",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Set custom mirror or local server for ${pkg.title}:",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Download URL") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Cockpit_Cyan,
                        unfocusedBorderColor = Color(0xFF2E3D52)
                    )
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) {
                        Text("RESET DEFAULT", color = Cockpit_RedAlert, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("CANCEL", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                        }
                        Button(
                            onClick = { onSave(urlText) },
                            colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan),
                            shape = CopilotShapes.R8
                        ) {
                            Text("SAVE", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
