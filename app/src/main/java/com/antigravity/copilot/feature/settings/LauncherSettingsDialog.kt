package com.antigravity.copilot.feature.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.persistence.FullBackupManager
import com.antigravity.copilot.core.recovery.CrashGuardian
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.util.NumberFormatUtils
import kotlinx.coroutines.launch

enum class SettingsTab(val title: String, val icon: ImageVector, val subtitle: String) {
    GENERAL("General", Icons.Default.Tune, "Cockpit theme & system preferences"),
    VEHICLE("Vehicle", Icons.Default.DirectionsCar, "Vehicle profile, fuel & tank capacity"),
    GEOFENCES("Trip Geofences", Icons.Default.Place, "Configured locations & automatic lifecycle"),
    DATA_STORAGE("Data & Storage", Icons.Default.Storage, "Permanent backup, restore & resets"),
    ABOUT("About", Icons.Default.Info, "Version, telemetry specs & system info")
}

/**
 * Rebuilt Full-Screen Automotive Settings Screen:
 * - Opens 100% full screen with native BackHandler and round back button.
 * - Dedicated Vertical Tab Rail on the left with prominent typography.
 * - Scaled, large, glanceable typography matching homescreen design tokens ("bada bada dikhe").
 * - Cleaned-up tabs: Removed Appearance, Tracking, Maps, and Speed & Safety (always enabled).
 * - Cleaned-up General: Removed Display & Screen Behavior and Localization.
 * - Cleaned-up Vehicle: Completely removed redundant Expected Mileage input.
 */
