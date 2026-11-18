package com.antigravity.copilot.feature.maps

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.CockpitTheme
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
                .background(CockpitTheme.colors.scrim)
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
    // Storage Access Framework picker for the India .pmtiles file. Many head units ship without
    // DocumentsUI, so OPEN_DOCUMENT can have no handler: fall back to GET_CONTENT (OEM file
    // manager), then to the built-in storage scan, instead of crashing.
    val importMapLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importMapFile(uri) }
    val getContentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) viewModel.importMapFile(uri) }
    val launchMapPicker: () -> Unit = {
        try {
            importMapLauncher.launch(arrayOf("application/octet-stream", "application/vnd.pmtiles", "*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                getContentLauncher.launch("*/*")
            } catch (_: android.content.ActivityNotFoundException) {
                viewModel.importFromUsb("No file picker found — scanning storage for .pmtiles…")
            }
        }
    }
    val isImportingUsb = usbImportProgressText != null

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    var editingPackageForUrl by remember { mutableStateOf<OfflineMapManager.OfflinePackage?>(null) }
    var pendingDelete by remember { mutableStateOf<OfflineMapManager.OfflinePackage?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.96f)
            .fillMaxHeight(0.95f)
            .clip(RoundedCornerShape(20.dp))
            .border(1.5.dp, CockpitTheme.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp)),
        color = CockpitTheme.colors.cardElevated
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
                            .background(CockpitTheme.colors.accent.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, CockpitTheme.colors.accent, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = CockpitTheme.colors.accent,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(
                            text = "Offline maps",
                            fontFamily = com.antigravity.copilot.core.designsystem.typography.GoogleSansFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp,
                            color = CockpitTheme.colors.textPrimary
                        )
                        Text(
                            text = "Maps that work without internet",
                            fontSize = 17.sp,
                            color = CockpitTheme.colors.textSecondary
                        )
                    }
                }

                // Close Button (Min 56dp touch area)
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(CockpitTheme.colors.border)
                        .clickable { onClose() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = CockpitTheme.colors.textPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Storage Budget Gauge Bar
            StorageBudgetCard(storageInfo = storageInfo)

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
                    color = CockpitTheme.colors.border,
                    shape = CopilotShapes.R12,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = CockpitTheme.colors.accent,
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = usbImportProgressText ?: "Importing...",
                            color = CockpitTheme.colors.accent,
                            fontSize = 17.sp
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
                        onDelete = { pendingDelete = pkg },
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
                        launchMapPicker()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.border),
                    shape = CopilotShapes.R12
                ) {
                    Icon(
                        imageVector = Icons.Default.Usb,
                        contentDescription = null,
                        tint = contentColorOn(CockpitTheme.colors.border),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Import map file",
                        fontWeight = FontWeight.Bold,
                        color = contentColorOn(CockpitTheme.colors.border),
                        fontSize = 17.sp
                    )
                }

                // Advanced: the download server address, kept out of the main list.
                OutlinedButton(
                    onClick = { editingPackageForUrl = installedPackages.firstOrNull() },
                    enabled = installedPackages.isNotEmpty(),
                    modifier = Modifier.height(52.dp),
                    shape = CopilotShapes.R12
                ) {
                    Icon(imageVector = Icons.Default.Link, contentDescription = null, tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Advanced", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CockpitTheme.colors.textPrimary)
                }

                // Check for Updates Button
                Button(
                    onClick = {
                        viewModel.checkForUpdates()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent.copy(alpha = 0.2f)),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = androidx.compose.ui.graphics.SolidColor(CockpitTheme.colors.accent)),
                    shape = CopilotShapes.R12
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = CockpitTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Check for updates",
                        fontWeight = FontWeight.Bold,
                        color = CockpitTheme.colors.textPrimary,
                        fontSize = 17.sp
                    )
                }
            }

            pendingDelete?.let { pkg ->
                com.antigravity.copilot.core.designsystem.components.RaahiConfirmDialog(
                    title = "Delete the ${friendlyMapTitle(pkg.title)}?",
                    message = "The map will stop working offline until you download or import it again.",
                    confirmText = "Delete map",
                    destructive = true,
                    icon = Icons.Default.Delete,
                    onConfirm = {
                        viewModel.deletePackage(pkg.id)
                        pendingDelete = null
                    },
                    onDismiss = { pendingDelete = null }
                )
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
            if (storageInfo.isLowStorageWarning) CockpitTheme.colors.critical else CockpitTheme.colors.border
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
                        tint = if (storageInfo.isLowStorageWarning) CockpitTheme.colors.critical else CockpitTheme.colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Storage",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = CockpitTheme.colors.textPrimary
                    )
                }
                Text(
                    text = String.format("Maps use %.1f GB · %.1f GB free", usedOfflineGb, freeGb),
                    fontSize = 17.sp,
                    color = if (storageInfo.isLowStorageWarning) CockpitTheme.colors.critical else CockpitTheme.colors.accent,
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
                    .background(CockpitTheme.colors.border)
            ) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(offlineRatio)
                            .background(CockpitTheme.colors.accent)
                    )
                }
            }

            if (storageInfo.isLowStorageWarning) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = CockpitTheme.colors.critical,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Storage is almost full (under 2 GB free).",
                        fontSize = 16.sp,
                        color = CockpitTheme.colors.critical,
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
        color = CockpitTheme.colors.border,
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f)),
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
                    fontSize = 19.sp,
                    color = CockpitTheme.colors.textPrimary
                )
                Text(
                    text = when (downloadState) {
                        OfflineMapManager.DownloadState.PAUSED_USER -> "Paused"
                        else -> String.format("%.1f%% (%.1f MB/s)", progress * 100f, speedMbs)
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = CockpitTheme.colors.accent
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CopilotShapes.R4),
                color = CockpitTheme.colors.accent,
                trackColor = CockpitTheme.colors.border
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
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitTheme.colors.critical)
                ) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Cancel", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CockpitTheme.colors.textPrimary)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Button(
                    onClick = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) onPause else onResume,
                    modifier = Modifier.height(48.dp),
                    shape = CopilotShapes.R8,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) CockpitTheme.colors.warning else CockpitTheme.colors.accent
                    )
                ) {
                    Icon(
                        imageVector = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = contentColorOn(if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) CockpitTheme.colors.warning else CockpitTheme.colors.accent))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) "Pause" else "Resume",
                        fontWeight = FontWeight.Bold,
                        color = contentColorOn(if (downloadState == OfflineMapManager.DownloadState.DOWNLOADING) CockpitTheme.colors.warning else CockpitTheme.colors.accent),
                        fontSize = 17.sp
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
        color = if (isCurrentGpsMatch && !pkg.isInstalled) CockpitTheme.colors.accentSoft else CockpitTheme.colors.border,
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCurrentGpsMatch) CockpitTheme.colors.accent.copy(alpha = 0.6f) else CockpitTheme.colors.border
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
                        text = friendlyMapTitle(pkg.title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    if (isCurrentGpsMatch) {
                        Surface(
                            color = CockpitTheme.colors.accent.copy(alpha = 0.25f),
                            shape = CopilotShapes.R6
                        ) {
                            Text(
                                text = "You are here",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Black,
                                color = CockpitTheme.colors.accent,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    // Status Badge
                    Surface(
                        color = if (pkg.isInstalled) CockpitTheme.colors.positive.copy(alpha = 0.2f) else CockpitTheme.colors.border,
                        shape = CopilotShapes.R6
                    ) {
                        Text(
                            text = if (pkg.isInstalled) "Downloaded" else "Not downloaded",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (pkg.isInstalled) CockpitTheme.colors.positive else CockpitTheme.colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }

                if (pkg.coveredStates.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = pkg.coveredStates,
                        fontSize = 16.sp,
                        color = CockpitTheme.colors.accent.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                val sizeText = if (pkg.estimatedSizeBytes >= 1024L * 1024L * 1024L) {
                    "~${String.format("%.1f", pkg.estimatedSizeBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))} GB"
                } else {
                    "${pkg.estimatedSizeBytes / 1024} KB"
                }
                // Installed: the storage card already shows the real size, so no estimate here.
                if (!pkg.isInstalled) Text(
                    text = "$sizeText download",
                    fontSize = 16.sp,
                    color = CockpitTheme.colors.textSecondary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Large Touch Action Buttons
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pkg.isInstalled) {
                    OutlinedButton(
                        onClick = onUpdate,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitTheme.colors.accent)
                    ) {
                        Text("Update", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CockpitTheme.colors.textPrimary)
                    }

                    OutlinedButton(
                        onClick = onDelete,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitTheme.colors.critical)
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text("Delete", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = CockpitTheme.colors.textPrimary)
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !isCurrentlyDownloading,
                        modifier = Modifier.height(48.dp),
                        shape = CopilotShapes.R8,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCurrentGpsMatch) CockpitTheme.colors.accent else CockpitTheme.colors.accentSoft
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            tint = if (isCurrentGpsMatch) contentColorOn(CockpitTheme.colors.accent) else CockpitTheme.colors.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Download",
                            fontWeight = FontWeight.Bold,
                            color = if (isCurrentGpsMatch) contentColorOn(CockpitTheme.colors.accent) else CockpitTheme.colors.textPrimary,
                            fontSize = 17.sp
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
        color = CockpitTheme.colors.critical.copy(alpha = 0.14f),
        shape = CopilotShapes.R12,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, CockpitTheme.colors.critical),
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
                        tint = CockpitTheme.colors.critical,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Download failed",
                        fontWeight = FontWeight.Bold,
                        fontSize = 19.sp,
                        color = CockpitTheme.colors.critical
                    )
                }

                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = CockpitTheme.colors.textPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = errorMessage,
                color = CockpitTheme.colors.textPrimary,
                fontSize = 17.sp,
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
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.critical),
                    shape = CopilotShapes.R8,
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Try again", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = contentColorOn(CockpitTheme.colors.critical))
                }

                OutlinedButton(
                    onClick = onImportUsb,
                    shape = CopilotShapes.R8,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = CockpitTheme.colors.accent),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = androidx.compose.ui.graphics.SolidColor(CockpitTheme.colors.accent)
                    ),
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(imageVector = Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Import from USB", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CockpitTheme.colors.textPrimary)
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
            color = CockpitTheme.colors.border,
            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Map download address",
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = CockpitTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Advanced: download the ${friendlyMapTitle(pkg.title)} from your own server.",
                    fontSize = 17.sp,
                    color = CockpitTheme.colors.textPrimary
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
                        focusedTextColor = CockpitTheme.colors.textPrimary,
                        unfocusedTextColor = CockpitTheme.colors.textPrimary,
                        focusedBorderColor = CockpitTheme.colors.accent,
                        unfocusedBorderColor = CockpitTheme.colors.textPrimary
                    )
                )

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onReset) {
                        Text("Reset", color = CockpitTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = CockpitTheme.colors.textPrimary, fontSize = 17.sp)
                        }
                        Button(
                            onClick = { onSave(urlText) },
                            colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent),
                            shape = CopilotShapes.R8
                        ) {
                            Text("Save", color = contentColorOn(CockpitTheme.colors.accent), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        }
                    }
                }
            }
        }
    }
}

/** "India Vector Map (PMTiles)" -> "India map": file-format words mean nothing to a driver. */
private fun friendlyMapTitle(title: String): String =
    title.replace(Regex("\\s*\\(.*?\\)"), "")
        .replace("Vector ", "")
        .replace(" Map", " map")
        .trim()
