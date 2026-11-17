package com.antigravity.copilot.feature.maps

import androidx.compose.runtime.Composable

/**
 * Backward compatibility alias redirecting to OfflineMapsManagerDialog.
 */
@Composable
fun IndiaOfflineMapDialog(
    onDismissRequest: () -> Unit
) {
    OfflineMapsManagerDialog(onDismissRequest = onDismissRequest)
}
