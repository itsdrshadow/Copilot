# Milestone 1 Deep-Dive Architectural Investigation & Implementation Blueprint
## Features 5, 6, 7, and 10: Trip Lifecycle, Idle Watchdog & Geofence Sync, Time Delta Clamping, Foreign Key Sequencing, and Reboot Location Anchor Restoration

**Agent:** Milestone 1 Explorer 2 (`m1_exp2`)  
**Target Repository:** `d:\Car Launcher` (`com.antigravity.drivingreport`)  
**Date:** November 10, 2026  
**Scope:** Milestone 1 Features 5, 6, 7, and 10  
- **Feature 5:** Trip Idle Watchdog Geofence Sync (`DrivingTrackingService.kt`, `TripDetectionEngine.kt`, `AppSettingsManager.kt`, `TripGeofenceManager.kt`)
- **Feature 6:** Unclamped Time Delta Protection (`TripDetectionEngine.handleActiveTripMovement`)
- **Feature 7:** Foreign Key Constraint Sequencing (`DrivingTrackingService.kt`, `TripRepository.kt`, `AppDatabase.kt`)
- **Feature 10:** Mid-Trip Reboot First-Fix Distance Accumulation (`DrivingTrackingService.kt`, `TripDetectionEngine.kt`, `AppDatabase.kt`)

---

## 1. OBSERVATIONS

### 1.1 Feature 5: Trip Idle Watchdog Geofence Sync
1. **Hardcoded Architectural Override Disabling Geofence Control**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt:205`
   ```kotlin
   engine = TripDetectionEngine(...)
   engine.isGeofenceControlled = false
   ```
   - Fact: When `DrivingTrackingService` instantiates `TripDetectionEngine`, it unconditionally overwrites `isGeofenceControlled` with `false`.
2. **Unconditional 300-Second Idle Stop Watchdog Finalizing Trips Outside Geofences**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:121-134`
   ```kotlin
   fun processStationaryWatchdog(now: Long = System.currentTimeMillis()) {
       if (currentStatus == TripStatus.ACTIVE_TRIP) {
           val stationaryDuration = now - lastMovementTime
           if (stationaryDuration >= 15000L) {
               currentStatus = TripStatus.TEMPORARY_STOP
               temporaryStopStartTime = lastMovementTime
           }
       } else if (currentStatus == TripStatus.TEMPORARY_STOP && temporaryStopStartTime > 0L) {
           val stoppedSec = (now - temporaryStopStartTime) / 1000
           if (stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
               evaluateTripCompletion(now)
           }
       }
   }
   ```
   - Fact: `processStationaryWatchdog` runs every 1 second from `DrivingTrackingService.kt:277`. It does not inspect `isGeofenceControlled`. After 300 seconds (5 minutes) of continuous standstill at a traffic light, railway crossing, highway rest area, or roadside parking, `evaluateTripCompletion(now)` is invoked.
3. **Redundant 300-Second Idle Timeout in `processLocation`**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:295-305`
   ```kotlin
   TripStatus.TEMPORARY_STOP -> {
       val stoppedSec = (now - temporaryStopStartTime) / 1000
       if (effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
           stationaryDurationMs += (now - temporaryStopStartTime)
           currentStatus = TripStatus.ACTIVE_TRIP
           lastAcceptedLocation = location
           lastLocationTime = now
       } else if (stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
           evaluateTripCompletion(now)
       }
   }
   ```
   - Fact: Even if the stationary watchdog is paused or delayed, any incoming GPS fix arriving while `stoppedSec >= 300` triggers `evaluateTripCompletion(now)`.
4. **Architectural Specification Conflict with `GEOFENCE_TRIP_DESIGN.md`**:
   - File: `GEOFENCE_TRIP_DESIGN.md:58-61`
   > "Stopping outside a geofence (red traffic signals, drive-throughs, highway rest stops, roadside parking) accumulates `stationaryDurationMs` but **NEVER** terminates the trip."
   - Fact: Setting `isGeofenceControlled = false` directly violates Section 2.2 and Section 4 of the architectural specification, causing journeys to fragment prematurely.
5. **False Trip Initiation on Internal Movement Inside Geofences**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:267-289`
   ```kotlin
   TripStatus.IDLE -> {
       if (effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
           currentStatus = TripStatus.MOVEMENT_CANDIDATE
           candidateStartTime = now
           ...
       }
   }
   ```
   - Fact: When `isGeofenceControlled` is false, driving at $\ge 6.0\text{ km/h}$ for $> 15\text{ s}$ inside an origin geofence (e.g. driving across a home driveway or office parking structure) initiates a trip via `startNewTrip()`. When the vehicle subsequently crosses the outer geofence boundary, `GeofenceTripLifecycleEngine` triggers `startGeofenceTrip()`, generating duplicate trip starts.

