# Milestone 1 Technical Design & Handoff Report: Features 1, 2, 3, and 4
**Agent**: Explorer 1 (`m1_exp1`)  
**Scope**: 
- Feature 1: GPS Filtering at Ingestion Layer (`DrivingTrackingService.kt`, `GpsLocationFilter.kt`)
- Feature 2: Service Listener Deduplication (`DrivingTrackingService.kt`)
- Feature 3: Tunnel & Signal Loss Geodesic Bridging (`TripDetectionEngine.kt`)
- Feature 4: Speedometer Dropout Graceful Decay (`SpeedometerCluster.kt`, `DashboardScreen.kt`, `DrivingTrackingService.kt`)  
**Target Repository**: `d:\Car Launcher`  
**Date**: November 10, 2026  

---

## 1. Observation

Direct code observations from the repository:

### Feature 1: GPS Filtering at Ingestion Layer
1. **Unfiltered Ingestion in `DrivingTrackingService.kt`**:
   - In `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt:60-70`:
     ```kotlin
     private val fusedCallback = object : LocationCallback() {
         override fun onLocationResult(result: LocationResult) {
             lastFusedFixTimeMs = android.os.SystemClock.elapsedRealtime()
             for (location in result.locations) {
                 if (::geofenceEngine.isInitialized) {
                     geofenceEngine.processLocation(location)
                 }
                 engine.processLocation(location)
             }
         }
     }
     ```
   - In `DrivingTrackingService.kt:478-489`:
     ```kotlin
     override fun onLocationChanged(location: Location) {
         val timeSinceFused = android.os.SystemClock.elapsedRealtime() - lastFusedFixTimeMs
         if (timeSinceFused < 3500L) {
             return
         }
         if (::geofenceEngine.isInitialized) {
             geofenceEngine.processLocation(location)
         }
         engine.processLocation(location)
     }
     ```
   - In `DrivingTrackingService.kt:420-445`, cached fixes from `locationManager` and `fusedLocationClient` are also passed directly to `engine.processLocation(...)`.
   - Observation: Raw GPS locations bypass `GpsLocationFilter` completely. Coordinates flow directly into `TripDetectionEngine` and SQLite (`TripPointDao`), causing stationary jitter (<2.5m) and multipath teleportation spikes to be recorded in the persistent trip trail.
2. **`GpsLocationFilter.kt` Spike Filter Accuracy Loophole**:
   - In `app/src/main/java/com/antigravity/drivingreport/core/map/GpsLocationFilter.kt:76-81`:
     ```kotlin
     val impliedSpeedKmh = (distanceM / dtSeconds) * 3.6
     if (impliedSpeedKmh > MAX_VALID_SPEED_KMH && rawAccuracyMeters > 15.0f) {
         // Reject sudden high-speed spike if accuracy is poor
         return previous.copy(timestamp = timestamp)
     }
     ```
   - Observation: When a GPS multipath jump occurs (e.g., jumping 5 km in 1 second, implying 18,000 km/h), if the GPS chipset claims `rawAccuracyMeters <= 15.0f`, the condition evaluates to false and the impossible glitch is accepted.

---

### Feature 2: Service Listener Deduplication
3. **Repeated Callback Registrations Without Deregistration**:
   - In `DrivingTrackingService.kt:97-106`:
     ```kotlin
     override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
         if (intent?.action == "ACTION_RESET_TELEMETRY") {
             if (::engine.isInitialized) engine.reset()
             if (::geofenceEngine.isInitialized) geofenceEngine.reset()
             lastRecordedDistanceMeters = 0.0
             return START_STICKY
         }
         startLocationUpdates()
         return START_STICKY
     }
     ```
   - In `DrivingTrackingService.kt:375`, `onCreate()` also calls `startLocationUpdates()`.
   - In `DrivingTrackingService.kt:418-475`, `startLocationUpdates()` calls:
     ```kotlin
     fusedLocationClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())
     locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 1.0f, this, Looper.getMainLooper())
     locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 5.0f, this, Looper.getMainLooper())
     ```
   - Observation: When the service starts, `onCreate()` and `onStartCommand()` run consecutively, calling `startLocationUpdates()` twice. Any subsequent start intent (from `BootReceiver` handling both `LOCKED_BOOT_COMPLETED` and `BOOT_COMPLETED`, or `MainActivity` resuming) registers duplicate listeners without invoking `removeLocationUpdates` or `removeUpdates`.
