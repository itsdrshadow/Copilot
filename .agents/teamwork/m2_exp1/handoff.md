# Milestone 2 — Features 11 & 12 Technical Investigation and Blueprint Report

**Author**: M2 Explorer 1 (`m2_exp1`)  
**Role**: M2 Explorer Odometer & Calibration  
**Scope**: 
- Feature 11: Odometer Double-Counting & Historical Query Isolation
- Feature 12: Dual Baseline Calibration Synchronization

---

## 1. Observation

### 1.1 Historical Trips Query Leakage in `AppDatabase.kt`
In `app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt`:
```kotlin
21:    @Query("SELECT * FROM trips WHERE isQualifying = 1 ORDER BY startTime DESC")
22:    fun getAllQualifyingTrips(): Flow<List<TripEntity>>
23:
24:    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0 ORDER BY startTime DESC")
25:    fun getAllHistoricalTrips(): Flow<List<TripEntity>>
26:
27:    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 ORDER BY startTime DESC LIMIT 3")
28:    fun getLastThreeTrips(): Flow<List<TripEntity>>
```
Direct observation:
- In `getAllHistoricalTrips()` (line 24), the WHERE clause uses `WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0`.
- An active trip being recorded has `status = 'ACTIVE_TRIP'`. When movement is detected and periodic database flushes occur in `TripRepository.kt` (lines 419–467), the trip row in SQLite is updated with `distanceMeters > 0`.
- Because of `OR distanceMeters > 0`, any ongoing trip that has accumulated even 1 millimeter of movement matches this query immediately.
- Furthermore, because of `OR isQualifying = 1`, once an active trip reaches 1,000 meters (where `isQualifying` is set to `true` in `onTripUpdated` at line 464), it matches that branch as well.
- In `getLastThreeTrips()` (line 27), the query `WHERE status = 'COMPLETED' OR isQualifying = 1` also leaks ongoing trips once they surpass 1 km, even though they have not completed.

### 1.2 Uncleaned Active Trip Distance in `TripRepository.kt`
In `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt`:
```kotlin
160:    val qualifyingTrips = tripDao.getAllHistoricalTrips()
...
472:    fun onTripCompleted(summary: TripSummary) {
473:        updateVisualLifecycle(TripVisualLifecycleEvent.COMPLETED, System.currentTimeMillis(), summary.id)
474:        _currentTrip.value = summary
475:        synchronized(trailLock) {
476:            activeTrailPoints = ArrayList()
477:        }
478:        trailVersion++
479:        _tripTrail.value = emptyList() // Immediately clear active route trail line when trip ends
480:
481:        scope.launch {
...
493:            tripDao.updateTrip(
494:                TripEntity(
495:                    id = summary.id,
496:                    startTime = summary.startTime,
497:                    endTime = summary.endTime,
498:                    distanceMeters = summary.distanceMeters,
499:                    drivingDurationMs = summary.drivingDurationMs,
500:                    stationaryDurationMs = summary.stationaryDurationMs,
501:                    avgSpeedKmh = summary.avgSpeedKmh,
502:                    maxSpeedKmh = summary.maxSpeedKmh,
503:                    estimatedFuelLiters = summary.estimatedFuelConsumedLiters,
504:                    calculatedMileageKmpl = if (summary.calculatedMileageKmpl > 0) summary.calculatedMileageKmpl else getEffectiveMileageFallback(),
505:                    driverScore = summary.driverScore,
506:                    startLocationName = summary.startLocationName.ifEmpty { "Trip Origin" },
507:                    endLocationName = summary.endLocationName.ifEmpty { "Trip Destination" },
508:                    status = TripStatus.COMPLETED.name,
509:                    isQualifying = true
510:                )
511:            )
```
Direct observation:
- At line 474, when `onTripCompleted` is called, `_currentTrip.value` is assigned the incoming `summary` object.
- The `summary` object contains the full trip distance: `distanceMeters = summary.distanceMeters`, `distanceKm = summary.distanceMeters / 1000.0`, and non-zero durations.
- The `_currentTrip` StateFlow is **never reset to empty** (e.g. distance = 0, status = IDLE).
- At line 510, the database row is updated to `status = 'COMPLETED'`.
- At line 214–229 in `TripRepository.init`, if an active trip row is loaded from SQLite where `endTime > 0`, it initializes `_currentTrip.value` with that completed trip's non-zero distance.

