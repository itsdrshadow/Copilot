# Comprehensive Survey & Architectural Analysis Report: Subsystem 7, Cross-Cutting Concerns, and Test Infrastructure

**Agent:** Survey Explorer 3 (`survey_exp3`)  
**Target Project:** DrivingReport Automotive Infotainment (`com.antigravity.drivingreport`)  
**Repository Working Directory:** `d:\Car Launcher\`  
**Date:** November 2026  
**Scope:** Subsystem 7 (Backup & Restore), Cross-cutting Concerns (Database Persistence, Schema Migrations, Write Failure Recovery, Concurrency, App Process Termination, Background Services, Reboot Handling, Duplicate Events, Memory Leaks & Resource Disposal), and Existing Test Suites & Verification Scripts.

---

## 1. OBSERVATIONS

### 1.1 Project Structure and Build Infrastructure
- **Project Type:** Native Android application written in Kotlin utilizing Jetpack Compose and Room Database.
- **Root Build File (`d:\Car Launcher\build.gradle.kts`):**
  - Android Gradle Plugin: `8.5.2` (line 2)
  - Kotlin version: `1.9.22` (lines 3–4) with Kapt enabled.
- **App Build Configuration (`d:\Car Launcher\app\build.gradle.kts`):**
  - `namespace = "com.antigravity.drivingreport"` (line 8)
  - `compileSdk = 34` (line 9), `minSdk = 26` (line 13), `targetSdk = 30` (line 14), `versionCode = 33` (line 15), `versionName = "1.18.0"` (line 16).
  - Kotlin JVM Target: `JavaVersion.VERSION_17` (lines 45–46, 50).
  - Room Compiler schema argument: `arg("room.schemaLocation", "$projectDir/schemas")` (line 25).
  - Packaging exclusions: `excludes += "/META-INF/{AL2.0,LGPL2.1}"` (line 67).
  - Dependencies:
    - Jetpack Compose BOM: `2024.02.01` (lines 78–79)
    - Room Database: `2.6.1` (`room-runtime`, `room-ktx`, `room-compiler`) (lines 96–99)
    - Map Engines: OSMDroid `6.1.18` (line 102), MapLibre GL `11.0.0` (line 103)
    - Play Services Location: `21.1.0` (line 106)
    - Coroutines: `1.8.0` (line 109)
    - Gson: `2.10.1` (line 110)
    - Test Dependencies: `junit:junit:4.13.2` (line 112), `kotlinx-coroutines-test:1.8.0` (line 113).

### 1.2 Subsystem 7: Backup and Restore Functionality
Two distinct backup engines coexist in the codebase:

#### 1.2.1 `FullBackupManager.kt` (`app/src/main/java/com/antigravity/drivingreport/core/persistence/FullBackupManager.kt`)
- **Version Constants (lines 36–38):**
  ```kotlin
  const val CURRENT_APP_VERSION = "1.18.0"
  const val CURRENT_VERSION_CODE = 33
  const val DATABASE_SCHEMA_VERSION = 8
  ```
- **Archive Format & Scope (lines 195–233):**
  - Produces standard `.zip` archives containing:
    1. `backup_manifest.json` (`BackupManifest`)
    2. `trips.json` (`List<TripEntity>`)
    3. `trip_points.json` (`List<TripPointEntity>`)
    4. `fuel_events.json` (`List<FuelEventEntity>`)
    5. `fuel_ledger_events.json` (`List<FuelLedgerEntity>`)
    6. `vehicle_state_transitions.json` (`List<VehicleStateTransitionEntity>`)
    7. `trip_geofences.json` (`List<TripGeofenceEntity>`)
    8. `preferences.json` (`Map<String, Map<String, TypedPrefEntry>>`) covering 8 SharedPreferences files
    9. `maps/india_offline_manifest.json` (if present)
- **Integrity Check (lines 236–243, 298–310):**
  - Verification only checks that `verifyZip.getEntry("backup_manifest.json") != null`.
  - No checksum (SHA-256 or CRC32) is computed or verified for individual JSON payload files or the overall archive.
- **Restore Execution (lines 336–392):**
  - Sequential database table deletions and insertions are performed across 6 DAOs **without a database transaction** (`db.runInTransaction` or `db.withTransaction` is absent):
    ```kotlin
    db.tripDao().clearAllTrips()
    db.tripDao().insertTrips(tripsList)
    db.tripDao().clearAllTripPoints()
    db.tripDao().insertPoints(pointsList)
    db.fuelDao().clearAllFuelEvents()
    db.fuelDao().insertFuelEvents(fuelList)
    db.fuelLedgerDao().clearAll()
    db.fuelLedgerDao().insertEvents(ledgerList)
    db.vehicleStateDao().clearAll()
    db.vehicleStateDao().insertTransitions(transList)
    db.tripGeofenceDao().clearAllGeofences()
    db.tripGeofenceDao().insertGeofences(geofencesList)
    ```
- **Post-Restore Repository State Sync (lines 468–474):**
  - After restore, only `FuelRepository.getInstance(context).syncMidAndOdometer(...)` is called:
    ```kotlin
    FuelRepository.getInstance(context).syncMidAndOdometer(
        newOdometerKm = FuelRepository.getInstance(context).fuelState.value.odometerKm,
        newMidBar = FuelRepository.getInstance(context).fuelState.value.currentMidBar,
        recordedLifetimeKm = FuelRepository.getInstance(context).fuelState.value.odometerKm
    )
    ```
  - It passes existing in-memory values instead of newly restored values.
  - `TripRepository`, `OdometerManager`, `AppSettingsManager`, `VehicleStateManager`, and `TripGeofenceManager` are never notified or reloaded.

#### 1.2.2 `PersistentDataManager.kt` (`app/src/main/java/com/antigravity/drivingreport/core/database/PersistentDataManager.kt`)
- Automatic silent persistence saving `permanent_data_store.json` to external storage and app files (lines 26–58).
- Scope omission: Only stores `trips` (summary only), `fuel_events`, `fuel_ledger_events`, `prefs`, `odometer_prefs`, and `station_prices`. It **completely omits `trip_points`**, `vehicle_state_transitions`, `trip_geofences`, and general `settings` (lines 301–414).
- In `restoreIfNeeded(context)` (lines 152–165, 201–214, 257–273): uses raw SQLite queries (`sqldb.execSQL("INSERT OR REPLACE INTO trips ...")`) with hardcoded column names rather than Room DAOs.
- Concurrency: `backupAsync` launches an unmanaged coroutine (`scope = CoroutineScope(Dispatchers.IO)`) writing to `permanent_data_store.json.tmp`. Multiple rapid calls can race writing to the same temporary file (lines 294–445).

### 1.3 Cross-Cutting Concern 1: Database Persistence, Migrations, Concurrency, and Transactions
- **Room Database Definition (`AppDatabase.kt`):**
  - Version: `8` (line 240).
  - `exportSchema = false` (line 241) — prevents Room compiler from verifying schema history against generated JSON schemas.
  - Journal Mode: `RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING` (line 420).
  - Migrations registered: `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4`, `MIGRATION_4_5`, `MIGRATION_5_6`, `MIGRATION_6_7`, `MIGRATION_7_8` (lines 255–411).
  - `MIGRATION_1_2` has an empty implementation (lines 255–259).
  - Migrations wrap SQL statements in blanket `try { ... } catch (_: Exception) {}`, silently swallowing structural migration errors.
  - `fallbackToDestructiveMigrationOnDowngrade()` is enabled (line 422).
- **Concurrency & Coroutine Scopes in Repositories:**
  - `TripRepository.kt`:
    - Buffers trip points in `pendingPoints = ConcurrentLinkedQueue<TripPointEntity>()` (line 273).
    - Every 4s or 20 points, launches `scope.launch { tripDao.insertPoints(toFlush); tripDao.updateTrip(...) }` without `try/catch` or `CoroutineExceptionHandler` (lines 338–362).
    - Startup loop (lines 175–215): iterates through all trips in DB executing individual updates (`tripDao.updateTrip(updated)`) and queries (`tripDao.getPointsForTrip(t.id)`) on the IO dispatcher without a transaction.
  - `FuelRepository.kt`:
    - Protected by `stateMutex = Mutex()` for in-memory calculations (line 48).
    - Dual writes to `fuel_ledger_events` and `fuel_events` are executed sequentially without a database transaction (lines 434–451, 587–606).
    - Debounce disk write storm (lines 723–736): `persistValuesDebounced()` spawns a new coroutine on every live GPS second, calling `PersistentDataManager.backupAsync(appContext)` every 5 seconds while driving. Each backup executes full table scans on `trips`, `fuel_events`, and `fuel_ledger_events`, serializing to JSON and writing to 3 filesystem paths.
  - `VehicleStateManager.kt`:
    - `onPeriodicTick()` (lines 264–271) runs every 1 second via `DrivingTrackingService` watchdog (line 278).
    - It calls `persistState(isSynchronous = false)` every second, triggering `editor.apply()` with 9 SharedPreferences keys on every single clock tick (lines 285–305).

### 1.4 Cross-Cutting Concern 2: App Process Termination, Services, Reboot, & Duplicate Events
- **`BootReceiver.kt` (`core/location/BootReceiver.kt`):**
  - Priority `999` receiver for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, `QUICKBOOT_POWERON` (lines 12–18).
  - On devices where both locked and unlocked boot intents fire, `onReceive` runs twice without deduplication.
- **`DrivingTrackingService.kt` (`core/location/DrivingTrackingService.kt`):**
  - `onStartCommand()` (lines 97–106) calls `startLocationUpdates()` unconditionally on every start command.
  - `startLocationUpdates()` (lines 418–475) registers new callbacks with `fusedLocationClient.requestLocationUpdates(...)` and `locationManager.requestLocationUpdates(...)` **without removing previous callbacks**. Repeated service starts create duplicate listeners.
  - In `startLocationUpdates()` (lines 421–444), both `locationManager.getLastKnownLocation` and `fusedLocationClient.lastLocation` pass cached points to `engine.processLocation()` within milliseconds without deduplicating identical fixes.
  - `onDestroy()` cancels `serviceScope`, unregisters `powerReceiver`, removes location listeners, and releases `wakeLock` (lines 503–520).
- **`TripDetectionEngine.kt` (`core/trip/TripDetectionEngine.kt`):**
  - In `restoreActiveTripState` (lines 353–368), `lastAcceptedLocation` is not restored.
  - In `handleActiveTripMovement` (lines 437–446):
    `val dt = (now - lastLocationTime).coerceAtLeast(0)`
    `dt` has no upper clamp. If GPS acquisition is delayed after reboot or tunnel exit, `dt` can be minutes or hours, distorting `drivingDurationMs` and collapsing calculated average speed.
  - Time shifts: If device clock adjusts forward (e.g., NTP/GPS sync), `dt` experiences massive forward jumps.
- **Crash Loop Guardian (`core/recovery/CrashGuardian.kt`):**
  - Tracks crash count within a 60-second window (lines 44–46, 83–87).
  - If $\ge 3$ consecutive crashes occur, launches `RecoveryActivity` in an isolated `:recovery` process (lines 107–124).
  - Resets crash counter after 25 seconds of clean execution (`notifySuccessfulBoot`, lines 142–152).
  - Immediate process termination using `Process.killProcess(Process.myPid())` and `System.exit(10)` (lines 134–135).

### 1.5 Cross-Cutting Concern 3: Memory Leak Hazards and Resource Disposal
- **`LiveMapView.kt` (`feature/maps/LiveMapView.kt`):**
  - `PulsingAuraOverlay` (lines 78–115) executes `mapView.postInvalidateDelayed(33L)` on line 113 inside `draw()`. It lacks any lifecycle check (pause/resume or screen on/off). While the view is attached, it schedules continuous 30 FPS invalidations.
  - SensorListener (hardware rotation vector compass, lines 656–720) is properly registered and unregistered within Compose `DisposableEffect`.
  - MapView lifecycle synchronization (lines 722–741) properly forwards `ON_RESUME`, `ON_PAUSE`, and `ON_DESTROY` to `mapViewInstance`.
- **`DrivingReportApp.kt` (`DrivingReportApp.kt:26`):**
  - Initializes `MapLibre.getInstance(this)`, loading native C++ OpenGL libraries, even though MapLibre is never used in the UI (only OSMDroid is rendered).
- **`MainActivity.kt` (`MainActivity.kt:78–85, 187–199`):**
  - `onBackupFileSelected` closure is assigned directly onto `MainActivity` from `LaunchedEffect(Unit)`. If configuration changes recreate the Activity without Compose recomposition, references can get mismatched.

### 1.6 Existing Test Suites & Build Configuration
- **Catalog of Existing Unit Tests (19 test files in `app/src/test/java/com/antigravity/drivingreport/`):**

| Test File | Test Count | Primary Focus |
|---|:---:|---|
| `AdaptiveFuelIntelligenceTest.kt` | 24 | 24 core mathematical edge cases of Adaptive Fuel Engine |
| `AdaptiveMetricsEngineTest.kt` | 2 | Duration bounds and cycle decay |
| `AnalyticsCalculationsTest.kt` | 3 | Division-by-zero safety, mileage & speed ratios |
| `BackupRestoreTypeSafetyTest.kt` | 3 | Backup version constants, typed pref encoding/decoding |
| `BestMileageSpeedEngineTest.kt` | 6 | Speed-efficiency curve binning and outlier filtering |
| `CockpitThemeSystemTest.kt` | 5 | Theme enum resolution and color palette tokens |
| `FuelCalculationConsistencyTest.kt` | 2 | End-to-end fuel math consistency |
| `FuelMileageDisplayResolutionTest.kt` | 5 | Formatting rules and decimal resolution |
| `FullBackupManagerTest.kt` | 2 | Manifest serialization & in-memory ZIP round-trip |
| `GeofenceTripLifecycleTest.kt` | 6 | Timer formatting, flash visual states, geofence radius |
| `GpsConcurrencyAndArbitrationTest.kt` | 2 | Concurrent queue safety & 3.5s arbitration window |
| `MissedEntryExtremeBoundaryTest.kt` | 3 | Range spread limits & missed refill detection bounds |
| `NumberFormatUtilsTest.kt` | 7 | Number formatting, decimal precision, commas |
| `OdometerManagerTest.kt` | 2 | Delta calculation & negative delta prevention |
| `OdometerPersistenceTest.kt` | 3 | 64-bit raw bits double round-trip & precision |
| `SatelliteMapAndMultiPassTrailTest.kt` | 5 | Satellite tile URLs, turnaround detection, trail slicing |
| `StoppingDistanceEngineTest.kt` | 6 | Physics braking distance on dry/wet asphalt |
| `VehicleJourneyAndFuelEngineValidationTest.kt` | 6 | Gauge monotonicity, slosh rejection, multi-day timer |
| `VehicleStateDomainTest.kt` | 2 | Vehicle state classification & movement thresholds |
| **TOTAL** | **94** | **100% JVM Unit Tests (JUnit 4 + Coroutines-Test)** |

- **Instrumentation / Integration Tests:**
  - `app/src/androidTest`: **0 files** (directory does not exist).
  - Compose UI Widget Tests: **0 tests**.
  - Database Migration Tests (`MigrationTestHelper`): **0 tests**.
  - Service Integration Tests: **0 tests**.
- **Verification Commands:**
  - Execute all unit tests: `.\gradlew.bat testDebugUnitTest`
  - Run specific test file: `.\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.<TestClassName>"`
  - Run with stack trace: `.\gradlew.bat testDebugUnitTest --stacktrace`

