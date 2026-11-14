# Project: Car Infotainment Launcher Stabilization (`com.antigravity.drivingreport`)

## Architecture
- **Application Core**: Native Android (Kotlin, minSdk 26, targetSdk 30, compileSdk 34, JVM target 17).
- **Presentation Layer**: Jetpack Compose (Material3 + Custom Automotive Cockpit Design System).
- **Persistence & Storage Layer**:
  - Room Database v8 (`AppDatabase`, SQLite WAL, 6 DAOs: `TripDao`, `TripPointDao`, `FuelDao`, `FuelLedgerDao`, `VehicleStateDao`, `TripGeofenceDao`).
  - SharedPreferences with 64-bit IEEE 754 bit-exact storage (`OdometerManager`, `AppSettingsManager`).
  - Multi-tier JSON snapshot storage (`PersistentDataManager`, `FullBackupManager`).
- **Telemetry & Location Layer**:
  - Foreground Service (`DrivingTrackingService`) with LocationManager & FusedLocationProviderClient.
  - State machines & filtering: `TripDetectionEngine`, `GpsLocationFilter`, `GeofenceTripLifecycleEngine`.
- **Fuel Intelligence Layer**:
  - `FuelRepository` orchestrating `FuelAIEngine`, `AdaptiveMileageEngine`, `FuelStateEstimator`, `FuelGaugeCalibrationEngine`, `MissedEntryIntelligence`.
- **Mapping & Geodesy**:
  - OSMDroid 6.1.18, Google Hybrid Satellite & ArcGIS tile sources, SQLite permanent tile cache (`cache.db`).
  - `IndiaOfflineMapManager` with multi-region offline download workers.

---

## Feature Inventory
Every feature and bug identified during the Phase 0 Survey across the 7 subsystems:

