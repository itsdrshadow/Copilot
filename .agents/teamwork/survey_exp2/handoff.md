# Survey & Deep-Dive Technical Audit Report: Subsystems 4, 5, and 6

**Author:** Survey Explorer 2 (`survey_exp2`)  
**Target Subsystems:**
- Subsystem 4: Odometer updates and historical records
- Subsystem 5: Fuel gauge, remaining fuel, and estimated range
- Subsystem 6: Offline map functionality  
**Target Codebase:** `d:\Car Launcher` (`com.antigravity.drivingreport`)  
**Investigation Mode:** Strict Read-Only Comprehensive Audit & Survey  
**Date:** 2026-11-10  

---

## 1. Observation

### 1.1 Architecture & Tech Stack Mapping
- **Platform & Language:** Android (Kotlin, minSdk 26, targetSdk 30, compileSdk 34, JVM target 17).
- **UI Framework:** Jetpack Compose (Material3 + Custom Automotive Cockpit Design System).
- **Database & Storage:** Android Room v8 (`AppDatabase`, SQLite WAL), Android `SharedPreferences` (with 64-bit IEEE 754 bit-exact storage), and JSON snapshot backups to permanent external storage (`PersistentDataManager`).
- **Mapping & Geodesy:** Osmdroid 6.1.18, Google Hybrid Satellite tile CDN (`mt0..mt3.google.com`) and ArcGIS Satellite fallback, SQLite permanent tile cache (`cache.db`).
- **Telemetry & Location:** FusedLocationProviderClient + Android LocationManager, custom `TripDetectionEngine`, `GpsLocationFilter`, `GeofenceTripLifecycleEngine`.

### 1.2 Key File Locations Across the 3 Subsystems
| Subsystem | Core Component | File Path | Primary Responsibilities |
|---|---|---|---|
| **4. Odometer & History** | `OdometerManager` | `app/src/main/java/com/antigravity/drivingreport/core/odometer/OdometerManager.kt` | Singleton managing baseline odometer, verified delta, live odometer calculation, 64-bit IEEE 754 raw bit persistence. |
| **4. Odometer & History** | `PersistentDataManager` | `app/src/main/java/com/antigravity/drivingreport/core/database/PersistentDataManager.kt` | Mirrors odometer calibrations, fuel records, and trip history to external permanent JSON storage. |
| **4. Odometer & History** | `TripRepository` & `TripDao` | `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt`<br>`app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt` | Distance accumulation, SQLite persistence, active trip caching, and trip queries (`getAllHistoricalTrips`). |
| **4. Odometer & History** | `OdometerCard` & `DashboardScreen` | `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt`<br>`app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt` | UI rendering of live odometer, calibration prompt, and lifetime distance / duration rollups. |
| **5. Fuel Gauge & Range** | `FuelRepository` | `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt` | Orchestrates fuel domain state, refuel transactions, live kinematic fuel burn, DTE calculation, and MID calibration. |
| **5. Fuel Gauge & Range** | `FuelStateEstimator` | `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/FuelStateEstimator.kt` | Decimal-safe continuous balance, 3-point bounds (`nominal`, `conservative`, `upper`), physical capacity clamping. |
| **5. Fuel Gauge & Range** | `AdaptiveMileageEngine` | `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/AdaptiveMileageEngine.kt` | Statistical partial refill calculation, IQR outlier rejection, exponential recency weighting, City vs Highway split. |
| **5. Fuel Gauge & Range** | `FuelGaugeCalibrationEngine` | `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/FuelGaugeCalibrationEngine.kt` | Non-linear 10-bar LED curve mapping, monotonicity enforcement, 60s rolling median slosh/jitter filter. |
| **5. Fuel Gauge & Range** | `MissedEntryIntelligence` | `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/MissedEntryIntelligence.kt` | Detects unlogged refills when observed gauge contradicts calculated physical fuel state by >= 4.0 L. |
| **5. Fuel Gauge & Range** | `FuelTankCard` | `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/FuelTankCard.kt` | 10-bar LED MID cluster, remaining volume, percentage, DTE range, and average mileage readout. |
| **6. Offline Maps** | `OfflineMapManager` | `app/src/main/java/com/antigravity/drivingreport/core/map/OfflineMapManager.kt` | OSMDroid configuration, 10-year cache retention, Google Hybrid Satellite & ArcGIS tile sources, regional bounding box cache. |
| **6. Offline Maps** | `IndiaOfflineMapManager` | `app/src/main/java/com/antigravity/drivingreport/core/map/IndiaOfflineMapManager.kt` | 6 tiered India regional satellite packs, 6-worker concurrent downloader, keep-alive connections, auto-pause/resume. |
| **6. Offline Maps** | `LiveMapView` | `app/src/main/java/com/antigravity/drivingreport/feature/maps/LiveMapView.kt` | Jetpack Compose `AndroidView(MapView)`, `SmoothMapNavigator` (60 FPS camera interpolation, multi-pass concentric colored trail), rotation gestures. |
| **6. Offline Maps** | `IndiaOfflineMapDialog` | `app/src/main/java/com/antigravity/drivingreport/feature/maps/IndiaOfflineMapDialog.kt` | Dialog UI for regional pack download, storage sizing, and server update check. |
| **6. Offline Maps** | `GpsLocationFilter` | `app/src/main/java/com/antigravity/drivingreport/core/map/GpsLocationFilter.kt` | Low-speed jitter dead-zone (< 2.5m stationary lock), multipath spike rejection (> 240 km/h), compass heading fusion. |