---

## 2. LOGIC CHAIN

```
[Observation 1.2.1: FullBackupManager.restoreFromBackupZip clears tables and inserts entities sequentially without db.runInTransaction]
  │
  ├─► Step 1: If an exception, corrupt JSON parse error, or OutOfMemoryError occurs while processing any entity list (e.g., fuel_events.json or trip_points.json)
  │            earlier tables (trips, trip_points) have already been cleared via clearAllTrips(), while later tables are skipped.
  │
  └─► Conclusion 1: Restore is non-atomic. A failure leaves the Room database partially deleted with no automated rollback to the safety snapshot.

[Observation 1.2.1 & 1.2.2: BackupManifest records schemaVersion=8, but restoreFromBackupZip never validates manifest.schemaVersion]
  │
  ├─► Step 2: Restoring an archive from an incompatible or newer schema version (e.g. v9+) or older version (e.g. v5 lacking fuel_ledger_events)
  │            causes Gson deserialization failures, missing foreign keys, or database constraint violations.
  │
  └─► Conclusion 2: Backup/restore lacks version migration logic and schema version gating.

[Observation 1.2.1: FullBackupManager.restoreFromBackupZip only calls FuelRepository.syncMidAndOdometer with stale in-memory values]
  │
  ├─► Step 3: TripRepository, OdometerManager, AppSettingsManager, VehicleStateManager, and TripGeofenceManager are never reloaded.
  │
  └─► Conclusion 3: The live UI and in-memory singletons continue displaying stale pre-restore trip, odometer, and telemetry state until app process restart.

[Observation 1.3: TripRepository launches database writes in unmanaged CoroutineScope(Dispatchers.IO) without CoroutineExceptionHandler]
  │
  ├─► Step 4: If SQLite throws SQLiteFullException (disk full) or SQLiteDatabaseLockedException,
  │            the unhandled exception crashes the coroutine, bubbles to Thread.defaultUncaughtExceptionHandler,
  │            which triggers CrashGuardian to terminate the process.
  │
  └─► Conclusion 4: Database write failures in background telemetry processing cause unhandled app crashes instead of resilient retries.

[Observation 1.3: FuelRepository.persistValuesDebounced calls PersistentDataManager.backupAsync every 5 seconds while driving]
  │
  ├─► Step 5: Every 5 seconds, full database queries (trips, fuel events, ledger) run, followed by JSON serialization and 3 disk writes.
  │            Concurrently, VehicleStateManager persists 9 preference keys every 1 second via editor.apply().
  │
  └─► Conclusion 5: The application creates severe flash memory write fatigue, disk I/O contention, and garbage collection churn while in motion.

[Observation 1.4: DrivingTrackingService.onStartCommand calls startLocationUpdates without removing existing listeners]
  │
  ├─► Step 6: When BootReceiver triggers on both LOCKED_BOOT_COMPLETED and BOOT_COMPLETED, or when MainActivity restarts the service,
  │            duplicate LocationCallback instances are registered with FusedLocationProviderClient and LocationManager.
  │
  └─► Conclusion 6: Redundant callbacks result in duplicate processing and resource leaks.

[Observation 1.4: TripDetectionEngine.handleActiveTripMovement computes dt = now - lastLocationTime without an upper bound]
  │
  ├─► Step 7: Following app restart, device reboot, or GPS signal loss in a tunnel,
  │            the first GPS fix incorporates the entire gap into drivingDurationMs, artificially depressing calculated average speed.
  │
  └─► Conclusion 7: Trip lifecycle calculations are vulnerable to GPS dropouts, sleep mode, and device clock synchronization jumps.

[Observation 1.5: LiveMapView.PulsingAuraOverlay schedules postInvalidateDelayed(33L) inside draw without checking lifecycle state]
  │
  ├─► Step 8: Even when the activity is paused or the user navigates to Trips/Fuel tabs, the overlay continuously triggers redraw invalidations at 30 FPS.
  │
  └─► Conclusion 8: Background rendering loop causes CPU consumption and battery drain.

[Observation 1.6: 94 unit tests exist covering math and models, but 0 tests cover Room migrations, restore execution, background services, or UI]
  │
  ├─► Step 9: Core resilience areas (backup restore atomicity, write recovery, database migration, process restart) have zero automated test validation.
  │
  └─► Conclusion 9: Significant test coverage gaps exist in persistence, lifecycle, and recovery infrastructure.
```