### 1.3 Unconditional Active Distance Addition in UI Consumers
In `app/src/main/java/com/antigravity/drivingreport/MainActivity.kt`:
```kotlin
201:    val recordedDistanceKm = remember(qualifyingTrips, currentTrip.distanceKm) {
202:        qualifyingTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
203:    }
204:
205:    LaunchedEffect(recordedDistanceKm) {
206:        viewModel.syncOdometer(recordedDistanceKm)
207:    }
```
In `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt`:
```kotlin
75:    // Memoized Lifetime Calculations (Single Source of Truth)
76:    val totalLifetimeKm = remember(allTrips, currentTrip.distanceKm) {
77:        allTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
78:    }
79:    val totalTripsCount = allTrips.size
80:    val (totalHours, totalRemainingMin) = remember(allTrips, currentTrip.drivingDurationMs, currentTrip.stationaryDurationMs) {
81:        val totalMs = allTrips.sumOf { it.drivingDurationMs + it.stationaryDurationMs } + (currentTrip.drivingDurationMs + currentTrip.stationaryDurationMs)
82:        val totalDurationMin = totalMs / 60000L
83:        Pair((totalDurationMin / 60).toInt(), (totalDurationMin % 60).toInt())
84:    }
...
95:    val totalLifetimeFuelLiters = remember(allTrips, currentTrip.estimatedFuelConsumedLiters, fallbackMileage) {
96:        allTrips.sumOf {
97:            if (it.estimatedFuelLiters > 0.0) it.estimatedFuelLiters
98:            else (it.distanceMeters / 1000.0) / (if (it.calculatedMileageKmpl > 0.0) it.calculatedMileageKmpl else fallbackMileage)
99:        } + (if (currentTrip.estimatedFuelConsumedLiters > 0.0) currentTrip.estimatedFuelConsumedLiters else 0.0)
100:    }
```
Direct observation:
- Both `MainActivity.kt` and `DashboardScreen.kt` unconditionally compute total lifetime distance by summing all trips in the database collection and adding `currentTrip.distanceKm`.
- There is no check on `currentTrip.status` (e.g. `currentTrip.status == TripStatus.ACTIVE_TRIP`).
- When `currentTrip` completes:
  1. The database row transitions to `COMPLETED`, so `qualifyingTrips` / `allTrips` immediately includes the completed trip (adding its distance to `sumOf { it.distanceMeters }`).
  2. Because `_currentTrip.value` retained the completed summary, `currentTrip.distanceKm` remains equal to that same distance.
  3. Consequently, the completed trip's distance is added twice.
- When `currentTrip` is active and driving:
  1. Because `getAllHistoricalTrips()` returned trips with `distanceMeters > 0`, the active trip was already present in `allTrips`.
  2. Adding `currentTrip.distanceKm` caused active distance to be counted twice during the entire drive.

### 1.4 Dual Baseline Definitions and Desynchronization in `FuelRepository` vs `OdometerManager`
In `app/src/main/java/com/antigravity/drivingreport/core/odometer/OdometerManager.kt`:
```kotlin
26:    data class OdometerState(
27:        val baselineOdometerKm: Double = 0.0,
28:        val baselineLifetimeKmAtUpdate: Double = 0.0,
29:        val isManuallyVerified: Boolean = false
30:    )
...
63:    fun setBaselineOdometer(context: Context, readingKm: Double, currentTotalLifetimeKm: Double = 0.0) {
64:        val safeReading = readingKm.coerceAtLeast(0.0)
65:        val safeLifetime = currentTotalLifetimeKm.coerceAtLeast(0.0)
66:        getPrefs(context).edit()
67:            .putLong(KEY_BASELINE_ODOMETER_KM + "_bits", java.lang.Double.doubleToRawLongBits(safeReading))
68:            .putLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", java.lang.Double.doubleToRawLongBits(safeLifetime))
69:            .putFloat(KEY_BASELINE_ODOMETER_KM, safeReading.toFloat())
70:            .putFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, safeLifetime.toFloat())
71:            .putBoolean(KEY_IS_MANUALLY_VERIFIED, true)
72:            .apply()
...
85:    fun calculateDeltaKm(totalLifetimeKm: Double): Double {
86:        val delta = totalLifetimeKm - _odometerConfig.value.baselineLifetimeKmAtUpdate
87:        return delta.coerceAtLeast(0.0)
88:    }
89:
90:    fun calculateLiveOdometer(totalLifetimeKm: Double): Double {
91:        val delta = calculateDeltaKm(totalLifetimeKm)
92:        return (_odometerConfig.value.baselineOdometerKm + delta).coerceAtLeast(0.0)
93:    }
```
In `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt`:
```kotlin
59:    private var baselineOdometerKm: Double = if (prefs.contains("baseline_odometer_bits")) {
60:        java.lang.Double.longBitsToDouble(prefs.getLong("baseline_odometer_bits", 0L))
61:    } else {
62:        prefs.getFloat("baseline_odometer", 0.0f).toDouble()
63:    }
64:    private var latestAppLifetimeKm: Double = 0.0
...
210:    fun getEffectiveOdometer(): Double {
211:        val odoMgr = OdometerManager.calculateLiveOdometer(latestAppLifetimeKm)
212:        return if (odoMgr > 0.0) FuelMath.roundDistance(odoMgr) else FuelMath.roundDistance(baselineOdometerKm + latestAppLifetimeKm)
213:    }
...
346:    fun calibrateOdometer(newOdometerReadingKm: Double, currentRecordedKm: Double) {
347:        latestAppLifetimeKm = currentRecordedKm
348:        baselineOdometerKm = max(0.0, newOdometerReadingKm - currentRecordedKm)
349:        OdometerManager.setBaselineOdometer(appContext, newOdometerReadingKm, currentRecordedKm)
350:        prefs.edit()
351:            .putLong("baseline_odometer_bits", java.lang.Double.doubleToRawLongBits(baselineOdometerKm))
352:            .putFloat("baseline_odometer", baselineOdometerKm.toFloat())
353:            .apply()
354:        _fuelState.value = buildFuelState()
355:    }
```
In `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt`:
```kotlin
485:            onConfirmCalibration = { newOdometerKm ->
486:                OdometerManager.setBaselineOdometer(context, newOdometerKm, totalLifetimeKm)
487:                showOdometerDialog = false
488:            }
```
Direct observation:
1. **Divergent Mathematical Models**:
   - `OdometerManager` defines `baselineOdometerKm` as the **dashboard cluster reading** at calibration (e.g. 15,000 km) and defines `baselineLifetimeKmAtUpdate` as the app's lifetime distance at that moment (e.g. 200 km). Added distance is `delta = lifetimeKm - baselineLifetimeKmAtUpdate`.
   - `FuelRepository` defines `baselineOdometerKm` as `newOdometerReadingKm - currentRecordedKm` (e.g. 15,000 - 200 = 14,800 km), which is the hypothetical reading when lifetime was 0.
   - These conflicting values were saved in two separate SharedPreferences files: `driving_report_odometer_prefs` (stored 15,000.0) and `driving_report_fuel_prefs` (stored 14,800.0).
