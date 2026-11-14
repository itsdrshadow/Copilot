# DISPATCH — Milestone 3 Implementation Worker

## Task
Implement Milestone 3: Storage, Persistence, Backup/Restore & Map Engine Hardening (Features 19–28).
Ensure 100% genuine logic, zero shortcuts, zero facades, zero regressions. All existing 219 tests must remain 100% green, plus targeted tests for Milestone 3.

## Authoritative References
- `d:\Car Launcher\.agents\teamwork\ORIGINAL_REQUEST.md`
- `d:\Car Launcher\PROJECT.md`
- `d:\Car Launcher\.agents\teamwork\survey_exp2\handoff.md`
- `d:\Car Launcher\.agents\teamwork\survey_exp3\handoff.md`

## MANDATORY INTEGRITY WARNING
> DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

---

## Detailed Specifications by Feature

### Feature 19, 20, 21: Full Backup & Restore Hardening (`FullBackupManager.kt`)
File: `app/src/main/java/com/antigravity/drivingreport/core/persistence/FullBackupManager.kt`
- **Feature 19 (Atomic Backup Restore with Snapshot Rollback)**:
  - In `restoreFromBackupZip(context: Context, backupZipUri: Uri)`:
  - Wrap database operations in `database.withTransaction { ... }`.
  - Implement a safety snapshot rollback: if archive extraction or JSON deserialization or database insertion fails at any point, rollback any partial changes so the existing Room database tables are never left in an inconsistent/corrupt state.
- **Feature 20 (Schema Version Validation)**:
  - Before restoring table entities from the backup zip, inspect `manifest.schemaVersion`.
  - Validate that `manifest.schemaVersion <= AppDatabase.DATABASE_SCHEMA_VERSION` (currently 2). If `manifest.schemaVersion > AppDatabase.DATABASE_SCHEMA_VERSION`, reject restore cleanly with an `IllegalArgumentException` / `BackupRestoreException("Incompatible schema version: ${manifest.schemaVersion}")` rather than crashing during Room query execution or leaving corrupted foreign keys.
- **Feature 21 (Post-Restore Live Repository State Reload)**:
  - Upon successful commit of the restore transaction, trigger live state reload across singleton repositories so UI and in-memory caches reflect restored database data immediately:
    - `TripRepository.getInstance(context).reloadFromDatabase()` (or re-query active trip and qualifying trips)
    - `OdometerManager.reloadFromPreferences(context)`
    - `AppSettingsManager.reload(context)` (or notify settings changed)
    - `FuelRepository.getInstance(context).rehydrateLedgerAsync()`

### Feature 22: Room Database Coroutine Exception Handling
Files:
- `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt`
- Wrap async Room DB calls (e.g. `coroutineScope.launch`) with a `CoroutineExceptionHandler` or try/catch blocks logging errors to Android Logcat (`Log.e(...)`) rather than unhandled coroutine cancellations or crashes if SQLite disk write fails (e.g., SQLiteFullException, SQLiteDiskIOException).

### Feature 23: Flash Wear & Disk I/O Throttling
Files:
- `app/src/main/java/com/antigravity/drivingreport/core/database/PersistentDataManager.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/trip/VehicleStateManager.kt`
- In `PersistentDataManager.kt`: optimize `backupAsync()` frequency. Debounce writes or increase minimum interval from 5s to 30s to protect automotive NAND flash wear during long driving trips.
- In `VehicleStateManager.kt`: ensure high-frequency state updates do not perform blocking synchronous disk writes; debounce `apply()` to SharedPreferences/files (minimum 1s window).

### Feature 24: Offline Map Tile Image Validation
File: `app/src/main/java/com/antigravity/drivingreport/core/map/IndiaOfflineMapManager.kt`
- In `fetchTileFast()` / tile download routines:
  - Check HTTP response code (`connection.responseCode == 200`).
  - Check content length or byte array size (reject empty or HTML 404 error payloads < 100 bytes).
  - Verify image magic bytes:
    - JPEG: starts with `0xFF, 0xD8, 0xFF`
    - PNG: starts with `0x89, 0x50, 0x4E, 0x47`
  - If invalid, discard tile and do NOT write corrupt/blank bytes into SQLite cache.

### Feature 25: SQLite Tile Writer Resource Management
File: `app/src/main/java/com/antigravity/drivingreport/core/map/IndiaOfflineMapManager.kt`
- In `downloadRegionTiles()`:
  - Ensure any created `SqlTileWriter` or SQLite database helper instances are safely closed and detached in a `finally` block or when download finishes/cancels, preventing SQLite connection leaks and database lock contention with MapView rendering.