@Composable
fun LauncherSettingsDialog(
    currentFuelState: FuelState,
    @Suppress("UNUSED_PARAMETER") recordedLifetimeKm: Double = 0.0,
    initialCarName: String = "My Car",
    initialFuelType: String = "Petrol",
    onSaveVehicleConfig: (name: String, fuelType: String, tankCapacity: Double, mileage: Double) -> Unit,
    @Suppress("UNUSED_PARAMETER") onCalibrateMidBar: (midBar: Int) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onCalibrateOdometer: (odometerKm: Double) -> Unit = {},
    onLaunchRestorePicker: () -> Unit = {},
    onResetAllDataConfirmed: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onPreviewTheme: (CockpitThemeMode) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        AppSettingsManager.init(context)
    }

    val settingsState by AppSettingsManager.settingsState.collectAsState()
    var selectedTab by remember { mutableStateOf(SettingsTab.GENERAL) }

    // Vehicle state
    var carName by remember(settingsState.carName) { mutableStateOf(settingsState.carName.ifBlank { initialCarName }) }
    var selectedFuelType by remember(settingsState.fuelType) { mutableStateOf(settingsState.fuelType.ifBlank { initialFuelType }) }
    var tankCapacityText by remember { mutableStateOf(currentFuelState.designCapacityLiters.toString()) }

    val coroutineScope = rememberCoroutineScope()
    var isBackupInProgress by remember { mutableStateOf(false) }

    // Two-Step Factory Reset State
    var isResetWarningOpen by remember { mutableStateOf(false) }
    var isPasswordPromptOpen by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF070D18))
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ==================== TOP ACTION BAR ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1424))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF162030))
                            .border(1.2.dp, Color(0xFF26354A), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Cockpit",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                            .border(1.2.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Automotive System Settings",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.Black,
                                fontSize = 24.sp
                            ),
                            color = Color.White
                        )
                        Text(
                            text = "${selectedTab.title} • ${selectedTab.subtitle}",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            color = Color(0xFF87929A)
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF162030))
                        .border(1.2.dp, Color(0xFF26354A), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1E2838), thickness = 1.5.dp)

            // ==================== BODY: VERTICAL TABS (LEFT) + CONTENT (RIGHT) ====================
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT NAVIGATION RAIL
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF0A111F))
                        .padding(horizontal = 14.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val activeBg = Color(0xFF0284C7).copy(alpha = 0.24f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) activeBg else Color.Transparent)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.65f) else Color(0xFF152238),
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .clickable { selectedTab = tab }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF141F32)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) Color.White else Color(0xFF87929A),
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = tab.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }

                // VERTICAL DIVIDER
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF1E2838))
                )

                // RIGHT CONTENT AREA (INDEPENDENT SCROLLING)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color(0xFF070D18))
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    AnimatedContent(
                        targetState = selectedTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "SettingsTabTransition"
                    ) { target ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            when (target) {
                                SettingsTab.GENERAL -> GeneralSettingsContent(
                                    state = settingsState
                                )
                                SettingsTab.VEHICLE -> VehicleSettingsContent(
                                    currentFuelState = currentFuelState,
                                    carName = carName,
                                    onCarNameChange = { carName = it },
                                    selectedFuelType = selectedFuelType,
                                    onFuelTypeChange = { selectedFuelType = it },
                                    tankCapacityText = tankCapacityText,
                                    onTankCapacityChange = { tankCapacityText = it },
                                    onSaveConfig = {
                                        val cap = (tankCapacityText.toDoubleOrNull() ?: 48.0).coerceIn(10.0, 200.0)
                                        val currentMileage = currentFuelState.expectedMileageKmpl.coerceIn(4.0, 60.0)
                                        AppSettingsManager.updateVehiclePhysicalSpecs(context, carName, selectedFuelType, cap, currentMileage)
                                        onSaveVehicleConfig(carName, selectedFuelType, cap, currentMileage)
                                        Toast.makeText(context, "Vehicle configuration saved!", Toast.LENGTH_SHORT).show()
                                    }
                                )
                                SettingsTab.GEOFENCES -> GeofenceSettingsContent()
                                SettingsTab.DATA_STORAGE -> DataStorageSettingsContent(
                                    isBackupInProgress = isBackupInProgress,
                                    onBackupNow = {
                                        if (!isBackupInProgress) {
                                            isBackupInProgress = true
                                            coroutineScope.launch {
                                                val res = FullBackupManager.createFullBackupZip(context)
                                                isBackupInProgress = false
                                                if (res.isSuccess && res.backupFile != null) {
                                                    Toast.makeText(
                                                        context,
                                                        "Backup saved: ${res.backupFile.name} (${res.manifest?.tripCount} trips, ${res.manifest?.fuelEventCount} fuel refills)",
                                                        Toast.LENGTH_LONG
                                                    ).show()
                                                } else {
                                                    Toast.makeText(context, "Backup failed: ${res.errorMessage}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    },
                                    onRestoreNow = onLaunchRestorePicker,
                                    onTriggerReset = {
                                        isResetWarningOpen = true
                                    }
                                )
                                SettingsTab.ABOUT -> AboutSettingsContent()
                            }
                        }
                    }
                }
            }
        }
    }

    // Step 1: Warning Dialog for Factory Reset
    if (isResetWarningOpen) {
        AlertDialog(
            onDismissRequest = { isResetWarningOpen = false },
            containerColor = Color(0xFF1A0B10),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Cockpit_RedAlert, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Factory Reset App Data",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontSize = 22.sp
                        ),
                        color = Cockpit_RedAlert
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Are you sure you want to perform a factory data reset?",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = Color.White
                    )
                    Text(
                        text = "This will permanently erase all recorded trips, driving GPS breadcrumbs, fuel refill history, and reset odometer counters to 0.0 km.\n\nNOTE: Existing portable backup files (*.zip) in storage will NOT be deleted.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 15.sp,
                            lineHeight = 22.sp
                        ),
                        color = Color(0xFFCBD5E1)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isResetWarningOpen = false
                        passwordInput = ""
                        passwordError = null
                        isPasswordPromptOpen = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Proceed to Confirmation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { isResetWarningOpen = false }) {
                    Text("Cancel", color = Color.White, fontSize = 16.sp)
                }
            }
        )
    }

    // Step 2: Password Authentication Confirmation Popup
    if (isPasswordPromptOpen) {
        AlertDialog(
            onDismissRequest = { isPasswordPromptOpen = false },
            containerColor = Color(0xFF111726),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(26.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Admin Authentication",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        ),
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Enter master admin password to confirm factory reset:",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp),
                        color = Color(0xFF87929A)
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = {
                            passwordInput = it
                            passwordError = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        shape = RoundedCornerShape(12.dp),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF1E2838)
                        ),
                        placeholder = { Text("Enter password", color = Color(0xFF64748B), fontSize = 16.sp) }
                    )

                    passwordError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            ),
                            color = Cockpit_RedAlert
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = passwordInput.trim()
                        if (trimmed == "@Shadow@511" || trimmed == "Shadow@511" || trimmed == "Shadow511" || (trimmed.contains("Shadow") && trimmed.contains("511"))) {
                            onResetAllDataConfirmed()
                            isPasswordPromptOpen = false
                            Toast.makeText(context, "App data successfully reset to 0!", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else {
                            passwordError = "Incorrect password! Access denied."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Confirm Factory Reset", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { isPasswordPromptOpen = false }) {
                    Text("Cancel", color = Color.White, fontSize = 16.sp)
                }
            }
        )
    }
}

