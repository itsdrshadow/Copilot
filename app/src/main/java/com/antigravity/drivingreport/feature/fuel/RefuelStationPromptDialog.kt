package com.antigravity.drivingreport.feature.fuel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.drivingreport.core.designsystem.CockpitTheme
import com.antigravity.drivingreport.core.fuel.FuelRepository
import com.antigravity.drivingreport.core.util.NumberFormatUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RefuelStationPromptDialog(
    promptData: FuelRepository.RefuelPromptData,
    onAcceptRefuel: () -> Unit,
    onDismiss: () -> Unit
) {
    val formattedTime = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(promptData.timestamp))

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(CockpitTheme.colors.card)
                    .border(1.5.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    .padding(22.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Header Bar with Icon & Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(CockpitTheme.colors.accentSoft)
                                    .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.LocalGasStation,
                                    contentDescription = null,
                                    tint = CockpitTheme.colors.accent,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(CockpitTheme.colors.accentSoft)
                                        .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "FUEL STATION DETECTED",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            letterSpacing = 0.5.sp
                                        ),
                                        color = CockpitTheme.colors.accent
                                    )
                                }
                                Text(
                                    text = "Did you refuel here?",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 18.sp
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(CockpitTheme.colors.cardElevated)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = CockpitTheme.colors.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Location Info Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PinDrop,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = promptData.locationName.ifBlank { "Fuel Station Nearby" },
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Odometer: ${NumberFormatUtils.formatOdometer(promptData.currentOdometerKm, 2)} km",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = CockpitTheme.colors.textSecondary
                                    )
                                    Text("•", color = CockpitTheme.colors.textDisabled, fontSize = 10.sp)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(11.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = formattedTime,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace
                                            ),
                                            color = CockpitTheme.colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "Vehicle has been stationary for over 30 seconds at this fuel station. Would you like to log this refill now?",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = CockpitTheme.colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    // 3 Actions: Dismiss, Not Now, Yes, Add Fuel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, CockpitTheme.colors.border)
                        ) {
                            Text(
                                "Dismiss",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp,
                                color = CockpitTheme.colors.textSecondary
                            )
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1.1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitTheme.colors.cardElevated,
                                contentColor = CockpitTheme.colors.textPrimary
                            )
                        ) {
                            Text(
                                "Not Now",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        Button(
                            onClick = onAcceptRefuel,
                            modifier = Modifier
                                .weight(1.6f)
                                .height(44.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitTheme.colors.accent,
                                contentColor = CockpitTheme.colors.onAccent
                            )
                        ) {
                            Icon(
                                Icons.Default.LocalGasStation,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                "Yes, Add Fuel",
                                fontWeight = FontWeight.Black,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