---

### 1.2 Feature 6: Unclamped Time Delta Protection
1. **Unbounded Time Delta Calculation in `handleActiveTripMovement`**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:437-447`
   ```kotlin
   val dt = (now - lastLocationTime).coerceAtLeast(0)
   if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
       drivingDurationMs += dt
       lastMovementTime = now
   } else {
       stationaryDurationMs += dt
       if ((now - lastMovementTime) > 15000) {
           currentStatus = TripStatus.TEMPORARY_STOP
           temporaryStopStartTime = lastMovementTime
       }
   }
   ```
   - Fact: `dt` is only clamped at the lower bound (`coerceAtLeast(0)`). There is **zero upper bound clamping**.
2. **Impact on Driving Duration and Average Speed Calculation**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:456-463`
   ```kotlin
   val drivingHours = drivingDurationMs / 3600000.0
   val avgSpeed = if (drivingHours > 0.002) {
       (totalDistanceMeters / 1000.0) / drivingHours
   } else if (speedCount > 0) {
       speedSumKmh / speedCount
   } else {
       0.0
   }
   ```
   - Fact: If the vehicle enters a tunnel or underground structure for 5 minutes, or device sleeps/reboots for 45 seconds, or system clock adjusts forward by 30 minutes via NTP/cellular network sync:
     `now - lastLocationTime = 300,000 to 1,800,000 ms`.
     When the next GPS fix arrives while moving (`speedKmh > 2.5`), the entire 5 to 30 minutes is added to `drivingDurationMs` for a single 1-second step distance.
     This inflates `drivingHours` by hours and permanently collapses `avgSpeed` toward zero (e.g. 10 km driven over an artificially inflated 4 hours yields 2.5 km/h).
3. **Impact on Real-Time Fuel Burn Consumption**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:453`
   ```kotlin
   onLiveMovement?.invoke(stepDistanceMeters, speedKmh.toDouble(), dt)
   ```
   - Fact: `dt` is passed directly to `fuelRepository.consumeLiveFuel(deltaDistanceMeters, currentSpeedKmh, deltaDurationMs = dt)`. An unclamped `dt` causes `FuelAIEngine` to calculate and consume 30 minutes worth of fuel in a single 1-second iteration.

---

### 1.3 Feature 7: Foreign Key Constraint Sequencing
1. **Strict Foreign Key Constraint Defined on `TripPointEntity`**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/database/Entities.kt:51-58`
   ```kotlin
   @Entity(
       tableName = "trip_points",
       indices = [Index(value = ["tripId"]), Index(value = ["timestamp"])],
       foreignKeys = [
           ForeignKey(
               entity = TripEntity::class,
               parentColumns = ["id"],
               childColumns = ["tripId"],
               onDelete = ForeignKey.CASCADE
           )
       ]
   )
   data class TripPointEntity(...)
   ```
   - Fact: Room enforces referential integrity in SQLite. Any row inserted into `trip_points` MUST reference an existing `id` in `trips`.
2. **Unsynchronized Parallel Execution in `DrivingTrackingService`**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt:127-130`
   ```kotlin
   engine.startGeofenceTrip(startTime, loc, departureDistMeters)
   lastRecordedDistanceMeters = departureDistMeters
   tripRepository.attachDeparturePoints(startTime, departurePoints)
   tripRepository.updateTripOrigin(startTime, originName)
   ```
   - In `TripDetectionEngine.kt:344`:
     `engine.startGeofenceTrip(...)` invokes `onTripStarted(currentTripId)`.
   - In `DrivingTrackingService.kt:178`:
     `onTripStarted` callback invokes `tripRepository.onTripStarted(tripId)`.
   - In `TripRepository.kt:293-304`:
     ```kotlin
     fun onTripStarted(tripId: Long) {
         ...
         scope.launch {
             tripDao.insertTrip(TripEntity(id = tripId, startTime = tripId, ...))
         }
     }
     ```
   - In `TripRepository.kt:238-251`:
     ```kotlin
     fun attachDeparturePoints(tripId: Long, points: List<TripPoint>) {
         if (points.isEmpty()) return
         scope.launch {
             val entities = points.map { ... }
             tripDao.insertPoints(entities)
             ...
         }
     }
     ```
   - Fact: `onTripStarted` spawns a coroutine on `Dispatchers.IO` to insert `TripEntity`. Concurrently, `attachDeparturePoints` spawns an independent coroutine on `Dispatchers.IO` to insert `TripPointEntity` records.
   - Fact: `Dispatchers.IO` uses a multi-threaded thread pool. There is no synchronization or await mechanism between these two coroutines. If the thread running `tripDao.insertPoints(entities)` acquires SQLite before `tripDao.insertTrip` completes, SQLite immediately throws:
     `android.database.sqlite.SQLiteConstraintException: FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)`.

---

### 1.4 Feature 10: Mid-Trip Reboot First-Fix Distance Accumulation
1. **`restoreActiveTripState` Discards Coordinates and Leaves `lastAcceptedLocation` Null**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:353-368`
   ```kotlin
   fun restoreActiveTripState(
       tripId: Long,
       startTime: Long,
       distanceMeters: Double,
       drivingMs: Long,
       stationaryMs: Long
   ) {
       currentStatus = TripStatus.ACTIVE_TRIP
       currentTripId = tripId
       tripStartTime = startTime
       lastMovementTime = System.currentTimeMillis()
       lastLocationTime = System.currentTimeMillis()
       totalDistanceMeters = distanceMeters
       drivingDurationMs = drivingMs
       stationaryDurationMs = stationaryMs
   }
   ```
   - Fact: `restoreActiveTripState` does not accept coordinate parameters and does not initialize `lastAcceptedLocation`. `lastAcceptedLocation` remains `null`.