| # | Feature / Bug Item | Description | Milestone | Source |
|---|-------------------|-------------|:---------:|--------|
| 1 | GPS Filtering at Ingestion Layer | Integrate `GpsLocationFilter` in `DrivingTrackingService` to reject stationary jitter (<2.5m) and multipath spikes (>240 km/h) before passing to engine and DB. | M1 | survey_exp1 |
| 2 | Service Listener Deduplication | Prevent redundant location callback registrations and eliminate duplicate initial cached fixes on multiple `onStartCommand` calls. | M1 | survey_exp1, survey_exp3 |
| 3 | Tunnel & Signal Loss Geodesic Bridging | Calculate geodesic bridge distance across signal outages (>15s) in `TripDetectionEngine` instead of dropping tunnel distance during warmup. | M1 | survey_exp1 |
| 4 | Speedometer Dropout Graceful Decay | Replace abrupt 0 km/h snap on 5-second fix gap with a smooth deceleration decay curve. | M1 | survey_exp1 |
| 5 | Trip Idle Watchdog Geofence Sync | Prevent premature trip termination at 5-minute stops outside geofences by conditioning `isGeofenceControlled` on app settings and geofence state. | M1 | survey_exp1 |
| 6 | Unclamped Time Delta Protection | Clamp `dt = now - lastLocationTime` to prevent clock sync jumps or long gaps from corrupting `drivingDurationMs` and average speed. | M1 | survey_exp3 |
| 7 | Foreign Key Constraint Sequencing | Sequence `onTripStarted` (`TripEntity` insert) to complete before `attachDeparturePoints` (`TripPointEntity` insert) to prevent `SQLiteConstraintException`. | M1 | survey_exp1 |
| 8 | Multi-day Trip Duration Alignment | Align duration calculation between `CurrentTripCard` and `TripHistoryScreen` to consistently represent running vs engine-off elapsed time. | M1 | survey_exp1 |
| 9 | Trail Points Memory Allocation Optimization | Replace 1 Hz full `ArrayList(activeTrailPoints)` allocation in `TripRepository` with memory-efficient incremental or unmodifiable updates. | M1 | survey_exp1 |
| 10 | Mid-Trip Reboot First-Fix Distance Accumulation | Restore last known location anchor in `TripDetectionEngine.restoreActiveTripState` to accumulate movement between reboot and first fix. | M1 | survey_exp2 |
| 11 | Active & Completed Trip Double-Counting Fix | Exclude active trips from `TripDao.getAllHistoricalTrips()` and reset `_currentTrip` distance on completion to prevent duplicate lifetime km summation. | M2 | survey_exp2 |
| 12 | Odometer Baseline Calibration Reconciliation | Harmonize baseline calibration definitions between `FuelRepository` and `OdometerManager`; fix legacy restore subtracting lifetime km again. | M2 | survey_exp2 |
| 13 | DTE Range Oscillation Fix | Bind DTE remaining range in `FuelRepository` strictly to learned empirical / baseline `rangeMileage` instead of 1-second `liveInstantMileage`. | M2 | survey_exp1, survey_exp2 |
| 14 | 10-Segment LED Fuel Gauge Monotonicity & Updates | Update `currentGaugeBar` dynamically via `gaugeCalibration.getBarForLiters()` in `FuelStateEstimator` / `buildFuelState()` so the cluster gauge decrements during driving. | M2 | survey_exp2 |
| 15 | Fuel Slosh & Slope Filter Activation | Connect 60-second rolling median `filterSloshAndSlope()` in production `syncMidAndOdometer()` to damp road pitch and float slosh. | M2 | survey_exp2 |
| 16 | Conservative Low-Fuel Reserve Range Multiplier | Apply the specified 0.85 conservative safety multiplier in `createSnapshot()` when nominal fuel is below 7.0 L or 15% tank capacity. | M2 | survey_exp2 |
| 17 | Active Trip Fuel Restoration on Reboot | Restore `cumulativeTripConsumedLiters` from Room DB `activeTrip.estimatedFuelLiters` in `DrivingTrackingService.onCreate()` / `restoreActiveTripState()`. | M2 | survey_exp1 |
| 18 | Decouple Gauge Calibration from Estimate Drift | Prevent software estimation drift from corrupting physical gauge calibration curve in `learnCalibrationPoint`. | M2 | survey_exp1 |
| 19 | Atomic Backup Restore with Snapshot Rollback | Wrap table clear and insertion operations across 6 DAOs in `FullBackupManager` in `db.withTransaction` with automated fallback rollback on failure. | M3 | survey_exp3 |
| 20 | Schema Version Validation in Backup/Restore | Validate `manifest.schemaVersion` against `DATABASE_SCHEMA_VERSION` (v8) before restoring archives to prevent constraint crashes or schema mismatch. | M3 | survey_exp3 |
| 21 | Post-Restore Live Repository State Reload | Reload `TripRepository`, `OdometerManager`, `AppSettingsManager`, and `VehicleStateManager` after archive restore so live UI reflects restored state. | M3 | survey_exp3 |
| 22 | Database Coroutine Exception Handling | Add `CoroutineExceptionHandler` and safe error recovery around Room database operations in `TripRepository` and `FuelRepository`. | M3 | survey_exp3 |
| 23 | Flash Wear & Disk I/O Throttling | Debounce and optimize disk write frequency for `PersistentDataManager.backupAsync` (currently every 5s) and `VehicleStateManager` (currently 1s `apply()`). | M3 | survey_exp3 |
| 24 | Offline Map Tile Image Validation | Verify HTTP status code, minimum payload size, and image magic bytes (JPEG/PNG) in `IndiaOfflineMapManager.fetchTileFast()` before writing to SQLite cache. | M3 | survey_exp2 |
| 25 | SQLite Tile Writer Resource Management | Ensure `SqlTileWriter` instances in `downloadRegionTiles` are safely closed and detached to prevent connection leaks and lock contention with `MapView`. | M3 | survey_exp2 |
| 26 | Activity Context Memory Leak Fix | Pass `applicationContext` to `IndiaOfflineMapManager.registerNetworkCallback` to prevent leaking `MainActivity` on configuration change. | M3 | survey_exp2 |
| 27 | LiveMapView Pulsing Aura Background Pause | Suspend 30 FPS `postInvalidateDelayed` loop in `PulsingAuraOverlay` when the MapView is paused or in the background. | M3 | survey_exp3 |
| 28 | Unused Native Library Removal | Remove unused `MapLibre.getInstance(this)` initialization in `DrivingReportApp` to save memory and startup time. | M3 | survey_exp3 |
| 29 | E2E Testing Suite (Tiers 1-4) | Comprehensive test suite covering feature coverage, boundary conditions, cross-module interactions, and real-world automotive driving workloads. | M4 / Test Track | survey_exp3 |
| 30 | Adversarial Coverage Hardening (Tier 5) | White-box stress testing and edge-case hardening targeting residual vulnerabilities. | M4 (Phase 2) | survey_exp3 |

---

## Milestones

| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Telemetry, GPS Ingestion & Trip Lifecycle | Features 1–10: GPS filtering, service deduplication, tunnel bridging, time delta clamping, trip idle & geofence handling, foreign key sequencing, multi-day duration, memory optimization. | none | DONE |
| M2 | Odometer, Fuel Intelligence & Gauge/Range Hardening | Features 11–18: Odometer double-counting fix, baseline calibration reconciliation, DTE range oscillation fix, 10-bar LED fuel gauge monotonicity, slosh filter activation, low-fuel reserve buffer, reboot fuel recovery. | M1 Interface Contracts defined | DONE |
| M3 | Storage, Persistence, Backup/Restore & Map Engine | Features 19–28: Atomic backup restore, schema version gating, post-restore singleton reload, DB write exception recovery, disk write throttling, tile image validation, SqlTileWriter leak fix, context leak fix, pulsing aura pause, unused MapLibre removal. | M1, M2 Interface Contracts defined | DONE |
| M4 | E2E Verification & Adversarial Hardening | Features 29–30: Pass 100% of E2E test suite (Tiers 1–4) published by E2E Testing Track; Tier 5 adversarial coverage hardening. | M1, M2, M3, TEST_READY.md | DONE |

