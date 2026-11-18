package com.antigravity.copilot.feature.fuel

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.util.NumberFormatUtils
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
    // Ignored prompt closes itself after AUTO_DISMISS_SECONDS (countdown shown on "Not now").
    val dismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    var secondsLeft by androidx.compose.runtime.remember(promptData) { androidx.compose.runtime.mutableIntStateOf(AUTO_DISMISS_SECONDS) }
    androidx.compose.runtime.LaunchedEffect(promptData) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1_000L)
            secondsLeft--
        }
        dismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) { com.antigravity.copilot.core.designsystem.PopupScaled {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(CockpitTheme.colors.scrim),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .cockpitSurface(CockpitTheme.colors.card, CockpitTheme.colors.accent.copy(alpha = 0.5f), CopilotShapes.R22, borderWidth = 1.5.dp)
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
                                    .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.5f), CopilotShapes.R12),
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
                                        .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.4f), CopilotShapes.R4)
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = "At a petrol pump",
                                        style = CockpitTheme.typography.labelMedium,
                                        color = CockpitTheme.colors.accent
                                    )
                                }
                                Text(
                                    text = "Did you fill up here?",
                                    style = CockpitTheme.typography.headlineSmall.copy(
                                        fontWeight = FontWeight.Black
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CopilotShapes.R8)
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
                            .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R12)
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
                                    style = CockpitTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Odometer: ${NumberFormatUtils.formatOdometer(promptData.currentOdometerKm)} km",
                                        style = CockpitTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        color = CockpitTheme.colors.textSecondary
                                    )
                                    Text("•", color = CockpitTheme.colors.textDisabled, style = CockpitTheme.typography.bodySmall)
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Schedule, contentDescription = null, tint = CockpitTheme.colors.textSecondary, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(3.dp))
                                        Text(
                                            text = formattedTime,
                                            style = CockpitTheme.typography.bodySmall.copy(
                                                fontFeatureSettings = "tnum"
                                            ),
                                            color = CockpitTheme.colors.textSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        text = "You stopped at a petrol pump. Add the fill-up now so your mileage stays right.",
                        style = CockpitTheme.typography.bodyMedium,
                        color = CockpitTheme.colors.textSecondary,
                        textAlign = TextAlign.Center
                    )

                    // 2 actions: "Not now" (same as the close button; auto after the countdown) and "Yes, add fuel".
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1.2f)
                                .height(44.dp),
                            shape = CopilotShapes.R10,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CockpitTheme.colors.cardElevated,
                                contentColor = CockpitTheme.colors.textPrimary
                            )
                        ) {
                            Text(
                                "Not now · ${secondsLeft}s",
                                style = CockpitTheme.typography.labelSmall, color = contentColorOn(CockpitTheme.colors.cardElevated)
                            )
                        }

                        Button(
                            onClick = onAcceptRefuel,
                            modifier = Modifier
                                .weight(1.6f)
                                .height(44.dp),
                            shape = CopilotShapes.R10,
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
                                "Yes, add fuel",
                                style = CockpitTheme.typography.labelMedium.copy(fontWeight = FontWeight.Black), color = contentColorOn(CockpitTheme.colors.accent)
                            )
                        }
                    }
                }
            }
        }
    }
}
}

private const val AUTO_DISMISS_SECONDS = 45