---

## 3. CAVEATS

1. **Build Execution Restriction:** In accordance with instructions, full application builds (`assembleDebug`, `assembleRelease`) and APK installations were not executed. Test suite execution was inspected through Gradle configurations and test source analysis.
2. **Hardware Environment:** Observations regarding GPS jitter, thermal throttling, and boot times are based on the target specifications (Android 11 automotive head unit, API 30, 1280×800) documented in `CRITIQUE_AND_SUGGESTIONS.md` and `README.md`.
3. **Multi-process Architecture:** `RecoveryActivity` runs in an isolated `:recovery` process. This survey analyzed process boundaries via `AndroidManifest.xml` and `CrashGuardian.kt` source code.

---

## 4. CONCLUSION

### 4.1 Identified Vulnerabilities and Bugs

| ID | Subsystem / Area | Severity | Root Cause & Impact |
|---|---|:---:|---|
| **BUG-S7-01** | Subsystem 7 (Restore Atomicity) | **Critical** | `FullBackupManager.restoreFromBackupZip` wipes and inserts database tables sequentially across 6 DAOs without a Room transaction (`db.withTransaction`). Any parsing or I/O failure leaves the database permanently half-wiped with no automated rollback. |
| **BUG-S7-02** | Subsystem 7 (Schema Versioning) | **High** | `FullBackupManager.restoreFromBackupZip` does not validate `manifest.schemaVersion` against `DATABASE_SCHEMA_VERSION` (version 8) before restoring, leading to potential crash loops or constraint violations when restoring backups from different schema versions. |
| **BUG-S7-03** | Subsystem 7 (Post-Restore Sync) | **High** | Restoring a backup fails to reload `TripRepository`, `OdometerManager`, `AppSettingsManager`, `VehicleStateManager`, and `TripGeofenceManager`. In-memory states and Compose screens remain populated with stale pre-restore data. |
| **BUG-S7-04** | Subsystem 7 (Integrity Verification) | **Medium** | Backup archives lack cryptographic hash (SHA-256) or CRC verification. Truncated or corrupt JSON files pass inspection as long as `backup_manifest.json` is readable. |
| **BUG-CC-01** | Concurrency / DB Recovery | **High** | `TripRepository` and `FuelRepository` execute database writes inside unmanaged `CoroutineScope(Dispatchers.IO)` lacking `CoroutineExceptionHandler`. Unhandled SQLite exceptions crash the coroutine and trigger `CrashGuardian` process termination. |
| **BUG-CC-02** | Disk I/O & Flash Wear | **High** | `FuelRepository.persistValuesDebounced` triggers `PersistentDataManager.backupAsync` every 5 seconds during driving, scanning all tables, generating large JSON, and writing to 3 filesystem targets. Simultaneously, `VehicleStateManager` writes 9 preference keys every 1 second. |
| **BUG-CC-03** | Service Lifecycle & Duplicate Listeners | **Medium** | `DrivingTrackingService.onStartCommand` invokes `startLocationUpdates()` repeatedly without clearing previously registered Fused and LocationManager callbacks, causing listener duplication and memory leaks. |
| **BUG-CC-04** | Trip Detection / Time Jump | **Medium** | `TripDetectionEngine.handleActiveTripMovement` does not clamp `dt = now - lastLocationTime`. GPS dropouts, sleep mode, or NTP clock sync forward-jumps distort `drivingDurationMs` and corrupt average speed calculations. |
| **BUG-CC-05** | Memory & Background CPU | **Medium** | `LiveMapView.PulsingAuraOverlay` continuously calls `postInvalidateDelayed(33L)` inside `draw()` without checking pause state or lifecycle, executing continuous 30 FPS invalidations in the background. |
| **BUG-CC-06** | Unused Dependency | **Low** | `MapLibre.getInstance(this)` in `DrivingReportApp.kt` loads native C++ libraries and allocates memory for MapLibre GL, although the application exclusively uses OSMDroid for map rendering. |

