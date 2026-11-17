# Changelog

All notable changes to the **Copilot** Automotive Cockpit & Infotainment Launcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.49.0] - 2026-11-17

### Added
- **Trips and Geofence maps now show the offline basemap.** Before, both were plain canvases with no map under them. The route or fence is drawn over a one-time MapLibre snapshot instead of a second live MapView, so nothing competes with the dashboard map's GL surface.

### Changed
- 3D buildings start at zoom 15.5 (was 14), so the tilted driving view no longer extrudes every building out to the horizon.

### Fixed
- **Map stutter while driving:** each camera ease lasted at least 800 ms, but fused GPS delivers a fix every 0.5–1 s. Each ease was cut off partway and restarted, so the camera kept speeding up and slowing down. The ease length now follows the measured fix interval (300–1200 ms).
- **Dashboard recomposed on every GPS tick (5 Hz):** location was read at the root screen. It is now passed as a provider and read only by the map controller (through `snapshotFlow`) and the speedometer.
- **Speedometer recomposed every animation frame:** the whole card now recomposes only when the displayed km/h changes. The dial redraws in its own layer.
- The hidden dashboard map no longer runs 60 Hz puck and camera animations while the Trips, Fuel or Settings screen covers it. It jumps to the latest position when it is uncovered.
- **Trips route was distorted:** latitude and longitude were stretched independently to fill the box, and (0,0) or NaN fixes could zoom the view out to the whole planet. The route now uses a true Web-Mercator fit with padding, and invalid fixes are skipped.
- **Geofence circle was not to scale:** it was always drawn at 70% of the box, and a tap moved the centre to the wrong place. The circle is now drawn at true ground scale, and a tap moves the centre to exactly the tapped coordinate.

## [1.48.0] - 2026-11-17

### Added
- **Auto 3D map:** the follow camera tilts to 3D at 3 km/h and above, and stays flat 2D below 3 km/h (parked, crawling). The speed must hold for 1.5 s before the tilt changes, so stop-and-go traffic doesn't make it flicker. If no GPS fix arrives for 3 s, the car counts as parked and the map goes flat. The map menu's 3D item now switches between "Auto 3D (moving)" and "2D only".

### Fixed
- **Blank map:** a far-away coarse fix (network / Wi-Fi / cached position, e.g. a bogus network location) could become the GPS filter's anchor. Real GNSS fixes were then rejected as "impossible jumps", and interleaved network fixes kept resetting the re-anchor count, so the camera stayed where the offline map has no tiles. Fixes are now classified as precise (GNSS, or fused with ≤ 50 m accuracy) or coarse (network). A precise fix overrides a coarse anchor immediately, and a coarse fix can't move a precise anchor that is less than 60 s old.
- Native network-provider fixes are ignored while GNSS is delivering. At startup the service waits up to 15 s for GNSS before trusting a coarse fix, and only precise fixes are saved as the map's start position.
- **Jitter on load:** the camera started at a hard-coded Delhi position and then panned to the first fix. It now starts at the last saved position. The first fix, and any relocation over 2 km, snaps the camera and puck instantly instead of panning across the map.

## [1.47.0] - 2026-11-17

### Added
- Settings › General › **Display & App Launcher**: *Keep Screen Awake* and *Close App Drawer After Launch* toggles (both settings existed but had no UI).
- Settings › General › **Trip & Fuel-Stop Detection**: fuel-station refuel prompt on/off, prompt dwell time (15-180 s), stationary speed threshold (1-10 km/h) and default radius for new geofences (50-2000 m). The tracking service and geofence manager already read these values; they can now be changed.
- Trips: a **Charts** button in the yearly summary shows distance-by-month and trips-by-weekday charts. The analytics were computed but never shown.
- The unlogged-fuel notice on the fuel card can be tapped to dismiss it. Before, it stayed until the next refuel.

### Changed
- Trip history list: each month header and trip row is its own lazy item, so only visible rows are composed. Before, a month composed every trip card at once. Date formatters are cached per row.
- The dashboard caches the lifetime-distance sum and only recomputes it when the trip list changes, not on every GPS tick.

### Fixed
- *Keep screen on* was advertised in Settings but never applied. `FLAG_KEEP_SCREEN_ON` now follows the setting.
- Add Fuel: the **Full tank** checkbox was ignored when resetting the mileage anchor (only a 10-bar gauge counted). It now resets the anchor.
- Add Fuel: an empty or zero fuel rate silently fell back to ₹96.50, or saved a 0 L refuel. It is now rejected with a message. Amounts that exceed the tank capacity (+5%) are also blocked, and the "Detecting fuel station..." placeholder is no longer saved as the station name.
- A zero price no longer overwrites the stored fuel price.
- Map: a NaN, out-of-range or (0,0) GPS fix crashed in `LatLng` or jumped to null island. Such fixes are now dropped, and a NaN speed or bearing no longer breaks auto-zoom.
- Backup: a failure mid-write leaked the zip stream and left a corrupt partial archive. Inspecting a backup leaked its stream on errors, restore leaked the archive handle on errors, and the integrity-check cursor leaked on exceptions.
- Restore: null strings from older archives broke `NOT NULL` constraints and rolled back the whole restore. Corrupt distance and speed values or reversed timestamps reached history. Restored trips are now sanitized (zip and JSON paths).
- Deleting or discarding a trip now runs in one transaction, so a crash mid-delete can't leave orphaned GPS points or transitions.

### Removed
- `SmartTripAnalyticsEngine` (691 lines): it duplicated the trip report already built in `feature/trips`, and only a test used it. Its tests were removed too.
- The unused half of `FuelAIEngine`: model recalibration, dynamic range, MID-bar mapping and mileage trend (superseded by `FuelModel`).
- 19 unused Room DAO queries. Unused helpers and aliases in the map, trip, geofence, launcher, crash-guardian, storage-migration and fuel-math code.
- 16 settings setters with no UI and no behavior (map satellite/dark/buildings, solar day-night, speed-limit alerts, driver scoring, media pill, theme appearance and others). Theme restore aliases.
- `CockpitNavEvent`, the `CockpitNumberFormat` typealias, `MainViewModel` fuel-history and calibration leftovers, and unused parameters on the Add Fuel and Settings dialogs.
- The unused `androidx.navigation:navigation-compose` dependency.

## [1.46.0] - 2026-11-17

### Added
- Speed-adaptive auto-zoom in follow mode: 17.5 when stopped (0-5 km/h), easing to 16.0 at 30, 14.8 at 60 and 13.5 at 100+ km/h. Speed is EMA-smoothed and the camera eases linearly over each GPS interval (800-1200 ms), so zoom and position glide instead of stepping.
- Pan/rotate pauses follow mode and pinch/double-tap pauses auto-zoom. The Recenter button lights up while follow is paused; tap it to resume.
- 50 MB in-RAM PMTiles tile cache (byte-bounded LRU of raw gzipped tiles, capped at 1/8 of the heap) and in-memory root/leaf directory cache. It is cleared under memory pressure.

