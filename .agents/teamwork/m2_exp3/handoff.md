# Technical Investigation Report & Code Modification Blueprint
## Milestone 2 — Features 15, 16, and 18

**Author:** M2 Explorer 3 (`m2_exp3`) — Gauge & Slosh Filter  
**Assigned Subsystems:**
- Feature 15: 10-Bar LED Fuel Gauge Dynamic Update & Monotonicity (`FuelStateEstimator.kt`, `FuelGaugeCalibrationEngine.kt`)
- Feature 16: Fuel Slosh & Slope Median Filter Integration (`FuelGaugeCalibrationEngine.kt`, `FuelStateEstimator.kt`, `FuelRepository.kt`)
- Feature 18: Reboot Fuel State Recovery from Room SQLite (`FuelRepository.kt`, `AppDatabase.kt`, `DrivingTrackingService.kt`)  
**Mode:** Strict Read-Only Investigation  
**Working Directory:** `d:\Car Launcher\.agents\teamwork\m2_exp3\`  
**Date:** 2026-11-10  

---

## 1. Observation

### 1.1 Feature 15: 10-Bar LED Fuel Gauge Freezes During Driving & Lacks Monotonicity Protection
In `FuelStateEstimator.kt:137-160`:
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

    if (odometerKm > lastReliableOdometerAnchorKm) {
        val kmSinceAnchor = odometerKm - lastReliableOdometerAnchorKm
        val decay = (kmSinceAnchor / 100.0).toInt()
        confidenceScore = (confidenceScore - decay).coerceIn(25, 95)
    }

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
In `FuelStateEstimator.kt:164-177`:
```kotlin
fun applyTripConsumption(
    consumedLiters: Double,
    newOdometerKm: Double,
    currentGaugePoints: Int = -1,
    @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
) {
    applyConsumption(consumedLiters, newOdometerKm, timestamp)
    if (newOdometerKm > lastReliableOdometerAnchorKm) {
        lastReliableOdometerAnchorKm = newOdometerKm
    }
    if (currentGaugePoints in 0..10) {
        currentGaugeBar = currentGaugePoints
    }
}
```

- **Direct Observation 1.1.1 (Gauge Freeze):** `FuelStateEstimator.applyConsumption()` deducts fuel volume from `nominalFuelLiters`, but **never** modifies `currentGaugeBar`. During continuous driving, `FuelRepository.consumeLiveFuel()` invokes `applyConsumption()`. As fuel drops from full tank (48 L) to reserve (5 L), `estimator.currentGaugeBar` remains completely frozen at whatever initial bar value was set at baseline or last refuel. The dashboard 10-bar LED visualizer never decrements.
- **Direct Observation 1.1.2 (Decoupled Estimator & Calibration):** `FuelStateEstimator` constructor (`FuelStateEstimator.kt:11-14`) has no reference to `FuelGaugeCalibrationEngine`. In `FuelRepository.kt:66-68`, `estimator` and `gaugeCalibration` are instantiated independently. Consequently, `estimator` cannot compute `gaugeCalibration.getBarForLiters(nominalFuelLiters)` on consumption ticks.
- **Direct Observation 1.1.3 (Monotonicity Violation on Sensor Noise):** In `applyTripConsumption()`, if an external `currentGaugePoints` is passed, `currentGaugeBar = currentGaugePoints` unconditionally assigns the value. If an erratic sensor reading steps upwards due to fluid slosh, the gauge bar flickers upward during driving, violating automotive cluster monotonicity between fill-ups.

---

### 1.2 Feature 16: Disconnected 60-Second Slosh & Slope Median Filter Causing False Refuel Triggers
In `FuelGaugeCalibrationEngine.kt:105-121`:
```kotlin
fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
    val clamped = newBar.coerceIn(0, 10)
    recentReadings.add(Pair(timestamp, clamped))

    // Keep last 60 seconds of readings
    while (recentReadings.isNotEmpty() && (timestamp - recentReadings.first().first > 60000L)) {
        recentReadings.removeAt(0)
    }

    if (recentReadings.size < 3) {
        return clamped
    }

    // Return the median reading to reject transient slope / acceleration sloshes
    val sortedBars = recentReadings.map { it.second }.sorted()
    return sortedBars[sortedBars.size / 2]
}
```
In `FuelRepository.kt:373-418`:
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

            if (detection.detected) {
                _missedRefuelAlert.value = detection
                ...
                estimator.applyManualAdjustment(
                    adjustedLiters = gaugeCalibration.getLitersForBar(newMidBar),
                    observedGaugePoints = newMidBar,
                    odometerKm = effectiveOdo,
                    reason = "Adjusted to inferred level (${newMidBar}/10 bars)",
                    confidence = EventConfidence.PROVISIONAL
                )
            } else {
                // Normal gauge update
                val litersForBar = gaugeCalibration.getLitersForBar(newMidBar)
                val prevState = estimator.toSnapshot()
                estimator.applyManualAdjustment(
                    adjustedLiters = litersForBar,
                    observedGaugePoints = newMidBar,
                    odometerKm = effectiveOdo,
                    reason = "Gauge updated to $newMidBar/10 bars",
                    confidence = EventConfidence.HIGH
                )
```

