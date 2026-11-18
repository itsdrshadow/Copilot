package com.antigravity.copilot.feature.settings

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import androidx.hilt.navigation.compose.hiltViewModel
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.Palette
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
 * - General: theme, ignition behaviour, display/launcher toggles and trip & fuel-stop detection
 *   thresholds — only settings the app actually acts on are exposed.
 * - Cleaned-up Vehicle: Completely removed redundant Expected Mileage input.
 */
@Composable
fun LauncherSettingsDialog(
    currentFuelState: FuelState,
    initialCarName: String = "My Car",
    initialFuelType: String = "Petrol",
    onSaveVehicleConfig: (name: String, fuelType: String, tankCapacity: Double, mileage: Double) -> Unit,
    onLaunchRestorePicker: () -> Unit = {},
    onResetAllDataConfirmed: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { msg ->
            Toast.makeText(context, msg, if (msg.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    val settingsState by viewModel.settings.collectAsStateWithLifecycle()
    val isBackupInProgress by viewModel.isBackupInProgress.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(SettingsTab.GENERAL) }

    // Vehicle state
    var carName by remember(settingsState.carName) { mutableStateOf(settingsState.carName.ifBlank { initialCarName }) }
    var selectedFuelType by remember(settingsState.fuelType) { mutableStateOf(settingsState.fuelType.ifBlank { initialFuelType }) }
    var tankCapacityText by remember { mutableStateOf(currentFuelState.designCapacityLiters.toString()) }


    // Two-Step Factory Reset State
    var isResetWarningOpen by remember { mutableStateOf(false) }
    var isPasswordPromptOpen by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .cockpitBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ==================== TOP ACTION BAR ====================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CockpitTheme.colors.cardElevated.copy(alpha = 0.85f))
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
                            .cockpitSurface(CockpitTheme.colors.card, CockpitTheme.colors.border, CircleShape, borderWidth = 1.2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Cockpit",
                            tint = CockpitTheme.colors.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.45f), CircleShape, borderWidth = 1.2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = CockpitTheme.colors.accent,
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Automotive System Settings",
                            style = CockpitTheme.typography.headlineLarge,
                            color = CockpitTheme.colors.textPrimary
                        )
                        Text(
                            text = "${selectedTab.title} • ${selectedTab.subtitle}",
                            style = CockpitTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium
                            ),
                            color = CockpitTheme.colors.textSecondary
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(48.dp)
                        .cockpitSurface(CockpitTheme.colors.card, CockpitTheme.colors.border, CircleShape, borderWidth = 1.2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = CockpitTheme.colors.textPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            HorizontalDivider(color = CockpitTheme.colors.border, thickness = 1.dp)

            // ==================== BODY: VERTICAL TABS (LEFT) + CONTENT (RIGHT) ====================
            Row(modifier = Modifier.fillMaxSize()) {
                // LEFT NAVIGATION RAIL
                Column(
                    modifier = Modifier
                        .width(270.dp)
                        .fillMaxHeight()
                        .background(CockpitTheme.colors.card.copy(alpha = 0.65f))
                        .padding(horizontal = 14.dp, vertical = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsTab.values().forEach { tab ->
                        val isSelected = selectedTab == tab
                        val activeBg = CockpitTheme.colors.accentSoft
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .clip(CopilotShapes.R16)
                                .background(if (isSelected) activeBg else Color.Transparent)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                                    shape = CopilotShapes.R16
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
                                    .background(if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.cardElevated),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = if (isSelected) CockpitTheme.colors.onAccent else CockpitTheme.colors.textSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Text(
                                text = tab.title,
                                style = CockpitTheme.typography.titleMedium.copy(
                                    fontWeight = if (isSelected) FontWeight.Black else FontWeight.Bold
                                ),
                                color = if (isSelected) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
                            )
                        }
                    }
                }

                // VERTICAL DIVIDER
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(CockpitTheme.colors.border)
                )

                // RIGHT CONTENT AREA (INDEPENDENT SCROLLING)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(Color.Transparent)
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
                                    state = settingsState,
                                    onSelectAccent = viewModel::selectAccent,
                                    onToggleAutoForeground = { viewModel.setAutoForegroundOnBoot(!settingsState.autoForegroundOnBoot) },
                                    onToggleScreenAlwaysOn = { viewModel.setScreenAlwaysOn(!settingsState.screenAlwaysOn) },
                                    onToggleAutoMinimizeDrawer = { viewModel.setAutoMinimizeDrawer(!settingsState.autoMinimizeDrawer) },
                                    onToggleStationPrompt = { viewModel.setStationStopPrompt(!settingsState.stationStopWatchdog) },
                                    onDwellSecondsChange = viewModel::setStationPromptDwellSeconds,
                                    onStationaryThresholdChange = viewModel::setStationarySpeedThreshold,
                                    onGeofenceRadiusChange = viewModel::setDefaultGeofenceRadius
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
                                        viewModel.saveVehicleSpecs(carName, selectedFuelType, cap, currentMileage)
                                        onSaveVehicleConfig(carName, selectedFuelType, cap, currentMileage)
                                    }
                                )
                                SettingsTab.GEOFENCES -> GeofenceSettingsContent()
                                SettingsTab.DATA_STORAGE -> DataStorageSettingsContent(
                                    isBackupInProgress = isBackupInProgress,
                                    onBackupNow = viewModel::backupNow,
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
            containerColor = CockpitTheme.colors.critical.copy(alpha = 0.14f),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = CockpitTheme.colors.critical, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Factory Reset App Data",
                        style = CockpitTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Black
                        ),
                        color = CockpitTheme.colors.critical
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Are you sure you want to perform a factory data reset?",
                        style = CockpitTheme.typography.titleLarge,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = "This will permanently erase all recorded trips, driving GPS breadcrumbs, fuel refill history, and reset odometer counters to 0.0 km.\n\nNOTE: Existing portable backup files (*.zip) in storage will NOT be deleted.",
                        style = CockpitTheme.typography.bodyMedium.copy(
                            lineHeight = 22.sp
                        ),
                        color = Palette.Slate300
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
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.critical),
                    shape = CopilotShapes.R12,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Proceed to Confirmation", style = CockpitTheme.typography.labelLarge, color = contentColorOn(CockpitTheme.colors.critical))
                }
            },
            dismissButton = {
                TextButton(onClick = { isResetWarningOpen = false }) {
                    Text("Cancel", color = CockpitTheme.colors.textPrimary, style = CockpitTheme.typography.labelLarge)
                }
            }
        )
    }

    // Step 2: Password Authentication Confirmation Popup
    if (isPasswordPromptOpen) {
        AlertDialog(
            onDismissRequest = { isPasswordPromptOpen = false },
            containerColor = CockpitTheme.colors.border,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Palette.Sky400, modifier = Modifier.size(26.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Admin Authentication",
                        style = CockpitTheme.typography.headlineMedium,
                        color = CockpitTheme.colors.textPrimary
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = "Enter master admin password to confirm factory reset:",
                        style = CockpitTheme.typography.bodyMedium,
                        color = Palette.Steel400
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
                        shape = CopilotShapes.R12,
                        textStyle = CockpitTheme.typography.bodyLarge,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = CockpitTheme.colors.textPrimary,
                            unfocusedTextColor = CockpitTheme.colors.textPrimary,
                            focusedBorderColor = Palette.Sky400,
                            unfocusedBorderColor = Palette.Ink750
                        ),
                        placeholder = { Text("Enter password", color = Palette.Slate500, style = CockpitTheme.typography.bodyMedium) }
                    )

                    passwordError?.let { err ->
                        Text(
                            text = err,
                            style = CockpitTheme.typography.labelLarge,
                            color = CockpitTheme.colors.critical
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
                    colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.critical),
                    shape = CopilotShapes.R12,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("Confirm Factory Reset", style = CockpitTheme.typography.labelLarge, color = contentColorOn(CockpitTheme.colors.critical))
                }
            },
            dismissButton = {
                TextButton(onClick = { isPasswordPromptOpen = false }) {
                    Text("Cancel", color = CockpitTheme.colors.textPrimary, style = CockpitTheme.typography.labelLarge)
                }
            }
        )
    }
}