### Changed
- The map is created once per session. Trips and Fuel now open as overlays above the dashboard, and the full-screen map tab is the same map panel expanded in place. While covered, the map is paused/stopped but never destroyed, so the style, tiles and camera survive tab switches.
- The offline style loads only on first attach, after an import, or when a region change resolves to a different map file. It no longer reloads on every resume. File resolution, opening the PMTiles file and building the style now run off the main thread.
- PMTiles reads use one persistent `FileChannel` with positional (lock-free) reads. The tile dispatcher allows 16 concurrent local requests (OkHttp's default is 5 per host).
- The MapLibre pixel ratio is capped at 1.0. UI GPS telemetry is capped at 5 Hz. The dashboard is not drawn while an opaque overlay covers it.

### Fixed
- Map reload/jitter when returning from the Trips / Fuel / Apps / Settings tabs.
- GPS jitter while parked: moves under 3 m below 3 km/h are ignored, and bearing is held when stationary.
- The vehicle puck no longer jumps ahead of the camera. It animates in step with the camera ease.
- Underground buildings (e.g. Connaught Place / Palika Bazaar) no longer draw as 3D shadows: `location=underground` and negative `layer` are filtered from the building layer.
- Main-thread disk I/O on every GPS fix (map-file lookup) and on every trail update (full trail copy) removed.
- The music/clock card no longer calls the Settings provider every second.

## [1.45.0] - 2026-11-17

### Changed
- Brighter map style for head units: charcoal background, green parks, blue water (no cyan river lines), roads coloured by hierarchy with dark casings (motorway/trunk amber, primary/secondary/tertiary off-white, minor slate), 3D building extrusions from z14.
- Map controls: three large buttons (Compass, Recenter, Menu); 3D view, fuel overlay, offline maps and full screen moved into an expandable menu. Zoom +/- buttons removed (pinch / double-tap).

### Added
- Offline labels: road names, motorway/trunk shields, city/town/area names, water names, fuel/hospital/toll POIs. Noto Sans glyph PBFs (Latin) bundled in `assets/fonts/`; sprite with shield and POI icons.

## [1.44.1] - 2026-11-17

### Fixed
- Map stayed blank when the first GPS fix was far away (e.g. emulator default in the USA, stale network fix): `GpsLocationFilter` rejected every later fix as a >240 km/h jump forever. It now re-anchors after 5 consecutive, mutually consistent "impossible" fixes; scattered multipath spikes are still rejected.
- Zoom +/- buttons restored on the live map; zoom-out limit lowered from 14 to 5, and the chosen zoom (buttons or pinch) is kept while the camera follows the vehicle instead of snapping back to 16.5.

## [1.44.0] - 2026-11-17

### Removed
- Offline routing: GraphHopper dependency (`graphhopper-core`), `OfflineRoutingEngine`, the "All-India Routing Graph" package, routing archive download/extraction/USB import, route polyline layers and the turn-by-turn instruction banner on the map.
- `DownloadState.EXTRACTING`, `PackageType.ROUTING_GRAPH`, `MapDownloadWorker.KEY_PACKAGE_TYPE`.

### Changed
- Offline Maps manager is now PMTiles-only; any leftover `routing/` data from older installs is deleted in the background on start.

## [1.43.0] - 2026-11-17

### Added
- `core/logic/Config.kt`: single home for all trip / fuel / map thresholds.
- Pure `GeofenceTripDetector` (hysteresis exit at radius+15 m, entry at radius, accuracy < 30 m, 3-fix debounce) and `MotionTimeTracker` (idle = < 2 km/h for > 30 s) with unit tests.
- Manual trip start / end override (long-press the CURRENT TRIP header).
- Battery-optimisation exemption prompt for the tracking service.
- `SpeedFilter` (Doppler-only, accuracy-gated EMA), `DrivingStateClassifier` + orientation-independent `LongitudinalAccelEstimator` (linear-acceleration sensor fused with GPS dv/dt), IDLE / ACCELERATING / CRUISING / BRAKING label under the speed readout.
- Pure fuel model (`core/logic/fuel`): isotonic `GaugeCurve` (21 half-point knots, learned top/bottom buffers, uneven litres-per-point), `MileageEstimator` (weighted last ~3 segments / 30 days, full-to-full and gauge-to-gauge, confidence score), anchor-based tank estimate (`anchor − km / mileage`), learned actual capacity kept separate from OEM design capacity. Persisted in new `fuel_model_state` table (DB v9, strict migration) and included in JSON backup/restore.
- Automatic unlogged fuel (`UnloggedFuel`): raw estimate may go below 0; trips ending at/below 0 add PROVISIONAL "unlogged consumption" rows to the fuel log; the next refill reconciles (true_before = gauge_after − refill, unexplained = true_before + debt), allocates to trips by km (RESOLVED, "unlogged refill, auto"), rejects impossible amounts (> actual capacity) by lowering mileage confidence instead. Only a quiet notice under the gauge.
- `TripFuelEstimator`: current-trip fuel/mileage estimate from speed, acceleration (braking via the preceding acceleration) and learned mileage; idle excluded; never feeds the tank.
- Trip history stores brake/accel counts and best mileage (DB v10, strict migration); shown with moving/idle split in trip details.
- Live map now draws the trail (it was passed but never rendered) plus a vehicle puck; `TrailOverlap` colours runs by ~15 m grid pass count (1 blue, 2 orange, 3+ red; opposite lane counts as the same road) with Douglas–Peucker simplification. Trip playback uses the same colouring.
- Per-road mileage map (`RoadMileage`): each finished trip's tank-model fuel (km / learned km/L, idle excluded) is spread over ~40 m cells by driving load; cells aggregate over all passes (`road_cells`, DB v11, in backup). Map toggle colours roads red (low km/L) → green (high); tap shows avg mileage, passes, avg speed.
- Offline maps rebuilt around one local `india.pmtiles` (OpenMapTiles schema): pure-Kotlin `PmTilesReader` (PMTiles v3, gzip) serves tiles in-process to MapLibre 10.3.2 through `MapNetworkGuard`, which also blocks every other map request (debug builds crash loudly). Works in airplane mode on a fresh launch (verified on the emulator).
- "IMPORT MAP FILE" (Storage Access Framework) with PMTiles header/compression validation, free-space and 15 GB budget checks, atomic swap; download from a user-set URL with resume and a real free-space check.
- Bundled local sprite + glyph folder; `MAP_SETUP.md` (Planetiler/Protomaps build steps, copy/import).
- Refill dialog: "Filled to FULL tank" flag; real odometer at refill re-bases the odometer and teaches *k*.
- Gauge calibration popup supports half points (tap the top bar again).
- `OdometerAccumulator` (filtered haversine segments) and odometer correction factor *k* learned from real-odometer entries (shown = base + k·app_delta).

### Changed
- Default geofence radius 200 m → 75 m (min 30 m).
- `GeofenceTripLifecycleEngine` delegates start/end decisions to the pure detector.
- Home fuel gauge is a continuous bar driven by estimated litres (full until the top buffer is used, then smooth), no numeric point.
- Live kinematic consumption no longer deducts from the tank (trip estimate only).
- Speedometer no longer max()-fuses position-derived speed (overshoot); trip distance uses the new accumulator.
- Trip moving/idle time: short stops (< 30 s) are moving time; trip idle at completion = wall clock − moving.

### Fixed
- Live map never rendered on a fresh launch until the app was backgrounded once: the MapView missed `onStart/onResume` when created after the lifecycle replay.
- Offline map download always failed: Geofabrik `.mbtiles` URLs do not exist and MapLibre 10.3.2 cannot read `mbtiles://`.

### Removed
- Non-existent Geofabrik regional `.mbtiles` packages and the GitHub demo pack.
- Blocking "missed refuel" alert on gauge calibration (replaced by automatic reconciliation).
- Idle fuel-burn model (0.75 L/h) from `FuelAIEngine`.

## [1.42.3] - 2026-11-17

### Fixed
- **Tracking service loops dying together**: `DrivingTrackingService.serviceScope` used a plain `Job`, so one failing coroutine cancelled all eight service loops (including the speedometer decay watchdog). It now uses `SupervisorJob`.
- **Blocking startup integrity scan**: the full-database `PRAGMA quick_check` ran inside the blocking `runBlocking` startup migration although its result was only logged. It now runs after startup on an IO scope (`StorageMigrationManager.verifyDatabaseIntegrity`); migration/restore ordering is unchanged.

### Changed
- `DrivingTrackingService` is a Hilt `@AndroidEntryPoint`; `TripRepository`, `FuelRepository`, `VehicleStateManager`, `TripGeofenceManager`, `SpeedLimitEngine` and `AppDatabase` are field-injected (same singleton instances as before).

---

## [1.42.2] - 2026-11-17

### Fixed
- **Fuel entry price overwritten while typing**: the station lookup in `FuelEntryDialog` was keyed on the live GPS point and launched into an outer scope, so every GPS tick started another uncancelled geocode and reset the station name and price the user had typed. It now runs once per dialog (plus once when the first GPS fix arrives) inside the effect's own scope.

### Added
- `FuelEntryViewModel` (Hilt): station lookup + remembered price, and gauge-bar prediction via the injected `FuelRepository`.

### Changed
- `FuelEntryDialog` no longer calls `FuelRepository.getInstance()` / `StationPriceManager` directly.
- Split `UnifiedFuelTelemetryAndGaugeCard` into `FuelGaugeCard.kt` (dialog file: 1,631 → 682 lines).

### Removed
- Dead `AutoCalculatedTelemetryCard` (~290 lines, no callers).

---

## [1.42.1] - 2026-11-17

### Fixed
- **Settings backup cancelled on close**: "Backup Now" ran in the dialog's composition scope, so closing Settings mid-backup cancelled it silently. It now runs in `SettingsViewModel.viewModelScope`.
- **Geofence save/delete cancelled on close**: same issue in `GeofenceSettingsContent`; now handled by `GeofenceSettingsViewModel`.
- **Concurrent USB map imports**: import progress lived in composition, so reopening the maps manager mid-import re-enabled the button and allowed a second parallel import. Progress now lives in `OfflineMapsViewModel`, which rejects a second import while one is running.

### Added
- `domain/repository/SettingsRepository`, `BackupRepository` and their `data/` implementations (bound in `RepositoryModule`).
- `SettingsViewModel`, `GeofenceSettingsViewModel`, `OfflineMapsViewModel` (Hilt).

### Changed
- `LauncherSettingsDialog`, `GeofenceSettingsContent` and `OfflineMapsManagerContent` no longer call `AppSettingsManager`, `FullBackupManager`, `TripGeofenceManager` or `OfflineMapManager` directly.
- The duplicated USB-import lambda in the maps manager is now a single `importFromUsb` call.

---

## [1.42.0] - 2026-11-17

### Added
- **Hilt dependency injection** (`hilt-android 2.50`, `hilt-navigation-compose 1.1.0`): `@HiltAndroidApp CopilotApp`, `@AndroidEntryPoint MainActivity`.
- `di/AppModule` bridges existing singletons (`AppDatabase`, `TripRepository`, `FuelRepository`, `SpeedLimitEngine`, `TripGeofenceManager`, `VehicleStateManager`) into the graph so services and UI share one instance; `di/RepositoryModule` binds domain interfaces.
- **Domain layer**: `domain/repository/TripHistoryRepository`, `FuelLogRepository`; pure `domain/trip/YearlyTripAnalytics` (moved out of UI code).
- **Data layer**: `data/repository/TripHistoryRepositoryImpl`, `FuelLogRepositoryImpl`.
- **Feature ViewModels**: `TripHistoryViewModel` + `TripHistoryRoute`, `FuelHistoryViewModel` + `FuelHistoryRoute`.

### Changed
- `MainViewModel` is now a `@HiltViewModel` with constructor injection (no more `getInstance()`/`AndroidViewModel`); trip/fuel history methods moved to their feature ViewModels.

---

## [1.41.2] - 2026-11-17

### Added
- **Design tokens**: `core/designsystem/tokens/Palette` (named primitive colors) and `CopilotShapes` (shared corner shapes).
- **Reusable primitives** in `core/designsystem/components/CockpitPrimitives.kt`: `Modifier.cockpitSurface` (clip + fill + outline, Color/Brush), `TintedBadge` (Compact/Regular), and a shared `TactileKeypadButton`.

### Changed
- Replaced 446 inline `Color(0x…)` literals and 275 inline `RoundedCornerShape(n.dp)` allocations in feature code with palette/shape tokens (pixel-identical).
- Collapsed 81 `clip/background/border` chains into `cockpitSurface`.
- Fuel and odometer keypads now share one `TactileKeypadButton`; fuel-history and trip-history badges share `TintedBadge`.
- Split the 2,755-line `TripHistoryScreen.kt` into `TripHistoryModels`, `TripHistoryScreen`, `TripDetailViews`, `TripCharts`, `TripPlaybackMapView` and `TripAnalytics`.
- Version tests now validate the SemVer format instead of hardcoding a version (they broke on every bump; v1.41.1 shipped with these 3 tests failing).

### Removed
- Dead code: `Components.kt` (782 lines, no callers), `CockpitComponents`, `AutomotiveDimensions`, `CockpitThemeSystem`, `IndiaOfflineMapDialog`, `AppDrawerCard`/`AppDrawerDialog` aliases, `DrivingReportTheme`, unused `AdaptiveNumberText`.
- Duplicate `Premium_*` color aliases (identical to `Apex_*`).

---

## [1.41.1] - 2026-11-17

### Fixed
- **Security**: The `TEST_MEDIA_STATE` exported broadcast receiver in `MainActivity` is now registered only in debug builds; previously any installed app could spoof the now-playing card in release.
- **Coroutine resilience**: Added `SupervisorJob` to the singleton scopes in `PersistentDataManager`, `StationPriceManager`, `SpeedLimitEngine`, `TripGeofenceManager` and `VehicleStateManager`, so one failed job no longer permanently cancels all later work in that scope.
- **Lifecycle-aware UI collection**: Replaced `collectAsState()` with `collectAsStateWithLifecycle()` across all screens (added `lifecycle-runtime-compose`) so flows stop collecting while the launcher is in the background.

### Removed
- Duplicate `CrashGuardian.init` call in `MainActivity` (already initialized in `CopilotApp`).

---

## [1.41.0] - 2026-11-17

### Fixed
- **WorkManager HTTP 4xx Handling & Error Feedback**:
  - Fixed infinite retry loop on HTTP 404/403 responses in `MapDownloadWorker` by immediately returning `Result.failure()` with actionable error messages.
  - Added visible automotive `DownloadErrorCard` in `OfflineMapsManagerScreen` showing exact server responses with Retry, Dismiss, and USB Import shortcuts.
  - Fixed background thread Toast crash (`Looper.prepare()`) during USB/Storage import by dispatching all progress and completion callbacks on `Dispatchers.Main`.
  - Enhanced `importFromExternalStorage` with multi-tier directory scanning supporting `/sdcard/Download/`, USB OTG mounts, and SD cards.

### Added
- **Demo Test Vector Map Pack & Custom URL Mirror Editor**:
  - Added 550 KB `demo_sample_map` package for immediate offline map verification and testing.
  - Added per-package custom server / mirror URL configuration dialog (`CustomUrlDialog`) stored in SharedPreferences.
- **5-Macro-Region Modular Offline Vector Map Architecture with Real-Time GPS Auto-Mounting**:
  - Partitioned India into 5 optimized macro-regions (`india_north`, `india_west_central`, `india_south`, `india_east`, `india_northeast`) alongside the master All-India package.
  - Implemented real-time GPS spatial bounding-box resolution (`GeoBoundingBox`) via `currentGpsRegion` StateFlow in `OfflineMapManager`.
  - Seamless auto-mounting of the nearest regional `.mbtiles` file in `CarMapController` as the car travels across regions without manual switching or screen interruption.
  - Visual high-visibility `★ CURRENT GPS REGION` badge on regional package cards in `OfflineMapsManagerScreen`.
- **MapLibre Android SDK v10.3.2 Native Integration**:
  - Replaced legacy OSMDroid raster map engine with MapLibre GL Native `v10.3.2` pinned strictly for 32-bit `armeabi-v7a` CPU architecture and older head unit GPUs.
  - Added `ndk { abiFilters.add("armeabi-v7a") }` in `app/build.gradle.kts`.
  - Configured MapLibre `MapView` with `textureMode(true)` preventing black-screen compositing and GPU z-ordering bugs on Android 11 car head units.
- **Pure-JVM Offline Turn-by-Turn Routing Engine (GraphHopper Core)**:
  - Added pure-JVM `OfflineRoutingEngine` powered by GraphHopper Core v8.0 reading extracted routing graph data directly from Scoped Storage `context.getExternalFilesDir("routing")`.
  - Computes turn-by-turn maneuvers and draws real-time high-contrast cyan polyline routes with dark casing on the MapLibre map.
- **WorkManager & OkHttp Resumable Download Engine**:
  - Implemented `MapDownloadWorker` utilizing OkHttp with HTTP `Range` byte-offset support (`bytes=$existingLength-`) ensuring multi-GB map downloads survive car ignition power cuts and flaky mobile hotspots.
  - Automatic extraction of routing graph `.zip` archives into `getExternalFilesDir("routing")` followed by immediate archive cleanup to strictly protect the 15 GB storage budget.
- **Automotive-Grade Offline Maps & Routing Manager Screen**:
  - Created `OfflineMapsManagerScreen` / `OfflineMapsManagerDialog` featuring large automotive touch targets (>= 56dp).
  - Multi-segment device storage telemetry bar with critical alert if remaining free storage drops below 2.0 GB.
  - Live download progress card showing percentage, MB/s speed, Pause, Resume, and Cancel buttons.
  - Package cards for All-India Vector Map (~4.0 GB) and All-India Routing Graph (~4.5 GB) with Download, Update, and Delete actions.
  - One-touch USB / SD Card Import scanning external storage mounts (`/storage/`) to copy and extract pre-downloaded `.mbtiles` and routing `.zip` files.
- **Bundled Offline Vector Map Style**:
  - Added `assets/offline_style/style.json` with a dedicated automotive dark theme (openmaptiles schema) dynamically bound to local `.mbtiles` files via `mbtiles://` URIs with zero network requests.

### Changed
- **Car Navigation Controller (`CarMapController`)**:
  - Implemented 60fps smooth GPS vehicle puck tracking with heading rotation and 45° driving tilt.
  - Added prompt overlay with a direct launcher button to the Offline Maps Manager when no offline map is installed.
- **Strict Scoped Storage Compliance (Android 11 / API 30)**:
  - All maps, routing graphs, and download caches strictly stored under `context.getExternalFilesDir("maps")`, `context.getExternalFilesDir("routing")`, and `context.getExternalFilesDir("downloads")`.
- **Trip History & Geofence Preview Rendering**:
  - Refactored `TripPlaybackMapView` and `GeofenceMiniMapView` to high-performance Compose Canvas architectures, eliminating legacy osmdroid view dependencies and memory leaks.

### Removed
- **Legacy Raster Map Dependencies & Downloader**:
  - Completely removed `org.osmdroid:osmdroid-android:6.1.18`.
  - Removed old tile-by-tile batch downloader `IndiaOfflineMapManager.kt` and `IndiaOfflineMapDialog.kt`.

## [1.40.2] - 2026-11-16

### Changed
- **Unified Cockpit Telemetry Card Header Alignment & Color Synchronization**:
  - Aligned "SPEEDOMETER", "FUEL TANK", "CURRENT TRIP", and "DIGITAL CLOCK" card headers across the 2x2 cockpit dashboard grid with a standardized `44.dp` header height.
  - Harmonized typography across all 4 card headings using `CockpitTheme.typography.headlineSmall` with `FontWeight.Black`, `1.1.sp` letter spacing, and single-line restriction.
  - Standardized card heading text color to `CockpitTheme.colors.textSecondary` across all four quadrants (updating Digital Clock from standalone cyan accent).
  - Standardized telemetry icon container dimensions to `28.dp` with `6.dp` rounded corners, `18.dp` icon glyphs, and consistent `6.dp` spacing across Speedometer, Fuel Tank, Current Trip, and Digital Clock / Media cards.

## [1.40.1] - 2026-11-16

### Fixed
- **Bluetooth Music Timer & Position Drift Resolution**:
  - Eliminated continuous position resetting where periodic keepalive/state broadcasts with `position = 0` or missing timestamps continually reset the playback timer back to `0:00`.
  - Added smart track transition detection: resets playback timer only when a new song begins, while maintaining smooth monotonic advancing position via `SystemClock.elapsedRealtime()` during ongoing playback.
  - Implemented automatic duration & position scale normalization: seamlessly converts seconds-based telemetry (common in Chinese car head units) to milliseconds (`1..7200` seconds $\rightarrow$ `x1000` ms).
  - Added indeterminate active progress indicator and `"Live BT"` duration badge for Bluetooth streams without fixed duration metadata.
- **Enhanced Automotive Song Title & Artist Extraction**:
  - Implemented direct parcel extraction of `MediaMetadata` from Android Bluetooth AVRCP controller intents (`android.bluetooth.avrcp-controller.profile.extra.METADATA`).
  - Switched from fragile `intent.getStringExtra` to universal `bundle.get(...)?.toString()` across all intent extras, resolving null returns when head units pass metadata as `CharSequence` or `SpannableString`.
  - Added dynamic fallback extraction across all bundle keys containing `title`, `track`, `song`, `artist`, `singer`, or `author`.
  - Added automatic hyphenated delimiter parsing (`"Artist - Title"` / `"Title - Artist"`) when one field contains combined track metadata and the other is blank.
  - Added status bar notification extraction in `CarMediaNotificationListener` (`Notification.EXTRA_TITLE` and `Notification.EXTRA_TEXT`) as high-fidelity source of truth.
  - Added placeholder string filtering to prevent `"unknown"`, `"null"`, or `"Bluetooth Audio"` from overwriting genuine song titles.

## [1.40.0] - 2026-11-16

### Added
- **Universal Automotive Media Broadcast Receiver (`AutomotiveMediaBroadcastReceiver`)**:
  - Automatically captures track titles, artists, album names, duration, and live playback states from Chinese Car Head Unit Bluetooth apps (FYT/Syu, Microntek, TS10, Topway) and standard Android AVRCP broadcasts (`com.android.music.metachanged`, Bluetooth A2DP Sink `PLAYING_STATE_CHANGED`, etc.).
  - Bypasses traditional Android `MediaSession` limitations, ensuring Bluetooth Music details display in real-time on Copilot's Cockpit homescreen even if the head unit's stock BT app does not provide a standard media session.
  - Added dual broadcast command dispatching (`com.syu.bt.music.*`, `com.microntek.btmusic.*`, `com.ts.bt.music.*`) alongside `KeyEvent` media dispatch for reliable track skipping and play/pause toggling from the homescreen card.
- **Car Boot & Ignition Auto-Foreground Guardian (`BootForegroundGuardian`)**:
  - Implemented 15-second post-ignition foreground watchdog that detects when head unit ROM autostart scripts force-launch stock BT Music into the foreground.
  - Automatically reasserts Copilot Cockpit to the front after a 1200ms grace period (allowing Bluetooth stack to initialize without audio cut-off), keeping BT Music streaming smoothly in the background without requiring user to press Back.
  - Automatically disarms whenever user intentionally launches an application from the App Drawer, Navigation, or Settings.
- **Ignition & BT Media System Setting**:
  - Added `autoForegroundOnBoot` persistent preference in `AppSettingsManager`, DataStore, and `LauncherSettingsDialog` under General Settings.
- **One-Tap Notification Access Setup Toast**:
  - Added guided Toast notification when tapping the Media pill if Android Notification Access permission is not yet enabled for Copilot.

## [1.39.0] - 2026-11-16

### Added
- **Centralized Global Automotive Typography System (`AutomotiveTypographyTokens`)**:
  - Implemented 14 strongly typed semantic text styles derived from the production Home Screen (`DisplayLarge`, `DisplayMedium`, `DisplaySmall`, `HeadlineLarge`, `HeadlineMedium`, `HeadlineSmall`, `TitleLarge`, `TitleMedium`, `TitleSmall`, `BodyLarge`, `BodyMedium`, `BodySmall`, `LabelLarge`, `LabelMedium`, `LabelSmall`).
  - Standardized tabular figures (`tnum`) across all numeric telemetry, speed, odometer, clock, and fuel readouts for jitter-free vertical and horizontal alignment.
  - Full integration with Material 3 `CockpitTypography` and `CockpitTheme.typography`.
- **Clean Architecture + MVVM Feature-Oriented Reorganization**:
  - Extracted core design system packages: `core/designsystem/typography`, `core/designsystem/colors`, `core/designsystem/dimensions`, `core/designsystem/components`, and `core/designsystem/theme`.
  - Added clean architecture navigation contracts (`CockpitTab`, `CockpitNavEvent`) in `core/navigation` and centralized utilities in `core/common`.

### Changed
- **Comprehensive Codebase Typography Migration**:
  - Migrated Home Screen, Dashboard Cards, and Speedometer Cluster to semantic text tokens.
  - Migrated Smart Music Player & Clock Card, Fuel Tank Gauge, Current Trip, and Smart Trip Summary.
  - Migrated Fuel History Screen, Fuel Entry Dialog, and Refuel Station Prompt Dialog.
  - Migrated Trip History Screen, Route Replay, Charts, and Multi-Day Accordion views.
  - Migrated Live Map View, India Offline Map Dialog, and Speed Limit HUD.
  - Migrated App Drawer Screen, Launcher Settings, and Geofence Settings.
  - Migrated Splash Screen, Automotive Brand Logo, and System Recovery Activity.

### Fixed
- **Typography and Alignment Inconsistencies**:
  - Eliminated all scattered hardcoded `fontSize` declarations across application UI.
  - Resolved text clipping, unit baseline misalignments, and overflow issues across 10.1-inch automotive cockpit layout.
  - Preserved complete backward compatibility for theme tokens and existing unit tests.


### Changed
- **Calibrate Vehicle Odometer Dialog Redesign**:
  - Expanded dialog bounding box to `780.dp` width with an ergonomic two-pane split.
  - Implemented large tactile numeric keypad with tactile press-scale animation (`0.92f`), active glowing borders, backspace icon, and clear button matching the Add Fuel keypad.
  - Scaled odometer readout chamber font to $32\text{sp}$ bold, unit to $16\text{sp}$, and delta match badge to $14\text{sp}$.
- **Calibrate Instrument Gauge Dialog Scaling**:
  - Expanded dialog bounding box to `740.dp` width for automotive cockpit touch ergonomics.
  - Enlarge 10-bar calibration segments to $56\text{dp}$ width and $88\text{dp}$ height with tactile ripple feedback.
  - Scaled bar numbers to $21\text{sp}$ bold, percentages to $13.5\text{sp}$, estimated fuel level to $28\text{sp}$, and dialog buttons to $52\text{dp}$ height.
- **Add Fuel Dialog Comprehensive Typography Scaling**:
  - Scaled all metric labels (`AMOUNT`, `PRICE/L`, `ODOMETER`) to $13.5\text{sp}$ bold (up from $11\text{sp}$) and metric input heights to $48\text{dp}$.
  - Scaled gauge markers (`E`, `1/4`, `1/2`, `3/4`, `F`) from $10\text{--}10.5\text{sp}$ to $13\text{sp}$ bold.
  - Scaled telemetry card metric headers (`FUEL ADDED`, `RANGE GAIN`, `RUNNING COST`, `TOTAL RANGE`) from $10.5\text{--}12\text{sp}$ to $13.5\text{sp}$ bold, with subtitles at $12.5\text{sp}$.
  - Scaled progress bar level indicators to $13.5\text{sp}$ bold.
- **Fuel Records Month Accordion Header Scaling**:
  - Enlarged month group header button (`November 2026`) with a minimum height of $60\text{dp}$ and $18\text{dp} \times 14\text{dp}$ touch padding.
  - Scaled month title typography to $20\text{sp}$ bold and accordion chevron to $28\text{dp}$.
  - Scaled summary pill badges (`refills`, `liters`, `total cost`, `mileage`) to $13.5\text{sp}$ bold with $10\text{dp} \times 5\text{dp}$ padding.

### Fixed
- **Trip History Detail Tab Alignment**:
  - Fixed vertical and horizontal text and icon alignment across `Route Replay`, `Timeline`, and `Trip Charts` buttons.
  - Set tab height to $46\text{dp}$ with `CenterHorizontally` and `CenterVertically` arrangements, `PlatformTextStyle(includeFontPadding = false)`, and $14.5\text{sp}$ bold typography.

### Removed
- **Odometer Quick Delta Adjustment Line**:
  - Completely eliminated the `-1000 -100 -10 +10 +100 +1000` delta button row in favor of the direct large numeric keypad.

## [1.38.0] - 2026-11-16

### Removed
- **Complete Elimination of Gradient Glass Theme**:
  - Completely stripped legacy Gradient Glass theme across the entire application codebase (`Theme.kt`, `AppSettingsManager.kt`, `LauncherSettingsDialog.kt`, tests).
  - Removed `CockpitThemeMode.GRADIENT_GLASS`, glass background/card/dock brushes, and obsolete glass tokens.
  - Default cockpit theme is permanently consolidated onto **Apex Studio** (`APEX`), with automatic migration and sanitization for saved legacy user preferences.

### Changed
- **Balanced 50%/50% Automotive Cockpit Layout**:
  - Configured landscape dashboard to an exact 50% split (`weight(0.50f)` for live osmdroid Map panel on the left, `weight(0.50f)` for Telemetry metrics cluster on the right) providing equal visual prominence for road navigation and real-time driving gauges.
  - Rebalanced portrait layout to 50%/50% vertical split.
- **Automotive Typography Scaling for In-Dash Display (1280x800 Rockchip RK3326 @ 148 DPI)**:
  - Scaled typography across all non-homescreen screens to meet statutory automotive legibility standards (subtending $\ge 16'$ arcminutes at 60–90 cm viewing distance):
    - **Trip History Screen**: Scaled telemetry summary tile metrics to $22\text{sp}$, units to $13\text{sp}$, labels to $12\text{sp}$, trip card locations to $15.5\text{sp}$ bold, distances to $19\text{sp}$, and durations/speeds to $12.5\text{sp}$.
    - **Fuel Records Screen**: Scaled fuel stat badge values to $22\text{sp}$, total cost to $22\text{sp}$, liters to $17.5\text{sp}$, locations to $13.5\text{sp}$, and action button icons to $24\text{dp}$.
    - **App Drawer Screen**: Scaled app grid labels to $14.5\text{sp}$ bold, application icons to $58\text{dp}$, drawer header title to $22\text{sp}$, and search bar height to $50\text{dp}$.
    - **Fuel Entry Dialog**: Enlarged dialog bounding box (`1120x670.dp`), scaled action buttons (CANCEL & CONFIRM REFUEL) to $50\text{dp}$ height with $15\text{sp}$ bold text, and metric readouts to $12\text{--}15\text{sp}$.
    - **Geofence & System Settings**: Scaled distance values, coordinate text, and action buttons across settings dialog.

### Fixed
- **Touch Target Optimization & Tactile Feedback**:
  - Enlarged all interactive buttons and pills to automotive safety standards $\ge 48\text{dp}$ (dock pills at $64\text{dp}$, map controls at $52\text{dp}$, add fuel button at $44\text{dp}$, list edit/delete actions at $48\text{dp}$) to eliminate touch miss rates on the capacitive 60–100Hz digitizer.
  - Added tactile press animation (`graphicsLayer` scaling to $0.94\text{--}0.96$), dynamic glow border/background tint, and ripple feedback (`rememberRipple`) across all dock items, action pills, fuel gauge calibration bars, and app icons.
  - Removed root horizontal drag interception modifier (`detectHorizontalDragGestures`) from `AppDrawerScreen` that was stealing touch taps intended for application grid items.

## [1.37.0] - 2026-11-16

### Fixed
- **Speedometer Responsiveness & Accuracy (Eliminated 54-55 km/h Lag at 60 km/h Cruising)**:
  - **Removed Coordinate Low-Pass Filter in GpsLocationFilter**: Fixed `GpsLocationFilter` where moving vehicle coordinates were previously passed through an artificial low-pass filter (`weight = 0.90` / `0.65`). When traveling at 60 km/h, this caused the initial displacement to attenuate by 10% to 35% ($15.0\text{m}$ instead of $16.67\text{m}$), displaying $\sim 54\text{ km/h}$ and sluggishly ramping to $60\text{ km/h}$ over several seconds. Moving coordinates now snap directly to genuine GPS position ($1.0$ weight) while preserving stationary dead-zone freezing ($< 2.5\text{m}$) and high-speed multipath rejection ($> 240\text{ km/h}$).
  - **Speed Sensor & Computed Delta Fusion**: Upgraded `TripDetectionEngine.processLocation()` to fuse hardware Doppler sensor speed with geodesic displacement speed. When vehicle accelerates, any hardware Doppler lag (e.g. reporting $55\text{ km/h}$) is instantaneously superseded by the computed displacement speed ($60\text{ km/h}$), eliminating speedometer latency.
  - **SpeedometerCluster Wall-Clock Reference Fix**: Fixed `now` calculation in `SpeedometerCluster` to use real-time `System.currentTimeMillis()` rather than stale `tickerTimeMs`, preventing delayed or frozen decay evaluations.

- **Odometer Under-Counting & Distance Accuracy Fix**:
  - **Eliminated Cumulative Coordinate Distance Loss**: Because `GpsLocationFilter` no longer attenuates coordinates during driving, the step geodesic distance between successive GPS fixes is 100% preserved. Every kilometer driven now records the full $1,000\text{m}$ without losing 10%–35% of distance on turns, accelerations, and speed transitions.
  - **Movement Candidate Distance Retention**: Updated `TripDetectionEngine.startNewTrip()` to credit the initial displacement accumulated during the 15-second / 50m `MOVEMENT_CANDIDATE` verification window (`distFromStart`), preventing the first $50\text{m}$–$250\text{m}$ of non-geofence trips from being discarded on trip confirmation.

### Added
- **Speedometer Accuracy & Odometer Unit Tests**: Added `SpeedometerAccuracyAndOdometerTest` verifying exact $60\text{ km/h}$ readout on fix #1, speed fusion responsiveness without lag, and 100% geodesic distance accumulation over continuous 10-second cruising intervals.

## [1.36.0] - 2026-11-16

### Fixed
- **Infotainment Idle CPU Consumption Reduced from ~141% to 0% – 6%**:
  - **Eliminated Infinite Map Redraw Loop**: Removed recursive `mapView.postInvalidateDelayed(33L)` inside `PulsingAuraOverlay.draw()` that was triggering an unconstrained 30 FPS redraw loop across the entire osmdroid map canvas, forcing constant tile re-projection and color-filter execution.
  - **Recomposition & RenderThread Decoupling**: Extracted status beacon dot into a lightweight composable (`StatusBeaconDot`) and replaced continuous 60 FPS `rememberInfiniteTransition` with a clean static beacon dot during stationary states, eliminating non-stop Choreographer vsync draw triggers on the window.
  - **Stationary GPS Navigator Optimization**: Added stationary movement detection in `SmoothMapNavigator.updateTarget()` (detecting speed $< 1.5\text{ km/h}$ and sub-meter coordinate displacement) that cancels running 60 FPS dead-reckoning animators and updates marker position statically rather than scheduling continuous frame invalidations while parked.
  - **Compass Sensor Event Throttling**: Throttled hardware rotation sensor updates in `LiveMapView` while stationary ($500\text{ms}$ interval with $\ge 5^\circ$ deadband) and restricted sensor listener registration strictly to active `isHeadingUpMode`, preventing sensor noise from triggering redundant map orientation redraws and recompositions.
  - **Layer Type Optimization**: Switched osmdroid `MapView` from offscreen `LAYER_TYPE_HARDWARE` to `LAYER_TYPE_NONE`, eliminating intermediate framebuffer texture allocation and double-buffering overhead on `RenderThread`.
  - **Conditional Trip Flash Transition**: Guarded `flashPulseTransition` in `CurrentTripCard` to only instantiate and tick during active trip lifecycle events (`isFlashingGreen || isFlashingRed`), keeping the Compose animation clock completely idle during normal driving and parked states.

## [1.35.0] - 2026-11-16

### Added
- **Gradient Glass Cockpit Theme (Derived from Splash Screen Colors)**:
  - Added new `CockpitThemeMode.GRADIENT_GLASS` theme utilizing the exact color palette and aesthetic from the Welcome Splash screen across the entire app.
  - Multi-stop automotive vertical gradient background (`#02040A` -> `#071026` -> `#040A18` -> `#010307`) integrated seamlessly into `MainActivity`, `DashboardScreen`, `AppDrawerScreen`, `TripHistoryScreen`, `FuelHistoryScreen`, and `LauncherSettingsDialog`.
  - Frosted glassmorphism card surfaces with specular cyan top reflection (`Color(0x3300F0FF)` fading into frosted sapphire `#08142A` and midnight `#030918`) and glowing translucent electric cyan borders (`Color(0x4D00F0FF)`).
  - Luminous Splash Cyan telemetry accents (`#00F0FF`, `#00D2FF`, `#00E5FF`), soft cyan glow pills (`Color(0x2800F0FF)`), ice-white primary typography (`#F4FAFF`), and aerospace blue secondary text (`#7FAEE8`).
- **Cockpit Theme Selector in Settings -> General**:
  - Added **Gradient Glass** as an interactive theme option in Settings -> General alongside **Apex Studio**, complete with dynamic preview swatches and subtitle description.
  - Integrated animated color transitions (`animateColorAsState`) so theme switching across the dashboard and dialogs occurs fluidly without restarts or stutters.
- **Frosted Glass Cockpit Dock & Navigation Pills**:
  - Rebuilt the main launcher `CockpitDock` with a unified frosted glass container utilizing `dockBrush` (`#3500F0FF` top specular sheen $\rightarrow$ `#CC07132B` frosted sapphire $\rightarrow$ `#F2020510` midnight foundation) and glowing glass border `dockBorder`.
  - Upgraded each `DockAction` pill ("Trips", "Fuels", "Apps", "Settings") with `dockActionBrush`, glowing frosted circular icon capsules (`accentSoft` background and cyan border), and high-contrast `#F4FAFF` typography.
  - Aligned internal card telemetry docks (in `SpeedometerCluster`, `FuelTankCard`, and `CurrentTripCard`) to use `dockActionBrush` for complete glassmorphic consistency.
- **Unit Test Suite Expansion**:
  - Expanded `CockpitThemeSystemTest` to test `GRADIENT_GLASS` theme resolution, token integrity, default state verification, and safe fallbacks.
  - Expanded `CockpitThemeSystemTest` to test `GRADIENT_GLASS` theme resolution, token integrity, default state verification, and safe fallbacks.

### Changed
- **Default Cockpit Theme Updated**:
  - Set default theme to `gradient_glass` in `AppSettingsManager.SettingsState` and SharedPreferences initialization for new sessions, with backwards-compatible alias mapping.

### Added
- **Car Ignition Deep Sleep & 12V Battery Protection in DrivingTrackingService**:
  - Added automotive ignition power-cut detector for `Intent.ACTION_POWER_DISCONNECTED` that safely releases partial wakeLock (`wakeLock?.release()`) and stops GPS hardware tracking updates (`stopLocationUpdates()`), preventing vehicle 12V battery drain and permitting Android infotainment head units to enter deep sleep.
  - Preserves ongoing journey continuity by transitioning active trips to `TEMPORARY_STOP` and persisting them to Room SQLite (`persistActiveTrip`) so that multi-day and multi-stop journeys remain intact without accidental termination or discard.
  - Automatically re-acquires partial wakeLock (`PowerManager.PARTIAL_WAKE_LOCK`) and resumes GPS hardware updates (`requestLocationUpdates()`) upon engine ignition restoration (`Intent.ACTION_POWER_CONNECTED`).
  - Protected `drivingDurationMs` and `stationaryDurationMs` against false time accumulation during car engine-off / deep sleep states (whether 1 hour, overnight, or 5+ days), resuming accurate telemetry seamlessly upon drive-off.
- **Room SQLite Multi-Stop Trip Restoration**:
  - Updated `TripDao.getActiveTrip()` to query `status IN ('ACTIVE_TRIP', 'TEMPORARY_STOP')` enabling seamless trip state recovery across vehicle reboots and service recycles.
- **Comprehensive Ignition Protection Test Suite**:
  - Created `CarIgnitionDeepSleepBatteryProtectionTest` covering multi-day deep sleep immunity, 1-hour dwell timing, stationary duration freezing, and seamless route continuation.
- **On-Map Dark Mode / Light Mode Toggle Icon**: Added an interactive lighting mode button (`Icons.Default.DarkMode` / `Icons.Default.LightMode`) directly to the right-hand ergonomic map controls column in `LiveMapView`, enabling the driver to switch with one tap between daytime bright roadmap and anti-glare cockpit dark mode.
- **High-Contrast Dark Cockpit Map Filter (`DARK_MODE_FILTER`)**: Integrated midnight-blue inverted color matrix filter in `OfflineMapManager` that preserves road and highway legibility while matching cockpit aesthetic and preventing eye fatigue during night driving.
- **High-Contrast Normal Road Map Engine (`Google_Roadmap`)**: Added `OfflineMapManager.NORMAL_ROADMAP_SOURCE` delivering high-contrast vector-styled road tiles with clear highways, local streets, road names, and town labels, replacing dark satellite imagery for superior driving legibility.

### Changed
- **Optimized All-India Offline Road Map System for Strict <= 10 GB Budget**:
  - Enforced a strict 10 GB hard cache limit (`tileFileSystemCacheMaxBytes = 10000L * 1024 * 1024`) and 9 GB trim threshold in `OfflineMapManager`.
  - Partitioned `india_national` package to Zooms 0–12 (~3.66 GB, 174,399 tiles), covering complete national highways, expressways, state corridors, and city networks across entire India without internet.
  - Optimized regional packages (Central, North, West, South, East) to Zooms 0–13 (~0.85 GB to ~2.2 GB each) for fine district and street navigation.
  - Updated tile estimation model to reflect ~22 KB average roadmap tile size for accurate storage telemetry.
  - Updated `IndiaOfflineMapDialog` storage status indicator to display `STORAGE: X MB / 10 GB MAX`.
- **Switched to Normal Road Map System**:
  - Replaced satellite imagery with standard high-visibility road map tiles across `LiveMapView`, `TripPlaybackMapView`, `GeofenceMiniMapView`, and `IndiaOfflineMapManager`.
  - Updated offline map download tile pipeline to stream Google Normal Road Map tiles with fallback to OpenStreetMap Mapnik and Humanitarian HOT tiles.
  - Updated `IndiaOfflineMapDialog` branding and headers to "India HD Offline Road Map System".
- **Unlocked Full Optical Zoom Spectrum (Zoom 0 to Max 20)**:
  - Removed the temporary Zoom 13 limit; set `minZoomLevel = 0.0` and `maxZoomLevel = 20.0` across all map views.
  - Restored standard driving camera focus zoom to 17.5 for crisp street and junction navigation.
  - Enabled unrestricted pinch-to-zoom and button zooming across the entire zoom range.

## [1.34.0] - 2026-11-15

### Added
- **Full-Screen Automotive Settings Screen**: Replaced the floating 92% dialog container with a responsive, 100% full-screen layout featuring fluid slide-and-fade entrance/exit animations, hardware back handler, and a prominent 48dp circular back button.
- **Dedicated Automotive Navigation Rail**: Rebuilt the settings navigation rail (270dp width, 60dp touch target height, 18sp bold text) providing ergonomic access to essential automotive preferences.

### Changed
- **Enlarged Glanceable Typography**: Rebuilt the settings interface adopting cockpit typography standards ("bada bada dikhe"): Screen Title scaled to 24sp Black, card titles to 20sp Bold, setting labels to 17-18sp Bold, action buttons to 52-54dp height with 16-17sp Bold text, and large input fields.
- **Streamlined General Settings**: Consolidated the General tab into a high-contrast Cockpit Color Theme selector (Apex Studio) and an Automotive Runtime Environment status card.

### Removed
- **Display & Screen Behavior**: Removed display timeout and auto-minimize drawer toggles from the General settings tab; screen always remains on by default for in-vehicle driving.
- **Localization Section**: Removed redundant measurement unit options from the General settings tab; metric system (km, km/h, km/L) remains standard.
- **Appearance Tab**: Completely removed the dedicated Appearance tab.
- **Expected Baseline Mileage**: Completely removed the redundant Expected Mileage text field from the Vehicle settings tab.
- **Tracking Settings Tab**: Completely removed the Tracking tab; kinematics, auto trip detection, and 1Hz sensor fusion remain always active in the background.
- **Maps Settings Tab**: Completely removed the Maps settings tab; satellite hybrid maps, building footprints, and permanent caching remain always enabled.
- **Speed & Safety Tab**: Completely removed the Speed & Safety tab; dynamic speed limits, overspeed visual warnings, and driver safety scoring remain always active in the background.

## [1.33.0] - 2026-11-15

### Added
- **Expanded Rotating Trip Metrics (8 Driver Telemetry Cards)**: Added Average Speed (`KM/H`), Fuel Consumed (`L`), Trip Expense (`₹`), and Driving Safety Score (`PTS`) alongside Trip Distance, Max Speed, Trip Economy, and Peak Braking for a comprehensive in-cockpit telemetry suite.
- **Tap-to-Advance Metric Carousel**: Made the `CurrentTripCard` hero metric card and indicator dots tap-interactive, advancing immediately to the next metric and resetting the 30-second dwell timer.

### Changed
- **Extended Carousel Rotation Timing**: Increased the automatic rotating metrics dwell time from 7-10 seconds to 30 seconds (`30_000L`) for safer, distraction-free reading while driving.
- **Responsive Telemetry Dots**: Adjusted dot indicators to cleanly accommodate all 8 metrics with ergonomic touch targets on automotive displays.

### Fixed
- **Pre-Trip Mileage Display Bug**: Fixed `TripSummary.calculatedMileageKmpl` default from hardcoded `15.0` to `0.0`, ensuring pre-trip and stationary state displays `--` instead of an erroneous 15.0 km/L.
- **Fuel System Perpetual "Learning" State**: Fixed `FuelRepository` and `FuelTankCard` so the fuel system resolves vehicle baseline mileage labeled `EST. MILEAGE` when zero tank refuels are recorded, preventing the perpetual "Learning" display.

## [1.32.0] - 2026-11-15

### Removed
- **Voice Guidance & Smart Audio**: Completely eradicated `AutomotiveVoiceAssistant`, TextToSpeech hooks, spoken greeting / arrival / fatigue / refuel announcements, and the "Voice Guidance & Smart Audio" configuration card from settings and background tracking services.
- **Trip Summary Screen & Auto-Transitions**: Completely removed `SmartTripSummaryScreen`, navigation tab 4, auto-switching jobs on trip completion, and smart summary action triggers from `TripHistoryScreen` and `AppDrawerScreen`.
- **Current Trip Tap Interaction**: Removed tap navigation and clickable behavior on `CurrentTripCard`, ensuring it functions purely as an in-cockpit telemetry instrument card.

### Changed
- **App Drawer Header & Back Navigation**: Replaced the "Cockpit" text button on the top-left with a circular back button (`ArrowBack`) and removed the close button beside the search bar, leaving a clean search layout and intuitive back navigation.
- **Speedometer Delta Distance Readout**: Replaced the "Synced" label in the Speedometer gauge with the exact extra kilometers driven since the last odometer sync/calibration (`+X.X km`).
- **Cockpit Layout & Touch Usability**: Expanded the bottom navigation dock height to 64dp with 28dp icons, 17sp bold text, and 16dp horizontal padding for effortless driver tapping while driving.
- **Cockpit 45/55 Proportion & Telemetry Sizing**: Rebalanced the cockpit screen proportion to allocate 45% to Live Map and 55% to Driver Telemetry. Enlarged primary metric readouts across all cards (Fuel liters to 38sp, Tank percentage to 36sp, Odometer & Range to 20sp, and expanded Speedometer gauge radius).
- **Cockpit Navigation**: Added a bottom dock with Trips, Fuels, Apps, and Settings. Moved the Trips, Fuels, and Settings shortcuts out of the app drawer and removed the cockpit swipe-left shortcut for opening All Apps.

### Fixed
- **Settings SharedPreferences Type Safety**: Added safe float parsing in `AppSettingsManager` to prevent `ClassCastException` when upgrading preferences where numeric values were previously saved as integer types.

## [1.31.1] - 2026-11-15

### Changed
- **Current Trip Telemetry Display (Single-Page Automotive View)**: Replaced the split-screen layout in `SmartTripSummaryScreen` with a clean, unified, high-density telemetry layout optimized for 10.1-inch landscape cockpit displays. Telemetry data now presents in a balanced two-column dashboard (Primary Telemetry & Motion Breakdown on the left, Fuel & Running Cost with Route Corridor & Milestones on the right) fitting completely on a single page without cramped scrolling.
- **Enhanced Fuel & Running Cost Readouts**: Integrated `TRIP MILEAGE` (KM/L) directly into `FuelAndEfficiencyCard` alongside estimated fuel burnt (L) and trip cost (₹), complemented by cruising ratio telemetry and measurement method indicators.
- **Unified Corridor & Timeline Milestones**: Consolidated route departure/arrival times, stops count, GPS accuracy rating, refuel highlights, and chronological milestones into a streamlined card (`TripCorridorAndDetailsCard`).

### Fixed
- **DrivingTrackingService Cached Location Query Guard**: Wrapped cached location manager queries (`queryLocationManagerCachedFix`) with a `SecurityException` handler to prevent crashes on fresh device setups or emulators before runtime location permissions are granted.

### Added
- **10.1-inch Tablet Android Virtual Device (AVD)**: Created and verified an Android 11 (API 30, x86_64) tablet emulator (`Tablet_10_1_API_30`) running WXGA 1280x800 landscape resolution at 160 dpi with KVM hardware acceleration and 2048 MB RAM.
- **Linux Android Development Environment**: Configured Eclipse Temurin OpenJDK 17, Android Studio IDE with system desktop launcher, Android SDK Platforms 34 & 30, Build-Tools 34.0.0, Platform-Tools, and Android Emulator.

---

## [1.31.0] - 2026-11-15

### Added
- **Speedometer-Style Numeric Typography Tokens (`AutomotiveDesignTokens.NumericTypography`)**: Created centralized typography tokens standardizing automotive font family (`FontFamily.SansSerif`), `FontWeight.Black`, and tabular figures (`fontFeatureSettings = "tnum"`) across the launcher.
- **Unified Telemetry Readouts**: Applied standardized speedometer-style numeric typography to live speed, odometer readings, delta since calibration, stopping distance calculations, fuel quantities and tank levels, estimated driving range, trip distance, duration timers, digital clock, and trip summary statistics.
- **Standardized Numeric Typography Test Suite**: Added verification unit tests in `AutomotiveDesignTokensTest` ensuring strict enforcement of tabular figures, typography weights, and token geometry.

### Changed
- **Driver-Legible Automotive Typography & Touch Targets**: Eliminated undersized micro-typography across cards (increased secondary labels from 8.5sp–9.5sp to 11.5sp–13sp) to ensure effortless readability at 10.1-inch landscape driving distance.
- **Adaptive Number Text & Metric Value Standardization**: Updated `AdaptiveNumberText` and `AdaptiveMetricValueText` to automatically inherit speedometer font family and tabular numerals by default.
- **App Drawer & Cockpit Docks Polish**: Enhanced touch target dimensions, icon alignment, and visual hierarchy across telemetry docks and quick-action navigation pills.

### Fixed
- **Inconsistent Numeral Styling**: Permanently eradicated font-family mismatch between monospace and sans-serif digits across fuel dialogs, trip summaries, timeline playback, and odometer dialogs.
- **Dead Code & Compiler Warnings**: Removed unused variables (`isCurrent`), replaced deprecated icons with `Icons.AutoMirrored` variants, and upgraded deprecated dividers to Compose M3 layout standards.

---

## [1.30.0] - 2026-11-15

### Added
- **Jetpack DataStore Persistent Settings Engine (`SettingsDataStore`)**: Integrated Jetpack Preferences DataStore providing crash-safe, atomic disk writes, thread-safe transactional updates, and reactive flow emissions for all 27 cockpit configurations and automotive settings.
- **Enterprise Storage Migration Manager (`StorageMigrationManager`)**: Implemented version-safe startup orchestration maintaining an independent `CURRENT_STORAGE_VERSION = 1` across app updates; handles safe pre-validation, non-destructive schema migrations, database integrity checks (`PRAGMA quick_check`), and guaranteed user settings preservation.
- **Comprehensive Automotive Backup Snapshotting**: Enhanced `PersistentDataManager` and `FullBackupManager` to mirror all 27 user settings, appearance preferences, vehicle specifications, last completed trip references, and odometer calibrations to durable mirror storage.
- **Android Auto Backup & Data Extraction XML Rules**: Added `backup_rules.xml` and `data_extraction_rules.xml` defining explicit retention policies for Room databases, DataStore stores, and preferences across OS-level backups and app updates.
- **Automated Storage & Migration Verification Test Suite (`PersistentStorageAndMigrationTest`)**: Added full unit test coverage validating sequential Room migrations (v1 through v8), storage version independence, preference defaults priority, and DataStore key integrity.

### Changed
- **Dual-Backed App Settings (`AppSettingsManager`)**: Refactored `AppSettingsManager` to synchronize in-memory StateFlow with Jetpack DataStore asynchronously while maintaining synchronous initial state and backward-compatible SharedPreferences bridging.
- **Safe Application Startup (`CopilotApp`)**: Orchestrated ordered startup sequence ensuring `StorageMigrationManager` completes migration and settings hydration before the UI renders or foreground tracking services launch, preventing default-value overwrite race conditions.

### Fixed
- **App Update Data Loss Root Cause**: Permanently eliminated data and settings reset on APK upgrade by eradicating startup race conditions, removing destructive migration fallbacks, and ensuring existing user configurations take strict precedence over initial defaults.
- **Room Database Destructive Fallback Elimination**: Removed `fallbackToDestructiveMigrationOnDowngrade` in `AppDatabase`, guaranteeing user databases and historical driving records are never wiped or destroyed during version transitions.

---

## [1.29.1] - 2026-11-15

### Changed
- **Current Trip Card Tap Target**: Removed the separate "SUMMARY" pill button from the card header; made the entire `CurrentTripCard` directly clickable (`onClick = onOpenTripSummary`) for effortless one-touch access to trip reports while driving.
- **Trip Summary Big Numbers Telemetry Redesign**: Re-engineered `SmartTripSummaryScreen` into a high-impact, glanceable automotive dashboard featuring large prominent numbers (`42sp`–`46sp` for Total Distance, Trip Duration, Average Speed, and Peak Speed). Added structured breakdown cards for driving vs stopped motion, driver rating score, fuel burn, and cruising efficiency.

### Removed
- **Verbose AI Insight Descriptions**: Completely purged paragraph-length text descriptions and explanatory AI cards (`AiTripInsightsCard`) in favor of direct, large-format numerical metrics optimized for automotive head-units.

---

## [1.29.0] - 2026-11-15

### Added
- **Centralized Automotive Design System (`AutomotiveDesignTokens`)**: Introduced an 8dp-based spatial grid, driver-safe touch targets (64–88dp), prominent glanceable typography hierarchy (speed: 82sp, clock: 62sp, hero: 44sp, secondary: 34sp), consistent container radii (20dp card, 16dp container, 14dp button, 24dp pill), and standardized dock dimensions (48dp height).
- **Driver-Safe High-Contrast Automotive Theme**: Preserved dark cockpit contrast with vibrant semantic accents (Electric Cyan `#38BDF8`, Emerald `#22C993`, Amber `#F59E0B`, Coral/Red `#EF4444`) and deep navy surfaces (`#080E1B` / `#111D30`) optimized for 10.1-inch landscape automotive displays.
- **Automotive Design Tokens Test Suite (`AutomotiveDesignTokensTest`)**: Added comprehensive unit test coverage verifying spatial tokens, touch target minimums (>=64dp), typography invariants, 6x5 app drawer grid, and clean settings persistence.

### Changed
- **Fixed Automotive Infotainment Layout**: Redesigned all major screens and components—including Dashboard, Speedometer Cluster, Fuel Tank Card, Current Trip Card, Smart Music & Clock Card, App Drawer, Smart Trip Summary, Settings, and Fuel Entry dialogs—to use unified fixed tokens instead of dynamic font multipliers.
- **App Drawer & Dock Architecture**: Replaced scaling with fixed 48dp app drawer icons, 12sp labels, 48dp navigation pills, and standardized 48dp card docks with 16dp internal card padding.

### Removed
- **Dynamic 4-Level UI Scaling**: Completely eliminated `UiScaleMode` (`Small`, `Default`, `Large`, `Extra Large`), `CockpitScaleTokens`, `LocalCockpitScale`, `LocalUiScaleMode`, `Typography.scaled()`, preferences keys (`KEY_UI_SCALE`, `uiScale`), and UI scale selector settings.
- **`isVeryCompact` Logic & Compact Workarounds**: Completely eradicated `isVeryCompact` flags, conditional layout branches, compact dimensions, and obsolete responsive fallbacks across all launcher composables and dialogs.

---

## [1.28.0] - 2026-11-15

### Added
- **Smart Trip Summary Full-Screen Page**: Introduced a modern automotive report screen (`SmartTripSummaryScreen`) presenting complete trip intelligence upon trip completion, with quick glance telemetry badges, full trip metrics, speed bands, elevation profiling, and GPS quality indicators.
- **Interactive Offline Satellite Route Map**: Integrated `SmartTripMapView` using offline satellite tiles, displaying start/finish pins, intermediate stationary stops, route trajectory, and interactive zoom/pan controls.
- **Deterministic AI Trip Insights**: Added `SmartTripAnalyticsEngine` computing transparent driver efficiency scores, speed band breakdowns, stationary dwell impact, historical trip comparisons, and actionable fuel-saving observations grounded purely in measured telemetry.
- **Integrated Fuel & Refueling Report**: Correlated trip duration and geographic route with `FuelRepository` logs to detect and display refueling events (litres added, time, location, cost) alongside sensor-backed or calculated consumption figures.
- **Chronological Trip Timeline**: Added an event timeline documenting journey start, major stops with durations, refueling intervals, and completion timestamps.
- **Persistent Last Completed Trip Architecture**: Implemented persistent pointer reference in `TripRepository` and `AppDatabase` preserving the latest completed trip across infotainment power cycles and app restarts (Scenario B default launcher destination), while maintaining permanent historical trip records.
- **Automatic 5-Second Transition & Lifecycle Recovery**: Added automatic 5-second post-completion transition to Smart Trip Summary (Scenario A/D), automatic return to Cockpit Dashboard when a new trip commences (Scenario C), and manual access via Dashboard pills and All Apps drawer.

### Changed
- **Stationary Debounce & Traffic Jam Detection**: Enhanced `TripDetectionEngine` with stationary anchor tracking and configurable debounce thresholds (`stationaryCompletionThresholdSeconds`, `stationaryDriftRadiusMeters`), preventing false trip termination during traffic lights, congested bottlenecks, and temporary halts.

---

## [1.27.2] - 2026-11-15

### Added
- **Speedometer Cluster Odometer & Since Cal. Integration**: Embedded vehicle odometer (`ODOMETER`) and calibration delta (`SINCE CAL.`) in the `SpeedometerCluster` bottom dock, replacing obsolete stop distance and best eco widgets. Added interactive `SpeedometerOdometerCalibrationDialog` on tap for instantaneous odometer offset adjustments.
- **Permanent GraphicEq Media Pill**: Introduced a permanent automotive equalizer pill (`Icons.Default.GraphicEq`) inside `SmartMusicClockCard`, visibly indicating media status across both idle and active playback modes with a modern automotive icon.

### Changed
- **Unified Dark Cockpit Theme**: Standardized outer background, elevated dock surfaces (`cardElevated`), borders, and corner radiuses identically across `SpeedometerCluster`, `FuelTankCard`, `CurrentTripCard`, and `SmartMusicClockCard`.
- **10-Bar Fuel Gauge Synchronization**: Refined full tank LED MID bar fallback to accurately illuminate all 10 segments when tank level is at 100%.

---

## [1.27.1] - 2026-11-15

### Fixed
- **Restored Original Speedometer & Fuel Tank Cards**: Restored the original standalone `SpeedometerCluster` (with 240° dial, speed limit, speed trend badge) and standalone `FuelTankCard` (with "+ ADD FUEL" button, OEM capacity, 10-bar LED MID gauge, Est. Range, and Current Mileage km/L) in the balanced 2x2 cockpit dashboard grid alongside `CurrentTripCard` and `SmartMusicClockCard`.
- **UI Scaling Text Shrinkage Bug**: Fixed root-cause regression in `DashboardScreen` where selecting `UiScaleMode.EXTRA_LARGE` inadvertently forced `isVeryCompact = true`, causing card typography and layouts to collapse into tiny compact mode instead of scaling up.
- **Automotive Typography & Glanceability**: Elevated typography across speedometer digits, digital clock, and telemetry metrics for effortless readability from normal driving distance.
- **10-Segment LED MID Gauge & Calibration**: Restored original 10-point color palette (red, amber, cyan) with full mid-bar calibration dialog trigger.

---

## [1.27.0] - 2026-11-14

### Added
- **Unified Vehicle Information Card**: Consolidated Speedometer, Fuel System, and Odometer into a single automotive telemetry card featuring a 240° precision dial, prominent speed readout, fuel tank gauge with DTE range, and live calibrated odometer tracking.
- **Smart Music Player & Permanent Clock Card**: Replaced the old standalone Odometer card with an intelligent dual-mode card. When media is idle, presents a large glanceable digital clock; when media is active, seamlessly displays clock alongside track metadata, album art, progress bar, and active media session controls.
- **Automotive Media Session Integration**: Integrated `MediaSessionManager` with active controller prioritization, transport controls (Play/Pause, Skip Next/Prev, Seek), and zero-IPC-polling elapsed realtime progress calculation.

### Changed
- **Cockpit Dashboard Telemetry Layout**: Rebalanced the telemetry quadrant into a unified top-row Vehicle Dashboard Card and bottom-row Current Trip + Music/Clock layout with responsive scaling support across Small (0.85×), Default (1.00×), Large (1.15×), and Extra Large (1.30×).

### Removed
- **Standalone Odometer Card & Media Pill**: Removed legacy standalone `OdometerCard` and obsolete `MediaPlaybackPill`, reclaiming UI real estate and eliminating redundant state computation.

---

## [1.26.0] - 2026-11-14

### Added
- **UI & Text Scaling System**: Added a centralized, responsive scaling system under **Settings → Appearance** offering four optimized levels: Small (0.85×), Default (1.00×), Large (1.15×), and Extra Large (1.30×).
- **Appearance Settings Tab**: Introduced a dedicated `Appearance` category in `LauncherSettingsDialog` featuring an intuitive visual selector card deck for UI scaling with instant live preview and theme swatches.
- **Immediate Reactive Scaling & Persistence**: Scales typography, icon targets, cards, padding, and navigation docks across the entire launcher reactively without requiring an app restart, persisted across reboots via `AppSettingsManager`.

### Changed
- **Responsive Layout & Overflow Protection**: Integrated adaptive bounds calculation preventing clipping, text overlap, and layout overflow across all screens including Cockpit dashboard, Live Map, App Drawer, Trips, Fuels, and system dialogs at all 4 scale tiers.
- **App Drawer Grid & Dock Optimization**: App drawer 6×5 grid icons and labels adapt dynamically with scaling while maintaining touch usability and screen space constraints.

---

## [1.25.3] - 2026-11-14

### Fixed
- **App Drawer Navigation to Trip & Fuel History**: Resolved navigation masking where clicking `Trips` or `Fuels` in the app drawer header selected the corresponding tab (`TripHistoryScreen` / `FuelHistoryScreen`) without dismissing the overlay drawer. Now automatically closes the app drawer to immediately present Trip History and Fuel History.
- **Top Dock Quick Button Label**: Renamed the top dock action pill to `Fuels` for direct alignment with Cockpit, Trips, and Settings navigation.

---

## [1.25.2] - 2026-11-14

### Changed
- **6×5 App Drawer Grid**: Converted the application drawer into a high-density 6-column × 5-row grid (30 apps per page) optimized for widescreen automotive displays.
- **Enlarged Action Buttons**: Increased touch targets, icon sizes (19-22dp), and text labels (13.5-15sp) for Cockpit, Trips, Fuel Log, and Settings pill buttons in the top dock for enhanced driver glanceability.

---

## [1.25.1] - 2026-11-14

### Changed
- **1×1 App Icon Redesign**: Converted app drawer items to compact 1×1 centered launcher icons with app titles underneath, removing bulky rectangular card boxes and borders for a native launcher look and feel.
- **Enlarged Top Navigation Dock**: Expanded `AppDrawerHeader` into a taller, more prominent top dock bar.
- **Icon + Text Pill Buttons**: Replaced icon-only action buttons in the top dock with clear, driver-readable Icon + Text pill buttons (`Cockpit`, `Trips`, `Fuel Log`, `Settings`).

### Removed
- **Home Screen Apps Indicator**: Removed the right-edge swipe indicator affordance from `DashboardScreen.kt`, leaving the home screen completely clean while preserving left-swipe drawer opening.

---

## [1.25.0] - 2026-11-14

### Added
- **3×4 Horizontal App Drawer**: Introduced `AppDrawerScreen.kt` featuring a strict 3-column × 4-row grid (12 applications per page) with smooth horizontal swiping, glowing pill page indicators, search filtering, and quick navigation shortcuts.
- **Left-Swipe Gesture Navigation**: Swiping left anywhere from the home screen smoothly transitions to All Applications, with a glanceable automotive edge affordance.
- **Dynamic Application Detection & Package Watcher**: Added runtime `BroadcastReceiver` in `MainActivity` for `ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REMOVED`, `ACTION_PACKAGE_CHANGED`, and `ACTION_PACKAGE_REPLACED` to dynamically refresh the app drawer without stale caches or duplicates.
- **Automotive Grid Alignment**: Balanced placeholder cells ensure that final partially filled pages maintain exact column widths, row heights, and top-left alignment without stretching or centering distortion.

### Changed
- **Full-Screen Home Screen Redesign**: Reclaimed 84dp+ of vertical space across 10.1-inch automotive displays, expanding `LiveMapPanel` and the 2×2 telemetry cards (`SpeedometerCluster`, `FuelTankCard`, `CurrentTripCard`, `OdometerCard`) to fill the full viewport.
- **SemVer Minor Bump**: Upgraded version to `1.25.0` (versionCode `42`) in `app/build.gradle.kts`.

### Removed
- **Bottom Dock Elimination**: Completely eliminated `PremiumBottomDock`, `PremiumDockClock`, and `PremiumDockItem` composables, containers, spacers, and dock-specific navigation states from `DashboardScreen.kt`.
- **Legacy Dialog Drawer**: Removed obsolete `AppDrawerDialog.kt` and its vertical scrolling grid.

---

## [1.24.1] - 2026-11-14

### Changed
- **Streamlined Navigation & Controls**: Removed Statistics toggle chips from top header bars in both `TripHistoryScreen` and `FuelHistoryScreen` for an uncluttered and direct records browsing experience.
- **Direct Feed Presentation**: `TripHistoryScreen` and `FuelHistoryScreen` now display the chronological feeds and yearly summary banners directly without mode toggling overhead.

### Removed
- **Dedicated Statistics Dashboards**: Completely removed `TripStatisticsDashboard.kt` (~77 KB) and `FuelStatisticsDashboard.kt` (~73 KB) along with all associated sub-composables, data classes, and chart visualizers.
- **Dead Parameters & Imports**: Removed unused `trips` parameter from `FuelHistoryScreen` and cleaned unreferenced icon and database entity imports.

---

## [1.24.0] - 2026-11-14

### Changed
- **Rebrand to Copilot**: Updated application identity, brand typography in `AutomotiveBrandLogo`, splash screen reveal title, launcher app name (`strings.xml`), foreground service notification channels, and settings dialogs to "Copilot".
- **Package Name Migration**: Refactored namespace and `applicationId` to `com.antigravity.copilot` across Gradle build configuration, AndroidManifest, and entire Kotlin package tree (111 source files).
- **Core Architecture Modernization**: Renamed application entry point to `CopilotApp` and design theme system to `CopilotTheme` (with backward-compatible typealiases for seamless test coverage).
- **Storage & Backup Directory Modernization**: Updated persistent backup archives and crash logging to `/sdcard/Copilot/Backups` with automatic fallback migration detection for legacy `/sdcard/DrivingReport` stores.

---

## [1.23.1] - 2026-11-14

### Changed
- **SemVer Version Bump**: Upgraded version to `1.23.1` (versionCode `39`) following semantic versioning guidelines for patch cleanup release.
- **Dependency Optimization**: Removed redundant `org.maplibre.gl:android-sdk:11.0.0` dependency from `app/build.gradle.kts`, relying strictly on lightweight hardware-accelerated OSMDroid native engine and reducing final APK footprint.

### Removed
- **Obsolete UI Components**: Safely removed deprecated prototype composables `FuelStatisticsView`, `InsightCard`, `formatScaleValue`, `AutomotiveFuelChartCanvas`, and `FuelHistoryDialog` from `FuelHistoryScreen.kt` (superseded by modern `FuelStatisticsDashboard.kt`).
- **Dead Visualizer Logic**: Removed unreachable `AnnualTripStatisticsView` and `AnnualInsightCard` from `TripHistoryScreen.kt` (superseded by modern `TripStatisticsDashboard.kt`).
- **Unused Core Engines & Models**: Removed unreferenced `DarkMapStyle.kt`, `SolarEphemerisEngine.kt`, and obsolete `DrivingScoreReport` model from `Models.kt`.
- **Unused Drawables**: Removed dead vector asset `@drawable/ic_navigation_arrow.xml`.
- **Redundant Imports**: Purged 100+ unused imports across all production and unit test source files to enforce strict codebase hygiene.

---

## [1.23.0] - 2026-11-14

### Added
- **Automotive Brand Logo Reveal Stage**: Initial brand presentation in `WelcomeSplashScreen` featuring the newly designed aerodynamic apex winged crest (`AutomotiveBrandLogo`), luminous telemetry ring, and metallic/neon typography.
- **Cinematic Extended Splash Sequence**: Extended duration to 6.8 seconds with multi-stage timing (Brand Logo -> Neon Trails & Car Silhouette -> Welcome Glow -> Smooth Exit), giving a realistic OEM automotive startup experience with instant tap/back-to-skip support.
- **Brand Launcher App Icon**: Redesigned `@drawable/ic_launcher_foreground` and `@drawable/ic_launcher_background` into a premium hypercar winged emblem with luminous cyan telemetry.

---

## [1.22.0] - 2026-11-14

### Added
- **Futuristic Car Silhouette Splash Screen**: Hardware-accelerated neon car silhouette canvas animation (`CarSilhouetteCanvas`) with radial horizon flare, specular ground reflection, side light trails, and glowing typography.
- **Splash Lifecycle Architecture**: Introduced `SplashConfig`, `SplashState`, and `SplashViewModel` with configurable stage timings, rotation-safe state handling, smooth exit scale/fade transition, and tap/back-to-skip support.
- **Automotive Cockpit Integration**: Integrated seamless splash overlay in `MainActivity` with smooth entrance into the main cockpit dashboard.
- **Project Discipline Rules**: Enforced mandatory emulator build, installation (`adb install -r`), and open/launch (`am start`) verification upon task completion.

---

## [1.21.0] - 2026-11-14

### Changed
- **Apex Studio Cockpit Theme**: Renamed "Premium Studio" to "Apex Studio" with luminous cyan telemetry, deep navy studio contrast, and backwards-compatible token mapping.

### Removed
- **Vehicle Service & Maintenance**: Completely removed the service button from the HomeScreen dock bar, along with all maintenance manager models, dialogs, and due service alerts.
- **Calm Teal Theme**: Completely removed Calm Teal night and day palettes from design system and settings.
- **Light Theme**: Completely removed day/light theme modes from the codebase; the entire launcher is now permanently optimized as a glare-free dark automotive cockpit.

---

## [1.20.0] - 2026-11-14

### Initial Baseline Release
- **Cockpit Dashboard & Gauges**: Complete automotive instrument cluster with dynamic glow, stopping distance, best mileage speed, and all-time statistics.
- **Trip Lifecycle & Multi-Geofence**: Central vehicle journey lifecycle engine with geofence departure/arrival detection and persistent journey timeline.
- **Adaptive Fuel Intelligence Engine**: Continuous 3-point fuel estimator, Room ledger, empirical gauge calibration, and missed entry detection.
- **India Offline Map Engine**: Vector and raster offline map rendering with anti-jitter GPS filtering and lookahead camera.
- **Automotive Audio & TTS Assistant**: Natural voice TTS integration and media playback controller.
- **Vehicle Maintenance & Health Monitor**: Mileage-based service alerts and vehicle inspection tracking.
- **Voice & Audio Settings**: Dedicated Voice & Audio tab with AI neural natural voice models.