- **Direct Observation 1.2.1 (Bypassed Slosh Filter):** `filterSloshAndSlope()` exists in `FuelGaugeCalibrationEngine.kt`, but `FuelRepository.syncMidAndOdometer()` completely bypasses it. Raw `newMidBar` is passed directly into `missedEntryIntel.evaluateObservedGauge()`.
- **Direct Observation 1.2.2 (False Inferred Refuel on Incline / Pitch):** When a vehicle ascends a steep grade, descends an incline, or undergoes heavy longitudinal acceleration, fuel slosh moves the float arm. A transient spike from 5 bars to 8 bars produces a `deltaNominal` of ~14.9 L in a 48 L tank. Because the threshold is 4.0 L (`thresholdLiters = 4.0`), `evaluateObservedGauge()` detects a false refuel (`detection.detected = true`), triggers a high-priority user prompt (`_missedRefuelAlert`), writes a provisional `INFERRED_REFUEL` to Room SQLite, and alters `estimator.nominalFuelLiters`.
- **Direct Observation 1.2.3 (Missing Slosh Method in Estimator & UI Calibration Lag):** `FuelStateEstimator` lacks a `filterSloshAndSlope()` method. Furthermore, `FuelGaugeCalibrationEngine` lacks a `resetSloshBuffer()` method; when a user manually and deliberately calibrates their gauge in `FuelTankCard`'s dialog, prior slosh history could suppress or delay the user's manual input if not cleared.

---