// ==================== TAB 1: GENERAL ====================

@Composable
private fun GeneralSettingsContent(
    state: AppSettingsManager.SettingsState,
    onSelectAccent: (String) -> Unit,
    onToggleAutoForeground: () -> Unit,
    onToggleScreenAlwaysOn: () -> Unit,
    onToggleAutoMinimizeDrawer: () -> Unit,
    onToggleStationPrompt: () -> Unit,
    onDwellSecondsChange: (Int) -> Unit,
    onStationaryThresholdChange: (Float) -> Unit,
    onGeofenceRadiusChange: (Float) -> Unit
) {

    // ACCENT COLOUR: buttons, selected states, nav highlight, small labels and icons
    SettingsCard(title = "Accent colour", icon = Icons.Default.Palette) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "Colour used for buttons, selected items, labels and icons across the app.",
                style = CockpitTheme.typography.bodyMedium,
                color = CockpitTheme.colors.textSecondary
            )
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                com.antigravity.copilot.core.designsystem.colors.AccentOption.entries.forEach { option ->
                    val selected = state.accentOption == option
                    val swatch = option.night
                    Column(
                        modifier = Modifier
                            .width(64.dp)
                            .clip(CopilotShapes.R10)
                            .clickable { onSelectAccent(option.id) }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) CockpitTheme.colors.textPrimary else CockpitTheme.colors.border,
                                    shape = CircleShape
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = CockpitTheme.colors.onAccent, modifier = Modifier.size(22.dp))
                            }
                        }
                        Text(
                            text = option.displayName,
                            style = CockpitTheme.typography.labelMedium,
                            color = if (selected) CockpitTheme.colors.textPrimary else CockpitTheme.colors.textSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    // CAR STARTUP & BLUETOOTH MEDIA CARD
    SettingsCard(title = "Car Ignition & Bluetooth Media", icon = Icons.Default.DirectionsCar) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "Controls whether Copilot stays in front when car ignition turns on, allowing stock BT Music to run in the background without audio cut-off.",
                style = CockpitTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = CockpitTheme.colors.textSecondary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CopilotShapes.R16)
                    .background(if (state.autoForegroundOnBoot) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                    .border(
                        width = 1.5.dp,
                        color = if (state.autoForegroundOnBoot) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                        shape = CopilotShapes.R16
                    )
                    .clickable(onClick = onToggleAutoForeground)
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Auto-Foreground on Ignition",
                        style = CockpitTheme.typography.titleMedium,
                        color = if (state.autoForegroundOnBoot) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = if (state.autoForegroundOnBoot)
                            "ACTIVE • Keeps Copilot visible & runs BT Music in background"
                        else
                            "DISABLED • Allows external apps to take foreground on startup",
                        style = CockpitTheme.typography.bodySmall,
                        color = CockpitTheme.colors.textSecondary
                    )
                }

                StatusBadge(
                    text = if (state.autoForegroundOnBoot) "ENABLED" else "DISABLED"
                )
            }
        }
    }

    // DISPLAY & APP LAUNCHER
    SettingsCard(title = "Display & App Launcher", icon = Icons.Default.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsToggleRow(
                title = "Keep Screen Awake",
                description = if (state.screenAlwaysOn) "Display never sleeps while Copilot is in front"
                else "Display follows the head unit's screen timeout",
                checked = state.screenAlwaysOn,
                onToggle = onToggleScreenAlwaysOn
            )
            SettingsToggleRow(
                title = "Close App Drawer After Launch",
                description = if (state.autoMinimizeDrawer) "Drawer slides away once an app is opened"
                else "Drawer stays open behind the launched app",
                checked = state.autoMinimizeDrawer,
                onToggle = onToggleAutoMinimizeDrawer
            )
        }
    }

    // TRIP & FUEL-STOP DETECTION
    SettingsCard(title = "Trip & Fuel-Stop Detection", icon = Icons.Default.Place) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsToggleRow(
                title = "Fuel Station Refuel Prompt",
                description = if (state.stationStopWatchdog) "Asks to log a refuel after stopping at a fuel station"
                else "No automatic refuel prompt at fuel stations",
                checked = state.stationStopWatchdog,
                onToggle = onToggleStationPrompt
            )
            if (state.stationStopWatchdog) {
                SettingsStepperRow(
                    title = "Prompt After Stopping For",
                    valueText = "${state.stationPromptDwellSeconds} s",
                    canDecrease = state.stationPromptDwellSeconds > 15,
                    canIncrease = state.stationPromptDwellSeconds < 180,
                    onDecrease = { onDwellSecondsChange(state.stationPromptDwellSeconds - 15) },
                    onIncrease = { onDwellSecondsChange(state.stationPromptDwellSeconds + 15) }
                )
            }
            SettingsStepperRow(
                title = "Stationary Below",
                valueText = "${NumberFormatUtils.formatPrecision(state.stationarySpeedThresholdKmh.toDouble(), 1)} km/h",
                canDecrease = state.stationarySpeedThresholdKmh > 1.0f,
                canIncrease = state.stationarySpeedThresholdKmh < 10.0f,
                onDecrease = { onStationaryThresholdChange(state.stationarySpeedThresholdKmh - 0.5f) },
                onIncrease = { onStationaryThresholdChange(state.stationarySpeedThresholdKmh + 0.5f) }
            )
            SettingsStepperRow(
                title = "New Geofence Radius",
                valueText = "${state.defaultGeofenceRadiusMeters.toInt()} m",
                canDecrease = state.defaultGeofenceRadiusMeters > 50f,
                canIncrease = state.defaultGeofenceRadiusMeters < 2000f,
                onDecrease = { onGeofenceRadiusChange(state.defaultGeofenceRadiusMeters - 25f) },
                onIncrease = { onGeofenceRadiusChange(state.defaultGeofenceRadiusMeters + 25f) }
            )
        }
    }

    // AUTOMOTIVE RUNTIME SPECIFICATION CARD
    SettingsCard(title = "Automotive Runtime Environment", icon = Icons.Default.Tune) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Infotainment display is calibrated for 10.1\" landscape (1280x800). Features driver-safe touch zones (64–88dp), an 8dp spatial grid, high-contrast glanceable telemetry, and automatic hardware acceleration.",
                style = CockpitTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = CockpitTheme.colors.textSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatusBadge(text = if (state.screenAlwaysOn) "SCREEN ALWAYS ON" else "SYSTEM TIMEOUT", modifier = Modifier.weight(1f))
                StatusBadge(text = "METRIC (KM & KM/H)", modifier = Modifier.weight(1f))
                StatusBadge(text = "APEX HIGH CONTRAST", modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CopilotShapes.R16)
            .background(if (checked) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
            .border(
                width = 1.5.dp,
                color = if (checked) CockpitTheme.colors.accent else CockpitTheme.colors.border,
                shape = CopilotShapes.R16
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = title,
                style = CockpitTheme.typography.titleMedium,
                color = if (checked) CockpitTheme.colors.accent else CockpitTheme.colors.textPrimary
            )
            Text(
                text = description,
                style = CockpitTheme.typography.bodySmall,
                color = CockpitTheme.colors.textSecondary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Large -/+ stepper: sliders are hard to hit precisely on a moving head-unit touchscreen. */
@Composable
private fun SettingsStepperRow(
    title: String,
    valueText: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R16)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = CockpitTheme.typography.titleMedium,
            color = CockpitTheme.colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = onDecrease, enabled = canDecrease, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $title", tint = CockpitTheme.colors.accent)
            }
            Text(
                text = valueText,
                style = CockpitTheme.typography.titleLarge,
                color = CockpitTheme.colors.textPrimary,
                modifier = Modifier.widthIn(min = 96.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = onIncrease, enabled = canIncrease, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Increase $title", tint = CockpitTheme.colors.accent)
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.5f), CopilotShapes.R10)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = CockpitTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Black
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
                    style = CockpitTheme.typography.titleMedium,
                    color = CockpitTheme.colors.textPrimary
                )
                OutlinedTextField(
                    value = carName,
                    onValueChange = onCarNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CopilotShapes.R14,
                    textStyle = CockpitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CockpitTheme.colors.textPrimary,
                        unfocusedTextColor = CockpitTheme.colors.textPrimary,
                        focusedBorderColor = Palette.Sky400,
                        unfocusedBorderColor = Palette.Ink750,
                        focusedContainerColor = Palette.Slate900,
                        unfocusedContainerColor = Palette.Ink950
                    ),
                    placeholder = { Text("e.g. Honda City, Creta, Nexon", color = Palette.Slate500, style = CockpitTheme.typography.bodyMedium) }
                )
            }

            // 2. Powertrain / Fuel Type Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Powertrain / Fuel Type",
                    style = CockpitTheme.typography.titleMedium,
                    color = CockpitTheme.colors.textPrimary
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
                                .clip(CopilotShapes.R12)
                                .background(if (isSelected) Palette.Sky600 else Palette.Slate900)
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) Palette.Sky400 else Palette.Ink750,
                                    shape = CopilotShapes.R12
                                )
                                .clickable { onFuelTypeChange(type) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type,
                                style = CockpitTheme.typography.titleSmall,
                                color = if (isSelected) CockpitTheme.colors.textPrimary else Palette.Slate400
                            )
                        }
                    }
                }
            }

            // 3. Design Tank Capacity (L)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Design Fuel Tank Capacity (Liters)",
                    style = CockpitTheme.typography.titleMedium,
                    color = CockpitTheme.colors.textPrimary
                )
                OutlinedTextField(
                    value = tankCapacityText,
                    onValueChange = onTankCapacityChange,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = CopilotShapes.R14,
                    textStyle = CockpitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = CockpitTheme.colors.textPrimary,
                        unfocusedTextColor = CockpitTheme.colors.textPrimary,
                        focusedBorderColor = Palette.Sky400,
                        unfocusedBorderColor = Palette.Ink750,
                        focusedContainerColor = Palette.Slate900,
                        unfocusedContainerColor = Palette.Ink950
                    )
                )
                Text(
                    text = "Learned Max: ${NumberFormatUtils.formatFuelLiters(currentFuelState.maxCapacityLiters, 2)} L (Automatically adapts via past fill-ups)",
                    style = CockpitTheme.typography.bodyMedium,
                    color = Palette.Sky400
                )
            }

            // 4. Save Button
            Button(
                onClick = onSaveConfig,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = CopilotShapes.R14,
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky600)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text("Save Vehicle Configuration", style = CockpitTheme.typography.labelLarge, color = contentColorOn(Palette.Sky600))
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
                style = CockpitTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = Palette.Slate400
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
                    shape = CopilotShapes.R12,
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky600)
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isBackupInProgress) "Creating Backup..." else "Backup to Storage Now",
                        style = CockpitTheme.typography.labelLarge, color = contentColorOn(Palette.Sky600)
                    )
                }

                OutlinedButton(
                    onClick = onRestoreNow,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape = CopilotShapes.R12,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Palette.Sky400)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, tint = CockpitTheme.colors.textPrimary, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore from Storage", style = CockpitTheme.typography.labelLarge, color = CockpitTheme.colors.textPrimary)
                }
            }
        }
    }

    // Visually Distinct Danger Zone
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .cockpitSurface(CockpitTheme.colors.critical.copy(alpha = 0.14f), CockpitTheme.colors.critical.copy(alpha = 0.7f), CopilotShapes.R18, borderWidth = 1.5.dp)
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = CockpitTheme.colors.critical, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Danger Zone – Factory Reset",
                    style = CockpitTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Black),
                    color = CockpitTheme.colors.critical
                )
            }
            Text(
                text = "Permanently reset all recorded trips, driving history, fuel refill logs, and odometer counters back to 0.0 km. Existing portable backup files (*.zip) in storage will NOT be deleted.",
                style = CockpitTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = CockpitTheme.colors.textPrimary
            )

            Button(
                onClick = onTriggerReset,
                colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.critical),
                shape = CopilotShapes.R12,
                modifier = Modifier.height(50.dp)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Factory Reset App Data", style = CockpitTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black), color = contentColorOn(CockpitTheme.colors.critical))
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
                        style = CockpitTheme.typography.titleMedium,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = "Blaupunkt Launcher3 (${CrashGuardian.STOCK_LAUNCHER_PACKAGE})",
                        style = CockpitTheme.typography.bodyMedium,
                        color = Palette.Steel400
                    )
                }
                Button(
                    onClick = {
                        CrashGuardian.launchStockLauncher(context)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Slate800),
                    shape = CopilotShapes.R10,
                    border = androidx.compose.foundation.BorderStroke(1.dp, Palette.Slate700),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, modifier = Modifier.size(18.dp), tint = contentColorOn(Palette.Slate800))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Switch to Stock", color = contentColorOn(Palette.Slate800), style = CockpitTheme.typography.labelLarge)
                }
            }

            HorizontalDivider(color = Palette.Ink800)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Button(
                    onClick = {
                        val ok = CrashGuardian.setCopilotAsDefaultHome(context)
                        Toast.makeText(context, if (ok) "Copilot set as Primary HOME Launcher" else "Failed setting HOME launcher", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Sky600),
                    shape = CopilotShapes.R12,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Set as Default Launcher", style = CockpitTheme.typography.labelLarge, color = contentColorOn(Palette.Sky600))
                }

                Button(
                    onClick = {
                        val ok = CrashGuardian.restoreStockLauncherAsDefault(context)
                        Toast.makeText(context, if (ok) "Stock Blaupunkt restored as default HOME" else "Stock Blaupunkt preference updated", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Slate700),
                    shape = CopilotShapes.R12,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(20.dp), tint = contentColorOn(Palette.Slate700))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore Stock as Default", style = CockpitTheme.typography.labelLarge, color = contentColorOn(Palette.Slate700))
                }
            }

            HorizontalDivider(color = Palette.Ink800)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CrashGuardian Watchdog",
                        style = CockpitTheme.typography.titleMedium,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = if (crashCount == 0) "Status: Normal (0 crashes recorded)" else "Recent crashes: $crashCount / ${CrashGuardian.MAX_CRASHES_BEFORE_RECOVERY}",
                        style = CockpitTheme.typography.bodyMedium,
                        color = if (crashCount == 0) Palette.Emerald500 else Palette.Amber500
                    )
                }
                if (crashCount > 0) {
                    OutlinedButton(
                        onClick = {
                            CrashGuardian.resetCrashHistory(context)
                            crashCount = 0
                            Toast.makeText(context, "Crash history reset", Toast.LENGTH_SHORT).show()
                        },
                        shape = CopilotShapes.R10,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text("Reset Counter", style = CockpitTheme.typography.labelMedium, color = CockpitTheme.colors.textPrimary)
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
                        style = CockpitTheme.typography.titleMedium,
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = "Release v$appVersionName (Build $appVersionCode)",
                        style = CockpitTheme.typography.bodyMedium,
                        color = CockpitTheme.colors.textSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .cockpitSurface(CockpitTheme.colors.accentSoft, CockpitTheme.colors.accent.copy(alpha = 0.5f), CopilotShapes.R8, borderWidth = 1.2.dp)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "v$appVersionName",
                        style = CockpitTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Black,
                            fontFamily = AutomotiveDesignTokens.NumericTypography.fontFamily,
                            fontFeatureSettings = "tnum"
                        ),
                        color = CockpitTheme.colors.accent
                    )
                }
            }

            HorizontalDivider(color = Palette.Ink800)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Cockpit Specification",
                    style = CockpitTheme.typography.titleMedium,
                    color = CockpitTheme.colors.textPrimary
                )
                Text(
                    text = "Optimized for 1280×800 landscape in-car digital clusters. Built with 100% Kotlin, Jetpack Compose Material 3, Room SQLite, OSMDroid Offline Navigation, and Autonomous Vehicle State Tracking.",
                    style = CockpitTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                    color = Palette.Steel400
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
            .cockpitSurface(CockpitTheme.colors.cardTopLightGradient, CockpitTheme.colors.border, CopilotShapes.R18, borderWidth = 1.2.dp)
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = CockpitTheme.colors.accent, modifier = Modifier.size(24.dp))
                Text(
                    text = title,
                    style = CockpitTheme.typography.titleLarge,
                    color = CockpitTheme.colors.textPrimary
                )
            }
            content()
        }
    }
}