4. **Duplicate Initial Cached Fix Dispatch**:
   - In `DrivingTrackingService.kt:420-445`:
     ```kotlin
     val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
     val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
     val lastPass = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
     val bestInitial = lastGps ?: lastNet ?: lastPass
     if (bestInitial != null) {
         ...
         if (ageMs < 5000L) {
             engine.processLocation(bestInitial)
         }
     }

     fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
         if (loc != null) {
             ...
             if (ageMs < 5000L) {
                 engine.processLocation(loc)
             }
         }
     }
     ```
   - Observation: Both `locationManager` and `fusedLocationClient` return the exact same cached position. Both independently pass it to `engine.processLocation(...)` within milliseconds. With repeated `startLocationUpdates()` calls, this dispatch is repeated multiple times.

---

### Feature 3: Tunnel & Signal Loss Geodesic Bridging
5. **Dropped Distance on GPS Signal Recovery**:
   - In `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt:147-184`:
     ```kotlin
     // Detect GPS signal loss / gap (> 15s) while driving to re-anchor smoothly
     if (lastLocationTime > 0 && (now - lastLocationTime) > 15000L && isGpsStabilized) {
         isGpsStabilized = false
         firstAccurateFixTimeMs = 0L
     }

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
                 lastLocationTime = now
                 ...
                 return
             } else {
                 isGpsStabilized = true
                 lastAcceptedLocation = location
                 lastLocationTime = now
                 ...
                 return
             }
         }
     }
     ```
   - Observation: When vehicle loses signal for >15s (e.g. 2 km tunnel transit), `isGpsStabilized` resets. Upon acquiring the recovery fix at the tunnel exit, `lastAcceptedLocation` is immediately overwritten with the new exit coordinate (lines 160, 167, 187). Straight-line geodesic distance between the entrance anchor and exit anchor is NEVER computed or accumulated into `totalDistanceMeters`. The entire tunnel distance is permanently lost.
6. **Unclamped Time Delta Distortion**:
   - In `TripDetectionEngine.kt:437-447`:
     ```kotlin
     val dt = (now - lastLocationTime).coerceAtLeast(0)
     if (speedKmh > STOP_SPEED_THRESHOLD_KMH) {
         drivingDurationMs += dt
         lastMovementTime = now
     }
     ```
   - Observation: `dt` has no upper clamp. When recovering from signal loss, the entire gap duration (which may include stationary or offline time) is added in a single tick to `drivingDurationMs`, corrupting average speed calculations.

---

### Feature 4: Speedometer Dropout Graceful Decay
7. **Abrupt 0 km/h Snap in `SpeedometerCluster.kt`**:
   - In `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt:61-68`:
     ```kotlin
     val now = System.currentTimeMillis()
     val isGpsValid = latestPoint != null &&
             latestPoint.latitude != 0.0 &&
             latestPoint.longitude != 0.0 &&
             latestPoint.accuracy < 60f &&
             (now - latestPoint.timestamp) < 5000L

     val rawSpeed = if (isGpsValid) speedKmh.coerceAtLeast(0f) else 0f
     ```
   - Observation: Cruising at 80 km/h, if a GPS fix is delayed by 5001ms, `isGpsValid` drops to `false` and `rawSpeed` instantly snaps to 0 km/h, causing the needle/digits to collapse and rebound abruptly when signal returns.
8. **Upstream Premature Timeout Interference**:
   - In `DrivingTrackingService.kt:270-276`:
     ```kotlin
     val elapsed = now - lastPointTimestamp
     val lastPt = latestEmittedPoint
     if (elapsed > 1800L && lastPt != null && lastPt.speedKmh > 0f) {
         val zeroPoint = lastPt.copy(speedKmh = 0f, timestamp = now)
         latestEmittedPoint = zeroPoint
         tripRepository.onLocationReceived(zeroPoint)
     }
     ```
   - In `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt:65-72`:
     ```kotlin
     LaunchedEffect(latestPoint) {
         isSpeedTimedOut = false
         if (latestPoint != null && latestPoint.speedKmh > 0f) {
             kotlinx.coroutines.delay(4500L)
             isSpeedTimedOut = true
         }
     }
     val currentSpeed = if (isSpeedTimedOut || latestPoint == null) 0f else latestPoint.speedKmh
     ```
   - Observation: Three competing watchdog timeouts choke speed to 0 km/h: `DrivingTrackingService` chokes it at 1.8s, `DashboardScreen` chokes it at 4.5s, and `SpeedometerCluster` chokes it at 5.0s.