### 1.3 Feature 18: Reboot Fuel State Loss & Active Trip Reset to Zero
In `FuelRepository.kt:74`:
```kotlin
private var cumulativeTripConsumedLiters = 0.0
```
In `DrivingTrackingService.kt:178-215`:
```kotlin
serviceScope.launch {
    val db = com.antigravity.drivingreport.core.database.AppDatabase.getInstance(applicationContext)
    val activeTrip = db.tripDao().getActiveTrip()
    if (activeTrip != null && activeTrip.endTime <= 0L) {
        geofenceEngine.restoreActiveTrip(activeTrip.id, activeTrip.startTime)

        val lastPt = db.tripDao().getLastPointForTrip(activeTrip.id)
        val anchorLoc = ...

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
In `FuelRepository.kt:124-170` (`rehydrateLedgerAsync`):
```kotlin
private fun rehydrateLedgerAsync() {
    scope.launch {
        stateMutex.withLock {
            try {
                val events = fuelLedgerDao.getAllEventsAsc()
                if (events.isNotEmpty()) {
                    ...
                    // Restore latest event snapshot
                    val latest = events.last()
                    estimator.restoreFromSnapshot(latest.toDomainEvent().resultingState ?: estimator.toSnapshot(), latest.odometerKm)
                }
...
```
In `TripRepository.kt:448-465`:
```kotlin
tripDao.updateTrip(
    TripEntity(
        id = summary.id,
        startTime = summary.startTime,
        endTime = summary.endTime,
        distanceMeters = summary.distanceMeters,
        drivingDurationMs = summary.drivingDurationMs,
        stationaryDurationMs = summary.stationaryDurationMs,
        avgSpeedKmh = summary.avgSpeedKmh,
        maxSpeedKmh = summary.maxSpeedKmh,
        estimatedFuelLiters = summary.estimatedFuelConsumedLiters,
        ...
```
In `TripDetectionEngine.kt:628-634`:
```kotlin
val fuelSnapshot = fuelTelemetryProvider?.invoke()
val effectiveMileage = fuelSnapshot?.mileageKmpl?.takeIf { it > 0.0 } ?: BASE_MILEAGE_KMPL
val effectivePrice = fuelSnapshot?.fuelPricePerLiter?.takeIf { it > 0.0 } ?: FUEL_PRICE_PER_LITER
val tripFuelConsumed = fuelSnapshot?.tripConsumedLiters?.takeIf { it > 0.0 }
    ?: ((totalDistanceMeters / 1000.0) / effectiveMileage)
val tripCost = tripFuelConsumed * effectivePrice
```

- **Direct Observation 1.3.1 (Trip Fuel Reset on Process Restart):** `cumulativeTripConsumedLiters` is stored as an in-memory variable in `FuelRepository`. When the Android OS kills the process or the car head unit reboots during an active journey, `DrivingTrackingService.onCreate()` restores distance, driving duration, and stationary duration into `TripDetectionEngine`, but **never** restores fuel into `FuelRepository`. `cumulativeTripConsumedLiters` starts at `0.0`.
- **Direct Observation 1.3.2 (Ledger Rehydration Overwrites Active Fuel Consumption):** `fuel_ledger_events` only receives `TRIP_FUEL_CONSUMPTION` when a trip is **completed** (`FuelRepository.onTripCompleted()`). During an active trip, no completed trip event exists in the ledger. On reboot, `rehydrateLedgerAsync()` restores `estimator` from `events.last().resultingState`, which reflects the tank level from **before** the active trip began. The fuel consumed during the pre-reboot portion of the trip is wiped out, reverting the tank balance upwards by `activeTrip.estimatedFuelLiters`.
- **Direct Observation 1.3.3 (UI Trip Fuel and Cost Drop to ₹0.00):** After reboot, as soon as `consumeLiveFuel()` records 0.001 L of new movement, `tripConsumedLiters` in `TripDetectionEngine` switches from distance-fallback to `fuelSnapshot.tripConsumedLiters` (0.001 L). The dashboard trip card abruptly resets from e.g. 4.8 L (₹463.20) to 0.001 L (₹0.10).

---

## 2. Logic Chain

### 2.1 Feature 15 Logic Chain: 10-Bar LED Fuel Gauge Dynamic Update & Monotonicity
1. `FuelTankCard.kt` displays 10 LED segments according to `i <= fuelState.currentMidBar`.
2. `fuelState.currentMidBar` is populated in `FuelRepository.buildFuelState()` by reading `estimator.currentGaugeBar`.
3. When the vehicle is driving, `DrivingTrackingService` calls `fuelRepository.consumeLiveFuel()`, which calls `estimator.applyConsumption(consumedLiters, odo)`.
4. Observation 1.1.1 proves that `applyConsumption()` only deducts volume from `nominalFuelLiters`, leaving `currentGaugeBar` untouched.
5. Therefore, the 10-segment cluster gauge never decrements during driving.
6. To fix this, `FuelStateEstimator` must receive a reference to `FuelGaugeCalibrationEngine`.
7. Inside `applyConsumption()`, after deducting `cleanBurn`, `gaugeCalibration.getBarForLiters(nominalFuelLiters)` must compute the target calibrated bar.
8. To prevent float slosh or calculation jitter from causing the gauge to step upward during active driving, the update rule must enforce strict monotonicity:
   $$\text{currentGaugeBar} = \min(\text{currentGaugeBar}, \text{calibratedBar})$$
9. In `applyTripConsumption()`, if an external `currentGaugePoints` is supplied, it must also be clamped:
   $$\text{currentGaugeBar} = \min(\text{currentGaugeBar}, \text{currentGaugePoints})$$
10. Upward step transitions are strictly restricted to confirmed refuels (`applyRefuel`) and manual cluster calibrations (`applyManualAdjustment`).
11. When `nominalFuelLiters <= 0.0`, `currentGaugeBar` is clamped to `0`.

### 2.2 Feature 16 Logic Chain: Fuel Slosh & Slope Median Filter Integration
1. Road hills, ramps, body roll, acceleration, and braking induce physical fuel sloshing in automotive tanks, causing float sensor readings to fluctuate by 2–4 bars for 5–20 seconds.
2. In `FuelRepository.kt:380`, `syncMidAndOdometer()` evaluates `observedGaugePoints = newMidBar` directly against `MissedEntryIntelligence`.
3. A 3-bar transient spike exceeds the 4.0 L threshold, triggering a false `INFERRED_REFUEL` alert and falsely adjusting the nominal tank volume.
4. Conversely, a transient dip causes a sudden drop in nominal fuel via `applyManualAdjustment()`.
5. `FuelGaugeCalibrationEngine.filterSloshAndSlope()` implements a 60-second rolling median filter. Because the median of $[5, 5, 5, 8]$ is $5$, transient pitch/slosh spikes are rejected.
6. Therefore, `syncMidAndOdometer()` must pass `newMidBar` through `filterSloshAndSlope()` before any business logic evaluates it:
   $$\text{filteredMidBar} = \text{estimator.filterSloshAndSlope}(\text{newMidBar})$$
7. When the user opens the "Calibrate Instrument Gauge" UI dialog and clicks "Apply Gauge Bar", this is an explicit, verified human action. A `resetSloshBuffer(selectedBar)` method must be added so the filter buffer is cleared and seeded with the confirmed bar, eliminating input latency.

### 2.3 Feature 18 Logic Chain: Reboot Fuel State Recovery from Room SQLite
1. Observation 1.3.1 demonstrates that `cumulativeTripConsumedLiters` is kept only in memory and resets to `0.0` on process termination.
2. In `TripRepository.kt:448-465`, `tripDao.updateTrip()` flushes `activeTrip.estimatedFuelLiters` to Room SQLite every 4 seconds or 20 points during active driving.
3. On reboot, `DrivingTrackingService.onCreate()` queries `db.tripDao().getActiveTrip()`, which successfully returns the active trip with its stored `estimatedFuelLiters`.
4. However, `DrivingTrackingService.onCreate()` never notifies `FuelRepository`.
5. Simultaneously, in `FuelRepository.rehydrateLedgerAsync()`, `estimator` is restored from `fuel_ledger_events.last().resultingState`. Because `fuel_ledger_events` only logs completed trips, this restores the tank level from **before** the trip began.
6. To achieve complete recovery across reboot:
   - `FuelRepository` must provide a `restoreActiveTripFuel(consumedLiters: Double)` method.
   - `DrivingTrackingService.onCreate()` must invoke `fuelRepository.restoreActiveTripFuel(activeTrip.estimatedFuelLiters)`.
   - `FuelRepository.rehydrateLedgerAsync()` must also check `database.tripDao().getActiveTrip()`: if an active trip is found with `estimatedFuelLiters > 0.0`, it restores `cumulativeTripConsumedLiters = activeTrip.estimatedFuelLiters` and applies that consumption to `estimator` via `estimator.applyConsumption(activeTrip.estimatedFuelLiters, getEffectiveOdometer())`.
7. This ensures both tank remaining volume and cumulative trip fuel burn survive process restarts and device reboots with zero loss and zero double-counting.

---

## 3. Caveats
1. **CAN-Bus vs GPS Kinematic Fuel Consumption:** In the current application architecture, fuel consumption during driving is calculated via the kinematic and thermal ICE model in `FuelAIEngine` (fusing GPS velocity, acceleration, idle burn, and speed efficiency) combined with 10-bar instrument cluster calibrations. Direct OBD-II / CAN-Bus PID polling is simulated via this fused software telemetry layer.
2. **First-Fix Slosh Buffering:** When the application starts up, `filterSloshAndSlope()` requires 3 readings before returning the true median; for the first 2 readings it returns the clamped raw input. This is intentional to ensure fast initialization while rapidly establishing the rolling window.
3. **Database Concurrency:** All Room database operations in `FuelRepository` are executed within `stateMutex.withLock` and `Dispatchers.IO` to ensure thread safety across concurrent GPS telemetry callbacks.

---

## 4. Conclusion & Code Modification Blueprint

Below is the complete, exact, step-by-step code modification blueprint.

### File 1: `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/FuelStateEstimator.kt`

#### Change 1.1: Constructor & Properties
**Location:** Lines 11–14
```kotlin
<<<< BEFORE (Lines 11-14)
class FuelStateEstimator(
    var designCapacityLiters: Double = 48.0,
    var maxCapacityLiters: Double = 48.0
) {
==== AFTER
class FuelStateEstimator(
    var designCapacityLiters: Double = 48.0,
    var maxCapacityLiters: Double = 48.0,
    var gaugeCalibration: FuelGaugeCalibrationEngine? = null
) {
>>>>
```

#### Change 1.2: Capacity Update & Gauge Re-sync
**Location:** Lines 51–59
```kotlin
<<<< BEFORE (Lines 51-59)
    fun updateDesignCapacity(newCapacity: Double) {
        val safe = newCapacity.coerceIn(10.0, 200.0)
        designCapacityLiters = safe
        maxCapacityLiters = safe
        ensureCapacityConsistency()
        nominalFuelLiters = nominalFuelLiters.coerceIn(0.0, maxCapacityLiters)
        conservativeFuelLiters = conservativeFuelLiters.coerceIn(0.0, nominalFuelLiters)
        upperFuelLiters = upperFuelLiters.coerceIn(nominalFuelLiters, maxCapacityLiters)
    }
==== AFTER
    fun updateDesignCapacity(newCapacity: Double) {
        val safe = newCapacity.coerceIn(10.0, 200.0)
        designCapacityLiters = safe
        maxCapacityLiters = safe
        ensureCapacityConsistency()
        gaugeCalibration?.updateDesignCapacity(safe)
        nominalFuelLiters = nominalFuelLiters.coerceIn(0.0, maxCapacityLiters)
        conservativeFuelLiters = conservativeFuelLiters.coerceIn(0.0, nominalFuelLiters)
        upperFuelLiters = upperFuelLiters.coerceIn(nominalFuelLiters, maxCapacityLiters)
        gaugeCalibration?.let { calibration ->
            currentGaugeBar = calibration.getBarForLiters(nominalFuelLiters)
        }
    }
>>>>
```

#### Change 1.3: Dynamic Gauge Bar Update & Strict Monotonicity in `applyConsumption()`
**Location:** Lines 137–159
```kotlin
<<<< BEFORE (Lines 137-159)
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

        if (odometerKm > lastReliableOdometerAnchorKm) {
            // Natural slight drift in confidence as distance increases between refills
            val kmSinceAnchor = odometerKm - lastReliableOdometerAnchorKm
            val decay = (kmSinceAnchor / 100.0).toInt()
            confidenceScore = (confidenceScore - decay).coerceIn(25, 95)
        }

        if (nominalFuelLiters <= 0.0) {
            currentExplanation = "Tank depleted (clamped at 0.0 L)"
        }
    }
==== AFTER
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

        // Feature 15: Dynamic 10-bar LED gauge update with strict monotonicity between fill-ups
        gaugeCalibration?.let { calibration ->
            val calibratedBar = calibration.getBarForLiters(nominalFuelLiters)
            // Enforce strict monotonicity: gauge bar must never step upwards due to sensor noise during active consumption
            currentGaugeBar = min(currentGaugeBar, calibratedBar)
        }

        if (odometerKm > lastReliableOdometerAnchorKm) {
            // Natural slight drift in confidence as distance increases between refills
            val kmSinceAnchor = odometerKm - lastReliableOdometerAnchorKm
            val decay = (kmSinceAnchor / 100.0).toInt()
            confidenceScore = (confidenceScore - decay).coerceIn(25, 95)
        }

        if (nominalFuelLiters <= 0.0) {
            currentExplanation = "Tank depleted (clamped at 0.0 L)"
            currentGaugeBar = 0
        }
    }
>>>>
```

#### Change 1.4: Monotonic Clamp in `applyTripConsumption()` & Slosh Filter Delegation
**Location:** Lines 164–177
```kotlin
<<<< BEFORE (Lines 164-177)
    fun applyTripConsumption(
        consumedLiters: Double,
        newOdometerKm: Double,
        currentGaugePoints: Int = -1,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        applyConsumption(consumedLiters, newOdometerKm, timestamp)
        if (newOdometerKm > lastReliableOdometerAnchorKm) {
            lastReliableOdometerAnchorKm = newOdometerKm
        }
        if (currentGaugePoints in 0..10) {
            currentGaugeBar = currentGaugePoints
        }
    }
==== AFTER
    fun applyTripConsumption(
        consumedLiters: Double,
        newOdometerKm: Double,
        currentGaugePoints: Int = -1,
        @Suppress("UNUSED_PARAMETER") timestamp: Long = System.currentTimeMillis()
    ) {
        applyConsumption(consumedLiters, newOdometerKm, timestamp)
        if (newOdometerKm > lastReliableOdometerAnchorKm) {
            lastReliableOdometerAnchorKm = newOdometerKm
        }
        if (currentGaugePoints in 0..10) {
            // Feature 15: Monotonicity guard against upward sensor noise steps during driving
            currentGaugeBar = min(currentGaugeBar, currentGaugePoints)
        }
    }

    /**
     * Feature 16: Filters short-term gauge fluctuations caused by vehicle slope or fuel sloshing.
     * Delegates to [gaugeCalibration] if configured, otherwise clamps to valid bar bounds.
     */
    fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
        return gaugeCalibration?.filterSloshAndSlope(newBar, timestamp) ?: newBar.coerceIn(0, 10)
    }
>>>>
```

---

### File 2: `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/FuelGaugeCalibrationEngine.kt`

#### Change 2.1: Slosh Buffer Reset for Manual Calibration & Capacity Coercion
**Location:** Lines 68–81 and Lines 105–129
```kotlin
<<<< BEFORE (Lines 68-81)
    fun getBarForLiters(liters: Double): Int {
        val fraction = (liters / designCapacityLiters).coerceIn(0.0, 1.0)
        var bestBar = 0
        var minDiff = Double.MAX_VALUE

        for (i in 0..10) {
            val diff = abs(calibratedFractions[i] - fraction)
            if (diff < minDiff) {
                minDiff = diff
                bestBar = i
            }
        }
        return bestBar
    }
==== AFTER
    fun getBarForLiters(liters: Double): Int {
        val safeCap = designCapacityLiters.coerceAtLeast(10.0)
        val fraction = (liters / safeCap).coerceIn(0.0, 1.0)
        var bestBar = 0
        var minDiff = Double.MAX_VALUE

        for (i in 0..10) {
            val diff = abs(calibratedFractions[i] - fraction)
            if (diff < minDiff) {
                minDiff = diff
                bestBar = i
            }
        }
        return bestBar
    }
>>>>
```
```kotlin
<<<< BEFORE (Lines 105-121)
    fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
        val clamped = newBar.coerceIn(0, 10)
        recentReadings.add(Pair(timestamp, clamped))

        // Keep last 60 seconds of readings
        while (recentReadings.isNotEmpty() && (timestamp - recentReadings.first().first > 60000L)) {
            recentReadings.removeAt(0)
        }

        if (recentReadings.size < 3) {
            return clamped
        }

        // Return the median reading to reject transient slope / acceleration sloshes
        val sortedBars = recentReadings.map { it.second }.sorted()
        return sortedBars[sortedBars.size / 2]
    }
==== AFTER
    /**
     * Resets or seeds the rolling slosh buffer on explicit user calibration.
     * Prevents prior transient readings from overriding intentional manual calibrations.
     */
    fun resetSloshBuffer(initialBar: Int = -1) {
        synchronized(recentReadings) {
            recentReadings.clear()
            if (initialBar in 0..10) {
                recentReadings.add(Pair(System.currentTimeMillis(), initialBar))
            }
        }
    }

    fun filterSloshAndSlope(newBar: Int, timestamp: Long = System.currentTimeMillis()): Int {
        val clamped = newBar.coerceIn(0, 10)
        synchronized(recentReadings) {
            recentReadings.add(Pair(timestamp, clamped))

            // Keep last 60 seconds of readings
            while (recentReadings.isNotEmpty() && (timestamp - recentReadings.first().first > 60000L)) {
                recentReadings.removeAt(0)
            }

            if (recentReadings.size < 3) {
                return clamped
            }

            // Return the median reading to reject transient slope / acceleration sloshes
            val sortedBars = recentReadings.map { it.second }.sorted()
            return sortedBars[sortedBars.size / 2]
        }
    }
>>>>
```

---

### File 3: `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt`

#### Change 3.1: Wire `gaugeCalibration` to `estimator`
**Location:** Lines 66–69
```kotlin
<<<< BEFORE (Lines 66-69)
    val estimator = FuelStateEstimator(designCapacityLiters = designCapacity, maxCapacityLiters = maxCapacity)
    val mileageEngine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = baselineVehicleMileage)
    val gaugeCalibration = FuelGaugeCalibrationEngine(designCapacityLiters = designCapacity)
==== AFTER
    val gaugeCalibration = FuelGaugeCalibrationEngine(designCapacityLiters = designCapacity)
    val estimator = FuelStateEstimator(
        designCapacityLiters = designCapacity,
        maxCapacityLiters = maxCapacity,
        gaugeCalibration = gaugeCalibration
    )
    val mileageEngine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = baselineVehicleMileage)
>>>>
```

#### Change 3.2: Rehydrate Active Trip Fuel and Deduct Mid-Trip Consumption
**Location:** Lines 167–170
```kotlin
<<<< BEFORE (Lines 167-170)
                        // Restore latest event snapshot
                        val latest = events.last()
                        estimator.restoreFromSnapshot(latest.toDomainEvent().resultingState ?: estimator.toSnapshot(), latest.odometerKm)
                    } else {
==== AFTER
                        // Restore latest event snapshot
                        val latest = events.last()
                        estimator.restoreFromSnapshot(latest.toDomainEvent().resultingState ?: estimator.toSnapshot(), latest.odometerKm)
                    }

                    // Feature 18: Audit active trip in Room SQLite on reboot / startup
                    val activeTrip = database.tripDao().getActiveTrip()
                    if (activeTrip != null && activeTrip.endTime <= 0L && activeTrip.estimatedFuelLiters > 0.0) {
                        if (cumulativeTripConsumedLiters == 0.0) {
                            cumulativeTripConsumedLiters = FuelMath.roundFuel(activeTrip.estimatedFuelLiters)
                        }
                        // Re-apply mid-trip consumption to continuous estimator so tank level reflects ongoing burn
                        estimator.applyConsumption(
                            consumedLiters = activeTrip.estimatedFuelLiters,
                            odometerKm = getEffectiveOdometer()
                        )
                    } else if (events.isEmpty()) {
>>>>
```

#### Change 3.3: Add `restoreActiveTripFuel()` Method
**Location:** Lines 673–677
```kotlin
<<<< BEFORE (Lines 673-677)
    fun resetTripConsumption() {
        cumulativeTripConsumedLiters = 0.0
        _fuelState.value = buildFuelState()
    }
==== AFTER
    fun resetTripConsumption() {
        cumulativeTripConsumedLiters = 0.0
        _fuelState.value = buildFuelState()
    }

    /**
     * Feature 18: Restores cumulative trip fuel consumed from Room SQLite on reboot or app recreation.
     * Prevents active trip fuel consumption and trip cost from resetting to 0.0.
     */
    fun restoreActiveTripFuel(consumedLiters: Double) {
        if (consumedLiters <= 0.0 || !consumedLiters.isFinite()) return
        scope.launch {
            stateMutex.withLock {
                if (cumulativeTripConsumedLiters == 0.0) {
                    cumulativeTripConsumedLiters = FuelMath.roundFuel(consumedLiters)
                    _fuelState.value = buildFuelState()
                }
            }
        }
    }
>>>>
```

#### Change 3.4: Integrate Slosh Filter into `syncMidAndOdometer()` and `calibrateMidBar()`
**Location:** Lines 364–453
```kotlin
<<<< BEFORE (Lines 364-453)
    fun calibrateMidBar(selectedBar: Int) {
        val currentOdo = getEffectiveOdometer()
        syncMidAndOdometer(currentOdo, selectedBar, latestAppLifetimeKm)
    }

    /**
     * Reconciles observed gauge level.
     * Evaluates against MissedEntryIntelligence to detect unrecorded refuels.
     */
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

                if (detection.detected) {
                    _missedRefuelAlert.value = detection
                    // Record provisional inferred refuel in immutable ledger
                    val event = detection.suggestedLedgerEvent ?: FuelLedgerEvent(
                        correlationId = "inferred_${System.currentTimeMillis()}",
                        eventType = FuelEventType.INFERRED_REFUEL,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = detection.plausibleNominalLiters,
                        fuelGaugePoints = newMidBar,
                        source = EventSource.INFERRED,
                        confidence = EventConfidence.PROVISIONAL,
                        reasonNote = "Missed refuel detected from gauge reading $newMidBar/10"
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(event))
                    estimator.applyManualAdjustment(
                        adjustedLiters = gaugeCalibration.getLitersForBar(newMidBar),
                        observedGaugePoints = newMidBar,
                        odometerKm = effectiveOdo,
                        reason = "Adjusted to inferred level (${newMidBar}/10 bars)",
                        confidence = EventConfidence.PROVISIONAL
                    )
                } else {
                    // Normal gauge update
                    val litersForBar = gaugeCalibration.getLitersForBar(newMidBar)
                    val prevState = estimator.toSnapshot()
                    estimator.applyManualAdjustment(
                        adjustedLiters = litersForBar,
                        observedGaugePoints = newMidBar,
                        odometerKm = effectiveOdo,
                        reason = "Gauge updated to $newMidBar/10 bars",
                        confidence = EventConfidence.HIGH
                    )
                    gaugeCalibration.learnCalibrationPoint(newMidBar, litersForBar, 0.15)

                    val ledgerEvent = FuelLedgerEvent(
                        correlationId = "gauge_${System.currentTimeMillis()}",
                        eventType = FuelEventType.FUEL_GAUGE_UPDATE,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = 0.0,
                        fuelGaugePoints = newMidBar,
                        source = EventSource.MANUAL,
                        confidence = EventConfidence.HIGH,
                        previousState = prevState,
                        resultingState = estimator.toSnapshot(),
                        reasonNote = "Cluster gauge synchronized to $newMidBar/10 bars"
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(ledgerEvent))

                    // Also mirror into legacy fuel_events for backward-compatibility
                    val legacyEvent = FuelEventEntity(
                        vehicleId = 1,
                        timestamp = System.currentTimeMillis(),
                        eventType = "MID_OBSERVED",
                        liters = 0.0,
                        midLevelObserved = newMidBar,
                        pricePerLiter = fuelPrice,
                        totalCost = 0.0,
                        calculatedBalanceLiters = estimator.nominalFuelLiters,
                        notes = "Manual MID cluster sync: $newMidBar/10 bars at $effectiveOdo km",
                        odometerKm = effectiveOdo,
                        calculatedMileageKmpl = mileageEngine.overallEstimatedMileage,
                        locationName = "Cluster Calibration"
                    )
                    fuelDao.insertFuelEvent(legacyEvent)
                }

                persistValuesImmediate()
                _fuelState.value = buildFuelState()
            }
        }
    }