2. **Bypassed Synchronization**:
   - When calibrating from `OdometerCard.kt` (line 486), the UI directly invoked `OdometerManager.setBaselineOdometer(...)`. It **never called** `FuelRepository.calibrateOdometer(...)`.
   - As a result, `FuelRepository` was never notified, its internal `baselineOdometerKm` remained stale, and `_fuelState` was not recomputed.
3. **Legacy Restore Double Subtraction Vulnerability**:
   - In `PersistentDataManager.kt` (line 116), legacy restore reads `p.getDouble("baseline_odometer")`. Because `FuelRepository` previously stored `newReading - lifetimeKm` in that field, if a restore function called `setBaselineOdometer(context, legacyOdo, lifetimeKm)`, the lifetime km would be subtracted a second time.

---

## 2. Logic Chain

1. **Active Trip Leakage to Historical Queries**:
   - Observation 1.1 shows that `TripDao.getAllHistoricalTrips()` specifies:
     `SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0 ORDER BY startTime DESC`.
   - Observation 1.1 also shows that active trips have `distanceMeters > 0` after the first 4 seconds of driving.
   - Therefore, the active trip is returned inside the Room `Flow<List<TripEntity>>` consumed as `qualifyingTrips` in `MainActivity` and `allTrips` in `DashboardScreen`.
   - Observation 1.3 shows that both screens compute total lifetime distance as:
     `historicalTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm`.
   - Because the active trip's distance is already included in the first term (from SQLite) and is added again in the second term (from `currentTrip.distanceKm`), active distance is double-counted continuously during the drive.

2. **Trip Completion Double Counting**:
   - Observation 1.2 shows that `TripRepository.onTripCompleted(summary)` updates SQLite with `status = 'COMPLETED'` and sets `_currentTrip.value = summary`.
   - Because `_currentTrip` still holds the finished trip's distance (`summary.distanceKm > 0`), and Room simultaneously emits `qualifyingTrips` containing the newly completed trip, the equation:
     `historicalTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm`
     adds the finished trip's distance twice upon completion.
   - The double counting persists until the app restarts or a new trip overwrites `_currentTrip`.

3. **Query Contract Resolution**:
   - By constraining `TripDao.getAllHistoricalTrips()` to:
     `SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC`,
     no trip with `status = 'ACTIVE_TRIP'` can ever be returned, regardless of its distance or qualification flag.
   - By clearing `_currentTrip.value = TripSummary.empty()` upon `onTripCompleted`, `currentTrip.distanceKm` instantly drops to `0.0`.
   - By conditioning the UI calculation on `isTripActive` (e.g. `currentTrip.status == ACTIVE_TRIP`), even if `_currentTrip` retains metadata for a completion banner, its distance is never added to the historical database sum.

4. **Dual Baseline Harmonization**:
   - Observation 1.4 shows two competing definitions of "baseline":
     - Definition A (`OdometerManager`): baseline = cluster reading at calibration; delta = totalLifetime - baselineLifetime.
     - Definition B (`FuelRepository`): baseline = cluster reading - totalLifetime; live = baseline + totalLifetime.
   - Mathematically, both definitions yield the same live reading:
     `Cluster + (Lifetime - BaseLifetime) == (Cluster - BaseLifetime) + Lifetime`.
     However, storing two different numbers in two different files under similar preference keys causes state divergence, race conditions, and double subtraction upon backup restore.
   - By designating `OdometerManager` as the **sole single source of truth** for baseline odometer calibration:
     - `OdometerManager.setBaselineOdometer` handles all persistence in `driving_report_odometer_prefs`.
     - `FuelRepository` drops its local `baselineOdometerKm` storage in `driving_report_fuel_prefs`.
     - `FuelRepository.calibrateOdometer` delegates directly to `OdometerManager.setBaselineOdometer`.
     - `FuelRepository.getEffectiveOdometer` delegates directly to `OdometerManager.calculateLiveOdometer`.
     - `OdometerCard.kt` routes calibration through `FuelRepository.calibrateOdometer` (or `MainViewModel.calibrateOdometer`), ensuring both `OdometerManager` and `FuelRepository`'s `_fuelState` update synchronously.

---

## 3. Caveats

1. **Database Migration Not Required**:
   `TripDao` queries are compile-time Room DAO definitions; modifying the SQL queries in `AppDatabase.kt` does not alter table schemas or column definitions. Therefore, bumping Room database schema version 8 is not required.
