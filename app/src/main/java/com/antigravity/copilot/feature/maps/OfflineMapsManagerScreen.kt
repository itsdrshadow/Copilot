package com.antigravity.copilot.feature.maps

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
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            OfflineMapsManagerContent(onClose = onDismissRequest)
        }
    }
}

@Composable
fun OfflineMapsManagerContent(
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val downloadState by OfflineMapManager.downloadState.collectAsState()
    val activePkgId by OfflineMapManager.activePackageId.collectAsState()
    val downloadProgress by OfflineMapManager.downloadProgress.collectAsState()
    val downloadSpeedMbs by OfflineMapManager.downloadSpeedMbs.collectAsState()
    val installedPackages by OfflineMapManager.installedPackages.collectAsState()
    val storageInfo by OfflineMapManager.storageInfo.collectAsState()
    val statusMessage by OfflineMapManager.statusMessage.collectAsState()

    var isImportingUsb by remember { mutableStateOf(false) }
    var usbImportProgressText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        OfflineMapManager.refreshPackageStatus(context)
        OfflineMapManager.refreshStorageInfo(context)
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.92f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, Cockpit_Cyan.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
        color = Color(0xFF0F141C)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
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
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "OFFLINE MAPS & ROUTING MANAGER",
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            color = Color.White
                        )
                        Text(
                            text = "100% Offline Navigation for Android 11 Head Unit",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                // Close Button (Min 56dp touch area)
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E2633))
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Storage Budget Gauge Bar & Warning
            StorageBudgetCard(storageInfo = storageInfo)

            Spacer(modifier = Modifier.height(16.dp))

            // Active Download Progress Card
            AnimatedVisibility(
                visible = downloadState == OfflineMapManager.DownloadState.DOWNLOADING ||
                        downloadState == OfflineMapManager.DownloadState.PAUSED_USER ||
                        downloadState == OfflineMapManager.DownloadState.EXTRACTING
            ) {
                ActiveDownloadCard(
                    packageTitle = installedPackages.find { it.id == activePkgId }?.title ?: "Offline Package",
                    downloadState = downloadState,
                    progress = downloadProgress,
                    speedMbs = downloadSpeedMbs,
                    onPause = { activePkgId?.let { OfflineMapManager.pauseDownload(context, it) } },
                    onResume = { activePkgId?.let { OfflineMapManager.resumeDownload(context, it) } },
                    onCancel = { activePkgId?.let { OfflineMapManager.cancelDownload(context, it) } }
                )
            }

            // USB / SD Card Import Progress
            if (isImportingUsb && usbImportProgressText != null) {
                Surface(
                    color = Color(0xFF16222F),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Cockpit_Cyan,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = usbImportProgressText ?: "Importing...",
                            color = Cockpit_Cyan,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Package List
            Text(
                text = "AVAILABLE PACKAGES (MAX 15 GB BUDGET)",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = 0.7f),
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(installedPackages) { pkg ->
                    PackageItemRow(
                        pkg = pkg,
                        isCurrentlyDownloading = downloadState == OfflineMapManager.DownloadState.DOWNLOADING && activePkgId == pkg.id,
                        onDownload = { OfflineMapManager.startDownload(context, pkg.id) },
                        onDelete = { OfflineMapManager.deletePackage(context, pkg.id) },
                        onUpdate = { OfflineMapManager.startDownload(context, pkg.id) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Bottom Actions Bar (USB Import + Check Updates)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // USB / SD Card Import Button (Min 56dp height)
                Button(
                    onClick = {
                        isImportingUsb = true
                        usbImportProgressText = "Scanning USB drive..."
                        OfflineMapManager.importFromExternalStorage(
                            context = context,
                            onProgress = { msg, _ -> usbImportProgressText = msg },
                            onComplete = { success, msg ->
                                isImportingUsb = false
                                usbImportProgressText = null
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E2D3D)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "IMPORT FROM USB / SD CARD",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }

                // Check for Updates Button
                Button(
                    onClick = {
                        OfflineMapManager.checkForUpdates(context)
                        Toast.makeText(context, "Checked for map updates", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan.copy(alpha = 0.2f)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(Cockpit_Cyan)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Cockpit_Cyan,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "CHECK FOR UPDATES",
                        fontWeight = FontWeight.Bold,
                        color = Cockpit_Cyan,
                        fontSize = 14.sp
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
    val freeRatio = if (totalGb > 0) (storageInfo.freeDeviceBytes.toFloat() / storageInfo.totalDeviceBytes.toFloat()).coerceIn(0f, 1f) else 0f
    val offlineRatio = if (totalGb > 0) (storageInfo.offlineMapsUsedBytes.toFloat() / storageInfo.totalDeviceBytes.toFloat()).coerceIn(0f, 1f) else 0f

    Surface(
        color = Color(0xFF141A24),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (storageInfo.isLowStorageWarning) Cockpit_RedAlert else Color(0xFF222B3A)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "DEVICE STORAGE BUDGET",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                }
                Text(
                    text = String.format("%.1f GB Free of %.1f GB (Maps: %.2f GB)", freeGb, totalGb, usedOfflineGb),
                    fontSize = 13.sp,
                    color = if (storageInfo.isLowStorageWarning) Cockpit_RedAlert else Cockpit_Cyan,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Multi-segment storage bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Cockpit_RedAlert,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "WARNING: Free device storage is below 2.0 GB. Please clean up unused maps.",
                        fontSize = 12.sp,
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
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Cockpit_Cyan.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = packageTitle,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = Color.White
                )
                Text(
                    text = when (downloadState) {
                        OfflineMapManager.DownloadState.EXTRACTING -> "Extracting..."
                        OfflineMapManager.DownloadState.PAUSED_USER -> "Paused"
                        else -> String.format("%.1f%% (%.1f MB/s)", progress * 100f, speedMbs)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cockpit_Cyan
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp)),
                color = Cockpit_Cyan,
                trackColor = Color(0xFF1C2B3C)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Pause / Resume / Cancel Controls (Min 52dp touch targets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_RedAlert)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CANCEL", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Button(
                    onClick = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) onPause else onResume,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) Cockpit_AmberWarning else Cockpit_Cyan
                    )
                ) {
                    Icon(
                        imageVector = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) "PAUSE" else "RESUME",
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItemRow(
    pkg: OfflineMapManager.OfflinePackage,
    isCurrentlyDownloading: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onUpdate: () -> Unit
) {
    Surface(
        color = Color(0xFF161C26),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF222B38)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = pkg.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    // Status Badge
                    Surface(
                        color = if (pkg.isInstalled) Cockpit_GreenEco.copy(alpha = 0.2f) else Color(0xFF2A3442),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (pkg.isInstalled) "INSTALLED" else "NOT INSTALLED",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pkg.isInstalled) Cockpit_GreenEco else Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${pkg.description} • ~${String.format("%.1f", pkg.estimatedSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))} GB",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Large Touch Action Buttons
            if (pkg.isInstalled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onUpdate,
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_Cyan)
                    ) {
                        Text("UPDATE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.height(52.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cockpit_RedAlert)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("DELETE", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            } else {
                Button(
                    onClick = onDownload,
                    enabled = !isCurrentlyDownloading,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan)
                ) {
                    Icon(imageVector = Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("DOWNLOAD", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 13.sp)
                }
            }
        }
    }
}