---

## 2. Logic Chain

1. **Chain for Feature 1 (GPS Ingestion Filtering)**:
   - Observations 1 & 2 establish that `DrivingTrackingService` passes raw `Location` fixes to `TripDetectionEngine` and `GeofenceTripLifecycleEngine`, while `GpsLocationFilter` is only used inside `LiveMapView`.
   - Observation 2 demonstrates that `GpsLocationFilter` fails to reject >240 km/h spikes if `rawAccuracyMeters <= 15.0f`.
   - Therefore, passing all incoming locations through `GpsLocationFilter` in `DrivingTrackingService` before dispatching to the engines and database eliminates stationary jitter (<2.5m) and multipath spikes (>240 km/h) across the entire application.
   - Removing the `rawAccuracyMeters > 15.0f` condition ensures that any impossible speed jump (>240 km/h) is rejected unconditionally.

2. **Chain for Feature 2 (Service Listener Deduplication)**:
   - Observation 3 proves that `startLocationUpdates()` is invoked repeatedly without listener removal or active-state guards.
   - Observation 4 proves that both `locationManager` and `fusedLocationClient` dispatch redundant initial cached fixes.
   - Therefore, introducing `isLocationUpdatesActive`, an explicit `stopLocationUpdates()` cleanup, and a single-execution gate (`initialCachedFixProcessed`) eliminates duplicate listener registrations, prevents memory leaks, and ensures exactly one valid cached fix is processed during startup.

3. **Chain for Feature 3 (Tunnel Geodesic Bridging)**:
   - Observation 5 proves that when GPS signal is lost for >15s during an active trip, the 3-second warmup gate overwrites `lastAcceptedLocation` with the exit fix, discarding the distance traversed through the tunnel.
   - Observation 6 proves that `dt` is unclamped, corrupting `drivingDurationMs`.
   - Therefore, caching `tunnelEntryLocation` and `tunnelEntryTimeMs` when an outage occurs, and computing straight-line geodesic distance (`Location.distanceBetween`) when an accurate recovery fix arrives, accurately bridges tunnel transit distance in `totalDistanceMeters` and notifies `onLiveMovement`.
   - Clamping `dt` to $\le 5000\text{ms}$ protects duration and average speed calculations from clock synchronization jumps.

4. **Chain for Feature 4 (Speedometer Decay)**:
   - Observation 7 proves that `SpeedometerCluster` abruptly snaps to 0 km/h after 5s.
   - Observation 8 reveals that `DrivingTrackingService` (1.8s) and `DashboardScreen` (4.5s) prematurely zero out speed before the 5-second cluster threshold is reached.
   - Therefore, implementing a two-phase decay curve (coasting deceleration from 2s–5s, exponential decay from 5s–10s) in `SpeedometerCluster`, and removing/adjusting upstream premature snaps (in `DrivingTrackingService` and `DashboardScreen`), ensures smooth, realistic speedometer behavior during temporary signal dropouts.

---

## 3. Caveats

1. **Hardware GPS Latency vs Indoor Simulation**: In a vehicle driving through physical tunnels, GPS receiver recovery time varies (3s to 8s depending on satellite constellation reacquisition). The 3-second warmup gate plus $\le 25\text{m}$ accuracy gate accommodates this variance.
2. **Scope Boundary**: Features 5–10 of Milestone 1 (such as geofence idle watchdog sync, foreign key sequencing, and multi-day duration) are analyzed by sibling explorers (`m1_exp2`, `m1_exp3`). Changes here are strictly scoped to Features 1, 2, 3, and 4.
3. **No Direct Source Changes**: In compliance with the explorer role, no production source files have been modified. This report delivers exact, turn-key specifications for the implementation agent.

---

## 4. Conclusion & Detailed Code Modification Strategy

### 4.1 Feature 1: GPS Filtering at Ingestion Layer

#### Target Files:
- `app/src/main/java/com/antigravity/drivingreport/core/map/GpsLocationFilter.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`

#### Exact Modifications:

**File 1: `GpsLocationFilter.kt` (lines 76–82)**
*Before:*
```kotlin
        val impliedSpeedKmh = (distanceM / dtSeconds) * 3.6
        if (impliedSpeedKmh > MAX_VALID_SPEED_KMH && rawAccuracyMeters > 15.0f) {
            // Reject sudden high-speed spike if accuracy is poor
            return previous.copy(timestamp = timestamp)
        }
```
*After:*
```kotlin
        val impliedSpeedKmh = (distanceM / dtSeconds) * 3.6
        if (impliedSpeedKmh > MAX_VALID_SPEED_KMH) {
            // Reject impossible speed spike (>240 km/h) unconditionally
            return previous.copy(timestamp = timestamp)
        }
```

**File 2: `DrivingTrackingService.kt`**
- Add member:
  ```kotlin
  private val gpsFilter = com.antigravity.drivingreport.core.map.GpsLocationFilter()
  ```
- Add centralized filtering method:
  ```kotlin
  private fun filterAndDispatchLocation(rawLocation: Location) {
      if (rawLocation.latitude == 0.0 && rawLocation.longitude == 0.0) return

      val rawSpeedKmh = if (rawLocation.hasSpeed()) rawLocation.speed * 3.6f else 0f
      val rawBearing = if (rawLocation.hasBearing()) rawLocation.bearing else 0f
      val rawAccuracy = if (rawLocation.hasAccuracy()) rawLocation.accuracy else 5.0f
      val timestamp = if (rawLocation.time > 0L) rawLocation.time else System.currentTimeMillis()

      val filtered = gpsFilter.filter(
          rawLat = rawLocation.latitude,
          rawLon = rawLocation.longitude,
          rawBearing = rawBearing,
          rawSpeedKmh = rawSpeedKmh,
          rawAccuracyMeters = rawAccuracy,
          timestamp = timestamp
      )

      val filteredLocation = Location(rawLocation).apply {
          latitude = filtered.latitude
          longitude = filtered.longitude
          bearing = filtered.bearing
          speed = filtered.speedKmh / 3.6f
          accuracy = filtered.accuracyMeters
          time = filtered.timestamp
      }

      if (::geofenceEngine.isInitialized) {
          geofenceEngine.processLocation(filteredLocation)
      }
      engine.processLocation(filteredLocation)
  }
  ```
- Replace direct `engine.processLocation(location)` calls in:
  - `fusedCallback` (lines 60–70)
  - `onLocationChanged` (lines 478–489)
  - cached location processing (lines 420–445)
- Reset filter on reset:
  - In `onStartCommand` under `"ACTION_RESET_TELEMETRY"`: `gpsFilter.reset()`
  - In `onDestroy()`: `gpsFilter.reset()`

---

### 4.2 Feature 2: Service Listener Deduplication & Initial Cached Fix Gating

#### Target File:
- `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`

#### Exact Modifications:
1. Add state tracking fields:
   ```kotlin
   private var isLocationUpdatesActive: Boolean = false
   private var initialCachedFixProcessed: Boolean = false
   private var lastCachedFixTimestamp: Long = 0L
   ```
2. Add safe unregister method `stopLocationUpdates()`:
   ```kotlin
   private fun stopLocationUpdates() {
       if (!isLocationUpdatesActive) return
       try {
           fusedLocationClient.removeLocationUpdates(fusedCallback)
           locationManager.removeUpdates(this)
       } catch (e: Exception) {
           e.printStackTrace()
       } finally {
           isLocationUpdatesActive = false
       }
   }
   ```
3. Update `startLocationUpdates()` (lines 418–475):
   ```kotlin
   @SuppressLint("MissingPermission")
   private fun startLocationUpdates() {
       if (isLocationUpdatesActive) {
           return
       }
       stopLocationUpdates()

       try {
           if (!initialCachedFixProcessed) {
               processInitialCachedLocation()
           }

           val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
               .setMinUpdateIntervalMillis(500L)
               .setMinUpdateDistanceMeters(0.5f)
               .build()
           fusedLocationClient.requestLocationUpdates(request, fusedCallback, Looper.getMainLooper())

           if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
               locationManager.requestLocationUpdates(
                   LocationManager.GPS_PROVIDER,
                   1000L,
                   1.0f,
                   this,
                   Looper.getMainLooper()
               )
           }
           if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
               locationManager.requestLocationUpdates(
                   LocationManager.NETWORK_PROVIDER,
                   2000L,
                   5.0f,
                   this,
                   Looper.getMainLooper()
               )
           }
           isLocationUpdatesActive = true
       } catch (e: SecurityException) {
           e.printStackTrace()
           isLocationUpdatesActive = false
       }
   }
   ```
