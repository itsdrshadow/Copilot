package com.antigravity.copilot.feature.settings

import android.widget.Toast
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.antigravity.copilot.core.persistence.FullBackupManager
import com.antigravity.copilot.core.recovery.CrashGuardian
import kotlinx.coroutines.launch
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.util.NumberFormatUtils

enum class SettingsTab(val title: String, val icon: ImageVector, val subtitle: String) {
    GENERAL("General", Icons.Default.Tune, "Theme, screen & system preferences"),
    VEHICLE("Vehicle", Icons.Default.DirectionsCar, "Vehicle profile, fuel & odometer"),
    TRACKING("Tracking", Icons.Default.GpsFixed, "GPS telemetry, trips & watchdog"),
    GEOFENCES("Trip Geofences", Icons.Default.Place, "Configured locations & automatic lifecycle"),
    MAPS("Maps", Icons.Default.Map, "Offline-first maps & display styling"),
    SPEED_SAFETY("Speed & Safety", Icons.Default.Speed, "Statutory speed limits & safety alerts"),
    DATA_STORAGE("Data & Storage", Icons.Default.Storage, "Permanent backup, restore & resets"),
    ABOUT("About", Icons.Default.Info, "Version, telemetry specs & system info")
}

/**
 * Premium Samsung One UI Inspired Automotive Settings Screen:
 * - Dedicated Vertical Tab Rail on the left (General, Vehicle, Tracking, Maps, Speed & Safety, Data & Storage, About).
 * - Independent scrollable settings container on the right.
 * - Optimized for 1280x800 landscape automotive displays.
 * - Fully persistent settings via AppSettingsManager and existing core managers.
 * - Dark mode automotive styling with high-contrast touch feedback.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    onPreviewTheme: (CockpitThemeMode) -> Unit = {},
    onDismiss: () -> Unit
) {
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
    var mileageText by remember { mutableStateOf(currentFuelState.expectedMileageKmpl.toString()) }



    val coroutineScope = rememberCoroutineScope()
    var isBackupInProgress by remember { mutableStateOf(false) }

    // Two-Step Factory Reset State
    var isResetWarningOpen by remember { mutableStateOf(false) }
    var isPasswordPromptOpen by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF070D18))
                .border(1.5.dp, Color(0xFF1E2838), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ==================== TOP ACTION BAR ====================
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0B1424))
                        .border(width = 0.dp, color = Color.Transparent)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                                .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.45f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                text = "Automotive System Settings",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Black,
                                    fontSize = 20.sp
                                ),
                                color = Color.White
                            )
                            Text(
                                text = "${selectedTab.title} • ${selectedTab.subtitle}",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                                color = Color(0xFF87929A)
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF162030))
                            .border(1.dp, Color(0xFF26354A), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                HorizontalDivider(color = Color(0xFF1E2838))

                // ==================== BODY: VERTICAL TABS (LEFT) + CONTENT (RIGHT) ====================
                Row(modifier = Modifier.fillMaxSize()) {
                    // LEFT NAVIGATION RAIL
                    Column(
                        modifier = Modifier
                            .width(235.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF0A111F))
                            .padding(horizontal = 10.dp, vertical = 12.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SettingsTab.values().forEach { tab ->
                            val isSelected = selectedTab == tab
                            val activeBg = Color(0xFF0284C7).copy(alpha = 0.22f)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) activeBg else Color.Transparent)
                                    .border(
                                        width = 1.dp,
                                        color = if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.5f) else Color.Transparent,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable { selectedTab = tab }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF141F32)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.White else Color(0xFF87929A),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold,
                                        fontSize = 14.sp
                                    ),
                                    color = if (isSelected) Color.White else Color(0xFF87929A)
                                )
                            }
                        }
                    }

                    // VERTICAL DIVIDER
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF1E2838))
                    )

                    // RIGHT CONTENT AREA (INDEPENDENT SCROLLING)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color(0xFF070D18))
                            .padding(18.dp)
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
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                when (target) {
                                    SettingsTab.GENERAL -> GeneralSettingsContent(
                                        state = settingsState,
                                        onToggleScreenAlwaysOn = { AppSettingsManager.updateScreenAlwaysOn(context, it) },
                                        onToggleAutoMinimize = { AppSettingsManager.updateAutoMinimizeDrawer(context, it) }
                                    )
                                    SettingsTab.VEHICLE -> VehicleSettingsContent(
                                        currentFuelState = currentFuelState,
                                        carName = carName,
                                        onCarNameChange = { carName = it },
                                        selectedFuelType = selectedFuelType,
                                        onFuelTypeChange = { selectedFuelType = it },
                                        tankCapacityText = tankCapacityText,
                                        onTankCapacityChange = { tankCapacityText = it },
                                        mileageText = mileageText,
                                        onMileageChange = { mileageText = it },
                                        onSaveConfig = {
                                            val cap = (tankCapacityText.toDoubleOrNull() ?: 48.0).coerceIn(10.0, 200.0)
                                            val mil = (mileageText.toDoubleOrNull() ?: 18.0).coerceIn(4.0, 60.0)
                                            AppSettingsManager.updateVehiclePhysicalSpecs(context, carName, selectedFuelType, cap, mil)
                                            onSaveVehicleConfig(carName, selectedFuelType, cap, mil)
                                            Toast.makeText(context, "Vehicle configuration saved!", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                    SettingsTab.TRACKING -> TrackingSettingsContent(
                                        state = settingsState,
                                        onToggleAutoTrip = { AppSettingsManager.updateAutoTripDetection(context, it) },
                                        onToggleEngineOff = { AppSettingsManager.updateEngineOffDetection(context, it) }
                                    )
                                    SettingsTab.GEOFENCES -> GeofenceSettingsContent()
                                    SettingsTab.MAPS -> MapsSettingsContent(
                                        state = settingsState,
                                        onToggleFootprints = { AppSettingsManager.updateMapBuildingFootprints(context, it) }
                                    )
                                    SettingsTab.SPEED_SAFETY -> SpeedSafetySettingsContent(
                                        state = settingsState,
                                        onToggleDynamicLimits = { AppSettingsManager.updateDynamicSpeedLimits(context, it) },
                                        onToggleOverspeedAlerts = { AppSettingsManager.updateOverspeedAlerts(context, it) },
                                        onToggleNearLimit = { AppSettingsManager.updateNearLimitWarning(context, it) },
                                        onToggleDriverScoring = { AppSettingsManager.updateDriverScoring(context, it) }
                                    )
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
    }

    // Step 1: Warning Dialog for Factory Reset
    if (isResetWarningOpen) {
        AlertDialog(
            onDismissRequest = { isResetWarningOpen = false },
            containerColor = Color(0xFF1A0B10),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Cockpit_RedAlert, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Factory Reset App Data",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                        color = Cockpit_RedAlert
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Are you sure you want to perform a factory data reset?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                    Text(
                        text = "This will permanently erase all recorded trips, driving GPS breadcrumbs, fuel refill history, and reset odometer counters to 0.0 km.\n\nNOTE: Existing portable backup files (*.zip) in storage will NOT be deleted.",
                        style = MaterialTheme.typography.bodyMedium,
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Proceed to Confirmation", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { isResetWarningOpen = false }) {
                    Text("Cancel", color = Color.White)
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
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Admin Authentication",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Enter master admin password to confirm factory reset:",
                        style = MaterialTheme.typography.bodyMedium,
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
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF1E2838)
                        ),
                        placeholder = { Text("Enter password", color = Color(0xFF64748B)) }
                    )

                    passwordError?.let { err ->
                        Text(
                            text = err,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("Confirm Factory Reset", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { isPasswordPromptOpen = false }) {
                    Text("Cancel", color = Color.White)
                }
            }
        )
    }
}

// ==================== TAB 1: GENERAL ====================

@Composable
private fun GeneralSettingsContent(
    state: AppSettingsManager.SettingsState,
    onToggleScreenAlwaysOn: (Boolean) -> Unit,
    onToggleAutoMinimize: (Boolean) -> Unit
) {
    val context = LocalContext.current

    // 1. DASHBOARD THEME & APPEARANCE
    SettingsCard(title = "Appearance & Theme", icon = Icons.Default.Palette) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Cockpit Color Theme",
                style = MaterialTheme.typography.titleSmall,
                color = CockpitTheme.colors.textPrimary
            )

            // Theme Option with Preview Swatches
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                        .border(
                            width = 1.2.dp,
                            color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { AppSettingsManager.updateCockpitTheme(context, mode) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
                        )
                        Text(
                            text = mode.subtitle,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = CockpitTheme.colors.textSecondary
                        )
                    }

                    // Small preview swatch (bg, card, accent)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        swatches.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, CockpitTheme.colors.border, CircleShape)
                            )
                        }
                    }
                }
            }
        }
    }

    // 2. DISPLAY & SCREEN BEHAVIOR
    SettingsCard(title = "Display & Screen Behavior", icon = Icons.Default.BrightnessMedium) {
        SettingsToggleRow(
            title = "Keep Screen Always On",
            subtitle = "Prevents display timeout while launcher is active or driving is detected",
            checked = state.screenAlwaysOn,
            onCheckedChange = onToggleScreenAlwaysOn
        )
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Auto-Minimize App Drawer",
            subtitle = "Automatically close the application drawer after launching an app",
            checked = state.autoMinimizeDrawer,
            onCheckedChange = onToggleAutoMinimize
        )
    }

    // 4. LOCALIZATION & UNITS
    SettingsCard(title = "Localization & Measurement Units", icon = Icons.Default.Language) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Distance & Speed Units", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("Kilometers (km), Speed (km/h), Consumption (km/L)", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
            }
            Text(
                text = "Metric (Standard)",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF38BDF8)
            )
        }
        HorizontalDivider(color = Color(0xFF1A2332))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Active Cockpit Theme", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("Premium Cockpit", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
            }
            Text(
                text = "Premium Studio",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF35BDF5)
            )
        }
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
    mileageText: String,
    onMileageChange: (String) -> Unit,
    onSaveConfig: () -> Unit
) {
    // 1. Vehicle Identity
    SettingsCard(title = "Vehicle Profile & Powertrain", icon = Icons.Default.DirectionsCar) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Vehicle Name / Model", style = MaterialTheme.typography.labelMedium, color = Color.White)
            OutlinedTextField(
                value = carName,
                onValueChange = onCarNameChange,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF38BDF8),
                    unfocusedBorderColor = Color(0xFF1E2838),
                    focusedContainerColor = Color(0xFF0F172A),
                    unfocusedContainerColor = Color(0xFF0B1220)
                ),
                placeholder = { Text("e.g. Honda City, Creta, Nexon", color = Color(0xFF64748B)) }
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Powertrain / Fuel Type", style = MaterialTheme.typography.labelMedium, color = Color.White)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val types = listOf("Petrol", "Diesel", "CNG", "Electric", "Hybrid")
                types.forEach { type ->
                    val isSelected = selectedFuelType.equals(type, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) Color(0xFF0284C7) else Color(0xFF0F172A))
                            .border(1.dp, if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E2838), RoundedCornerShape(10.dp))
                            .clickable { onFuelTypeChange(type) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = type,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                            color = if (isSelected) Color.White else Color(0xFF87929A)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Design Tank Capacity (L)", style = MaterialTheme.typography.labelMedium, color = Color.White)
                OutlinedTextField(
                    value = tankCapacityText,
                    onValueChange = onTankCapacityChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
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
                    text = "Learned Max: ${NumberFormatUtils.formatFuelLiters(currentFuelState.maxCapacityLiters, 2)} L (Adapts via past fill-ups)",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color(0xFF38BDF8).copy(alpha = 0.85f)
                )
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Expected Baseline Mileage (km/L)", style = MaterialTheme.typography.labelMedium, color = Color.White)
                OutlinedTextField(
                    value = mileageText,
                    onValueChange = onMileageChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF38BDF8),
                        unfocusedBorderColor = Color(0xFF1E2838),
                        focusedContainerColor = Color(0xFF0F172A),
                        unfocusedContainerColor = Color(0xFF0B1220)
                    )
                )
            }
        }

        Button(
            onClick = onSaveConfig,
            modifier = Modifier.fillMaxWidth().height(42.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Save Vehicle Configuration", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }

}

// ==================== TAB 3: TRACKING ====================

@Composable
private fun TrackingSettingsContent(
    state: AppSettingsManager.SettingsState,
    onToggleAutoTrip: (Boolean) -> Unit,
    onToggleEngineOff: (Boolean) -> Unit
) {
    SettingsCard(title = "Kinematic Vehicle State Tracking", icon = Icons.Default.GpsFixed) {
        SettingsToggleRow(
            title = "Automatic Trip Detection",
            subtitle = "Initiates trip when vehicle moves > 3 km/h; completes trip on sustained standstill",
            checked = state.autoTripDetection,
            onCheckedChange = onToggleAutoTrip
        )
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Stationary Engine-Off Classification",
            subtitle = "Guarantees Engine-Off state remains held across zero-speed watchdog ticks and ignition power cuts",
            checked = state.engineOffDetection,
            onCheckedChange = onToggleEngineOff
        )
    }

    SettingsCard(title = "GPS Telemetry Performance", icon = Icons.Default.Sensors) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Telemetry Polling Frequency", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("High-accuracy 1 Hz automotive sensor fusion", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
            }
            Text("1000 ms (1 Hz)", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF10B981))
        }
    }
}

// ==================== TAB 4: MAPS ====================

@Composable
private fun MapsSettingsContent(
    state: AppSettingsManager.SettingsState,
    onToggleFootprints: (Boolean) -> Unit
) {
    SettingsCard(title = "Default Satellite Automotive Navigation", icon = Icons.Default.Map) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Map Engine Style", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("High-Resolution Satellite & Aerial Hybrid with high-contrast road overlays", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
            }
            Text("Satellite Hybrid", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF38BDF8))
        }
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Building Footprints & House Numbers",
            subtitle = "Renders structural footprints, emergency services and civic landmarks",
            checked = state.mapBuildingFootprints,
            onCheckedChange = onToggleFootprints
        )
    }

    SettingsCard(title = "Permanent Cache Retention", icon = Icons.Default.Storage) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Tile Expiration Policy", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Text("Tiles are cached permanently and never purged while offline", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
            }
            Text("10-Year Permanent", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = Color(0xFF10B981))
        }
    }
}

// ==================== TAB 5: SPEED & SAFETY ====================

@Composable
private fun SpeedSafetySettingsContent(
    state: AppSettingsManager.SettingsState,
    onToggleDynamicLimits: (Boolean) -> Unit,
    onToggleOverspeedAlerts: (Boolean) -> Unit,
    onToggleNearLimit: (Boolean) -> Unit,
    onToggleDriverScoring: (Boolean) -> Unit
) {
    SettingsCard(title = "Statutory Speed Limits (MoRTH & OSM)", icon = Icons.Default.Speed) {
        SettingsToggleRow(
            title = "Dynamic Speed Limit Engine",
            subtitle = "Evaluates OSM road attributes & MoRTH regulations (120/100/80/50 km/h zones)",
            checked = state.dynamicSpeedLimits,
            onCheckedChange = onToggleDynamicLimits
        )
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Overspeed Visual Alert Ring",
            subtitle = "Pulses crimson warning on speedometer when vehicle speed exceeds zone limit",
            checked = state.overspeedAlerts,
            onCheckedChange = onToggleOverspeedAlerts
        )
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Near-Limit Proximity Indicator",
            subtitle = "Amber advisory visual cues when speed reaches within 88% of road limit",
            checked = state.nearLimitWarning,
            onCheckedChange = onToggleNearLimit
        )
        HorizontalDivider(color = Color(0xFF1A2332))
        SettingsToggleRow(
            title = "Automotive Driver Scoring",
            subtitle = "Calculates driving safety score based on harsh braking, acceleration and idle ratio",
            checked = state.driverScoring,
            onCheckedChange = onToggleDriverScoring
        )
    }

    // Voice Assistant & Audio Guidance Settings
    val assistant = com.antigravity.copilot.core.tts.AutomotiveVoiceAssistant.getInstance(LocalContext.current)
    val voices by assistant.availableVoices.collectAsState()
    val context = LocalContext.current

    SettingsCard(title = "Voice Guidance & Smart Audio", icon = Icons.Default.RecordVoiceOver) {
        SettingsToggleRow(
            title = "Spoken Automotive Announcements",
            subtitle = "Voice greeting on departure, trip arrival summary, fuel station reminders & fatigue alerts",
            checked = state.voiceGreetingsEnabled,
            onCheckedChange = { AppSettingsManager.updateVoiceGreetings(context, it) }
        )

        HorizontalDivider(color = CockpitTheme.colors.border)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Assistant Voice Profile",
                style = MaterialTheme.typography.titleSmall,
                color = CockpitTheme.colors.textPrimary
            )

            voices.forEach { profile ->
                val isSelected = state.ttsVoiceId == profile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                        .border(
                            1.dp,
                            if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { AppSettingsManager.updateTtsVoiceId(context, profile.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            ),
                            color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
                        )
                        Text(
                            text = profile.description,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                            color = CockpitTheme.colors.textSecondary
                        )
                    }

                    // Test Voice Play Button
                    Button(
                        onClick = { assistant.testVoicePrompt(profile) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.card
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = "Test voice",
                            tint = if (isSelected) CockpitTheme.colors.onAccent else CockpitTheme.colors.textPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "Test",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isSelected) CockpitTheme.colors.onAccent else CockpitTheme.colors.textPrimary
                        )
                    }
                }
            }
        }
    }
}

// ==================== TAB 6: DATA & STORAGE ====================

@Composable
private fun DataStorageSettingsContent(
    isBackupInProgress: Boolean,
    onBackupNow: () -> Unit,
    onRestoreNow: () -> Unit,
    onTriggerReset: () -> Unit
) {
    SettingsCard(title = "Portable Backup & Archive", icon = Icons.Default.Backup) {
        Text(
            text = "Create a complete, portable ZIP archive with manifest containing all recorded trips, GPS trails, fuel ledger entries, odometer calibrations, vehicle profile and preferences. Saved to /sdcard/Copilot/Backups.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF87929A)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onBackupNow,
                enabled = !isBackupInProgress,
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
            ) {
                Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(if (isBackupInProgress) "Creating Backup..." else "Backup to Storage Now", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }

            OutlinedButton(
                onClick = onRestoreNow,
                modifier = Modifier.weight(1f).height(42.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8))
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Restore from Storage", fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Color(0xFF38BDF8))
            }
        }
    }

    // Visually Distinct Danger Zone
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF261014))
            .border(1.2.dp, Cockpit_RedAlert.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = Cockpit_RedAlert, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Danger Zone – Factory Reset",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black, fontSize = 15.sp),
                    color = Cockpit_RedAlert
                )
            }
            Text(
                text = "Permanently reset all recorded trips, driving history, fuel refill logs, and odometer counters back to 0.0 km. Existing portable backup files (*.zip) in storage will NOT be deleted.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Color.White.copy(alpha = 0.85f)
            )

            Button(
                onClick = onTriggerReset,
                colors = ButtonDefaults.buttonColors(containerColor = Cockpit_RedAlert),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Factory Reset App Data", fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
        }
    }
}

// ==================== TAB 7: ABOUT ====================

@Composable
private fun AboutSettingsContent() {
    val context = LocalContext.current
    var crashCount by remember { mutableIntStateOf(CrashGuardian.getCrashCount(context)) }

    SettingsCard(title = "Infotainment Launcher & Recovery", icon = Icons.Default.Security) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Stock Blaupunkt Launcher", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text("Blaupunkt Launcher3 (${CrashGuardian.STOCK_LAUNCHER_PACKAGE})", style = MaterialTheme.typography.bodySmall, color = Color(0xFF87929A))
                }
                Button(
                    onClick = {
                        CrashGuardian.launchStockLauncher(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                ) {
                    Icon(Icons.Default.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color(0xFF38BDF8))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Switch to Stock", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color(0xFF1A2332))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val ok = CrashGuardian.setCopilotAsDefaultHome(context)
                        Toast.makeText(context, if (ok) "Copilot set as Primary HOME Launcher" else "Failed setting HOME launcher", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Set as Default Launcher", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val ok = CrashGuardian.restoreStockLauncherAsDefault(context)
                        Toast.makeText(context, if (ok) "Stock Blaupunkt restored as default HOME" else "Stock Blaupunkt preference updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Restore Stock as Default", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = Color(0xFF1A2332))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("CrashGuardian Watchdog", style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(
                        if (crashCount == 0) "Status: Normal (0 crashes recorded)" else "Recent crashes: $crashCount / ${CrashGuardian.MAX_CRASHES_BEFORE_RECOVERY}",
                        style = MaterialTheme.typography.bodySmall,
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
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Reset Counter", fontSize = 11.sp)
                    }
                }
            }
        }
    }

    SettingsCard(title = "Copilot Automotive Infotainment", icon = Icons.Default.Info) {
        val appVersionName = com.antigravity.copilot.BuildConfig.VERSION_NAME
        val appVersionCode = com.antigravity.copilot.BuildConfig.VERSION_CODE
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Application Version", style = MaterialTheme.typography.titleSmall, color = CockpitTheme.colors.textPrimary)
                Text("Release v$appVersionName (Build $appVersionCode)", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textSecondary)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(CockpitTheme.colors.accentSoft)
                    .border(1.dp, CockpitTheme.colors.accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "v$appVersionName",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    ),
                    color = CockpitTheme.colors.accent
                )
            }
        }

        HorizontalDivider(color = Color(0xFF1A2332))

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Cockpit Specification", style = MaterialTheme.typography.titleSmall, color = Color.White)
            Text(
                text = "Optimized for 1280×800 landscape in-car digital clusters. Built with 100% Kotlin, Jetpack Compose Material 3, Room SQLite, OSMDroid Offline Navigation, and Autonomous Vehicle State Tracking.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF87929A)
            )
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
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF0C1424))
            .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 14.5.sp),
                    color = Color.White
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp), color = Color.White)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = Color(0xFF87929A))
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF0284C7),
                uncheckedThumbColor = Color(0xFF87929A),
                uncheckedTrackColor = Color(0xFF141F32),
                uncheckedBorderColor = Color(0xFF1E2838)
            )
        )
    }
}