### Feature 26: Activity Context Memory Leak Fix
File: `app/src/main/java/com/antigravity/drivingreport/core/map/IndiaOfflineMapManager.kt`
- In `registerNetworkCallback(context: Context)`:
  - Ensure `context.applicationContext` is used when retrieving `ConnectivityManager` and storing any listener references, preventing leaking the `MainActivity` instance across configuration changes (orientation/split-screen).

### Feature 27: LiveMapView Pulsing Aura Background Pause
File: `app/src/main/java/com/antigravity/drivingreport/feature/maps/LiveMapView.kt` (or `PulsingAuraOverlay`)
- When MapView lifecycle is `onPause()` or detached from window, cancel the 30 FPS `postInvalidateDelayed` animation loop.
- Resume animation only when MapView is `onResume()` / visible, saving vehicle CPU and GPU cycles when driver switches to other launcher screens.

### Feature 28: Unused Native Library Removal
File: `app/src/main/java/com/antigravity/drivingreport/DrivingReportApp.kt`
- Remove redundant or unused `MapLibre.getInstance(this)` initialization if the app exclusively uses OSM / osmdroid / MapsForge, eliminating native SO library loading overhead and memory bloat on app startup.

---

## Verification Requirements
1. Add targeted unit tests in `app/src/test/java/com/antigravity/drivingreport/` covering:
   - `Milestone3StorageAndPersistenceTest.kt`: Backup manifest schema validation, atomic restore rollback simulation, post-restore singleton rehydration.
   - `Milestone3MapTileAndLifecycleTest.kt`: Offline map tile magic byte validation (reject HTML/truncated payloads, accept valid PNG/JPEG), animation pause state.
2. Run full test suite: `.\gradlew.bat testDebugUnitTest`. All tests must pass (100% green, 0 failures, 0 regressions).
3. Document all changes, files touched, commands run, and test counts in `d:\Car Launcher\.agents\teamwork\m3_worker\handoff.md`.


## 2026-11-10T19:35:45Z
You are the Milestone 3 Implementation Worker.
Your working directory is: d:\Car Launcher\.agents\teamwork\m3_worker\
Read:
- `d:\Car Launcher\.agents\teamwork\m3_worker\DISPATCH.md`
- `d:\Car Launcher\.agents\teamwork\ORIGINAL_REQUEST.md`
- `d:\Car Launcher\PROJECT.md`
- `d:\Car Launcher\.agents\teamwork\survey_exp2\handoff.md`
- `d:\Car Launcher\.agents\teamwork\survey_exp3\handoff.md`

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. A teamwork_auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Implement Features 19–28 per the detailed instructions in DISPATCH.md:
- Feature 19: Atomic backup restore with snapshot rollback in `FullBackupManager.kt`.
- Feature 20: Schema version validation (`manifest.schemaVersion <= AppDatabase.DATABASE_SCHEMA_VERSION`).
- Feature 21: Post-restore live singleton reload (`TripRepository`, `OdometerManager`, `AppSettingsManager`, `FuelRepository`).
- Feature 22: Coroutine exception handling for Room DB in `TripRepository.kt` & `FuelRepository.kt`.
- Feature 23: Flash wear & disk I/O throttling (debounce 30s in `PersistentDataManager.kt`, debounce 1s in `VehicleStateManager.kt`).
- Feature 24: Offline map tile image validation (HTTP 200, length > 100 bytes, JPEG/PNG magic bytes) in `IndiaOfflineMapManager.kt`.
- Feature 25: SQLite tile writer resource management (`SqlTileWriter` safe close in `IndiaOfflineMapManager.kt`).
- Feature 26: Activity context memory leak fix (use `context.applicationContext` in `IndiaOfflineMapManager.kt`).
- Feature 27: `LiveMapView` pulsing aura animation pause when detached / paused.
- Feature 28: Unused native library removal (`MapLibre.getInstance(this)` in `DrivingReportApp.kt`).

Add targeted unit test suites:
- `Milestone3StorageAndPersistenceTest.kt`
- `Milestone3MapTileAndLifecycleTest.kt`

Run `.\gradlew.bat testDebugUnitTest` and verify 100% pass (0 failures, 0 regressions).
Write your 5-component handoff report to `d:\Car Launcher\.agents\teamwork\m3_worker\handoff.md` and send a message when done.