==== AFTER
    fun calibrateMidBar(selectedBar: Int) {
        val currentOdo = getEffectiveOdometer()
        // Feature 16: Reset slosh buffer on explicit user calibration so user's manual action applies immediately
        gaugeCalibration.resetSloshBuffer(selectedBar)
        syncMidAndOdometer(currentOdo, selectedBar, latestAppLifetimeKm)
    }

    /**
     * Reconciles observed gauge level.
     * Evaluates against MissedEntryIntelligence to detect unrecorded refuels.
     */
    fun syncMidAndOdometer(newOdometerKm: Double, newMidBar: Int, recordedLifetimeKm: Double = latestAppLifetimeKm) {
        scope.launch {
            stateMutex.withLock {
                latestAppLifetimeKm = recordedLifetimeKm
                val effectiveOdo = if (newOdometerKm > 0.0) newOdometerKm else getEffectiveOdometer()

                // Feature 16: Filter transient pitch, roll, acceleration, and slosh anomalies with 60s rolling median filter
                val filteredMidBar = estimator.filterSloshAndSlope(newMidBar)

                // Check for missed refuel event using slosh-filtered bar reading
                val detection = missedEntryIntel.evaluateObservedGauge(
                    observedGaugePoints = filteredMidBar,
                    estimator = estimator,
                    currentOdometerKm = effectiveOdo
                )

                if (detection.detected) {
                    _missedRefuelAlert.value = detection
                    // Record provisional inferred refuel in immutable ledger
                    val event = detection.suggestedLedgerEvent ?: FuelLedgerEvent(
                        correlationId = "inferred_${System.currentTimeMillis()}",
                        eventType = FuelEventType.INFERRED_REFUEL,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = detection.plausibleNominalLiters,
                        fuelGaugePoints = filteredMidBar,
                        source = EventSource.INFERRED,
                        confidence = EventConfidence.PROVISIONAL,
                        reasonNote = "Missed refuel detected from gauge reading $filteredMidBar/10"
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(event))
                    estimator.applyManualAdjustment(
                        adjustedLiters = gaugeCalibration.getLitersForBar(filteredMidBar),
                        observedGaugePoints = filteredMidBar,
                        odometerKm = effectiveOdo,
                        reason = "Adjusted to inferred level (${filteredMidBar}/10 bars)",
                        confidence = EventConfidence.PROVISIONAL
                    )
                } else {
                    // Normal gauge update using filtered bar
                    val litersForBar = gaugeCalibration.getLitersForBar(filteredMidBar)
                    val prevState = estimator.toSnapshot()
                    estimator.applyManualAdjustment(
                        adjustedLiters = litersForBar,
                        observedGaugePoints = filteredMidBar,
                        odometerKm = effectiveOdo,
                        reason = "Gauge updated to $filteredMidBar/10 bars",
                        confidence = EventConfidence.HIGH
                    )
                    gaugeCalibration.learnCalibrationPoint(filteredMidBar, litersForBar, 0.15)

                    val ledgerEvent = FuelLedgerEvent(
                        correlationId = "gauge_${System.currentTimeMillis()}",
                        eventType = FuelEventType.FUEL_GAUGE_UPDATE,
                        timestamp = System.currentTimeMillis(),
                        odometerKm = effectiveOdo,
                        fuelQuantityLiters = 0.0,
                        fuelGaugePoints = filteredMidBar,
                        source = EventSource.MANUAL,
                        confidence = EventConfidence.HIGH,
                        previousState = prevState,
                        resultingState = estimator.toSnapshot(),
                        reasonNote = "Cluster gauge synchronized to $filteredMidBar/10 bars"
                    )
                    fuelLedgerDao.insertEvent(FuelLedgerEntity.fromDomainEvent(ledgerEvent))

                    // Also mirror into legacy fuel_events for backward-compatibility
                    val legacyEvent = FuelEventEntity(
                        vehicleId = 1,
                        timestamp = System.currentTimeMillis(),
                        eventType = "MID_OBSERVED",
                        liters = 0.0,
                        midLevelObserved = filteredMidBar,
                        pricePerLiter = fuelPrice,
                        totalCost = 0.0,
                        calculatedBalanceLiters = estimator.nominalFuelLiters,
                        notes = "Manual MID cluster sync: $filteredMidBar/10 bars at $effectiveOdo km",
                        odometerKm = effectiveOdo,
                        calculatedMileageKmpl = mileageEngine.overallEstimatedMileage,
                        locationName = "Cluster Calibration"
                    )
                    fuelDao.insertFuelEvent(legacyEvent)
                }

                persistValuesImmediate()
                _fuelState.value = buildFuelState()
            }
        }
    }