2. **Pre-existing SQLite Data**:
   Trips previously written to SQLite while active retain whatever `status` was stored. However, because our new query requires `status = 'COMPLETED'`, any orphaned active trips left from past ungraceful kills will not appear in historical rollups (which is the correct behavior).
3. **Visual Trip Completion Banner**:
   `CurrentTripCard` uses `TripRepository.tripVisualState` (which fires `TripVisualLifecycleEvent.COMPLETED` with a timestamp) to show the 10-second red pulsing completion banner. Resetting `_currentTrip.value = TripSummary.empty()` does not interfere with the banner because the visual state is tracked independently.
4. **Short Non-Qualifying Trips (< 1.0 km)**:
   In `TripDetectionEngine.kt`, trips under 200m are discarded via `onTripDiscarded` (deleted from SQLite). Trips between 200m and 1000m are marked `isQualifying = false` during movement. With `WHERE status = 'COMPLETED' AND isQualifying = 1`, completed trips under 1 km do not appear in `getAllHistoricalTrips()` or `TripHistoryScreen`. If business logic requires all completed trips $\ge 200\text{m}$ to be historical, `isQualifying` on completion must be explicitly set to `summary.distanceMeters >= 200.0` or `summary.distanceMeters >= 1000.0`.

---

## 4. Conclusion & Actionable Blueprint

We have formulated an exhaustive, step-by-step code modification blueprint across all 8 involved files.

### 4.1 Step-by-Step Blueprint with Exact Line-by-Line Code Blocks

#### Target File 1: `app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt`
- **Location**: Lines 21–28
- **Purpose**: Restrict historical and qualifying trip queries strictly to completed, qualifying trips (`status = 'COMPLETED' AND isQualifying = 1`).

**Before (Lines 21–28):**
```kotlin
    @Query("SELECT * FROM trips WHERE isQualifying = 1 ORDER BY startTime DESC")
    fun getAllQualifyingTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0 ORDER BY startTime DESC")
    fun getAllHistoricalTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 ORDER BY startTime DESC LIMIT 3")
    fun getLastThreeTrips(): Flow<List<TripEntity>>
```

**After (Lines 21–28):**
```kotlin
    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC")
    fun getAllQualifyingTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC")
    fun getAllHistoricalTrips(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE status = 'COMPLETED' AND isQualifying = 1 ORDER BY startTime DESC LIMIT 3")
    fun getLastThreeTrips(): Flow<List<TripEntity>>
```

---

#### Target File 2: `app/src/main/java/com/antigravity/drivingreport/core/model/Models.kt`
- **Location**: Line 63 (inside `data class TripSummary`)
- **Purpose**: Add `TripSummary.empty()` factory function to support clean lifecycle resets.

**Before (Line 63):**
```kotlin
        return directions[index]
    }
}

enum class MileageLearningState {
```

**After (Line 63):**
```kotlin
        return directions[index]
    }

    companion object {
        fun empty(): TripSummary = TripSummary(
            id = 0,
            startTime = 0L,
            status = TripStatus.IDLE
        )
    }
}

enum class MileageLearningState {
```

---

#### Target File 3: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt`
- **Location**: Lines 214–234 (recovery of completed trip) and Lines 472–514 (`onTripCompleted`)
- **Purpose**: Reset `_currentTrip.value = TripSummary.empty()` on completion and align qualification check to $\ge 1000\text{m}$.

**Before (Lines 214–229):**
```kotlin
            } else if (activeTrip != null) {
                // Trip was already completed: do not load route trail line onto the map
                _currentTrip.value = TripSummary(
                    id = activeTrip.id,
                    startTime = activeTrip.startTime,
                    endTime = activeTrip.endTime,
                    distanceMeters = activeTrip.distanceMeters,
                    drivingDurationMs = activeTrip.drivingDurationMs,
                    stationaryDurationMs = activeTrip.stationaryDurationMs,
                    avgSpeedKmh = activeTrip.avgSpeedKmh,
                    maxSpeedKmh = activeTrip.maxSpeedKmh,
                    calculatedMileageKmpl = if (activeTrip.calculatedMileageKmpl > 0) activeTrip.calculatedMileageKmpl else getEffectiveMileageFallback(),
                    startLocationName = activeTrip.startLocationName,
                    endLocationName = activeTrip.endLocationName,
                    status = TripStatus.COMPLETED
                )
                synchronized(trailLock) {
                    activeTrailPoints = ArrayList()
                }
                _tripTrail.value = emptyList()
            }
```

**After (Lines 214–229):**
```kotlin
            } else if (activeTrip != null) {
                // Trip was already completed: reset current trip to empty to avoid double-counting with historical list
                _currentTrip.value = TripSummary.empty()
                synchronized(trailLock) {
                    activeTrailPoints = ArrayList()
                }
                _tripTrail.value = emptyList()
            }
```

**Before (Lines 472–480 and 508–511):**
```kotlin
    fun onTripCompleted(summary: TripSummary) {
        updateVisualLifecycle(TripVisualLifecycleEvent.COMPLETED, System.currentTimeMillis(), summary.id)
        _currentTrip.value = summary
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList() // Immediately clear active route trail line when trip ends

        scope.launch {
            // Strictly await parent TripEntity insertion before flushing remaining points
            activeTripInsertionJob?.join()
...
                    status = TripStatus.COMPLETED.name,
                    isQualifying = true
                )
            )
            PersistentDataManager.backupAsync(appContext)
        }
    }
```