4. Extract single-execution cached fix handler:
   ```kotlin
   @SuppressLint("MissingPermission")
   private fun processInitialCachedLocation() {
       initialCachedFixProcessed = true
       val now = System.currentTimeMillis()

       // Prefer Fused Location, fall back to LocationManager if absent
       fusedLocationClient.lastLocation.addOnSuccessListener { fusedLoc ->
           if (fusedLoc != null && (now - fusedLoc.time) < 5000L) {
               lastCachedFixTimestamp = fusedLoc.time
               com.antigravity.drivingreport.core.map.OfflineMapManager.saveLastKnownPosition(
                   applicationContext, fusedLoc.latitude, fusedLoc.longitude
               )
               filterAndDispatchLocation(fusedLoc)
           } else {
               queryLocationManagerCachedFix(now)
           }
       }.addOnFailureListener {
           queryLocationManagerCachedFix(now)
       }
   }

   @SuppressLint("MissingPermission")
   private fun queryLocationManagerCachedFix(now: Long) {
       if (lastCachedFixTimestamp > 0L) return
       val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
       val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
       val lastPass = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
       val bestInitial = lastGps ?: lastNet ?: lastPass

       if (bestInitial != null && (now - bestInitial.time) < 5000L) {
           lastCachedFixTimestamp = bestInitial.time
           com.antigravity.drivingreport.core.map.OfflineMapManager.saveLastKnownPosition(
               applicationContext, bestInitial.latitude, bestInitial.longitude
           )
           filterAndDispatchLocation(bestInitial)
       }
   }
   ```
5. In `onCreate()` line 375: Remove redundant `startLocationUpdates()` call (handled by `onStartCommand`).
6. In `onDestroy()` lines 511–516: Call `stopLocationUpdates()`.

---

### 4.3 Feature 3: Tunnel & Signal Loss Geodesic Bridging

#### Target File:
- `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`

#### Exact Modifications:
1. Add state fields:
   ```kotlin
   // Tunnel & Signal Loss Bridging Anchor
   private var tunnelEntryLocation: Location? = null
   private var tunnelEntryTimeMs: Long = 0L
   private var isOutageBridgingPending: Boolean = false
   ```
2. In `processLocation()` (lines 146–150):
   *Before:*
   ```kotlin
   // Detect GPS signal loss / gap (> 15s) while driving to re-anchor smoothly
   if (lastLocationTime > 0 && (now - lastLocationTime) > 15000L && isGpsStabilized) {
       isGpsStabilized = false
       firstAccurateFixTimeMs = 0L
   }
   ```
   *After:*
   ```kotlin
   // Detect GPS signal loss / gap (> 15s) while driving to bridge distance smoothly
   if (lastLocationTime > 0 && (now - lastLocationTime) > 15000L && isGpsStabilized) {
       if (currentStatus == TripStatus.ACTIVE_TRIP || currentStatus == TripStatus.TEMPORARY_STOP) {
           tunnelEntryLocation = lastAcceptedLocation
           tunnelEntryTimeMs = lastLocationTime
           isOutageBridgingPending = true
       }
       isGpsStabilized = false
       firstAccurateFixTimeMs = 0L
   }
   ```
3. Geodesic Bridging Execution on Stable Fix (at line 185, when `isGpsStabilized = true`):
   ```kotlin
   // Check if recovering from a signal outage while on an active trip
   if (isOutageBridgingPending && tunnelEntryLocation != null) {
       val entryLoc = tunnelEntryLocation!!
       val recoveryLoc = location
       val results = FloatArray(1)
       Location.distanceBetween(
           entryLoc.latitude, entryLoc.longitude,
           recoveryLoc.latitude, recoveryLoc.longitude,
           results
       )
       val bridgeDistMeters = results[0].toDouble()
       val outageDurationSec = ((now - tunnelEntryTimeMs) / 1000.0).coerceAtLeast(1.0)
       val impliedSpeedKmh = (bridgeDistMeters / outageDurationSec) * 3.6

       // Interface Contract: accuracy <= 25m, plausible vehicle speed (< 220 km/h)
       if (accuracy <= 25.0f && bridgeDistMeters >= 10.0 && impliedSpeedKmh in 2.5..220.0) {
           totalDistanceMeters += bridgeDistMeters
           val bridgeDurationMs = (outageDurationSec * 1000.0).toLong().coerceIn(1000L, 600000L)
           drivingDurationMs += bridgeDurationMs
           onLiveMovement?.invoke(bridgeDistMeters, impliedSpeedKmh, bridgeDurationMs)
       }

       tunnelEntryLocation = null
       tunnelEntryTimeMs = 0L
       isOutageBridgingPending = false
   }
   ```