>>>>
```

---

### File 4: `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`

#### Change 4.1: Reconstruct Active Trip Fuel from SQLite on Service Startup
**Location:** Lines 205–216
```kotlin
<<<< BEFORE (Lines 205-216)
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
==== AFTER
                engine.restoreActiveTripState(
                    tripId = activeTrip.id,
                    startTime = activeTrip.startTime,
                    distanceMeters = activeTrip.distanceMeters,
                    drivingMs = activeTrip.drivingDurationMs,
                    stationaryMs = activeTrip.stationaryDurationMs,
                    lastLocation = anchorLoc
                )
                lastRecordedDistanceMeters = activeTrip.distanceMeters

                // Feature 18: Reconstruct cumulative trip fuel consumption from Room SQLite
                if (activeTrip.estimatedFuelLiters > 0.0) {
                    fuelRepository.restoreActiveTripFuel(activeTrip.estimatedFuelLiters)
                }
            }
        }
>>>>
```

---

## 5. Verification Method

### 5.1 Project Test Command Execution
To independently verify the test suite:
```powershell
.\gradlew.bat testReleaseUnitTest
```
Or for debug test target:
```powershell
.\gradlew.bat testDebugUnitTest
```

### 5.2 Targeted Unit Test Specifications
The following test suite (`FuelGaugeAndSloshRecoveryTest.kt`) specifies and verifies all three target features:

```kotlin
package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.fuel.intelligence.EventConfidence
import com.antigravity.drivingreport.core.fuel.intelligence.FuelGaugeCalibrationEngine
import com.antigravity.drivingreport.core.fuel.intelligence.FuelStateEstimator
import com.antigravity.drivingreport.core.fuel.intelligence.MissedEntryIntelligence
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FuelGaugeAndSloshRecoveryTest {

    private lateinit var calibration: FuelGaugeCalibrationEngine
    private lateinit var estimator: FuelStateEstimator
    private lateinit var missedEntryIntel: MissedEntryIntelligence

    @Before
    fun setUp() {
        calibration = FuelGaugeCalibrationEngine(designCapacityLiters = 48.0)
        estimator = FuelStateEstimator(
            designCapacityLiters = 48.0,
            maxCapacityLiters = 48.0,
            gaugeCalibration = calibration
        )
        missedEntryIntel = MissedEntryIntelligence(calibrationEngine = calibration, thresholdLiters = 4.0)
    }

    // --- FEATURE 15 TESTS: 10-Bar LED Gauge Dynamic Update & Strict Monotonicity ---

    @Test
    fun test15_1_GaugeDecrementsDynamicallyDuringActiveConsumption() {
        // Initialize at 48L (10 bars)
        estimator.initializeBaseline(initialLiters = 48.0, gaugePoints = 10, odometerKm = 10000.0)
        assertEquals(10, estimator.currentGaugeBar)

        // Consume 20 L (leaving 28 L, ~0.58 fraction, closest to bar 6 at 0.60)
        estimator.applyConsumption(consumedLiters = 20.0, odometerKm = 10300.0)
        assertEquals(28.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Gauge must dynamically decrement from 10 to 6 bars", 6, estimator.currentGaugeBar)

        // Consume another 15 L (leaving 13 L, ~0.27 fraction, closest to bar 3)
        estimator.applyConsumption(consumedLiters = 15.0, odometerKm = 10550.0)
        assertEquals(13.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Gauge must dynamically decrement to 3 bars", 3, estimator.currentGaugeBar)
    }

    @Test
    fun test15_2_StrictMonotonicityPreventsUpwardStepDuringDriving() {
        estimator.initializeBaseline(initialLiters = 30.0, gaugePoints = 6, odometerKm = 10000.0)
        assertEquals(6, estimator.currentGaugeBar)

        // Sensor glitch sends higher bar (8) during active trip consumption
        estimator.applyTripConsumption(consumedLiters = 2.0, newOdometerKm = 10030.0, currentGaugePoints = 8)
        assertEquals("Monotonicity guard must prevent gauge from stepping upwards to 8 bars during driving", 6, estimator.currentGaugeBar)

        // Sensor reports lower bar (5) — valid decrement
        estimator.applyTripConsumption(consumedLiters = 3.0, newOdometerKm = 10080.0, currentGaugePoints = 5)
        assertEquals("Monotonicity permits downward step to 5 bars", 5, estimator.currentGaugeBar)
    }

    @Test
    fun test15_3_GaugeClampsToZeroWhenTankDepleted() {
        estimator.initializeBaseline(initialLiters = 2.0, gaugePoints = 1, odometerKm = 10000.0)
        estimator.applyConsumption(consumedLiters = 5.0, odometerKm = 10050.0)
        assertEquals(0.0, estimator.nominalFuelLiters, 0.001)
        assertEquals("Gauge must clamp to 0 bar on empty tank", 0, estimator.currentGaugeBar)
    }

    @Test
    fun test15_4_RefuelAllowsUpwardGaugeStep() {
        estimator.initializeBaseline(initialLiters = 10.0, gaugePoints = 2, odometerKm = 10000.0)
        assertEquals(2, estimator.currentGaugeBar)

        // Confirmed refuel of 25 L (observed 7 bars)
        estimator.applyRefuel(addedLiters = 25.0, observedGaugePoints = 7, odometerKm = 10000.0)
        assertEquals(35.0, estimator.nominalFuelLiters, 0.01)
        assertEquals("Refuel must legitimately step gauge upwards to 7 bars", 7, estimator.currentGaugeBar)
    }

    // --- FEATURE 16 TESTS: Slosh & Slope Median Filter Integration ---

    @Test
    fun test16_1_SloshFilterRejectsTransientSpikesFromHillOrCornering() {
        val now = System.currentTimeMillis()

        // Steady vehicle state at 5 bars
        assertEquals(5, estimator.filterSloshAndSlope(5, now - 4000L))
        assertEquals(5, estimator.filterSloshAndSlope(5, now - 3000L))

        // Sudden transient slosh jump to 8 bars (due to braking or incline)
        val filtered = estimator.filterSloshAndSlope(8, now - 2000L)
        assertEquals("Transient 8-bar slosh spike must be rejected; median of [5,5,8] is 5", 5, filtered)

        // Transient dip to 2 bars (due to acceleration or downhill)
        val filteredDip = estimator.filterSloshAndSlope(2, now - 1000L)
        assertEquals("Transient dip must be filtered; median remains 5", 5, filteredDip)
    }

    @Test
    fun test16_2_SloshFilterPreventsFalseMissedRefuelAlert() {
        estimator.initializeBaseline(initialLiters = 24.0, gaugePoints = 5, odometerKm = 10000.0)
        val now = System.currentTimeMillis()

        // Establish steady 5-bar reading
        estimator.filterSloshAndSlope(5, now - 4000L)
        estimator.filterSloshAndSlope(5, now - 3000L)

        // Single transient slosh spike to 8 bars
        val filteredBar = estimator.filterSloshAndSlope(8, now - 1000L)
        val detection = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = filteredBar,
            estimator = estimator,
            currentOdometerKm = 10020.0
        )

        assertFalse("Transient slosh must NOT trigger false missed refuel alert", detection.detected)
    }

    @Test
    fun test16_3_PersistentReadingAcknowledgedAsLegitimateChange() {
        estimator.initializeBaseline(initialLiters = 24.0, gaugePoints = 5, odometerKm = 10000.0)
        val now = System.currentTimeMillis()

        // True refuel occurs: readings stay at 8 bars consistently
        estimator.filterSloshAndSlope(8, now - 3000L)
        estimator.filterSloshAndSlope(8, now - 2000L)
        val filtered = estimator.filterSloshAndSlope(8, now - 1000L)

        assertEquals("Persistent readings must transition filter to 8 bars", 8, filtered)
        val detection = missedEntryIntel.evaluateObservedGauge(
            observedGaugePoints = filtered,
            estimator = estimator,
            currentOdometerKm = 10020.0
        )
        assertTrue("Persistent 8-bar shift must detect true missed refuel", detection.detected)
    }

    @Test
    fun test16_4_ManualCalibrationResetsSloshBuffer() {
        val now = System.currentTimeMillis()
        // Buffer currently populated with 4 bars
        calibration.filterSloshAndSlope(4, now - 4000L)
        calibration.filterSloshAndSlope(4, now - 3000L)

        // User manually calibrates to 8 bars in UI dialog
        calibration.resetSloshBuffer(8)
        val immediate = calibration.filterSloshAndSlope(8, now)
        assertEquals("Resetting buffer must allow immediate manual calibration to 8 bars without old lag", 8, immediate)
    }

    // --- FEATURE 18 TESTS: Reboot Active Trip Fuel Recovery ---

    @Test
    fun test18_1_ContinuousEstimatorReflectsRestoredActiveTripConsumption() {
        // Pre-trip state: 40 L
        estimator.initializeBaseline(initialLiters = 40.0, gaugePoints = 8, odometerKm = 20000.0)
        assertEquals(40.0, estimator.nominalFuelLiters, 0.01)

        // Active trip prior to reboot had consumed 2.75 L
        val restoredTripConsumed = 2.75
        estimator.applyConsumption(consumedLiters = restoredTripConsumed, odometerKm = 20045.0)

        assertEquals("Continuous fuel estimator must reflect 40 - 2.75 = 37.25 L", 37.25, estimator.nominalFuelLiters, 0.01)
        assertTrue("Gauge bar must adjust for 37.25 L", estimator.currentGaugeBar <= 8)
    }
}
```

### 5.3 Invalidation Conditions
This investigation and its proposed solutions would be invalidated if:
1. `FuelStateEstimator`'s continuous fuel calculation is redesigned to use direct CAN bus float percentages instead of discrete 10-bar instrument cluster points.
2. `TripDao.getActiveTrip()` query schema or Room table structure is fundamentally replaced by an external non-SQLite persistence store.
3. The cluster fuel gauge UI visualizer is replaced with a continuous analog dial that does not use discrete LED bars.
