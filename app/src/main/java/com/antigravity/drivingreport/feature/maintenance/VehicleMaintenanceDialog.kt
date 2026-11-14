package com.antigravity.drivingreport.feature.maintenance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.drivingreport.core.designsystem.*
import com.antigravity.drivingreport.core.maintenance.MaintenanceItem
import com.antigravity.drivingreport.core.maintenance.MaintenanceManager

@Composable
fun VehicleMaintenanceDialog(
    currentOdoKm: Double,
    onDismissRequest: () -> Unit
) {
    val items by MaintenanceManager.items.collectAsState()
    val dueCount = remember(items, currentOdoKm) {
        MaintenanceManager.getDueItemsCount(currentOdoKm)
    }

    val cardBg = CockpitTheme.colors.card
    val cardBorder = CockpitTheme.colors.border
    val cyanAccent = CockpitTheme.colors.accent
    val positiveAccent = CockpitTheme.colors.accent
    val warningAccent = CockpitTheme.colors.warning
    val dangerAccent = CockpitTheme.colors.critical
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.90f)
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.25.dp, cardBorder, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = cardBg)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "VEHICLE SERVICE & MAINTENANCE",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 1.sp,
                                    color = textPrimary
                                )
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Track component lifespans, periodic fluid flushes and policy expiries",
                                style = MaterialTheme.typography.bodySmall.copy(color = textSecondary)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Status Pill
                            val statusBg = if (dueCount > 0) dangerAccent.copy(alpha = 0.2f) else positiveAccent.copy(alpha = 0.2f)
                            val statusColor = if (dueCount > 0) dangerAccent else positiveAccent
                            val statusText = if (dueCount > 0) "$dueCount SERVICE DUE" else "ALL HEALTHY"

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(statusBg)
                                    .border(1.dp, statusColor, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (dueCount > 0) Icons.Default.Warning else Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = statusColor,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = statusColor
                                        )
                                    )
                                }
                            }

                            IconButton(onClick = onDismissRequest) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close",
                                    tint = textSecondary
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Maintenance Items List
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items, key = { it.id }) { item ->
                            MaintenanceItemCard(
                                item = item,
                                currentOdoKm = currentOdoKm,
                                onMarkServiced = {
                                    MaintenanceManager.markServiced(item.id, currentOdoKm)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceItemCard(
    item: MaintenanceItem,
    currentOdoKm: Double,
    onMarkServiced: () -> Unit
) {
    val health = remember(item, currentOdoKm) { item.healthPercentage(currentOdoKm) }
    val isDue = remember(item, currentOdoKm) { item.isDue(currentOdoKm) }

    val barColor = when {
        health <= 15 -> CockpitTheme.colors.critical
        health <= 35 -> CockpitTheme.colors.warning
        else -> CockpitTheme.colors.accent
    }

    val cardBorder = if (isDue) CockpitTheme.colors.critical.copy(alpha = 0.5f) else CockpitTheme.colors.border

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(CockpitTheme.colors.cardElevated)
            .border(1.dp, cardBorder, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.customTitle,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = CockpitTheme.colors.textPrimary
                        )
                    )
                    Text(
                        text = item.type.description,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = CockpitTheme.colors.textSecondary,
                            fontSize = 11.5.sp
                        )
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Mark Serviced Button
                Button(
                    onClick = onMarkServiced,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDue) CockpitTheme.colors.critical else CockpitTheme.colors.accentSoft
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (isDue) "Mark Done" else "Reset / Log",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isDue) CockpitTheme.colors.onAccent else CockpitTheme.colors.accent
                        )
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { (health / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = barColor,
                trackColor = CockpitTheme.colors.border
            )

            Spacer(Modifier.height(6.dp))

            // Telemetry Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val remKm = item.remainingKm(currentOdoKm)
                val remDays = item.remainingDays()

                val telemetryText = if (item.isDateOnly) {
                    if (remDays >= 0) "$remDays days remaining" else "${-remDays} days EXPIRED"
                } else {
                    "${remKm.toInt()} km remaining (${remDays.coerceAtLeast(0)} days)"
                }

                Text(
                    text = telemetryText,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = barColor,
                        fontSize = 12.sp
                    )
                )

                Text(
                    text = "$health% Life",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        color = CockpitTheme.colors.textSecondary,
                        fontSize = 12.sp
                    )
                )
            }
        }
    }
}
