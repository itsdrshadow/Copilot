package com.antigravity.drivingreport.core.recovery

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.antigravity.drivingreport.MainActivity
import com.antigravity.drivingreport.core.designsystem.*
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
            .background(Color(0xFF060B14))
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF140D12))
                    .border(1.2.dp, Cockpit_RedAlert.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
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
                            .background(Cockpit_RedAlert.copy(alpha = 0.2f))
                            .border(1.2.dp, Cockpit_RedAlert, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alert",
                            tint = Cockpit_RedAlert,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "EMERGENCY SYSTEM RECOVERY",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 16.sp,
                                    letterSpacing = 0.5.sp
                                ),
                                color = Color.White
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Cockpit_RedAlert.copy(alpha = 0.25f))
                                    .border(0.8.dp, Cockpit_RedAlert, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "$crashCount FAILURES DETECTED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    ),
                                    color = Cockpit_RedAlert
                                )
                            }
                        }
                        Text(
                            text = "Repeated crash loop intercepted. Vehicle safety guaranteed via stock launcher fallback.",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = Cockpit_TextSecondary
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
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        color = if (isCountdownPaused) Cockpit_AmberWarning else Cockpit_Cyan
                    )

                    OutlinedButton(
                        onClick = { isCountdownPaused = !isCountdownPaused },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp),
                        shape = RoundedCornerShape(6.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(Cockpit_CardBorder, Cockpit_Cyan.copy(alpha = 0.4f)))
                        )
                    ) {
                        Text(
                            text = if (isCountdownPaused) "RESUME" else "PAUSE",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = Color.White
                        )
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
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF091424))
                            .border(1.dp, Cockpit_GreenEco.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Protected",
                                tint = Cockpit_GreenEco,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = "Telemetry & Fuel Database Intact",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontSize = 12.5.sp),
                                    color = Color.White
                                )
                                Text(
                                    text = "All fuel records, odometer baseline and completed trips are securely preserved in permanent SQLite storage.",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, lineHeight = 14.sp),
                                    color = Cockpit_TextSecondary
                                )
                            }
                        }
                    }

                    // Crash Diagnostic Card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0A101C))
                            .border(1.dp, Cockpit_CardBorder, RoundedCornerShape(12.dp))
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
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.5.sp,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = Cockpit_TextSecondary
                                )

                                TextButton(
                                    onClick = { showFullTrace = !showFullTrace },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (showFullTrace) "SHOW SUMMARY" else "VIEW FULL TRACE",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = Cockpit_Cyan
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF060B12))
                                    .border(0.8.dp, Cockpit_CardBorder.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = if (showFullTrace) crashTrace else crashMessage,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = 14.sp
                                    ),
                                    color = if (showFullTrace) Color(0xFFCBD5E1) else Cockpit_RedAlert
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cockpit_Cyan)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "LAUNCH BLAUPUNKT STOCK LAUNCHER",
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        fontSize = 13.sp,
                                        letterSpacing = 0.5.sp
                                    ),
                                    color = Color.Black
                                )
                                Text(
                                    text = "Instant 1-tap fallback to factory OEM interface",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    color = Color(0xFF003847)
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
                        shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(Cockpit_CardBorder, Cockpit_Cyan.copy(alpha = 0.5f)))
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsBackupRestore,
                                contentDescription = null,
                                tint = Cockpit_Cyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "RESTORE BLAUPUNKT AS DEFAULT HOME",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color.White
                                )
                            )
                        }
                    }

                    // Action 3: Restart DrivingReport in Safe Mode
                    Button(
                        onClick = onStartSafeMode,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF143026))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = Cockpit_GreenEco,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "RESTART DRIVINGREPORT IN SAFE MODE",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Cockpit_GreenEco
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
                        shape = RoundedCornerShape(10.dp),
                        border = ButtonDefaults.outlinedButtonBorder.copy(
                            brush = Brush.horizontalGradient(listOf(Cockpit_CardBorder, Cockpit_AmberWarning.copy(alpha = 0.5f)))
                        )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = null,
                                tint = Cockpit_AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "REBOOT INFOTAINMENT UNIT",
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.5.sp,
                                    color = Cockpit_AmberWarning
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