4. In `handleActiveTripMovement` (line 437): Clamp time delta `dt`:
   *Before:*
   ```kotlin
   val dt = (now - lastLocationTime).coerceAtLeast(0)
   ```
   *After:*
   ```kotlin
   val dt = (now - lastLocationTime).coerceIn(0L, 5000L)
   ```
5. Clear bridging anchor in `reset()`, `startNewTrip()`, `startGeofenceTrip()`, and `evaluateTripCompletion()`:
   ```kotlin
   tunnelEntryLocation = null
   tunnelEntryTimeMs = 0L
   isOutageBridgingPending = false
   ```

---

### 4.4 Feature 4: Speedometer Dropout Graceful Decay

#### Target Files:
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`

#### Exact Modifications:

**File 1: `SpeedometerCluster.kt` (lines 61–78)**
*Before:*
```kotlin
    val now = System.currentTimeMillis()
    val isGpsValid = latestPoint != null &&
            latestPoint.latitude != 0.0 &&
            latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f &&
            (now - latestPoint.timestamp) < 5000L

    val rawSpeed = if (isGpsValid) speedKmh.coerceAtLeast(0f) else 0f
    val speedLimit = speedLimitInfo.speedLimitKmh

    val animatedSpeed by animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PremiumSpeedInterpolation"
    )
```
*After:*
```kotlin
    var lastValidSpeedKmh by remember { mutableFloatStateOf(0f) }
    var lastValidSpeedTimeMs by remember { mutableLongStateOf(0L) }
    var tickerTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val now = tickerTimeMs
    val isGpsPointFresh = latestPoint != null &&
            latestPoint.latitude != 0.0 &&
            latestPoint.longitude != 0.0 &&
            latestPoint.accuracy < 60f

    // Continuously latch latest genuine speed reading
    if (isGpsPointFresh && (now - latestPoint!!.timestamp) < 2000L && speedKmh >= 0f) {
        lastValidSpeedKmh = speedKmh
        lastValidSpeedTimeMs = latestPoint.timestamp
    }

    val gapMs = if (lastValidSpeedTimeMs > 0L) (now - lastValidSpeedTimeMs).coerceAtLeast(0L) else 0L

    // Smooth Multi-Stage Decay Model:
    // 0 - 2000ms: Maintain last speed (absorbs nominal 1-2s GPS jitter)
    // 2000 - 5000ms: Natural aerodynamic coasting deceleration (-2.0 km/h per second)
    // 5000 - 10000ms: Smooth quadratic decay to 0 km/h at 10 seconds
    val rawSpeed = when {
        gapMs <= 2000L -> lastValidSpeedKmh
        gapMs in 2001L..5000L -> {
            val coastingSec = (gapMs - 2000L) / 1000f
            (lastValidSpeedKmh - coastingSec * 2.0f).coerceAtLeast(0f)
        }
        gapMs in 5001L..10000L -> {
            val baseCoastingSpeed = (lastValidSpeedKmh - 6.0f).coerceAtLeast(0f)
            val progress = (gapMs - 5000L) / 5000f // 0.0 to 1.0
            val decayFactor = (1f - progress) * (1f - progress)
            baseCoastingSpeed * decayFactor
        }
        else -> 0f
    }

    // Decay Animation Driver: updates tickerTimeMs every 100ms while decaying to 0
    LaunchedEffect(gapMs > 2000L && rawSpeed > 0f) {
        if (gapMs > 2000L && rawSpeed > 0f) {
            while (true) {
                kotlinx.coroutines.delay(100L)
                tickerTimeMs = System.currentTimeMillis()
                val currentGap = (tickerTimeMs - lastValidSpeedTimeMs).coerceAtLeast(0L)
                if (currentGap > 10000L) break
            }
        }
    }

    val speedLimit = speedLimitInfo.speedLimitKmh

    val animatedSpeed by animateFloatAsState(
        targetValue = rawSpeed,
        animationSpec = tween(durationMillis = 200, easing = LinearEasing),
        label = "PremiumSpeedInterpolation"
    )