**After (Lines 472–480 and 508–511):**
```kotlin
    fun onTripCompleted(summary: TripSummary) {
        updateVisualLifecycle(TripVisualLifecycleEvent.COMPLETED, System.currentTimeMillis(), summary.id)
        _currentTrip.value = TripSummary.empty()
        synchronized(trailLock) {
            activeTrailPoints = ArrayList()
        }
        trailVersion++
        _tripTrail.value = emptyList() // Immediately clear active route trail line when trip ends

        scope.launch {
            // Strictly await parent TripEntity insertion before flushing remaining points
            activeTripInsertionJob?.join()
...
                    status = TripStatus.COMPLETED.name,
                    isQualifying = summary.distanceMeters >= 1000.0
                )
            )
            PersistentDataManager.backupAsync(appContext)
        }
    }
```

---

#### Target File 4: `app/src/main/java/com/antigravity/drivingreport/MainActivity.kt`
- **Location**: Lines 201–207
- **Purpose**: Calculate `recordedDistanceKm` by isolating active distance strictly to active trip states (`ACTIVE_TRIP`, `TEMPORARY_STOP`, `MOVEMENT_CANDIDATE`).

**Before (Lines 201–207):**
```kotlin
    val recordedDistanceKm = remember(qualifyingTrips, currentTrip.distanceKm) {
        qualifyingTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
    }

    LaunchedEffect(recordedDistanceKm) {
        viewModel.syncOdometer(recordedDistanceKm)
    }
```

**After (Lines 201–207):**
```kotlin
    val recordedDistanceKm = remember(qualifyingTrips, currentTrip.distanceKm, currentTrip.status) {
        val historicalKm = qualifyingTrips.sumOf { it.distanceMeters } / 1000.0
        val isTripActive = currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.ACTIVE_TRIP ||
            currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.MOVEMENT_CANDIDATE ||
            currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.TEMPORARY_STOP
        val activeKm = if (isTripActive) currentTrip.distanceKm else 0.0
        historicalKm + activeKm
    }

    LaunchedEffect(recordedDistanceKm) {
        viewModel.syncOdometer(recordedDistanceKm)
    }
```

---

#### Target File 5: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt`
- **Location**: Lines 74–104
- **Purpose**: Isolate active trip telemetry metrics (distance, driving time, lifetime fuel, avg speed) to active trip states.

**Before (Lines 74–104):**
```kotlin
    // Memoized Lifetime Calculations (Single Source of Truth)
    val totalLifetimeKm = remember(allTrips, currentTrip.distanceKm) {
        allTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
    }
    val totalTripsCount = allTrips.size
    val (totalHours, totalRemainingMin) = remember(allTrips, currentTrip.drivingDurationMs, currentTrip.stationaryDurationMs) {
        val totalMs = allTrips.sumOf { it.drivingDurationMs + it.stationaryDurationMs } + (currentTrip.drivingDurationMs + currentTrip.stationaryDurationMs)
        val totalDurationMin = totalMs / 60000L
        Pair((totalDurationMin / 60).toInt(), (totalDurationMin % 60).toInt())
    }
    val lifetimeAvgSpeed = remember(allTrips, totalLifetimeKm, currentTrip.drivingDurationMs) {
        val totalDrivingMs = allTrips.sumOf { it.drivingDurationMs } + currentTrip.drivingDurationMs
        if (totalDrivingMs > 60000L && totalLifetimeKm > 0.1) {
            val hours = totalDrivingMs / 3600000.0
            totalLifetimeKm / hours
        } else if (allTrips.isNotEmpty()) {
            val valid = allTrips.map { it.avgSpeedKmh }.filter { it > 0 }
            if (valid.isNotEmpty()) valid.average() else 0.0
        } else 0.0
    }
    val fallbackMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else (if (fuelState.expectedMileageKmpl > 0.0) fuelState.expectedMileageKmpl else 18.0)
    val totalLifetimeFuelLiters = remember(allTrips, currentTrip.estimatedFuelConsumedLiters, fallbackMileage) {
        allTrips.sumOf {
            if (it.estimatedFuelLiters > 0.0) it.estimatedFuelLiters
            else (it.distanceMeters / 1000.0) / (if (it.calculatedMileageKmpl > 0.0) it.calculatedMileageKmpl else fallbackMileage)
        } + (if (currentTrip.estimatedFuelConsumedLiters > 0.0) currentTrip.estimatedFuelConsumedLiters else 0.0)
    }
    val lifetimeMileage = remember(totalLifetimeKm, totalLifetimeFuelLiters, fallbackMileage) {
        if (totalLifetimeFuelLiters > 0.05) totalLifetimeKm / totalLifetimeFuelLiters else fallbackMileage
    }
```

