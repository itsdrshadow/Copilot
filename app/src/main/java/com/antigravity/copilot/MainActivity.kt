package com.antigravity.copilot

import com.antigravity.copilot.core.designsystem.components.contentColorOn
import com.antigravity.copilot.core.designsystem.components.cockpitBackground
import dagger.hilt.android.AndroidEntryPoint
import com.antigravity.copilot.feature.trips.TripHistoryRoute
import com.antigravity.copilot.feature.fuel.FuelHistoryRoute
import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import com.antigravity.copilot.core.designsystem.components.RailItem
import com.antigravity.copilot.core.designsystem.components.RaahiNavRail
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.hilt.navigation.compose.hiltViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Startup permission chain: runtime (location, storage, notifications) → background location
    // → all-files access → battery exemption. Each step starts the next from its result callback
    // so the system dialogs/screens appear one after another instead of clobbering each other.
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasLocationPermission()) DrivingTrackingService.start(this)
        requestBackgroundLocationIfNeeded()
    }

    private val backgroundPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> requestAllFilesAccessIfNeeded() }

    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ -> requestBatteryOptimizationExemptionIfNeeded() }

    var onBackupFileSelected: ((Uri) -> Unit)? = null
    val backupFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onBackupFileSelected?.invoke(uri)
        }
    }
    private val backupGetContentLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) onBackupFileSelected?.invoke(uri) }

    fun launchBackupPicker() {
        // Head units often lack DocumentsUI (no OPEN_DOCUMENT handler): fall back to GET_CONTENT.
        try {
            backupFilePickerLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                backupGetContentLauncher.launch("*/*")
            } catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No file picker app found on this device", Toast.LENGTH_LONG).show()
            }
        }
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

        // Debug-only test hook: an exported receiver in release would let any app spoof now-playing state.
        if (BuildConfig.DEBUG) registerTestMediaReceiver()

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

        // CrashGuardian is initialized in CopilotApp.onCreate
        if (CrashGuardian.isSafeModeActive(this)) {
            Toast.makeText(this, "Copilot: Running in Safe Mode", Toast.LENGTH_LONG).show()
        }

        lifecycleScope.launch {
            delay(CrashGuardian.STABLE_BOOT_THRESHOLD_MS)
            CrashGuardian.notifySuccessfulBoot(applicationContext)
        }

        setContent {
            val settings by AppSettingsManager.settingsState.collectAsStateWithLifecycle()

            // Head-unit display stays awake while Copilot is in front (Settings › Display toggle).
            val keepScreenOn = settings.screenAlwaysOn

            // The splash window background (colour + logo layer-list) would otherwise be painted under
            // the opaque Compose root on every frame — pure overdraw on the head-unit GPU. Drop it once
            // Compose has drawn its first frame.
            LaunchedEffect(Unit) {
                withFrameNanos { }
                window.setBackgroundDrawable(null)
            }

            LaunchedEffect(keepScreenOn) {
                if (keepScreenOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CopilotTheme(
                themeMode = settings.cockpitThemeMode,
                appearanceMode = settings.appearanceMode,
                accentOption = settings.accentOption
            ) {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    private fun registerTestMediaReceiver() {
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
    }

    override fun onResume() {
        super.onResume()
        com.antigravity.copilot.core.recovery.BootForegroundGuardian.onMainActivityResumed()
        WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        viewModel.loadInstalledApps()
    }

    override fun onPause() {
        super.onPause()
        com.antigravity.copilot.core.recovery.BootForegroundGuardian.onMainActivityPaused(this)
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

    private fun isGranted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasLocationPermission() =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Asks for every permission the app uses, once per launch, for whatever is still missing. */
    private fun checkAndRequestPermissions() {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = wanted.filterNot(::isGranted)
        if (hasLocationPermission()) DrivingTrackingService.start(this)
        if (missing.isNotEmpty()) {
            // Android 11+ requires foreground location before background, so background is chained.
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    /** Android 11+: .pmtiles maps in Download/USB are non-media files, readable only with all-files access. */
    private fun requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || android.os.Environment.isExternalStorageManager()) {
            requestBatteryOptimizationExemptionIfNeeded()
            return
        }
        val appIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:$packageName")
        )
        try {
            allFilesAccessLauncher.launch(appIntent)
        } catch (_: android.content.ActivityNotFoundException) {
            try {
                allFilesAccessLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: android.content.ActivityNotFoundException) {
                requestBatteryOptimizationExemptionIfNeeded()
            }
        }
    }

    /**
     * Tracking must survive screen-off / Doze: ask once per launch for the battery-optimisation
     * exemption if the app is not already whitelisted. Fails silently on OEMs without the screen.
     */
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                @android.annotation.SuppressLint("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Battery optimisation exemption prompt unavailable: ${e.message}")
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission() &&
            !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            backgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        } else {
            requestAllFilesAccessIfNeeded()
        }
    }
}

private enum class RailDest { DRIVE, TRIPS, FUEL, MAPS, APPS, SETTINGS }

@Composable
private fun MainAppScreen(
    viewModel: MainViewModel
) {
    val currentTrip by viewModel.currentTrip.collectAsStateWithLifecycle()
    // Held as State (not `by`): reading .value here would recompose this whole root on every
    // GPS tick. Only the leaves that display location read it, via the providers below.
    val latestPointState = viewModel.latestPoint.collectAsStateWithLifecycle()
    val tripTrailState = viewModel.tripTrail.collectAsStateWithLifecycle()
    val latestPointProvider = remember(latestPointState) { { latestPointState.value } }
    val tripTrailProvider = remember(tripTrailState) { { tripTrailState.value } }
    val qualifyingTrips by viewModel.qualifyingTrips.collectAsStateWithLifecycle(initialValue = emptyList())
    val lastThreeTrips by viewModel.lastThreeTrips.collectAsStateWithLifecycle(initialValue = emptyList())
    val fuelState by viewModel.fuelState.collectAsStateWithLifecycle()
    val tripVisualState by viewModel.tripVisualState.collectAsStateWithLifecycle()
    val speedLimitInfo by viewModel.speedLimitInfo.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val isAppDrawerOpen by viewModel.isAppDrawerOpen.collectAsStateWithLifecycle()
    val isFuelDialogOpen by viewModel.isFuelDialogOpen.collectAsStateWithLifecycle()
    val isSettingsDialogOpen by viewModel.isSettingsDialogOpen.collectAsStateWithLifecycle()
    val installedApps by viewModel.installedApps.collectAsStateWithLifecycle()
    val splashViewModel: SplashViewModel = viewModel()
    val isSplashCompleted by splashViewModel.isSplashCompleted.collectAsStateWithLifecycle()
    val isSplashExiting by splashViewModel.isExiting.collectAsStateWithLifecycle()

    // Warm the Trips / Fuel history screens during the splash: same activity-scoped instances the
    // routes get from hiltViewModel(), and their lists load eagerly, so the first open is instant.
    hiltViewModel<com.antigravity.copilot.feature.trips.TripHistoryViewModel>()
    hiltViewModel<com.antigravity.copilot.feature.fuel.FuelHistoryViewModel>()

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

    // Historical total only changes when the trip list does; don't re-sum it on every GPS tick.
    val historicalKm = remember(qualifyingTrips) { qualifyingTrips.sumOf { it.distanceMeters } / 1000.0 }
    val recordedDistanceKm = remember(historicalKm, currentTrip.distanceKm, currentTrip.status) {
        val isTripActive = currentTrip.status == com.antigravity.copilot.core.model.TripStatus.ACTIVE_TRIP ||
            currentTrip.status == com.antigravity.copilot.core.model.TripStatus.MOVEMENT_CANDIDATE ||
            currentTrip.status == com.antigravity.copilot.core.model.TripStatus.TEMPORARY_STOP
        val activeKm = if (isTripActive) currentTrip.distanceKm else 0.0
        historicalKm + activeKm
    }

    LaunchedEffect(recordedDistanceKm) {
        viewModel.syncOdometer(recordedDistanceKm)
    }

    // Raahi left rail. Drive / Trips / Fuel / Maps are the existing tabs (0 / 2 / 3 / 1); Apps and
    // Settings open the existing full-screen overlays. Any rail tap closes an open overlay first.
    val railItems = remember {
        listOf(
            RailItem(RailDest.DRIVE, "Drive", Icons.Outlined.DirectionsCar),
            RailItem(RailDest.TRIPS, "Trips", Icons.Outlined.Route),
            RailItem(RailDest.FUEL, "Fuel", Icons.Outlined.LocalGasStation),
            RailItem(RailDest.MAPS, "Maps", Icons.Outlined.Map),
            RailItem(RailDest.APPS, "Apps", Icons.Outlined.Apps)
        )
    }
    val railBottom = remember { listOf(RailItem(RailDest.SETTINGS, "Settings", Icons.Outlined.Settings)) }
    val activeRail = when {
        isSettingsDialogOpen -> RailDest.SETTINGS
        isAppDrawerOpen -> RailDest.APPS
        selectedTab == 1 -> RailDest.MAPS
        selectedTab == 2 -> RailDest.TRIPS
        selectedTab == 3 -> RailDest.FUEL
        else -> RailDest.DRIVE
    }
    val onRailSelect: (RailDest) -> Unit = { dest ->
        when (dest) {
            RailDest.APPS -> {
                viewModel.setSettingsDialogOpen(false)
                viewModel.setAppDrawerOpen(true)
            }
            RailDest.SETTINGS -> {
                viewModel.setAppDrawerOpen(false)
                viewModel.setSettingsDialogOpen(true)
            }
            else -> {
                viewModel.setAppDrawerOpen(false)
                viewModel.setSettingsDialogOpen(false)
                viewModel.selectTab(
                    when (dest) {
                        RailDest.MAPS -> 1
                        RailDest.TRIPS -> 2
                        RailDest.FUEL -> 3
                        else -> 0
                    }
                )
            }
        }
    }

    // Startup order: the splash's first frame is drawn alone (cheap), then the dashboard, map and
    // rail compose behind it in the next frame while the logo is still. The splash animation waits
    // for that heavy frame, so it never stutters mid-animation.
    var mainContentComposed by remember { mutableStateOf(isSplashCompleted) }
    LaunchedEffect(Unit) {
        if (!mainContentComposed) {
            withFrameNanos { }
            mainContentComposed = true
        }
    }
    // The map's GL setup + first style load also stalls the main thread: preload it behind the
    // still logo too, but never hold the splash more than 2.5 s for it (e.g. no map installed).
    val mapStyleLoaded by com.antigravity.copilot.core.map.CarMapController.firstStyleLoaded
        .collectAsStateWithLifecycle()
    var mapWaitTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(mainContentComposed) {
        if (mainContentComposed) {
            delay(2_500L)
            mapWaitTimedOut = true
        }
    }
    val splashContentReady = mainContentComposed && (mapStyleLoaded || mapWaitTimedOut)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .cockpitBackground()
    ) {
      if (mainContentComposed) Row(modifier = Modifier.fillMaxSize()) {
        RaahiNavRail(
            items = railItems,
            bottomItems = railBottom,
            selected = activeRail,
            onSelect = onRailSelect
        )
      Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
        // Tabs 2 (Trips) and 3 (Fuel) are opaque overlays on top of a dashboard that stays composed
        // for the whole session, so the single MapView (and its loaded style, tiles and camera) is
        // never destroyed on tab switches. Tab 1 (full map) is the same map panel expanded in place.
        val overlayTab = selectedTab.takeIf { it == 2 || it == 3 }
        var lastOverlayTab by remember { mutableIntStateOf(2) }
        if (overlayTab != null) lastOverlayTab = overlayTab
        val tabOverlayState = remember { MutableTransitionState(false) }
        tabOverlayState.targetState = overlayTab != null
        val drawerState = remember { MutableTransitionState(false) }
        drawerState.targetState = isAppDrawerOpen
        val settingsState = remember { MutableTransitionState(false) }
        settingsState.targetState = isSettingsDialogOpen

        // Fully covered only once an opaque overlay has finished sliding in; uncovered as soon as
        // one starts sliding out, so the map is already live when it becomes visible again.
        fun MutableTransitionState<Boolean>.fullyShown() = isIdle && currentState && targetState
        val isDashboardCovered = tabOverlayState.fullyShown() || drawerState.fullyShown() || settingsState.fullyShown()

        DashboardScreen(
            currentTrip = currentTrip,
            latestPoint = latestPointProvider,
            tripTrail = tripTrailProvider,
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
            onToggleFullMap = { viewModel.selectTab(if (selectedTab == 1) 0 else 1) },
            onCalibrateMidBar = { point -> viewModel.calibrateGaugePoint(point) },
            onDismissFuelNotice = { viewModel.dismissFuelNotice() },
            isMapFullScreen = selectedTab == 1,
            isCovered = isDashboardCovered,
            // Hidden under an opaque overlay (or the splash, until it starts fading): alpha 0 lets
            // the RenderThread skip drawing it, while the map keeps loading its style and tiles.
            modifier = Modifier.graphicsLayer {
                alpha = if (isDashboardCovered || !isSplashExiting) 0f else 1f
            }
        )

        androidx.compose.animation.AnimatedVisibility(
            visibleState = tabOverlayState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> (fullWidth * 0.08f).toInt() },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .cockpitBackground()
            ) {
                when (overlayTab ?: lastOverlayTab) {
                    2 -> TripHistoryRoute(onBackToCockpit = { viewModel.selectTab(0) })
                    3 -> FuelHistoryRoute(
                        onBackToCockpit = { viewModel.selectTab(0) },
                        fuelState = fuelState,
                        onAddFuel = { viewModel.setFuelDialogOpen(true) }
                    )
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visibleState = drawerState,
            enter = slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            ) + fadeIn(animationSpec = tween(280)),
            exit = slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth },
                animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
            ) + fadeOut(animationSpec = tween(220))
        ) {
            val appSettings by AppSettingsManager.settingsState.collectAsStateWithLifecycle()
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

        val refuelPromptData by viewModel.refuelPromptEvent.collectAsStateWithLifecycle()
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
                currentLocationPoint = latestPointState.value,
                onDismiss = { viewModel.setFuelDialogOpen(false) },
                onSaveRefuel = { liters, midBar, price, cost, odo, locationName, lat, lon, fullTank ->
                    viewModel.recordRefuel(
                        fullTank = fullTank,
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

        androidx.compose.animation.AnimatedVisibility(
            visibleState = settingsState,
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
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Restore, contentDescription = null, tint = CockpitTheme.colors.accent, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Confirm Application Restore", style = MaterialTheme.typography.titleLarge, color = CockpitTheme.colors.textPrimary)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Selected backup archive details:", style = MaterialTheme.typography.bodyMedium, color = CockpitTheme.colors.textSecondary)

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R12)
                                .padding(12.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("• Created: ${pendingRestoreManifest?.formattedDate}", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textPrimary)
                                Text("• App Version: v${pendingRestoreManifest?.appVersion}", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textPrimary)
                                Text("• Driving Trips: ${pendingRestoreManifest?.tripCount}", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textPrimary)
                                Text("• Fuel Refills: ${pendingRestoreManifest?.fuelEventCount}", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textPrimary)
                                Text("• Ledger Events: ${pendingRestoreManifest?.fuelLedgerCount}", style = MaterialTheme.typography.bodySmall, color = CockpitTheme.colors.textPrimary)
                            }
                        }

                        Text(
                            "Restoring will overwrite current driving and fuel records with data from this archive. An automatic pre-restore safety snapshot will be created before applying.",
                            style = MaterialTheme.typography.bodySmall,
                            color = CockpitTheme.colors.textSecondary
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
                        colors = ButtonDefaults.buttonColors(containerColor = CockpitTheme.colors.accent, contentColor = CockpitTheme.colors.onAccent),
                        shape = CopilotShapes.R12
                    ) {
                        Text(if (isRestoring) "Restoring..." else "Confirm Restore", fontWeight = FontWeight.Bold, color = contentColorOn(CockpitTheme.colors.accent))
                    }
                },
                dismissButton = {
                    if (!isRestoring) {
                        TextButton(onClick = {
                            pendingRestoreUri = null
                            pendingRestoreManifest = null
                        }) {
                            Text("Cancel", color = CockpitTheme.colors.textPrimary)
                        }
                    }
                }
            )
        }

      }
      }

        if (!isSplashCompleted) {
            WelcomeSplashScreen(
                viewModel = splashViewModel,
                contentReady = splashContentReady,
                onSplashFinished = {
                    splashViewModel.onAnimationFinished()
                }
            )
        }
    }
}