// ==================== TAB 1: GENERAL ====================

@Composable
private fun GeneralSettingsContent(
    state: AppSettingsManager.SettingsState
) {
    val context = LocalContext.current

    // DASHBOARD THEME & APPEARANCE
    SettingsCard(title = "Cockpit Color Theme & Styling", icon = Icons.Default.Palette) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Select high-contrast cockpit styling calibrated for day and night driving:",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                color = CockpitTheme.colors.textSecondary
            )

            listOf(
                Triple(
                    CockpitThemeMode.APEX,
                    CockpitThemeMode.APEX.displayName,
                    listOf(Apex_Background, Apex_CardSurface, Apex_PrimaryAccent)
                )
            ).forEach { (mode, name, swatches) ->
                val isSelected = state.cockpitThemeMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .clickable { AppSettingsManager.updateCockpitTheme(context, mode) }
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            ),
                            color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
                        )
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            color = CockpitTheme.colors.textSecondary
                        )
                    }

                    // Preview Swatches
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        swatches.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.2.dp, CockpitTheme.colors.border, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }

    // AUTOMOTIVE RUNTIME SPECIFICATION CARD
    SettingsCard(title = "Automotive Runtime Environment", icon = Icons.Default.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Infotainment display is calibrated for 10.1\" landscape (1280x800). Features driver-safe touch zones (64–88dp), an 8dp spatial grid, high-contrast glanceable telemetry, and automatic hardware acceleration.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = CockpitTheme.colors.textSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusBadge(text = "SCREEN ALWAYS ON", modifier = Modifier.weight(1f))
                StatusBadge(text = "METRIC (KM & KM/H)", modifier = Modifier.weight(1f))
                StatusBadge(text = "APEX HIGH CONTRAST", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(CockpitTheme.colors.accentSoft)
            .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Black,
                fontSize = 13.5.sp
            ),
            color = CockpitTheme.colors.accent
        )
    }
}

// ==================== TAB 2: VEHICLE ====================

@Composable
private fun VehicleSettingsContent(
    currentFuelState: FuelState,
    carName: String,
    onCarNameChange: (String) -> Unit,
    selectedFuelType: String,
    onFuelTypeChange: (String) -> Unit,
    tankCapacityText: String,
    onTankCapacityChange: (String) -> Unit,
    onSaveConfig: () -> Unit
) {
    SettingsCard(title = "Vehicle Profile & Powertrain", icon = Icons.Default.DirectionsCar) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // 1. Vehicle Name
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Vehicle Name / Model",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = Color.White
                )
                OutlinedTextField(
                    value = carName,
                    onValueChange = onCarNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF1E2838),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0B1220)
                    ),
                    placeholder = { Text("e.g. Honda City, Creta, Nexon", color = Color(0xFF64748B), fontSize = 16.sp) }
                )
            }

            // 2. Powertrain / Fuel Type Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Powertrain / Fuel Type",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = Color.White
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val types = listOf("Petrol", "Diesel", "CNG", "Electric", "Hybrid")
                    types.forEach { type ->
                        val isSelected = selectedFuelType.equals(type, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF0F172A))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2838),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable { onFuelTypeChange(type) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                ),
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            // 3. Design Tank Capacity (L)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Design Fuel Tank Capacity (Liters)",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    ),
                    color = Color.White
                )
                OutlinedTextField(
                    value = tankCapacityText,
                    onValueChange = onTankCapacityChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF1E2838),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0B1220)
                    )
                )
                Text(
                    text = "Learned Max: ${NumberFormatUtils.formatFuelLiters(currentFuelState.maxCapacityLiters, 2)} L (Automatically adapts via past fill-ups)",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = Color(0xFF38BDF8)
                )
            }

            // 4. Save Button
            Button(
                onClick = onSaveConfig,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Save Vehicle Configuration", fontWeight = FontWeight.Bold, fontSize = 17.sp)
            }
        }
    }
}

// ==================== TAB 3: DATA & STORAGE ====================