### 4.2 Comprehensive Test Coverage Gaps

1. **Subsystem 7 (Backup & Restore):**
   - No test verifies `FullBackupManager.restoreFromBackupZip` on actual Room database tables.
   - No test verifies schema migration or rejection of incompatible backup schema versions.
   - No test verifies corruption detection or rollback on restore failure.
   - `PersistentDataManager`: 0 tests for `backupAsync` or `restoreIfNeeded`.
   - `FullFactoryResetManager`: 0 tests for full factory reset execution.
2. **Database Persistence & Migrations:**
   - 0 tests for Room migrations (versions 1 through 8). No `MigrationTestHelper` tests exist.
   - 0 tests for SQLite disk full or locked database exception handling.
   - 0 tests for dual-write consistency between `fuel_ledger_events` and `fuel_events`.
3. **Lifecycle, Services & Watchdogs:**
   - `DrivingTrackingService`: 0 unit/service tests.
   - `BootReceiver`: 0 tests for intent deduplication or Direct Boot behavior.
   - `CrashGuardian`: 0 tests for crash window accumulation or recovery dispatch.
4. **UI & Presentation:**
   - 0 Compose UI widget tests or automated screenshot regression tests.

---

## 5. VERIFICATION METHOD

### 5.1 Verification Commands
To independently verify the test infrastructure and existing unit test suite:

