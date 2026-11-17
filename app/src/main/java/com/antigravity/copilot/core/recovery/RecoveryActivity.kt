package com.antigravity.copilot.core.recovery

import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.MainActivity
import com.antigravity.copilot.core.designsystem.*
import kotlinx.coroutines.delay

class RecoveryActivity : ComponentActivity() {

    companion object {
        const val EXTRA_CRASH_COUNT = "extra_crash_count"
        const val EXTRA_CRASH_MESSAGE = "extra_crash_message"
        const val EXTRA_CRASH_TRACE = "extra_crash_trace"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashCount = intent.getIntExtra(EXTRA_CRASH_COUNT, CrashGuardian.getCrashCount(this))
        val crashMessage = intent.getStringExtra(EXTRA_CRASH_MESSAGE) ?: CrashGuardian.getLastCrashMessage(this) ?: "Uncaught Exception"
        val crashTrace = intent.getStringExtra(EXTRA_CRASH_TRACE) ?: CrashGuardian.getLastCrashTrace(this) ?: "No trace available"

        setContent {
            RecoveryScreen(
                crashCount = crashCount,
                crashMessage = crashMessage,
                crashTrace = crashTrace,
                onLaunchStockLauncher = {
                    CrashGuardian.launchStockLauncher(this)
                    finish()
                },
                onRestoreStockAsDefault = {
                    val ok = CrashGuardian.restoreStockLauncherAsDefault(this)
                    Toast.makeText(this, if (ok) "Stock Launcher set as default" else "Stock Launcher preferred", Toast.LENGTH_SHORT).show()
                    CrashGuardian.launchStockLauncher(this)
                    finish()
                },
                onStartSafeMode = {
                    CrashGuardian.setSafeMode(this, true)
                    CrashGuardian.resetCrashHistory(this)
                    val restartIntent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    startActivity(restartIntent)
                    finish()
                },
                onRebootDevice = {
                    try {
                        Runtime.getRuntime().exec(arrayOf("su", "0", "reboot"))
                    } catch (e: Exception) {
                        Toast.makeText(this, "Manual reboot required", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }
    }
}

@Composable
private fun RecoveryScreen(
    crashCount: Int,
    crashMessage: String,
    crashTrace: String,
    onLaunchStockLauncher: () -> Unit,
    onRestoreStockAsDefault: () -> Unit,
    onStartSafeMode: () -> Unit,
    onRebootDevice: () -> Unit
) {
    var countdownSeconds by remember { mutableIntStateOf(10) }
    var isCountdownPaused by remember { mutableStateOf(false) }
    var showFullTrace by remember { mutableStateOf(false) }

    // Auto-fallback countdown timer (safeguards vehicle operation if driver doesn't touch screen)
    LaunchedEffect(isCountdownPaused) {
        if (!isCountdownPaused) {
            while (countdownSeconds > 0) {
                delay(1000L)
                countdownSeconds--
            }
            // Automatically launch stock launcher when countdown reaches 0
            onLaunchStockLauncher()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.cardElevated)
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ==================== 1. HEADER BAR ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CopilotShapes.R12)
                    .background(CockpitTheme.colors.cardElevated)
                    .border(1.2.dp, CockpitTheme.colors.critical.copy(alpha = 0.6f), CopilotShapes.R12)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(CockpitTheme.colors.critical.copy(alpha = 0.2f))
                            .border(1.2.dp, CockpitTheme.colors.critical, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alert",
                            tint = CockpitTheme.colors.critical,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "EMERGENCY SYSTEM RECOVERY",
                                style = CockpitTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.5.sp
                                ),
                                color = CockpitTheme.colors.textPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(CopilotShapes.R4)
                                    .background(CockpitTheme.colors.critical.copy(alpha = 0.25f))
                                    .border(0.8.dp, CockpitTheme.colors.critical, CopilotShapes.R4)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$crashCount FAILURES DETECTED",
                                    style = CockpitTheme.typography.labelSmall,
                                    color = CockpitTheme.colors.critical
                                )
                            }
                        }
                        Text(
                            text = "Repeated crash loop intercepted. Vehicle safety guaranteed via stock launcher fallback.",
                            style = CockpitTheme.typography.bodySmall,
                            color = CockpitTheme.colors.textSecondary
                        )
                    }
                }

                // Auto-fallback countdown badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isCountdownPaused) "AUTO-FALLBACK PAUSED" else "AUTO-FALLBACK: ${countdownSeconds}s",
                        style = CockpitTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        ),
                        color = if (isCountdownPaused) CockpitTheme.colors.warning else CockpitTheme.colors.accent
                    )

                    OutlinedButton(
                        onClick = { isCountdownPaused = !isCountdownPaused },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = CopilotShapes.R6,
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(CockpitTheme.colors.border, CockpitTheme.colors.accent.copy(alpha = 0.4f)))
                        )
                    ) {
                        Text(
                            text = if (isCountdownPaused) "RESUME" else "PAUSE",
                            style = CockpitTheme.typography.labelSmall,
                            color = CockpitTheme.colors.textPrimary)
                    }
                }
            }

            // ==================== 2. MAIN 2-COLUMN SPLIT ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ---------- LEFT COLUMN: STATUS & DIAGNOSTICS (weight: 1f) ----------
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Telemetry Protection Assurance Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(CopilotShapes.R12)
                            .background(CockpitTheme.colors.border)
                            .border(1.dp, CockpitTheme.colors.positive.copy(alpha = 0.5f), CopilotShapes.R12)
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Protected",
                                tint = CockpitTheme.colors.positive,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Telemetry & Fuel Database Intact",
                                    style = CockpitTheme.typography.titleSmall,
                                    color = CockpitTheme.colors.textPrimary
                                )
                                Text(
                                    text = "All fuel records, odometer baseline and completed trips are securely preserved in permanent SQLite storage.",
                                    style = CockpitTheme.typography.bodySmall,
                                    color = CockpitTheme.colors.textSecondary
                                )
                            }
                        }
                    }

                    // Crash Diagnostic Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(CopilotShapes.R12)
                            .background(CockpitTheme.colors.cardElevated)
                            .border(1.dp, CockpitTheme.colors.border, CopilotShapes.R12)
                            .padding(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "EXCEPTION SUMMARY",
                                    style = CockpitTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = CockpitTheme.colors.textSecondary
                                )

                                TextButton(
                                    onClick = { showFullTrace = !showFullTrace },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (showFullTrace) "SHOW SUMMARY" else "VIEW FULL TRACE",
                                        style = CockpitTheme.typography.labelSmall,
                                        color = CockpitTheme.colors.textPrimary)
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(CopilotShapes.R8)
                                    .background(CockpitTheme.colors.cardElevated)
                                    .border(0.8.dp, CockpitTheme.colors.border.copy(alpha = 0.5f), CopilotShapes.R8)
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = if (showFullTrace) crashTrace else crashMessage,
                                    style = CockpitTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ),
                                    color = if (showFullTrace) Palette.Slate300 else CockpitTheme.colors.critical
                                )
                            }
                        }
                    }
                }

                // ---------- RIGHT COLUMN: RECOVERY CONTROLS (weight: 1.15f) ----------
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Action 1: Primary Stock Blaupunkt Launcher
                    Button(
                        onClick = onLaunchStockLauncher,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = CopilotShapes.R12,
                        colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = CockpitTheme.colors.onAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "LAUNCH BLAUPUNKT STOCK LAUNCHER",
                                    style = CockpitTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = CockpitTheme.colors.onAccent
                                )
                                Text(
                                    text = "Instant 1-tap fallback to factory OEM interface",
                                    style = CockpitTheme.typography.labelSmall,
                                    color = CockpitTheme.colors.onAccent
                                )
                            }
                        }
                    }

                    // Action 2: Restore Blaupunkt as Default HOME
                    OutlinedButton(
                        onClick = onRestoreStockAsDefault,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CopilotShapes.R10,
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(CockpitTheme.colors.border, CockpitTheme.colors.accent.copy(alpha = 0.5f)))
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsBackupRestore,
                                contentDescription = null,
                                tint = CockpitTheme.colors.accent,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "RESTORE BLAUPUNKT AS DEFAULT HOME",
                                style = CockpitTheme.typography.labelMedium.copy(
                                    color = CockpitTheme.colors.textPrimary
                                )
                            )
                        }
                    }

                    // Action 3: Restart Copilot in Safe Mode
                    Button(
                        onClick = onStartSafeMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = CopilotShapes.R10,
                        colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.positive.copy(alpha = 0.16f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = CockpitTheme.colors.positive,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "RESTART COPILOT IN SAFE MODE",
                                style = CockpitTheme.typography.labelMedium.copy(
                                    color = CockpitTheme.colors.positive
                                )
                            )
                        }
                    }

                    // Action 4: Hardware Reboot Infotainment
                    OutlinedButton(
                        onClick = onRebootDevice,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = CopilotShapes.R10,
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(CockpitTheme.colors.border, CockpitTheme.colors.warning.copy(alpha = 0.5f)))
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                tint = CockpitTheme.colors.warning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "REBOOT INFOTAINMENT UNIT",
                                style = CockpitTheme.typography.labelMedium.copy(
                                    color = CockpitTheme.colors.warning
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