---

## Interface Contracts

### Telemetry / GPS (`DrivingTrackingService`) ↔ Trip Lifecycle (`TripDetectionEngine`)
- **Location Ingestion**: `DrivingTrackingService` must pass raw location fixes through `GpsLocationFilter.filter(rawLoc)` before invoking `TripDetectionEngine.processLocation(filteredLoc)`.
- **Tunnel Geodesic Bridging**: When `now - lastLocationTime > 15000L` and accuracy of next fix $\le 25\text{m}$, `TripDetectionEngine` computes straight-line distance between `lastAcceptedLocation` and `location` using `Location.distanceBetween` and accumulates it into `totalDistanceMeters`.
- **Trip Insertion Sequencing**: `onTripStarted(startTime)` must be a suspend function or return a Deferred that completes the Room `tripDao.insertTrip(entity)` before `attachDeparturePoints` can invoke `tripDao.insertPoints(pointsList)`.

### Trip Repository (`TripRepository`) ↔ Presentation / Odometer (`OdometerManager`, `DashboardScreen`, `MainActivity`)
- **Historical Trips Query Contract**: `tripDao.getAllHistoricalTrips()` must query `WHERE status = 'COMPLETED' AND isQualifying = 1`. Active trips (`status = 'ACTIVE_TRIP'`) are exclusively accessed via `currentTrip: StateFlow<TripSummary>`.
- **Trip Completion Cleanup**: Upon `onTripCompleted(summary)`, `TripRepository` emits the final trip event and resets `_currentTrip.value = TripSummary.empty()`, ensuring completed trips are never double-counted in lifetime distance rollups.
- **Odometer Baseline Contract**: `OdometerManager.setBaselineOdometer(context, readingKm, currentTotalLifetimeKm)` is the single source of truth for baseline calibration. `FuelRepository.calibrateOdometer` delegates directly to this method and reads `OdometerManager.getEffectiveOdometer()`.

### Fuel Intelligence (`FuelRepository`) ↔ UI Cards (`FuelTankCard`, `DashboardScreen`)
- **Range Mileage Contract**: `FuelStateSnapshot.estimatedRangeKm` is strictly computed from `rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage`. If remaining fuel $< 7.0\text{ L}$ or fraction $< 0.15$, multiply by $0.85$. `liveInstantMileage` is exposed exclusively for instant fuel economy displays (`instantFuelEconomyL100km`).
- **Gauge Cluster Contract**: `FuelStateSnapshot.currentMidBar` must be continuously updated in `FuelStateEstimator.applyConsumption()` via `gaugeCalibration.getBarForLiters(nominalFuelLiters)`.

### Storage & Backup (`FullBackupManager`) ↔ Application Singletons
- **Atomic Transaction Contract**: `restoreFromBackupZip(context, uri)` executes all database modifications inside `db.withTransaction { ... }`. Before clearing tables, a temporary in-memory or staging safety snapshot is created. If any exception occurs during restore, transaction aborts and state rolls back.
- **Post-Restore Reload Hook**: Upon successful transaction commit, `FullBackupManager` calls:
  - `TripRepository.getInstance(context).reloadFromDatabase()`
  - `OdometerManager.reloadFromPreferences(context)`
  - `AppSettingsManager.reload(context)`
  - `VehicleStateManager.getInstance(context).reloadFromDatabase()`
  - `FuelRepository.getInstance(context).reloadFromDatabase()`

---

## Code Layout
- `app/src/main/java/com/antigravity/drivingreport/`
  - `core/location/` — `DrivingTrackingService.kt`, `BootReceiver.kt`
  - `core/trip/` — `TripDetectionEngine.kt`, `TripRepository.kt`, `VehicleStateManager.kt`, `GeofenceTripLifecycleEngine.kt`
  - `core/odometer/` — `OdometerManager.kt`
  - `core/fuel/` — `FuelRepository.kt`, `intelligence/` (`FuelAIEngine.kt`, `FuelStateEstimator.kt`, `AdaptiveMileageEngine.kt`, `FuelGaugeCalibrationEngine.kt`, `MissedEntryIntelligence.kt`, `FuelMath.kt`)
  - `core/map/` — `OfflineMapManager.kt`, `IndiaOfflineMapManager.kt`, `GpsLocationFilter.kt`
  - `core/database/` — `AppDatabase.kt`, `Entities.kt`, `PersistentDataManager.kt`
  - `core/persistence/` — `FullBackupManager.kt`, `FullFactoryResetManager.kt`
  - `feature/dashboard/` — `DashboardScreen.kt`, `OdometerCard.kt`, `FuelTankCard.kt`, `SpeedometerCluster.kt`, `CurrentTripCard.kt`
  - `feature/maps/` — `LiveMapView.kt`, `IndiaOfflineMapDialog.kt`
  - `feature/trips/` — `TripHistoryScreen.kt`
- `app/src/test/java/com/antigravity/drivingreport/` — JVM Unit Test Suites