**After (Lines 74–104):**
```kotlin
    // Memoized Lifetime Calculations (Single Source of Truth)
    val isTripActive = currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.ACTIVE_TRIP ||
        currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.MOVEMENT_CANDIDATE ||
        currentTrip.status == com.antigravity.drivingreport.core.model.TripStatus.TEMPORARY_STOP

    val activeTripDistanceKm = if (isTripActive) currentTrip.distanceKm else 0.0
    val activeTripDurationMs = if (isTripActive) (currentTrip.drivingDurationMs + currentTrip.stationaryDurationMs) else 0L
    val activeDrivingDurationMs = if (isTripActive) currentTrip.drivingDurationMs else 0L
    val activeTripFuelLiters = if (isTripActive && currentTrip.estimatedFuelConsumedLiters > 0.0) currentTrip.estimatedFuelConsumedLiters else 0.0

    val totalLifetimeKm = remember(allTrips, activeTripDistanceKm) {
        allTrips.sumOf { it.distanceMeters } / 1000.0 + activeTripDistanceKm
    }
    val totalTripsCount = allTrips.size
    val (totalHours, totalRemainingMin) = remember(allTrips, activeTripDurationMs) {
        val totalMs = allTrips.sumOf { it.drivingDurationMs + it.stationaryDurationMs } + activeTripDurationMs
        val totalDurationMin = totalMs / 60000L
        Pair((totalDurationMin / 60).toInt(), (totalDurationMin % 60).toInt())
    }
    val lifetimeAvgSpeed = remember(allTrips, totalLifetimeKm, activeDrivingDurationMs) {
        val totalDrivingMs = allTrips.sumOf { it.drivingDurationMs } + activeDrivingDurationMs
        if (totalDrivingMs > 60000L && totalLifetimeKm > 0.1) {
            val hours = totalDrivingMs / 3600000.0
            totalLifetimeKm / hours
        } else if (allTrips.isNotEmpty()) {
            val valid = allTrips.map { it.avgSpeedKmh }.filter { it > 0 }
            if (valid.isNotEmpty()) valid.average() else 0.0
        } else 0.0
    }
    val fallbackMileage = if (fuelState.currentMileageKmpl > 0.0) fuelState.currentMileageKmpl else (if (fuelState.expectedMileageKmpl > 0.0) fuelState.expectedMileageKmpl else 18.0)
    val totalLifetimeFuelLiters = remember(allTrips, activeTripFuelLiters, fallbackMileage) {
        allTrips.sumOf {
            if (it.estimatedFuelLiters > 0.0) it.estimatedFuelLiters
            else (it.distanceMeters / 1000.0) / (if (it.calculatedMileageKmpl > 0.0) it.calculatedMileageKmpl else fallbackMileage)
        } + activeTripFuelLiters
    }
    val lifetimeMileage = remember(totalLifetimeKm, totalLifetimeFuelLiters, fallbackMileage) {
        if (totalLifetimeFuelLiters > 0.05) totalLifetimeKm / totalLifetimeFuelLiters else fallbackMileage
    }
```

---

#### Target File 6: `app/src/main/java/com/antigravity/drivingreport/core/odometer/OdometerManager.kt`
- **Location**: Full class
- **Purpose**: Add `latestLifetimeKm` tracking, `reloadFromPreferences(context)`, and `getEffectiveOdometer()` so `OdometerManager` is the single source of truth for both live calculations and post-restore reloading.

**Before (Lines 35–53 and 63–79):**
```kotlin
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        val prefs = getPrefs(context)
        val baseline = if (prefs.contains(KEY_BASELINE_ODOMETER_KM + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_ODOMETER_KM + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_ODOMETER_KM, 0.0f).toDouble()
        }
        val baselineLifetime = if (prefs.contains(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, 0.0f).toDouble()
        }
        val verified = prefs.getBoolean(KEY_IS_MANUALLY_VERIFIED, false)
        _odometerConfig.value = OdometerState(baseline, baselineLifetime, verified)
        isInitialized = true
    }
...
    fun setBaselineOdometer(context: Context, readingKm: Double, currentTotalLifetimeKm: Double = 0.0) {
        val safeReading = readingKm.coerceAtLeast(0.0)
        val safeLifetime = currentTotalLifetimeKm.coerceAtLeast(0.0)
        getPrefs(context).edit()
            .putLong(KEY_BASELINE_ODOMETER_KM + "_bits", java.lang.Double.doubleToRawLongBits(safeReading))
            .putLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", java.lang.Double.doubleToRawLongBits(safeLifetime))
            .putFloat(KEY_BASELINE_ODOMETER_KM, safeReading.toFloat())
            .putFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, safeLifetime.toFloat())
            .putBoolean(KEY_IS_MANUALLY_VERIFIED, true)
            .apply()

        _odometerConfig.value = OdometerState(
            baselineOdometerKm = safeReading,
            baselineLifetimeKmAtUpdate = safeLifetime,
            isManuallyVerified = true
        )
    }
```