1. **Execute All Existing Unit Tests (94 tests):**
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   *Expected Result:* All 94 existing unit tests pass without failure.

2. **Verify Subsystem 7 Backup Tests:**
   ```powershell
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.FullBackupManagerTest"
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.BackupRestoreTypeSafetyTest"
   ```

3. **Verify Fuel Engine and Concurrency Tests:**
   ```powershell
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.AdaptiveFuelIntelligenceTest"
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.GpsConcurrencyAndArbitrationTest"
   ```

### 5.2 Key Source Files to Inspect

- **Backup & Restore:**
  - `app/src/main/java/com/antigravity/drivingreport/core/persistence/FullBackupManager.kt` (lines 336–392, 468–474)
  - `app/src/main/java/com/antigravity/drivingreport/core/database/PersistentDataManager.kt` (lines 294–445)
  - `app/src/main/java/com/antigravity/drivingreport/core/persistence/FullFactoryResetManager.kt` (lines 29–80)
- **Database Persistence & Migrations:**
  - `app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt` (lines 230–427)
  - `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt` (lines 273–362)
  - `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt` (lines 723–736)
- **Background Service & Lifecycle:**
  - `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt` (lines 97–106, 418–475)
  - `app/src/main/java/com/antigravity/drivingreport/core/location/BootReceiver.kt` (lines 9–35)
  - `app/src/main/java/com/antigravity/drivingreport/core/recovery/CrashGuardian.kt` (lines 73–136)
  - `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt` (lines 353–368, 437–446)
  - `app/src/main/java/com/antigravity/drivingreport/core/trip/VehicleStateManager.kt` (lines 264–305)
- **Memory & Overlay Resource Management:**
  - `app/src/main/java/com/antigravity/drivingreport/feature/maps/LiveMapView.kt` (lines 78–115, 722–741)
  - `app/src/main/java/com/antigravity/drivingreport/DrivingReportApp.kt` (lines 24–29)

### 5.3 Invalidation Conditions
This analysis will be invalidated if:
1. `FullBackupManager.restoreFromBackupZip` is rewritten to execute within a Room database transaction with automated pre-restore snapshot rollback.
2. The database schema version is upgraded beyond 8 with migration test coverage.
3. `DrivingTrackingService.startLocationUpdates()` implements callback deduplication.
4. `TripDetectionEngine` implements an upper clamp on time delta `dt`.