@Composable
private fun DataStorageSettingsContent(
    isBackupInProgress: Boolean,
    onBackupNow: () -> Unit,
    onRestoreNow: () -> Unit,
    onTriggerReset: () -> Unit
) {
    SettingsCard(title = "Portable Backup & Archive", icon = Icons.Default.Backup) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Create a complete, portable ZIP archive with manifest containing all recorded trips, GPS trails, fuel ledger entries, odometer calibrations, vehicle profile and preferences. Saved to /sdcard/Copilot/Backups.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = Color(0xFF94A3B8)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = onBackupNow,
                    enabled = !isBackupInProgress,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBackupInProgress) "Creating Backup..." else "Backup to Storage Now",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }

                OutlinedButton(
                    onClick = onRestoreNow,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF38BDF8))
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore from Storage", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF38BDF8))
                }
            }
        }
    }

    // Visually Distinct Danger Zone
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF261014))
            .border(1.5.dp, Cockpit_RedAlert.copy(alpha = 0.7f), RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Cockpit_RedAlert, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Danger Zone – Factory Reset",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 20.sp),
                    color = Cockpit_RedAlert
                )
            }
            Text(
                text = "Permanently reset all recorded trips, driving history, fuel refill logs, and odometer counters back to 0.0 km. Existing portable backup files (*.zip) in storage will NOT be deleted.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = Color.White.copy(alpha = 0.9f)
            )

            Button(
                onClick = onTriggerReset,
                colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(50.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Factory Reset App Data", fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
    }
}

// ==================== TAB 4: ABOUT ====================

@Composable
private fun AboutSettingsContent() {
    val context = LocalContext.current
    var crashCount by remember { mutableIntStateOf(CrashGuardian.getCrashCount(context)) }

    SettingsCard(title = "Infotainment Launcher & Recovery", icon = Icons.Default.Security) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stock Blaupunkt Launcher",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        color = Color.White
                    )
                    Text(
                        text = "Blaupunkt Launcher3 (${CrashGuardian.STOCK_LAUNCHER_PACKAGE})",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = Color(0xFF87929A)
                    )
                }
                Button(
                    onClick = {
                        CrashGuardian.launchStockLauncher(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp), tint = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch to Stock", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color(0xFF1A2332))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = {
                        val ok = CrashGuardian.setCopilotAsDefaultHome(context)
                        Toast.makeText(context, if (ok) "Copilot set as Primary HOME Launcher" else "Failed setting HOME launcher", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set as Default Launcher", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val ok = CrashGuardian.restoreStockLauncherAsDefault(context)
                        Toast.makeText(context, if (ok) "Stock Blaupunkt restored as default HOME" else "Stock Blaupunkt preference updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore Stock as Default", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color(0xFF1A2332))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CrashGuardian Watchdog",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        color = Color.White
                    )
                    Text(
                        text = if (crashCount == 0) "Status: Normal (0 crashes recorded)" else "Recent crashes: $crashCount / ${CrashGuardian.MAX_CRASHES_BEFORE_RECOVERY}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = if (crashCount == 0) Color(0xFF10B981) else Color(0xFFF59E0B)
                    )
                }
                if (crashCount > 0) {
                    OutlinedButton(
                        onClick = {
                            CrashGuardian.resetCrashHistory(context)
                            crashCount = 0
                            Toast.makeText(context, "Crash history reset", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Reset Counter", fontSize = 14.sp)
                    }
                }
            }
        }
    }

    SettingsCard(title = "Copilot Automotive Infotainment", icon = Icons.Default.Info) {
        val appVersionName = com.antigravity.copilot.BuildConfig.VERSION_NAME
        val appVersionCode = com.antigravity.copilot.BuildConfig.VERSION_CODE
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Application Version",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = "Release v$appVersionName (Build $appVersionCode)",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                        color = CockpitTheme.colors.textSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CockpitTheme.colors.accentSoft)
                        .border(1.2.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "v$appVersionName",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum",
                            fontSize = 16.sp
                        ),
                        color = CockpitTheme.colors.accent
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1A2332))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Cockpit Specification",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp),
                    color = Color.White
                )
                Text(
                    text = "Optimized for 1280×800 landscape in-car digital clusters. Built with 100% Kotlin, Jetpack Compose Material 3, Room SQLite, OSMDroid Offline Navigation, and Autonomous Vehicle State Tracking.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = Color(0xFF87929A)
                )
            }
        }
    }
}

// ==================== REUSABLE SETTINGS COMPONENTS ====================

@Composable
private fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF0C1424))
            .border(1.2.dp, Color(0xFF1E2838), RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ),
                    color = Color.White
                )
            }
            content()
        }
    }
}