**After (Lines 35–53 and 63–79):**
```kotlin
    private var isInitialized = false
    @Volatile
    private var latestLifetimeKm: Double = 0.0

    fun init(context: Context) {
        if (isInitialized) return
        reloadFromPreferences(context)
        isInitialized = true
    }

    fun reloadFromPreferences(context: Context) {
        val prefs = getPrefs(context)
        val baseline = if (prefs.contains(KEY_BASELINE_ODOMETER_KM + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_ODOMETER_KM + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_ODOMETER_KM, 0.0f).toDouble()
        }
        val baselineLifetime = if (prefs.contains(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits")) {
            java.lang.Double.longBitsToDouble(prefs.getLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", 0L))
        } else {
            prefs.getFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, 0.0f).toDouble()
        }
        val verified = prefs.getBoolean(KEY_IS_MANUALLY_VERIFIED, false)
        _odometerConfig.value = OdometerState(baseline, baselineLifetime, verified)
    }

    fun syncLifetimeKm(km: Double) {
        latestLifetimeKm = km.coerceAtLeast(0.0)
    }

    fun getEffectiveOdometer(): Double {
        return calculateLiveOdometer(latestLifetimeKm)
    }
...
    fun setBaselineOdometer(context: Context, readingKm: Double, currentTotalLifetimeKm: Double = 0.0) {
        val safeReading = readingKm.coerceAtLeast(0.0)
        val safeLifetime = currentTotalLifetimeKm.coerceAtLeast(0.0)
        latestLifetimeKm = safeLifetime
        getPrefs(context).edit()
            .putLong(KEY_BASELINE_ODOMETER_KM + "_bits", java.lang.Double.doubleToRawLongBits(safeReading))
            .putLong(KEY_BASELINE_LIFETIME_AT_UPDATE + "_bits", java.lang.Double.doubleToRawLongBits(safeLifetime))
            .putFloat(KEY_BASELINE_ODOMETER_KM, safeReading.toFloat())
            .putFloat(KEY_BASELINE_LIFETIME_AT_UPDATE, safeLifetime.toFloat())
            .putBoolean(KEY_IS_MANUALLY_VERIFIED, true)
            .apply()

        _odometerConfig.value = OdometerState(
            baselineOdometerKm = safeReading,
            baselineLifetimeKmAtUpdate = safeLifetime,
            isManuallyVerified = true
        )
    }
```

---

#### Target File 7: `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt`
- **Location**: Lines 59–65, 110–120, 210–213, 346–363
- **Purpose**: Remove redundant `baselineOdometerKm` member variable and SharedPreferences key. Delegate `calibrateOdometer`, `syncOdometerDistance`, and `getEffectiveOdometer` directly to `OdometerManager`.

**Before (Lines 59–65):**
```kotlin
    private var baselineOdometerKm: Double = if (prefs.contains("baseline_odometer_bits")) {
        java.lang.Double.longBitsToDouble(prefs.getLong("baseline_odometer_bits", 0L))
    } else {
        prefs.getFloat("baseline_odometer", 0.0f).toDouble()
    }
    private var latestAppLifetimeKm: Double = 0.0
```

**After (Lines 59–65):**
```kotlin
    private var latestAppLifetimeKm: Double = 0.0
```

**Before (Lines 104–119 in `init`):**
```kotlin
    init {
        // Hydrate baseline from persisted preferences and past ledger
        val initialLiters = if (prefs.contains("remaining_liters")) {
            prefs.getFloat("remaining_liters", (designCapacity * 0.5f).toFloat()).toDouble()
        } else {
            designCapacity * 0.5
        }
        val lastBar = gaugeCalibration.getBarForLiters(initialLiters)
        val initialOdo = getEffectiveOdometer()

        estimator.initializeBaseline(
            initialLiters = initialLiters,
            gaugePoints = lastBar,
            odometerKm = initialOdo
        )
```

**After (Lines 104–119 in `init`):**
```kotlin
    init {
        // Ensure OdometerManager is initialized prior to computing initial odometer reading
        OdometerManager.init(appContext)

        // Hydrate baseline from persisted preferences and past ledger
        val initialLiters = if (prefs.contains("remaining_liters")) {
            prefs.getFloat("remaining_liters", (designCapacity * 0.5f).toFloat()).toDouble()
        } else {
            designCapacity * 0.5
        }
        val lastBar = gaugeCalibration.getBarForLiters(initialLiters)
        val initialOdo = getEffectiveOdometer()

        estimator.initializeBaseline(
            initialLiters = initialLiters,
            gaugePoints = lastBar,
            odometerKm = initialOdo
        )
```

**Before (Lines 210–213):**
```kotlin
    fun getEffectiveOdometer(): Double {
        val odoMgr = OdometerManager.calculateLiveOdometer(latestAppLifetimeKm)
        return if (odoMgr > 0.0) FuelMath.roundDistance(odoMgr) else FuelMath.roundDistance(baselineOdometerKm + latestAppLifetimeKm)
    }
```

**After (Lines 210–213):**
```kotlin
    fun getEffectiveOdometer(): Double {
        val odoMgr = OdometerManager.calculateLiveOdometer(latestAppLifetimeKm)
        return FuelMath.roundDistance(odoMgr)
    }
```

**Before (Lines 346–363):**
```kotlin
    fun calibrateOdometer(newOdometerReadingKm: Double, currentRecordedKm: Double) {
        latestAppLifetimeKm = currentRecordedKm
        baselineOdometerKm = max(0.0, newOdometerReadingKm - currentRecordedKm)
        OdometerManager.setBaselineOdometer(appContext, newOdometerReadingKm, currentRecordedKm)
        prefs.edit()
            .putLong("baseline_odometer_bits", java.lang.Double.doubleToRawLongBits(baselineOdometerKm))
            .putFloat("baseline_odometer", baselineOdometerKm.toFloat())
            .apply()
        _fuelState.value = buildFuelState()
    }

    fun syncOdometerDistance(currentRecordedKm: Double) {
        if (latestAppLifetimeKm != currentRecordedKm) {
            latestAppLifetimeKm = currentRecordedKm
            _fuelState.value = buildFuelState()
        }
    }
```