---

### 1.3 Direct Source Code Observations & Exact Line Citations

#### Observation 1: Double-Counting of Active and Completed Trips in Lifetime Distance & Odometer
In `MainActivity.kt:201-203`:
```kotlin
val recordedDistanceKm = remember(qualifyingTrips, currentTrip.distanceKm) {
    qualifyingTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
}
```
In `DashboardScreen.kt:75-77`:
```kotlin
val totalLifetimeKm = remember(allTrips, currentTrip.distanceKm) {
    allTrips.sumOf { it.distanceMeters } / 1000.0 + currentTrip.distanceKm
}
```
In `TripRepository.kt:97`:
```kotlin
val qualifyingTrips = tripDao.getAllHistoricalTrips()
```
In `AppDatabase.kt:24-25` (`TripDao`):
```kotlin
@Query("SELECT * FROM trips WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0 ORDER BY startTime DESC")
fun getAllHistoricalTrips(): Flow<List<TripEntity>>
```
In `TripRepository.kt:341-359` (`onTripUpdated`):
```kotlin
tripDao.updateTrip(
    TripEntity(
        id = summary.id,
        startTime = summary.startTime,
        endTime = summary.endTime,
        distanceMeters = summary.distanceMeters,
        ...
        status = summary.status.name,
        isQualifying = summary.distanceMeters >= 1000.0
    )
)
```
In `TripRepository.kt:365-367` (`onTripCompleted`):
```kotlin
fun onTripCompleted(summary: TripSummary) {
    updateVisualLifecycle(TripVisualLifecycleEvent.COMPLETED, System.currentTimeMillis(), summary.id)
    _currentTrip.value = summary
...
```
- **Direct Fact:** When an active trip flushes to the database (every 4s or 20 points), its SQLite record has `distanceMeters > 0`. Because `getAllHistoricalTrips()` selects `WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0`, the active trip row is included in `qualifyingTrips` and `allTrips`. Summing `allTrips.sumOf { it.distanceMeters }` includes the active trip, and then adds `+ currentTrip.distanceKm` a second time.
- **Direct Fact:** When `onTripCompleted(summary)` is called, the database marks the trip `COMPLETED`, but `_currentTrip.value = summary` is retained in memory with its non-zero distance. The completed trip is counted once in `allTrips` and a second time in `currentTrip.distanceKm` until a completely new trip starts.

