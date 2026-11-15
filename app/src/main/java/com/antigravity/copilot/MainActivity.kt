package com.antigravity.copilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.antigravity.copilot.core.database.PersistentDataManager
import com.antigravity.copilot.core.persistence.FullBackupManager
import com.antigravity.copilot.core.recovery.CrashGuardian
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.designsystem.CopilotTheme
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.core.location.DrivingTrackingService
import com.antigravity.copilot.feature.dashboard.DashboardScreen
import com.antigravity.copilot.feature.fuel.FuelEntryDialog
import com.antigravity.copilot.feature.fuel.FuelHistoryScreen
import com.antigravity.copilot.feature.fuel.RefuelStationPromptDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.antigravity.copilot.feature.launcher.AppDrawerScreen
import com.antigravity.copilot.feature.launcher.LauncherManager
import com.antigravity.copilot.feature.maps.LiveMapPanel
import com.antigravity.copilot.feature.settings.LauncherSettingsDialog
import com.antigravity.copilot.feature.splash.SplashViewModel
import com.antigravity.copilot.feature.splash.WelcomeSplashScreen
import com.antigravity.copilot.feature.trips.TripHistoryScreen
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (fineGranted || coarseGranted) {
            DrivingTrackingService.start(this)
            requestBackgroundLocationIfNeeded()
        }
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Background location result handled
    }

    var onBackupFileSelected: ((Uri) -> Unit)? = null
    val backupFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onBackupFileSelected?.invoke(uri)
        }
    }

    fun launchBackupPicker() {
        backupFilePickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            LauncherManager.clearIconCache()
            viewModel.loadInstalledApps()
        }
    }

    private var testMediaReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register package change receiver to dynamically handle app install/uninstall
        val packageFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, packageFilter)

        val mediaFilter = IntentFilter("com.antigravity.copilot.TEST_MEDIA_STATE")
        testMediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val isPlaying = intent?.getBooleanExtra("playing", true) ?: true
                val title = intent?.getStringExtra("title") ?: if (isPlaying) "Nightcall" else ""
                val artist = intent?.getStringExtra("artist") ?: if (isPlaying) "Kavinsky" else ""
                val album = intent?.getStringExtra("album") ?: if (isPlaying) "OutRun" else ""
                val code = if (isPlaying) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_STOPPED
                com.antigravity.copilot.core.media.MediaPlaybackManager.setTestPlaybackState(
                    com.antigravity.copilot.core.media.MediaPlaybackState(
                        title = title,
                        artist = artist,
                        album = album,
                        isPlaying = isPlaying,
                        hasActiveSession = isPlaying,
                        playbackStateCode = code,
                        durationMs = 259000L,
                        positionMs = 64000L,
                        lastPositionUpdateTime = android.os.SystemClock.elapsedRealtime(),
                        playbackSpeed = 1.0f
                    )
                )
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(testMediaReceiver, mediaFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(testMediaReceiver, mediaFilter)
        }

        // Automotive Edge-to-Edge Immersive Sticky Mode (Hides status bar and gesture navigation)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        checkAndRequestPermissions()
        AppSettingsManager.init(applicationContext)
        com.antigravity.copilot.core.odometer.OdometerManager.init(applicationContext)
        com.antigravity.copilot.core.media.MediaPlaybackManager.init(applicationContext)
        PersistentDataManager.backupAsync(applicationContext)

        // Automotive CrashGuardian lifecycle verification
        CrashGuardian.init(application)
        if (CrashGuardian.isSafeModeActive(this)) {
            Toast.makeText(this, "Copilot: Running in Safe Mode", Toast.LENGTH_LONG).show()
        }

        lifecycleScope.launch {
            delay(CrashGuardian.STABLE_BOOT_THRESHOLD_MS)
            CrashGuardian.notifySuccessfulBoot(applicationContext)
        }

        setContent {
            val settings by AppSettingsManager.settingsState.collectAsState()

            CopilotTheme(
                themeMode = settings.cockpitThemeMode
            ) {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        viewModel.loadInstalledApps()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (_: Exception) {}
        try {
            testMediaReceiver?.let { unregisterReceiver(it) }
        } catch (_: Exception) {}
    }

    private fun checkAndRequestPermissions() {
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (fineGranted || coarseGranted) {
            DrivingTrackingService.start(this)
            requestBackgroundLocationIfNeeded()
        } else {
            // Android 11+ requires requesting foreground location first
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bgGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (!bgGranted) {
                backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }
}

@Composable
private fun MainAppScreen(
    viewModel: MainViewModel
) {
    val currentTrip by viewModel.currentTrip.collectAsState()
    val latestPoint by viewModel.latestPoint.collectAsState()
    val tripTrail by viewModel.tripTrail.collectAsState()
    val qualifyingTrips by viewModel.qualifyingTrips.collectAsState(initial = emptyList())
    val lastThreeTrips by viewModel.lastThreeTrips.collectAsState(initial = emptyList())
    val fuelState by viewModel.fuelState.collectAsState()
    val fuelHistory by viewModel.fuelHistory.collectAsState(initial = emptyList())
    val tripVisualState by viewModel.tripVisualState.collectAsState()
    val speedLimitInfo by viewModel.speedLimitInfo.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isAppDrawerOpen by viewModel.isAppDrawerOpen.collectAsState()
    val isFuelDialogOpen by viewModel.isFuelDialogOpen.collectAsState()
    val isSettingsDialogOpen by viewModel.isSettingsDialogOpen.collectAsState()
    val installedApps by viewModel.installedApps.collectAsState()
    val splashViewModel: SplashViewModel = viewModel()
    val isSplashCompleted by splashViewModel.isSplashCompleted.collectAsState()

    BackHandler(enabled = !isSplashCompleted) {
        splashViewModel.skipSplash()
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var pendingRestoreManifest by remember { mutableStateOf<FullBackupManager.BackupManifest?>(null) }
    var isRestoring by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        (context as? MainActivity)?.onBackupFileSelected = { uri ->
            coroutineScope.launch {
                val manifest = FullBackupManager.inspectBackup(context, uri)
                if (manifest != null) {
                    pendingRestoreManifest = manifest
                    pendingRestoreUri = uri
                } else {
                    Toast.makeText(context, "Invalid backup archive: manifest missing or corrupted", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    val recordedDistanceKm = remember(qualifyingTrips, currentTrip.distanceKm, currentTrip.status) {
        val historicalKm = qualifyingTrips.sumOf { it.distanceMeters } / 1000.0
        val isTripActive = currentTrip.status == com.antigravity.copilot.core.model.TripStatus.ACTIVE_TRIP ||
            currentTrip.status == com.antigravity.copilot.core.model.TripStatus.MOVEMENT_CANDIDATE ||
            currentTrip.status == com.antigravity.copilot.core.model.TripStatus.TEMPORARY_STOP
        val activeKm = if (isTripActive) currentTrip.distanceKm else 0.0
        historicalKm + activeKm
    }

    LaunchedEffect(recordedDistanceKm) {
        viewModel.syncOdometer(recordedDistanceKm)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(CockpitTheme.colors.background)
    ) {
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                val isForward = targetState > initialState
                (slideInHorizontally(
                    initialOffsetX = { fullWidth -> if (isForward) (fullWidth * 0.12f).toInt() else -(fullWidth * 0.12f).toInt() },
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                ) + fadeIn(animationSpec = tween(280)) + scaleIn(
                    initialScale = 0.97f,
                    animationSpec = tween(280)
                )).togetherWith(
                    slideOutHorizontally(
                        targetOffsetX = { fullWidth -> if (isForward) -(fullWidth * 0.08f).toInt() else (fullWidth * 0.08f).toInt() },
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                    ) + fadeOut(animationSpec = tween(220))
                )
            },
            label = "MainScreenNavigation",
            modifier = Modifier.fillMaxSize()
        ) { tabIndex ->
            when (tabIndex) {
                0 -> DashboardScreen(
                    currentTrip = currentTrip,
                    latestPoint = latestPoint,
                    tripTrail = tripTrail,
                    fuelState = fuelState,
                    allTrips = qualifyingTrips,
                    lastThreeTrips = lastThreeTrips,
                    tripVisualState = tripVisualState,
                    selectedNavIndex = selectedTab,
                    speedLimitInfo = speedLimitInfo,
                    onNavItemSelected = { index -> viewModel.selectTab(index) },
                    onOpenFuelDialog = { viewModel.setFuelDialogOpen(true) },
                    onOpenFuelHistory = { viewModel.selectTab(3) },
                    onOpenAppDrawer = { viewModel.setAppDrawerOpen(true) },
                    onLaunchNavi = { viewModel.launchNavigation() },
                    onLaunchSettings = { viewModel.setSettingsDialogOpen(true) },
                    onToggleFullMap = { viewModel.selectTab(1) },
                    onCalibrateMidBar = { bar -> viewModel.calibrateMidBar(bar) }
                )
                1 -> LiveMapPanel(
                    latestPoint = latestPoint,
                    trail = tripTrail,
                    speedLimitInfo = speedLimitInfo,
                    modifier = Modifier.fillMaxSize(),
                    isFullScreen = true,
                    onToggleFullScreen = { viewModel.selectTab(0) }
                )
                2 -> TripHistoryScreen(
                    trips = qualifyingTrips,
                    onLoadTripPoints = { tripId -> viewModel.getPointsForTrip(tripId) },
                    onLoadTripTransitions = { tripId, start, end -> viewModel.getTransitionsForTrip(tripId, start, end) },
                    onLoadTripFuelEvents = { start, end -> viewModel.getFuelEventsForTrip(start, end) },
                    onBackToCockpit = { viewModel.selectTab(0) },
                    onDeleteTrip = { tripId -> viewModel.deleteTrip(tripId) },
                    fuelPrice = fuelState.fuelPricePerLiter
                )
                3 -> FuelHistoryScreen(
                    fuelEvents = fuelHistory,
                    onBackToCockpit = { viewModel.selectTab(0) },
                    onEditEvent = { updatedEvent -> viewModel.updateFuelEvent(updatedEvent) },
                    onDeleteEvent = { eventId -> viewModel.deleteFuelEvent(eventId) }
                )
            }
        }

        AnimatedVisibility(
            visible = isAppDrawerOpen,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            val appSettings by AppSettingsManager.settingsState.collectAsState()
            AppDrawerScreen(
                apps = installedApps,
                onAppClick = { app ->
                    viewModel.launchApp(app.packageName, app.activityName)
                    if (appSettings.autoMinimizeDrawer) {
                        viewModel.setAppDrawerOpen(false)
                    }
                },
                onBackToHome = {
                    viewModel.setAppDrawerOpen(false)
                    viewModel.selectTab(0)
                }
            )
        }

        val refuelPromptData by viewModel.refuelPromptEvent.collectAsState()
        refuelPromptData?.let { prompt ->
            RefuelStationPromptDialog(
                promptData = prompt,
                onAcceptRefuel = {
                    viewModel.dismissRefuelPrompt()
                    viewModel.setFuelDialogOpen(true)
                },
                onDismiss = {
                    viewModel.dismissRefuelPrompt()
                }
            )
        }

        if (isFuelDialogOpen) {
            FuelEntryDialog(
                fuelState = fuelState,
                recordedLifetimeKm = recordedDistanceKm,
                currentLocationPoint = latestPoint,
                onDismiss = { viewModel.setFuelDialogOpen(false) },
                onSaveRefuel = { liters, midBar, price, cost, odo, locationName, lat, lon ->
                    viewModel.recordRefuel(
                        litersAdded = liters,
                        midBarObserved = midBar,
                        pricePerLiter = price,
                        totalCost = cost,
                        odometerKm = odo,
                        locationName = locationName,
                        latitude = lat,
                        longitude = lon,
                        recordedLifetimeKm = recordedDistanceKm
                    )
                }
            )
        }

        AnimatedVisibility(
            visible = isSettingsDialogOpen,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            LauncherSettingsDialog(
                currentFuelState = fuelState,
                recordedLifetimeKm = recordedDistanceKm,
                onSaveVehicleConfig = { _, _, cap, mileage ->
                    viewModel.updateVehicleConfig(cap, mileage)
                },
                onLaunchRestorePicker = {
                    (context as? MainActivity)?.launchBackupPicker()
                },
                onResetAllDataConfirmed = {
                    viewModel.resetAllData()
                },
                onDismiss = { viewModel.setSettingsDialogOpen(false) }
            )
        }

        // Restore Confirmation Dialog
        if (pendingRestoreUri != null && pendingRestoreManifest != null) {
            AlertDialog(
                onDismissRequest = {
                    if (!isRestoring) {
                        pendingRestoreUri = null
                        pendingRestoreManifest = null
                    }
                },
                containerColor = Color(0xFF111726),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Confirm Application Restore", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black), color = Color.White)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Selected backup archive details:", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF94A3B8))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0B1220))
                                .border(1.dp, Color(0xFF1E2838), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("• Created: ${pendingRestoreManifest?.formattedDate}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                                Text("• App Version: v${pendingRestoreManifest?.appVersion}", style = MaterialTheme.typography.bodySmall, color = Color.White)
                                Text("• Driving Trips: ${pendingRestoreManifest?.tripCount}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF38BDF8))
                                Text("• Fuel Refills: ${pendingRestoreManifest?.fuelEventCount}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF10B981))
                                Text("• Ledger Events: ${pendingRestoreManifest?.fuelLedgerCount}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFF59E0B))
                            }
                        }

                        Text(
                            "Restoring will overwrite current driving and fuel records with data from this archive. An automatic pre-restore safety snapshot will be created before applying.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val uri = pendingRestoreUri ?: return@Button
                            if (!isRestoring) {
                                isRestoring = true
                                coroutineScope.launch {
                                    val result = FullBackupManager.restoreFromBackupZip(context, uri)
                                    isRestoring = false
                                    pendingRestoreUri = null
                                    pendingRestoreManifest = null
                                    if (result.isSuccess) {
                                        Toast.makeText(context, "Restore completed! Restored ${result.restoredTrips} trips and ${result.restoredFuelEvents} refills.", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Restore failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isRestoring,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (isRestoring) "Restoring..." else "Confirm Restore", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    if (!isRestoring) {
                        TextButton(onClick = {
                            pendingRestoreUri = null
                            pendingRestoreManifest = null
                        }) {
                            Text("Cancel", color = Color.White)
                        }
                    }
                }
            )
        }

        if (!isSplashCompleted) {
            WelcomeSplashScreen(
                viewModel = splashViewModel,
                onSplashFinished = {
                    splashViewModel.onAnimationFinished()
                }
            )
        }
    }
}