```

**File 2: `DrivingTrackingService.kt` (lines 270–276)**
*Before:*
```kotlin
    val now = System.currentTimeMillis()
    val elapsed = now - lastPointTimestamp
    val lastPt = latestEmittedPoint
    if (elapsed > 1800L && lastPt != null && lastPt.speedKmh > 0f) {
        val zeroPoint = lastPt.copy(speedKmh = 0f, timestamp = now)
        latestEmittedPoint = zeroPoint
        tripRepository.onLocationReceived(zeroPoint)
    }
```
*After:*
```kotlin
    val now = System.currentTimeMillis()
    val elapsed = now - lastPointTimestamp
    val lastPt = latestEmittedPoint
    // Only synthesize zero-speed after full 10-second confirmed standstill
    // Allows UI SpeedometerCluster to execute graceful deceleration curve
    if (elapsed > 10000L && lastPt != null && lastPt.speedKmh > 0f) {
        val zeroPoint = lastPt.copy(speedKmh = 0f, timestamp = now)
        latestEmittedPoint = zeroPoint
        tripRepository.onLocationReceived(zeroPoint)
    }
```

**File 3: `DashboardScreen.kt` (lines 65–73)**
*Before:*
```kotlin
    var isSpeedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(latestPoint) {
        isSpeedTimedOut = false
        if (latestPoint != null && latestPoint.speedKmh > 0f) {
            kotlinx.coroutines.delay(4500L)
            isSpeedTimedOut = true
        }
    }
    val currentSpeed = if (isSpeedTimedOut || latestPoint == null) 0f else latestPoint.speedKmh
```
*After:*
```kotlin
    var isSpeedTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(latestPoint) {
        isSpeedTimedOut = false
        if (latestPoint != null && latestPoint.speedKmh > 0f) {
            kotlinx.coroutines.delay(10000L)
            isSpeedTimedOut = true
        }
    }
    val currentSpeed = if (isSpeedTimedOut || latestPoint == null) 0f else latestPoint.speedKmh
```

---

## 5. Verification Method

### 5.1 Verification Commands
1. **Execute Debug Unit Test Suite**:
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   *Expected Result*: All existing 94 unit tests pass without errors.
2. **Execute Targeted Subsystem Tests**:
   ```powershell
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.GpsConcurrencyAndArbitrationTest"
   .\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.FuelCalculationConsistencyTest"
   ```

### 5.2 Recommended New Unit Tests for Implementer (`worker_m1`):
1. **`GpsLocationFilterTest.kt`**:
   - `testStationaryJitterRejection_lessThan2_5Meters()`: Feed points wandering 1.2m at 0.5 km/h; assert output coordinates stay frozen.
   - `testMultipathSpikeRejection_exceeding240Kmh()`: Feed a 5 km jump in 1s with reported accuracy 10m; assert spike is rejected and previous state retained.
2. **`DrivingTrackingServiceDeduplicationTest.kt`**:
   - Verify repeated calls to `startLocationUpdates()` do not duplicate listener registrations or emit multiple initial cached fixes.
3. **`TunnelBridgingTest.kt`**:
   - Simulate active trip, simulate 45s tunnel outage (>15s), feed recovery fix 1.2 km away with 15m accuracy.
   - Assert `totalDistanceMeters` increases by 1200m (geodesic distance).
   - Assert `drivingDurationMs` increases by 45s.
4. **`SpeedometerDecayTest.kt`**:
   - Verify speed decays progressively at 2s, 5s, 8s, and reaches 0 at 10s without snapping.

### 5.3 Invalidation Conditions
- If `DrivingTrackingService` continues directly passing raw `Location` fixes to `engine.processLocation()` without calling `GpsLocationFilter`, Feature 1 is invalidated.
- If `startLocationUpdates()` lacks `isLocationUpdatesActive` or continues running both Fused and LocationManager cached fixes without deduplication, Feature 2 is invalidated.
- If `TripDetectionEngine` clears `lastAcceptedLocation` during warmup without geodesic straight-line distance accumulation, Feature 3 is invalidated.
- If `SpeedometerCluster` or upstream watchdogs still force speed to 0 within 5 seconds, Feature 4 is invalidated.