#### Observation 2: Decoupled Baseline Semantic Discrepancy Between FuelRepository and OdometerManager
In `FuelRepository.kt:346-353`:
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
```
In `OdometerManager.kt:63-78`:
```kotlin
fun setBaselineOdometer(context: Context, readingKm: Double, currentTotalLifetimeKm: Double = 0.0) {
    val safeReading = readingKm.coerceAtLeast(0.0)
    val safeLifetime = currentTotalLifetimeKm.coerceAtLeast(0.0)
    ...
    _odometerConfig.value = OdometerState(
        baselineOdometerKm = safeReading,
        baselineLifetimeKmAtUpdate = safeLifetime,
        isManuallyVerified = true
    )
}
```
In `PersistentDataManager.kt:111-118`:
```kotlin
} else if (root.has("prefs")) {
    val p = root.getJSONObject("prefs")
    if (p.has("baseline_odometer")) {
        val legacyOdo = p.getDouble("baseline_odometer")
        if (legacyOdo > 0.0) {
            com.antigravity.drivingreport.core.odometer.OdometerManager.setBaselineOdometer(context, legacyOdo, 0.0)
        }
    }
}
```
- **Direct Fact:** `OdometerManager` treats `baselineOdometerKm` as the literal dashboard gauge reading (e.g. 50,000 km) and computes live distance as `baselineOdometerKm + (currentTotalLifetimeKm - baselineLifetimeKmAtUpdate)`. Conversely, `FuelRepository` defines `baselineOdometerKm` as the subtracted offset `newOdometerReadingKm - currentRecordedKm` (e.g. 49,900 km).
- **Direct Fact:** If an automatic restore executes from legacy `prefs.baseline_odometer`, it takes the subtracted offset (49,900) and feeds it into `OdometerManager.setBaselineOdometer(context, legacyOdo, 0.0)`. The driver's calibrated odometer is permanently rolled backward by `currentRecordedKm`.

#### Observation 3: Mid-Trip Reboot First-Fix Distance Discard
In `TripDetectionEngine.kt:353-368` and `394-398`:
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
```kotlin
private fun handleActiveTripMovement(now: Long, location: Location, speedKmh: Float) {
    val prevLoc = lastAcceptedLocation
    var stepDistanceMeters = 0.0

    if (prevLoc != null) {
        ...
```
- **Direct Fact:** On reboot recovery, `restoreActiveTripState` restores cumulative distance and duration, but leaves `lastAcceptedLocation` set to `null`. When the first GPS fix arrives after reboot, `prevLoc == null`, so the step distance from the pre-reboot position to the post-reboot position is never calculated or accumulated.

#### Observation 4: DTE Range Oscillation Caused by 1-Second Instantaneous Mileage Contamination
In `FuelRepository.kt:238-245`:
```kotlin
// For DTE range estimation: if empirical mileage is not yet available, use baselineVehicleMileage as prior
val rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage
val effectiveRangeMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage

val snapshot = estimator.createSnapshot(effectiveRangeMileage)
val range = snapshot.estimatedRangeKm
val minRange = snapshot.rangeMinKm
val maxRange = snapshot.rangeMaxKm
```
In `FuelRepository.kt:75`:
```kotlin
private var liveInstantMileage: Double = baselineVehicleMileage
```
In `FuelRepository.kt:634-642` (`consumeLiveFuel`):
```kotlin
val result = aiEngine.calculateLiveConsumption(
    deltaDistanceMeters = deltaDistanceMeters,
    currentSpeedKmh = currentSpeedKmh,
    deltaDurationMs = deltaDurationMs,
    currentRemainingLiters = estimator.nominalFuelLiters,
    designCapacityLiters = effectiveMax
)

liveInstantMileage = result.liveInstantMileageKmpl
```
- **Direct Fact:** `liveInstantMileage` is computed on every 1-second GPS tick by `FuelAIEngine.calculateLiveConsumption()`, reflecting immediate throttle position, acceleration penalties ($0.45\times$ to $4.5\times$), and idle stationary status ($0.0\text{ km/L}$).
- **Direct Fact:** Because `liveInstantMileage` is initialized to `baselineVehicleMileage` (> 0.0) and continuously overwritten with 1-second instant efficiency, `effectiveRangeMileage` is ALWAYS assigned to `liveInstantMileage` rather than `rangeMileage` (the learned average consumption).
- **Direct Fact:** While driving, accelerating at $1.5\text{ m/s}^2$ drops `liveInstantMileage` to $6.0\text{ km/L}$, immediately slashing DTE range from $450\text{ km}$ to $150\text{ km}$. Coasting downhill or slowing down spikes `liveInstantMileage` to $35\text{ km/L}$, skyrocketing DTE range to $875\text{ km}$. At stoplights, `liveInstantMileage` drops to $0.0$, causing DTE to abruptly jump back to `rangeMileage`.

#### Observation 5: 10-Segment Instrument Cluster Fuel Gauge Freezes During Continuous Driving
In `FuelStateEstimator.kt:137-159` (`applyConsumption`):
```kotlin
fun applyConsumption(
    consumedLiters: Double,
    odometerKm: Double,
    @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
) {
    val cleanBurn = FuelMath.roundFuel(consumedLiters.coerceAtLeast(0.0))

    // Deduct from nominal, conservative, and upper bounds
    nominalFuelLiters = FuelMath.roundFuel((nominalFuelLiters - cleanBurn).coerceAtLeast(0.0))
    conservativeFuelLiters = FuelMath.roundFuel((conservativeFuelLiters - cleanBurn).coerceAtLeast(0.0))
    upperFuelLiters = FuelMath.roundFuel((upperFuelLiters - cleanBurn).coerceAtLeast(0.0))
...
    if (nominalFuelLiters <= 0.0) {
        currentExplanation = "Tank depleted (clamped at 0.0 L)"
    }
}
```
In `FuelRepository.kt:247`:
```kotlin
val midBar = estimator.currentGaugeBar.coerceIn(0, 10)
```
In `FuelTankCard.kt:252-254`:
```kotlin
for (i in 1..10) {
    val isSegmentActive = i <= fuelState.currentMidBar
```
- **Direct Fact:** In `FuelStateEstimator`, `currentGaugeBar` is only set in `initializeBaseline()`, `applyRefuel()`, `applyTripConsumption()`, and `applyManualAdjustment()`. It is NEVER updated in `applyConsumption()`.
- **Direct Fact:** `FuelRepository.consumeLiveFuel` invokes `estimator.applyConsumption(consumedLiters, currentOdo)`. As fuel is consumed over hundreds of kilometers, `nominalFuelLiters` drops, but `currentGaugeBar` remains completely static.
- **Direct Fact:** `buildFuelState()` extracts `estimator.currentGaugeBar` directly without calculating `gaugeCalibration.getBarForLiters(balance)`. Consequently, the 10-segment LED cluster visualizer on the dashboard remains permanently frozen at whatever bar level was last set.

#### Observation 6: Disconnected Gauge Jitter & Slosh Median Filter
In `FuelGaugeCalibrationEngine.kt:105-121`:
```kotlin
fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
    val clamped = newBar.coerceIn(0, 10)
    recentReadings.add(Pair(timestamp, clamped))

    while (recentReadings.isNotEmpty() && (timestamp - recentReadings.first().first > 60000L)) {
        recentReadings.removeAt(0)
    }

    if (recentReadings.size < 3) {
        return clamped
    }

    val sortedBars = recentReadings.map { it.second }.sorted()
    return sortedBars[sortedBars.size / 2]
}
```
In `FuelRepository.kt:373-385`:
```kotlin
fun syncMidAndOdometer(newOdometerKm: Double, newMidBar: Int, recordedLifetimeKm: Double = latestAppLifetimeKm) {
    scope.launch {
        stateMutex.withLock {
            latestAppLifetimeKm = recordedLifetimeKm
            val effectiveOdo = if (newOdometerKm > 0.0) newOdometerKm else getEffectiveOdometer()

            // Check for missed refuel event
            val detection = missedEntryIntel.evaluateObservedGauge(
                observedGaugePoints = newMidBar,
                estimator = estimator,
                currentOdometerKm = effectiveOdo
            )
```
- **Direct Fact:** `filterSloshAndSlope` is implemented in `FuelGaugeCalibrationEngine.kt`, but `git grep` confirms it is ONLY invoked in `VehicleJourneyAndFuelEngineValidationTest.kt`. In production code, `syncMidAndOdometer` receives raw `newMidBar` and passes it immediately into `evaluateObservedGauge` and `applyManualAdjustment` without slosh filtering.

#### Observation 7: Missing Conservative Reserve Buffer in Low Fuel DTE
In `FUEL_ENGINE_DESIGN.md:92`:
> "In fuel reserve ($F_{\text{nom}} < 7.0\text{ L}$ or $F_{\text{nom}} / C_{\text{design}} < 0.15$), DTE switches to a conservative 85th percentile multiplier ($M_{\text{eff}} \times 0.85$) so drivers are not stranded by optimistic predictions."
In `FuelStateEstimator.kt:221-227`:
```kotlin
fun createSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot {
    val safeMileage = estimatedMileageKmpl.coerceIn(5.0, 45.0)

    val nominalRange = FuelMath.roundDistance(nominalFuelLiters * safeMileage)
    val minRange = FuelMath.roundDistance(conservativeFuelLiters * safeMileage * 0.90)
    val maxRange = FuelMath.roundDistance(upperFuelLiters * safeMileage * 1.05)
```
- **Direct Fact:** `createSnapshot` multiplies `nominalFuelLiters * safeMileage` with no check for reserve volume ($F_{\text{nom}} < 7.0\text{ L}$ or fraction $< 0.15$). The 0.85 conservative reserve safety multiplier specified in design is missing from code.

#### Observation 8: Zero Image Validation in Offline Map Tile Fetching Leading to 10-Year Permanent Cache Corruption
In `IndiaOfflineMapManager.kt:543-550`:
```kotlin
val tileBytes = fetchTileFast(serverPrefix, zoom, x, y)
if (tileBytes != null && tileBytes.isNotEmpty()) {
    synchronized(tileWriterLock) {
        tileWriter.saveFile(tileSource, tileIndex, tileBytes.inputStream(), null)
    }
    val count = downloadedCount.incrementAndGet()
```
In `IndiaOfflineMapManager.kt:609-614` (`fetchTileFast`):
```kotlin
val code = conn.responseCode
if (code == 200) {
    val bytes = conn.inputStream.use { it.readBytes() }
    keepAlive = true
    return bytes
}
```
- **Direct Fact:** `fetchTileFast` treats any response with HTTP status 200 and length > 0 as valid tile image bytes. If a captive portal, Wi-Fi login redirect, HTTP proxy, or CDN throttle returns an HTML error page with status 200, the HTML payload is written directly to `cache.db` via `tileWriter.saveFile()`.
- **Direct Fact:** In `OfflineMapManager.kt:126`, `config.expirationExtendedDuration = 1000L * 60 * 60 * 24 * 365 * 10` (10 years). Once written, `tileWriter.exists(tileSource, tileIndex)` returns `true`, permanently locking corrupted, un-renderable non-image data into SQLite.

#### Observation 9: Unclosed `SqlTileWriter` Resources and Multi-Instance Concurrency
In `IndiaOfflineMapManager.kt:463`:
```kotlin
val tileWriter = SqlTileWriter()
```
- **Direct Fact:** `downloadRegionTiles` instantiates `SqlTileWriter()` but never calls `tileWriter.onDetach()` or closes the underlying SQLite helper in a `finally` block. Calling `startDownload("ALL_INDIA")` iterates over 6 regions, leaving 6 unclosed SQLite helper connections.
- **Direct Fact:** `MapView` on the main UI thread simultaneously runs its own internal `SqlTileWriter` pointing to the same `cache.db` file, introducing risk of database contention.

#### Observation 10: Activity Context Memory Leak in Network Callback
In `IndiaOfflineMapManager.kt:254-284`:
```kotlin
private fun registerNetworkCallback(context: Context) {
    if (isConnectivityRegistered) return
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
...
```
In `LiveMapView.kt:746-747`:
```kotlin
LaunchedEffect(Unit) {
    OfflineMapManager.initOfflineConfiguration(context)
    IndiaOfflineMapManager.init(context)
}
```
- **Direct Fact:** `LiveMapView` passes `LocalContext.current` (which is `MainActivity`) into `IndiaOfflineMapManager.init(context)`. The `ConnectivityManager.NetworkCallback` instance captures `context` inside its `resumeDownload(context)` callback. Because `IndiaOfflineMapManager` is a singleton `object`, when `MainActivity` is recreated, the old `MainActivity` instance is permanently leaked.

---

## 2. Logic Chain

### 2.1 Subsystem 4 Logic Chain: Odometer Updates & Historical Records
1. From Observation 1, `TripDao.getAllHistoricalTrips()` query explicitly selects all rows with `distanceMeters > 0`.
2. When a trip is actively driving, `TripRepository.onTripUpdated` persists the active trip's distance to SQLite every 4s / 20 points.
3. Therefore, `getAllHistoricalTrips()` returns the active trip as part of `qualifyingTrips` and `allTrips`.
4. In both `MainActivity.kt:202` and `DashboardScreen.kt:76`, total distance is computed as:
   $$\text{Total} = \left(\sum_{t \in \text{allTrips}} t.\text{distanceMeters}\right) + \text{currentTrip.distanceKm}$$
5. Since the active trip is already included in $\sum t.\text{distanceMeters}$, adding $\text{currentTrip.distanceKm}$ double-counts the active trip distance in total lifetime kilometers.
6. When `onTripCompleted(summary)` is called, the completed trip is marked in SQLite, but `_currentTrip.value = summary` is preserved with its completed distance. Thus, the completed trip remains double-counted until a new trip starts.
7. Furthermore, by Observation 2, `FuelRepository` defines `baselineOdometerKm = newReading - lifetime`, whereas `OdometerManager` defines `baselineOdometerKm = newReading`. A restore from legacy `prefs` passes the subtracted value to `OdometerManager`, corrupting the calibrated baseline.

### 2.2 Subsystem 5 Logic Chain: Fuel Gauge, Remaining Fuel & Estimated Range
1. From Observation 4, `effectiveRangeMileage` is assigned to `liveInstantMileage` whenever `liveInstantMileage > 0.0`.
2. `liveInstantMileage` is updated on every 1-second GPS tick by `FuelAIEngine` and swings violently from 4 km/L (acceleration) to 45 km/L (fuel cut-off deceleration).
3. The DTE range formula in `createSnapshot` calculates $\text{Range} = \text{NominalFuel} \times \text{effectiveRangeMileage}$.
4. Therefore, the estimated range displayed on the dashboard oscillates wildly every second while driving, jumping by hundreds of kilometers instead of providing a steady, reliable estimate based on average consumption.
5. From Observation 5, while driving, `consumeLiveFuel` calls `estimator.applyConsumption(consumedLiters, currentOdo)`.
6. `applyConsumption` decrements `nominalFuelLiters`, but never updates `currentGaugeBar`.
7. `buildFuelState` extracts `estimator.currentGaugeBar`, and `FuelTankCard` lights up $i \le \text{currentMidBar}$.
8. Therefore, the 10-bar LED cluster visualizer on the dashboard remains frozen at its initial level and never reflects fuel burned during continuous driving.
9. From Observation 6, `filterSloshAndSlope` is never called in production code, leaving the gauge vulnerable to road slope and float sloshing.
10. From Observation 7, the conservative 0.85 reserve range multiplier is omitted from `createSnapshot`.

### 2.3 Subsystem 6 Logic Chain: Offline Map Functionality
1. From Observation 8, `fetchTileFast` accepts any HTTP 200 response with length > 0 without validating whether the payload is a valid JPEG/PNG image.
2. In public Wi-Fi or captive portal environments, HTTP 200 responses often contain HTML login pages or redirect scripts.
3. These non-image payloads are saved into `cache.db` with a 10-year expiration timestamp.
4. Subsequent calls check `tileWriter.exists()`, which returns `true` because the database record exists.
5. Consequently, the affected geographic tiles are permanently corrupted in SQLite and will never render or reload.
6. From Observation 9, `downloadRegionTiles` creates an unclosed `SqlTileWriter` on every regional pack, leaking database helpers and increasing SQLite lock contention with `MapView`.
7. From Observation 10, passing `MainActivity` to the singleton network callback leaks the activity on configuration change.

---

## 3. Caveats
1. **Network Availability for Tile Download:** Full offline satellite pack downloading across all of India requires ~10.5 GB of storage and active broadband connection; tests in unit test suites mock or isolate network operations.
2. **OBD-II / CAN Hardware Float Sender:** The codebase currently simulates float gauge observations via manual 10-bar calibration (`syncMidAndOdometer`) and missed entry intelligence (`MissedEntryIntelligence`), rather than direct CAN bus hardware integration.
3. **Hardware Accelerometer / Gyroscope:** G-Force and acceleration calculations in `TripDetectionEngine` are mathematically derived from GPS speed deltas and time intervals ($a = \Delta v / \Delta t$) rather than physical IMU sensors.

---

## 4. Conclusion
1. **Subsystem 4 (Odometer & History):** The 64-bit IEEE 754 bit-exact storage in `OdometerManager` provides pristine precision above 100,000 km. However, a critical **double-counting bug** exists in `MainActivity.kt` and `DashboardScreen.kt`, where active trips (and recently completed trips) are summed from both SQLite (`getAllHistoricalTrips()`) and `currentTrip.distanceKm`. In addition, a semantic discrepancy exists between `FuelRepository` and `OdometerManager` baseline definitions, and mid-trip reboot drops first-fix distance.
2. **Subsystem 5 (Fuel Gauge & Range):** The underlying mathematical algorithms in `FuelMath`, `AdaptiveMileageEngine`, and `FuelStateEstimator` are mathematically sound. However, **DTE estimated range oscillates wildly** because it binds to 1-second `liveInstantMileage` instead of learned average consumption. Furthermore, the **10-segment LED cluster gauge freezes** while driving because `applyConsumption` never updates `currentGaugeBar`, and the 60-second slosh/jitter filter is disconnected dead code.
3. **Subsystem 6 (Offline Maps):** The offline mapping engine features excellent architecture (concentric colored turnaround trails, lookahead camera offset, permanent SQLite caching). However, it contains a critical **tile corruption vulnerability** (zero image validation on HTTP 200, caching captive portal HTML for 10 years), unclosed `SqlTileWriter` instances, and an Activity context leak in `IndiaOfflineMapManager`.

---

## 5. Verification Method

### 5.1 Project Test Command Execution
To independently verify the test suite:
```powershell
.\gradlew testReleaseUnitTest
```
- **Observed Result:** 19 test suites executed, 56 tasks (9 executed, 47 up-to-date), `BUILD SUCCESSFUL in 4m 46s`.
- All unit tests in `OdometerManagerTest`, `OdometerPersistenceTest`, `AdaptiveFuelIntelligenceTest`, `FuelCalculationConsistencyTest`, `SatelliteMapAndMultiPassTrailTest`, etc. pass.

### 5.2 Specific Code Files to Inspect for Audit Verification
1. **Odometer Double-Counting:** `MainActivity.kt:201-203`, `DashboardScreen.kt:75-77`, `TripDao.kt:24-25`, `TripRepository.kt:341-367`.
2. **DTE Range Oscillation:** `FuelRepository.kt:238-245`, `FuelRepository.kt:642`.
3. **Fuel Gauge Freeze:** `FuelStateEstimator.kt:137-159`, `FuelRepository.kt:247`, `FuelTankCard.kt:252-254`.
4. **Disconnected Slosh Filter:** `FuelGaugeCalibrationEngine.kt:105-121`, `FuelRepository.kt:373-385`.
5. **Tile Corruption Vulnerability:** `IndiaOfflineMapManager.kt:543-550`, `IndiaOfflineMapManager.kt:609-614`.
6. **Activity Context Leak:** `IndiaOfflineMapManager.kt:254-284`, `LiveMapView.kt:746-747`.

### 5.3 Invalidation Conditions
This survey and its conclusions would be invalidated if:
1. `TripDao.getAllHistoricalTrips()` is modified to strictly exclude active trips (`status = 'COMPLETED' AND isQualifying = 1`), and `_currentTrip` is reset to 0 distance upon `onTripCompleted`.
2. `FuelRepository.buildFuelState()` is updated to bind DTE range strictly to `validEmpiricalMileage` / `baselineVehicleMileage` rather than `liveInstantMileage`.
3. `FuelStateEstimator.applyConsumption()` or `FuelRepository.buildFuelState()` dynamically updates `currentGaugeBar` via `gaugeCalibration.getBarForLiters(balance)` as fuel is consumed.
4. `IndiaOfflineMapManager.fetchTileFast()` validates image magic bytes (e.g. JPEG `FF D8`, PNG `89 50 4E 47`) and enforces a minimum tile size (> 512 bytes) before invoking `tileWriter.saveFile()`.