**After (Lines 346–363):**
```kotlin
    fun calibrateOdometer(newOdometerReadingKm: Double, currentRecordedKm: Double) {
        latestAppLifetimeKm = currentRecordedKm
        OdometerManager.setBaselineOdometer(appContext, newOdometerReadingKm, currentRecordedKm)
        _fuelState.value = buildFuelState()
    }

    fun syncOdometerDistance(currentRecordedKm: Double) {
        if (latestAppLifetimeKm != currentRecordedKm) {
            latestAppLifetimeKm = currentRecordedKm
            OdometerManager.syncLifetimeKm(currentRecordedKm)
            _fuelState.value = buildFuelState()
        }
    }
```

---

#### Target File 8: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt`
- **Location**: Lines 485–488
- **Purpose**: Synchronize calibration confirmation through `FuelRepository.calibrateOdometer` so both `OdometerManager` and `FuelRepository` update together.

**Before (Lines 485–488):**
```kotlin
            onConfirmCalibration = { newOdometerKm ->
                OdometerManager.setBaselineOdometer(context, newOdometerKm, totalLifetimeKm)
                showOdometerDialog = false
            }
```

**After (Lines 485–488):**
```kotlin
            onConfirmCalibration = { newOdometerKm ->
                com.antigravity.drivingreport.core.fuel.FuelRepository.getInstance(context).calibrateOdometer(newOdometerKm, totalLifetimeKm)
                showOdometerDialog = false
            }
```

---

## 5. Verification Method

### 5.1 Unit Test Specifications

Create `app/src/test/java/com/antigravity/drivingreport/OdometerDoubleCountingAndCalibrationTest.kt` covering both features:

1. **Test Case 1: `testActiveTripDistanceDoesNotDoubleCountHistoricalRollups`**
   - Given: A completed historical trip of 15.0 km (`status = COMPLETED`, `isQualifying = true`).
   - When: An active trip is underway with 3.5 km accumulated (`status = ACTIVE_TRIP`).
   - Then:
     - `historicalTrips.sumOf { it.distanceKm }` must equal `15.0 km`.
     - Active distance `if (isTripActive) currentTrip.distanceKm else 0.0` must equal `3.5 km`.
     - Total lifetime distance must equal `18.5 km` (not `22.0 km`).

2. **Test Case 2: `testCompletedTripDistanceDoesNotDoubleCountWithHistoricalList`**
   - Given: A trip completes with 10.0 km.
   - When: `TripRepository.onTripCompleted(summary)` is executed and the trip is inserted as `COMPLETED` in SQLite.
   - Then:
     - `_currentTrip.value.status` must be `TripStatus.IDLE` and `distanceKm` must be `0.0`.
     - `historicalTrips` now includes the 10.0 km trip.
     - Total lifetime calculation `historicalSum + activeDistance` must equal `10.0 km` (not `20.0 km`).

3. **Test Case 3: `testOdometerCalibrationBaselineSingleSourceOfTruth`**
   - Given: Recorded app lifetime distance is `250.0 km`.
   - When: Physical dashboard reading is calibrated to `12,500.0 km` via `FuelRepository.calibrateOdometer(12500.0, 250.0)`.
   - Then:
     - `OdometerManager.odometerConfig.value.baselineOdometerKm` must be `12500.0`.
     - `OdometerManager.odometerConfig.value.baselineLifetimeKmAtUpdate` must be `250.0`.
     - `FuelRepository.getEffectiveOdometer()` must return `12500.0`.
     - When vehicle moves to `265.0 km` lifetime distance, `FuelRepository.getEffectiveOdometer()` and `OdometerManager.calculateLiveOdometer(265.0)` must both return `12515.0 km`.

4. **Test Case 4: `testHistoricalQueryExcludesActiveAndNonQualifyingTrips`**
   - Given:
     - Trip 1: `status = 'ACTIVE_TRIP'`, `distanceMeters = 5000.0`, `isQualifying = 1`
     - Trip 2: `status = 'COMPLETED'`, `distanceMeters = 500.0`, `isQualifying = 0`
     - Trip 3: `status = 'COMPLETED'`, `distanceMeters = 12000.0`, `isQualifying = 1`
   - When: Query `getAllHistoricalTrips()` is executed.
   - Then: Only Trip 3 is returned. Trip 1 (active) and Trip 2 (non-qualifying) are strictly excluded.

### 5.2 Verification Commands
To independently verify the test suite:
```powershell
./gradlew testDebugUnitTest --tests "com.antigravity.drivingreport.OdometerDoubleCountingAndCalibrationTest"
./gradlew testDebugUnitTest --tests "com.antigravity.drivingreport.OdometerManagerTest"
```

### 5.3 Invalidation Conditions
The blueprint is invalidated if:
1. `_currentTrip.value` distance is accessed directly without checking whether `status` is an active driving state (`ACTIVE_TRIP`, `MOVEMENT_CANDIDATE`, `TEMPORARY_STOP`).
2. Any component reintroduces writes to `"baseline_odometer_bits"` or `"baseline_odometer"` in `driving_report_fuel_prefs`, reviving the dual baseline bug.
3. `TripDao.getAllHistoricalTrips()` includes `OR distanceMeters > 0` or `OR isQualifying = 1` without requiring `status = 'COMPLETED'`.