2. **First Post-Reboot Fix Movement Distance Calculation Bypassed**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:394-410`
   ```kotlin
   private fun handleActiveTripMovement(now: Long, location: Location, speedKmh: Float) {
       val prevLoc = lastAcceptedLocation
       var stepDistanceMeters = 0.0

       if (prevLoc != null) {
           val dist = prevLoc.distanceTo(location).toDouble()
           ...
           if (isGenuinelyMoving && impliedSpeedKmh < 220.0) {
               totalDistanceMeters += dist
               stepDistanceMeters = dist
           }
       }
   ```
   - Fact: Because `prevLoc == null`, lines 398–426 are skipped completely on the first fix.
3. **Warmup Gate Overwrites Restored Coordinates Before Movement Handler Runs**:
   - File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:156-189`
   ```kotlin
   if (!isGpsStabilized) {
       if (isAccurateFix) {
           if (firstAccurateFixTimeMs == 0L) {
               firstAccurateFixTimeMs = now
               lastAcceptedLocation = location
               lastLocationTime = now
           }
           val warmUpElapsed = now - firstAccurateFixTimeMs
           if (warmUpElapsed < GPS_WARMUP_STABILIZATION_MS) {
               lastAcceptedLocation = location
               ...
               return
           } else {
               isGpsStabilized = true
               lastAcceptedLocation = location
               ...
               return
           }
       }
   }
   ```
   - Fact: During the 3-second GPS warmup following a reboot, lines 160, 167, and 187 overwrite `lastAcceptedLocation` with the newly observed post-reboot GPS fix coordinates, accumulating zero distance.
   - Consequence: If a head unit reboots while driving at 60 km/h (e.g. 40-second reboot interval covering ~660 meters), that 660 meters is permanently discarded from the active trip's distance, corrupting odometer synchronization and trip records.

---

## 2. LOGIC CHAIN

```
[Observation 1.1: DrivingTrackingService hardcodes engine.isGeofenceControlled = false]
  │
  ├─► Step 1: TripDetectionEngine treats all trips as non-geofence governed.
  │            processStationaryWatchdog triggers evaluateTripCompletion(now) at 300s.
  │
  ├─► Step 2: Stops outside geofences (traffic signals, parking, drive-throughs) terminate
  │            the active trip prematurely, fragmenting single journeys into multiple fragments.
  │
  └─► Conclusion 1: engine.isGeofenceControlled must dynamically sync with geofenceManager
                    and AppSettingsManager. When geofence-controlled, 300s idle timeouts must NOT terminate trips.

[Observation 1.2: dt = (now - lastLocationTime).coerceAtLeast(0) lacks an upper clamp]
  │
  ├─► Step 3: Following a GPS gap, tunnel transit, sleep cycle, reboot, or NTP clock sync forward jump,
  │            (now - lastLocationTime) can be minutes or hours (e.g. 300,000ms to 7,200,000ms).
  │
  ├─► Step 4: When a fix arrives while moving, the entire multi-minute/hour delta is added to drivingDurationMs.
  │            Because avgSpeed = distance / drivingHours, average speed collapses to near 0 km/h.
  │
  └─► Conclusion 2: dt must be bounded by dt = (now - lastLocationTime).coerceIn(0L, MAX_STEP_DT_MS),
                    where MAX_STEP_DT_MS = 5000L.

[Observation 1.3: onTripStarted (insertTrip) and attachDeparturePoints (insertPoints) execute asynchronously on Dispatchers.IO]
  │
  ├─► Step 5: Room database enforces foreign key constraints from trip_points.tripId -> trips.id.
  │
  ├─► Step 6: When attachDeparturePoints schedules insertPoints, if SQLite executes it before insertTrip completes,
  │            SQLiteConstraintException is thrown and departure points are dropped.
  │
  └─► Conclusion 3: TripRepository must maintain activeTripInsertionJob: Job? on onTripStarted.
                    attachDeparturePoints and updateTripOrigin must await activeTripInsertionJob.join()
                    before executing dependent database operations.

[Observation 1.4: restoreActiveTripState leaves lastAcceptedLocation null; warmup gate overwrites pre-reboot coordinates]
  │
  ├─► Step 7: On reboot recovery, coordinates of the last accepted pre-reboot fix from SQLite are never passed to the engine.
  │
  ├─► Step 8: Even if passed, the 3-second GPS warmup loop unconditionally overwrites lastAcceptedLocation without calculating step distance.
  │
  └─► Conclusion 4: DrivingTrackingService must fetch the last recorded point for the active trip from SQLite,
                    pass it to restoreActiveTripState, preserve it as rebootAnchorLocation across warmup,
                    and bridge geodesic distance to the first stabilized post-reboot fix.
```

---

## 3. CAVEATS

1. **Strictly Read-Only Role**: In accordance with the Explorer role, no production source files have been modified. This report delivers exact, comprehensive, line-level code changes for Builder.
2. **Existing Unit Tests**: Running `.\gradlew.bat testDebugUnitTest` confirms that all 19 test suites currently pass (28 actionable tasks, 1 executed, 27 up-to-date, `BUILD SUCCESSFUL in 29s`). All proposed changes are non-destructive and backward-compatible with existing interfaces.
3. **Database Schema Version**: Adding an index-optimized query `@Query` method to `TripDao` does NOT alter the Room database schema (schema version remains v8). No Room database migration is required.
4. **GPS Accuracy During Tunnel & Signal Outage**: When bridging across a signal loss or reboot, distance accumulation must enforce accuracy $\le 25\text{m}$ on the recovering fix and validate implied velocity $< 220\text{ km/h}$ to prevent multipath noise injection.

---

## 4. CONCLUSION & IMPLEMENTATION BLUEPRINT

### 4.1 Feature 5 Implementation Plan: Trip Idle Watchdog Geofence Sync

#### Target File 1: `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`
- **Current Lines 205**:
  ```kotlin
  engine.isGeofenceControlled = false
  ```
- **Replacement Strategy**:
  Delete the hardcoded `engine.isGeofenceControlled = false`.
  Replace with dynamic reactive synchronization in `onCreate()` that binds `engine.isGeofenceControlled` to `geofenceManager.geofences` and `AppSettingsManager`:
  ```kotlin
  // Synchronize geofence control state with user geofences and settings
  serviceScope.launch {
      geofenceManager.geofences.collect { geofences ->
          val hasEnabled = geofences.any { it.isEnabled }
          engine.isGeofenceControlled = hasEnabled
      }
  }
  ```

#### Target File 2: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`
- **Current Lines 128–133 (`processStationaryWatchdog`)**:
  ```kotlin
  } else if (currentStatus == TripStatus.TEMPORARY_STOP && temporaryStopStartTime > 0L) {
      val stoppedSec = (now - temporaryStopStartTime) / 1000
      if (stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
          evaluateTripCompletion(now)
      }
  }
  ```
- **Target Replacement**:
  ```kotlin
  } else if (currentStatus == TripStatus.TEMPORARY_STOP && temporaryStopStartTime > 0L) {
      val stoppedSec = (now - temporaryStopStartTime) / 1000
      // In geofence-controlled mode, standstills outside geofences NEVER terminate trips
      if (!isGeofenceControlled && stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
          evaluateTripCompletion(now)
      }
  }
  ```

- **Current Lines 295–305 (`processLocation` under `TEMPORARY_STOP`)**:
  ```kotlin
  TripStatus.TEMPORARY_STOP -> {
      val stoppedSec = (now - temporaryStopStartTime) / 1000
      if (effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
          stationaryDurationMs += (now - temporaryStopStartTime)
          currentStatus = TripStatus.ACTIVE_TRIP
          lastAcceptedLocation = location
          lastLocationTime = now
      } else if (stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
          evaluateTripCompletion(now)
      }
  }
  ```
- **Target Replacement**:
  ```kotlin
  TripStatus.TEMPORARY_STOP -> {
      val stoppedSec = (now - temporaryStopStartTime) / 1000
      if (effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
          stationaryDurationMs += (now - temporaryStopStartTime)
          currentStatus = TripStatus.ACTIVE_TRIP
          lastAcceptedLocation = location
          lastLocationTime = now
      } else if (!isGeofenceControlled && stoppedSec >= IDLE_TRIP_END_TIMEOUT_SECONDS) {
          evaluateTripCompletion(now)
      }
  }
  ```

- **Current Lines 267–275 and 307–315 (`processLocation` under `IDLE` and `COMPLETED`)**:
  ```kotlin
  TripStatus.IDLE -> {
      if (effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
          currentStatus = TripStatus.MOVEMENT_CANDIDATE
          candidateStartTime = now
          lastMovementTime = now
          lastAcceptedLocation = location
          lastLocationTime = now
      }
  }
  ```
- **Target Replacement**:
  ```kotlin
  TripStatus.IDLE -> {
      // In geofence mode, trips ONLY start upon confirmed geofence departure
      if (!isGeofenceControlled && effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
          currentStatus = TripStatus.MOVEMENT_CANDIDATE
          candidateStartTime = now
          lastMovementTime = now
          lastAcceptedLocation = location
          lastLocationTime = now
      }
  }
  ```
  And under `TripStatus.STOP_CANDIDATE, TripStatus.COMPLETED, TripStatus.INTERRUPTED`:
  ```kotlin
  TripStatus.STOP_CANDIDATE, TripStatus.COMPLETED, TripStatus.INTERRUPTED -> {
      if (!isGeofenceControlled && effectiveSpeedKmh >= MOVEMENT_SPEED_THRESHOLD_KMH) {
          currentStatus = TripStatus.MOVEMENT_CANDIDATE
          candidateStartTime = now
          lastMovementTime = now
          lastAcceptedLocation = location
          lastLocationTime = now
      }
  }
  ```

---

### 4.2 Feature 6 Implementation Plan: Unclamped Time Delta Protection

#### Target File: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`
- **Add Constant to `Companion` (Line 72)**:
  ```kotlin
  companion object {
      ...
      const val MAX_STEP_DT_MS = 5000L // 5-second upper clamp on telemetry dt
  }
  ```
- **Modify `handleActiveTripMovement` (Lines 437–447)**:
  - **Before**:
    ```kotlin
    val dt = (now - lastLocationTime).coerceAtLeast(0)
    if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
        drivingDurationMs += dt
        lastMovementTime = now
    } else {
        stationaryDurationMs += dt
        if ((now - lastMovementTime) > 15000) {
            currentStatus = TripStatus.TEMPORARY_STOP
            temporaryStopStartTime = lastMovementTime
        }
    }
    ```
  - **After**:
    ```kotlin
    val dt = if (lastLocationTime > 0L) {
        (now - lastLocationTime).coerceIn(0L, MAX_STEP_DT_MS)
    } else {
        0L
    }

    if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
        drivingDurationMs += dt
        lastMovementTime = now
    } else {
        stationaryDurationMs += dt
        if ((now - lastMovementTime) > 15000) {
            currentStatus = TripStatus.TEMPORARY_STOP
            temporaryStopStartTime = lastMovementTime
        }
    }
    ```
- **Also Harden Derivative Calculation (Line 399)**:
  - **Before**:
    ```kotlin
    val timeDeltaSec = ((now - lastLocationTime) / 1000.0).coerceAtLeast(0.1)
    ```
  - **After**:
    ```kotlin
    val timeDeltaSec = if (lastLocationTime > 0L) {
        ((now - lastLocationTime) / 1000.0).coerceIn(0.1, 5.0)
    } else {
        1.0
    }
    ```

---

### 4.3 Feature 7 Implementation Plan: Foreign Key Constraint Sequencing

#### Target File 1: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt`
- **Add State Variable to `TripRepository` (around line 98)**:
  ```kotlin
  @Volatile
  private var activeTripInsertionJob: Job? = null
  ```

- **Update `onTripStarted` (Lines 280–305)**:
  - **Before**:
    ```kotlin
    fun onTripStarted(tripId: Long) {
        updateVisualLifecycle(TripVisualLifecycleEvent.STARTED, System.currentTimeMillis(), tripId)
        val summary = TripSummary(
            id = tripId,
            startTime = tripId,
            status = TripStatus.ACTIVE_TRIP
        )
        _currentTrip.value = summary
        synchronized(activeTrailPoints) {
            activeTrailPoints.clear()
        }
        _tripTrail.value = emptyList()

        scope.launch {
            tripDao.insertTrip(
                TripEntity(
                    id = tripId,
                    startTime = tripId,
                    startLocationName = "Start Location",
                    endLocationName = "",
                    status = TripStatus.ACTIVE_TRIP.name,
                    isQualifying = false
                )
            )
        }
    }
    ```
  - **After**:
    ```kotlin
    fun onTripStarted(tripId: Long): Job {
        updateVisualLifecycle(TripVisualLifecycleEvent.STARTED, System.currentTimeMillis(), tripId)
        val summary = TripSummary(
            id = tripId,
            startTime = tripId,
            status = TripStatus.ACTIVE_TRIP
        )
        _currentTrip.value = summary
        synchronized(activeTrailPoints) {
            activeTrailPoints.clear()
        }
        _tripTrail.value = emptyList()

        val job = scope.launch {
            tripDao.insertTrip(
                TripEntity(
                    id = tripId,
                    startTime = tripId,
                    startLocationName = "Start Location",
                    endLocationName = "",
                    status = TripStatus.ACTIVE_TRIP.name,
                    isQualifying = false
                )
            )
        }
        activeTripInsertionJob = job
        return job
    }
    ```

- **Update `attachDeparturePoints` (Lines 236–258)**:
  - **Before**:
    ```kotlin
    fun attachDeparturePoints(tripId: Long, points: List<TripPoint>) {
        if (points.isEmpty()) return
        scope.launch {
            val entities = points.map { pt ->
                TripPointEntity(
                    tripId = tripId,
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    altitude = pt.altitude,
                    speedKmh = pt.speedKmh,
                    bearing = pt.bearing,
                    accuracy = pt.accuracy,
                    timestamp = pt.timestamp
                )
            }
            tripDao.insertPoints(entities)
            val snapshot = synchronized(activeTrailPoints) {
                activeTrailPoints.addAll(0, points)
                ArrayList(activeTrailPoints)
            }
            _tripTrail.value = snapshot
        }
    }
    ```
  - **After**:
    ```kotlin
    fun attachDeparturePoints(tripId: Long, points: List<TripPoint>): Job {
        if (points.isEmpty()) return Job().apply { complete() }
        return scope.launch {
            // Strictly await parent TripEntity insertion in SQLite to prevent SQLiteConstraintException
            activeTripInsertionJob?.join()
            
            // Safety check: ensure parent trip row exists before inserting points
            val tripExists = tripDao.getTripById(tripId) != null
            if (!tripExists) {
                tripDao.insertTrip(
                    TripEntity(
                        id = tripId,
                        startTime = tripId,
                        startLocationName = "Trip Origin",
                        status = TripStatus.ACTIVE_TRIP.name
                    )
                )
            }

            val entities = points.map { pt ->
                TripPointEntity(
                    tripId = tripId,
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    altitude = pt.altitude,
                    speedKmh = pt.speedKmh,
                    bearing = pt.bearing,
                    accuracy = pt.accuracy,
                    timestamp = pt.timestamp
                )
            }
            tripDao.insertPoints(entities)
            val snapshot = synchronized(activeTrailPoints) {
                activeTrailPoints.addAll(0, points)
                ArrayList(activeTrailPoints)
            }
            _tripTrail.value = snapshot
        }
    }
    ```

- **Harden `updateTripOrigin` (Lines 220–225)**:
  ```kotlin
  fun updateTripOrigin(tripId: Long, originName: String) {
      if (originName.isBlank()) return
      scope.launch {
          activeTripInsertionJob?.join()
          val trip = tripDao.getTripById(tripId) ?: return@launch
          tripDao.updateTrip(trip.copy(startLocationName = originName))
      }
  }
  ```

---

### 4.4 Feature 10 Implementation Plan: Mid-Trip Reboot First-Fix Distance Accumulation

#### Target File 1: `app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt` (`TripDao`)
- **Add O(1) Indexed Query to `TripDao` (Line 38)**:
  ```kotlin
  @Query("SELECT * FROM trip_points WHERE tripId = :tripId ORDER BY timestamp DESC LIMIT 1")
  suspend fun getLastPointForTrip(tripId: Long): TripPointEntity?
  ```

#### Target File 2: `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`
- **Update Active Trip Restoration in `onCreate()` (Lines 143–157)**:
  - **Before**:
    ```kotlin
    serviceScope.launch {
        val db = com.antigravity.drivingreport.core.database.AppDatabase.getInstance(applicationContext)
        val activeTrip = db.tripDao().getActiveTrip()
        if (activeTrip != null && activeTrip.endTime <= 0L) {
            geofenceEngine.restoreActiveTrip(activeTrip.id, activeTrip.startTime)
            engine.restoreActiveTripState(
                tripId = activeTrip.id,
                startTime = activeTrip.startTime,
                distanceMeters = activeTrip.distanceMeters,
                drivingMs = activeTrip.drivingDurationMs,
                stationaryMs = activeTrip.stationaryDurationMs
            )
            lastRecordedDistanceMeters = activeTrip.distanceMeters
        }
    }
    ```
  - **After**:
    ```kotlin
    serviceScope.launch {
        val db = com.antigravity.drivingreport.core.database.AppDatabase.getInstance(applicationContext)
        val activeTrip = db.tripDao().getActiveTrip()
        if (activeTrip != null && activeTrip.endTime <= 0L) {
            geofenceEngine.restoreActiveTrip(activeTrip.id, activeTrip.startTime)

            // Retrieve last recorded GPS coordinate for the active trip to bridge distance across reboot
            val lastPt = db.tripDao().getLastPointForTrip(activeTrip.id)
            val anchorLoc = if (lastPt != null && lastPt.latitude != 0.0 && lastPt.longitude != 0.0) {
                Location("sqlite_reboot_anchor").apply {
                    latitude = lastPt.latitude
                    longitude = lastPt.longitude
                    altitude = lastPt.altitude
                    bearing = lastPt.bearing
                    time = lastPt.timestamp
                }
            } else {
                val (cachedLat, cachedLon) = com.antigravity.drivingreport.core.map.OfflineMapManager.getLastKnownPosition(applicationContext)
                if (cachedLat != 0.0 || cachedLon != 0.0) {
                    Location("pref_reboot_anchor").apply {
                        latitude = cachedLat
                        longitude = cachedLon
                        time = activeTrip.startTime
                    }
                } else null
            }

            engine.restoreActiveTripState(
                tripId = activeTrip.id,
                startTime = activeTrip.startTime,
                distanceMeters = activeTrip.distanceMeters,
                drivingMs = activeTrip.drivingDurationMs,
                stationaryMs = activeTrip.stationaryDurationMs,
                lastLocation = anchorLoc
            )
            lastRecordedDistanceMeters = activeTrip.distanceMeters
        }
    }
    ```

#### Target File 3: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`
- **Add Anchor Storage Variables**:
  ```kotlin
  private var rebootAnchorLocation: Location? = null
  ```
  In `reset()` (Line 74):
  ```kotlin
  rebootAnchorLocation = null
  ```

- **Update `restoreActiveTripState` (Lines 353–368)**:
  - **Before**:
    ```kotlin
    fun restoreActiveTripState(
        tripId: Long,
        startTime: Long,
        distanceMeters: Double,
        drivingMs: Long,
        stationaryMs: Long
    ) {
        currentStatus = TripStatus.ACTIVE_TRIP
        currentTripId = tripId
        tripStartTime = startTime
        lastMovementTime = System.currentTimeMillis()
        lastLocationTime = System.currentTimeMillis()
        totalDistanceMeters = distanceMeters
        drivingDurationMs = drivingMs
        stationaryDurationMs = stationaryMs
    }
    ```
  - **After**:
    ```kotlin
    fun restoreActiveTripState(
        tripId: Long,
        startTime: Long,
        distanceMeters: Double,
        drivingMs: Long,
        stationaryMs: Long,
        lastLocation: Location? = null
    ) {
        currentStatus = TripStatus.ACTIVE_TRIP
        currentTripId = tripId
        tripStartTime = startTime
        val now = System.currentTimeMillis()
        lastMovementTime = now
        lastLocationTime = if (lastLocation != null && lastLocation.time > 0L) lastLocation.time else now
        totalDistanceMeters = distanceMeters
        drivingDurationMs = drivingMs
        stationaryDurationMs = stationaryMs

        if (lastLocation != null) {
            currentAltitude = if (lastLocation.hasAltitude()) lastLocation.altitude else currentAltitude
            currentBearing = if (lastLocation.hasBearing()) lastLocation.bearing else currentBearing
            if (!isGpsStabilized) {
                rebootAnchorLocation = lastLocation
                lastAcceptedLocation = lastLocation
            } else {
                // If GPS already stabilized prior to SQLite query return:
                val curLoc = lastAcceptedLocation
                if (curLoc != null) {
                    val bridgeDist = lastLocation.distanceTo(curLoc).toDouble()
                    val timeDeltaSec = ((now - lastLocation.time) / 1000.0).coerceAtLeast(1.0)
                    val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6
                    if (bridgeDist >= 4.0 && impliedSpeedKmh < 220.0) {
                        totalDistanceMeters += bridgeDist
                        onLiveMovement?.invoke(bridgeDist, impliedSpeedKmh, 1000L)
                    }
                }
                lastAcceptedLocation = curLoc ?: lastLocation
            }
        }
    }
    ```

- **Update 3-Second GPS Warmup in `processLocation` (Lines 156–205)**:
  Ensure `rebootAnchorLocation` is preserved across the warmup window, and bridge distance is accumulated upon stabilization:
  ```kotlin
  if (!isGpsStabilized) {
      if (isAccurateFix) {
          if (firstAccurateFixTimeMs == 0L) {
              firstAccurateFixTimeMs = now
              if (rebootAnchorLocation == null) {
                  lastAcceptedLocation = location
              }
              lastLocationTime = now
          }
          val warmUpElapsed = now - firstAccurateFixTimeMs
          if (warmUpElapsed < GPS_WARMUP_STABILIZATION_MS) {
              // Inside 3-second warm-up window:
              // Settle telemetry without overwriting reboot anchor
              if (rebootAnchorLocation == null) {
                  lastAcceptedLocation = location
              }
              lastLocationTime = now
              currentAltitude = if (location.hasAltitude()) location.altitude else currentAltitude
              currentBearing = if (location.hasBearing()) location.bearing else currentBearing

              val warmUpPoint = TripPoint(
                  tripId = currentTripId,
                  latitude = location.latitude,
                  longitude = location.longitude,
                  altitude = currentAltitude,
                  speedKmh = 0f,
                  bearing = currentBearing,
                  accuracy = accuracy,
                  timestamp = now
              )
              onPointReceived(warmUpPoint)
              return
          } else {
              // 3 seconds of solid GPS lock confirmed!
              isGpsStabilized = true
              
              // Bridge geodesic distance from pre-reboot anchor to the first stable post-reboot fix
              val anchor = rebootAnchorLocation
              if (anchor != null) {
                  rebootAnchorLocation = null
                  if (currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP) {
                      val bridgeDist = anchor.distanceTo(location).toDouble()
                      val timeDeltaSec = ((now - anchor.time) / 1000.0).coerceAtLeast(1.0)
                      val impliedSpeedKmh = (bridgeDist / timeDeltaSec) * 3.6
                      if (bridgeDist >= 4.0 && impliedSpeedKmh < 220.0) {
                          totalDistanceMeters += bridgeDist
                          onLiveMovement?.invoke(bridgeDist, impliedSpeedKmh, 1000L)
                      }
                  }
              }

              lastAcceptedLocation = location
              lastLocationTime = now
              currentAltitude = if (location.hasAltitude()) location.altitude else currentAltitude
              currentBearing = if (location.hasBearing()) location.bearing else currentBearing

              val initialStablePoint = TripPoint(
                  tripId = currentTripId,
                  latitude = location.latitude,
                  longitude = location.longitude,
                  altitude = currentAltitude,
                  speedKmh = 0f,
                  bearing = currentBearing,
                  accuracy = accuracy,
                  timestamp = now
              )
              onPointReceived(initialStablePoint)
              return
          }
      } else {
          return
      }
  }
  ```

---

## 5. VERIFICATION METHOD

### 5.1 Project Test Command Execution
To independently verify the test suite:
```powershell
.\gradlew.bat testDebugUnitTest
```
- **Observed Result**: 19 test suites executed, `BUILD SUCCESSFUL in 29s`. All 94 existing unit tests pass.

### 5.2 Verification of Features 5, 6, 7, and 10
1. **Feature 5 (Geofence Sync & Idle Watchdog)**:
   - Verify `DrivingTrackingService.kt` does not contain `engine.isGeofenceControlled = false`.
   - Verify `engine.isGeofenceControlled` is updated dynamically via `geofenceManager.geofences.collect`.
   - Verify `processStationaryWatchdog` in `TripDetectionEngine.kt` does NOT call `evaluateTripCompletion` when `isGeofenceControlled == true`.
2. **Feature 6 (Unclamped Time Delta Protection)**:
   - Verify `dt = (now - lastLocationTime).coerceIn(0L, MAX_STEP_DT_MS)` in `handleActiveTripMovement`.
   - Verify that simulated time jumps (e.g. `now - lastLocationTime = 3_600_000L`) accumulate at most `5000L` to `drivingDurationMs`.
3. **Feature 7 (Foreign Key Sequencing)**:
   - Verify `onTripStarted` returns a `Job` and stores `activeTripInsertionJob`.
   - Verify `attachDeparturePoints` calls `activeTripInsertionJob?.join()` before calling `tripDao.insertPoints(entities)`.
   - Verify `SQLiteConstraintException` cannot be triggered under rapid concurrent service startup.
4. **Feature 10 (Mid-Trip Reboot First-Fix Distance Accumulation)**:
   - Verify `getLastPointForTrip(activeTrip.id)` query retrieves the last recorded fix before reboot.
   - Verify `restoreActiveTripState` receives and stores `rebootAnchorLocation`.
   - Verify geodesic bridge distance is added to `totalDistanceMeters` upon post-reboot GPS stabilization.

### 5.3 Invalidation Conditions
This investigation and its blueprint would be invalidated if:
1. `DrivingTrackingService.kt` re-introduces a hardcoded `engine.isGeofenceControlled = false` or decouples geofences from trip completion.
2. `TripDetectionEngine.kt` allows `dt` to remain unbounded without an upper clamp.
3. `attachDeparturePoints` is executed without joining `activeTripInsertionJob`.
4. `restoreActiveTripState` omits pre-reboot coordinates, leaving `lastAcceptedLocation` unbridged.
