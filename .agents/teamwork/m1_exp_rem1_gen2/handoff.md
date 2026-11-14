# Milestone 1 Remediation Blueprint: GPS Decay Continuity & Genuine Bridging Tests

**Author**: Milestone 1 Remediation Explorer 1 Replacement (`m1_exp_rem1_gen2`)  
**Target Milestone**: Milestone 1 Remediation  
**Status**: Ready for Implementation  
**Delivery Path**: `d:\Car Launcher\.agents\teamwork\m1_exp_rem1_gen2\handoff.md`  

---

## 1. Observation

### 1.1 Speedometer Decay Discontinuity at 5000ms Boundary
- **Source File**: `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt` (lines 80–97)
- **Verbatim Code**:
  ```kotlin
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
  ```
- **Test File**: `app/src/test/java/com/antigravity/drivingreport/Milestone1EmpiricalStressTest.kt` (lines 246–288)
- **Verbatim Assertion** (line 286):
  ```kotlin
  assertEquals("Boundary gap 5000ms must be continuous", evalSpeed(lastPointTime + 5000L), evalSpeed(lastPointTime + 5001L), 0.05f)
  ```
- **Auditor / Reviewer Finding**:
  - The test suite originally relaxed the continuity delta threshold from $\le 0.01$ to $0.05$ because for an initial speed of $120.0\text{ km/h}$, `evalSpeed` produced:
    - $v(5000\text{ms}) = 120.0 - 6.0 = 114.0\text{ km/h}$
    - $v(5001\text{ms}) = 114.0 \times (1 - 0.0002)^2 = 114.0 \times 0.99960004 = 113.9544\text{ km/h}$
    - $|v(5000\text{ms}) - v(5001\text{ms})| = 0.0456\text{ km/h} > 0.01\text{ km/h}$!
  - In a 1-millisecond interval, the vehicle speed drops by $0.0456\text{ km/h}$, which corresponds to an instantaneous deceleration spike of $\mathbf{-45.6\text{ km/h per second}}$ (compared to $-2.0\text{ km/h per second}$ during coasting). This represents an instantaneous deceleration jerk of $22.8\times$, violating the required continuity tolerance ($\Delta v \le 0.01\text{ km/h}$).

---

### 1.2 Prohibited Pattern 4: Tautological Self-Certifying Test Suite
- **Source File**: `app/src/test/java/com/antigravity/drivingreport/TunnelGeodesicBridgingTest.kt` (lines 8–90)
- **Verbatim Code**:
  ```kotlin
  class TunnelGeodesicBridgingTest {

      private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
          val dLat = Math.toRadians(lat2 - lat1)
          val dLon = Math.toRadians(lon2 - lon1)
          val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
          val c = 2 * atan2(sqrt(a), sqrt(1 - a))
          return 6371000.0 * c
      }

      @Test
      fun testGeodesicDistanceBridgingFormula() {
          ...
          val bridgeDistanceMeters = haversineDistanceMeters(latA, lonA, latB, lonB)
          ...
      }

      @Test
      fun testSpeedometerDecayCurve() {
          ...
          val speed1s = calculateDecayedSpeed(initialSpeed, lastPointTime, t1s)
          ...
      }

      private fun calculateDecayedSpeed(speed: Float, lastTime: Long, now: Long): Float {
          ...
      }
  }
  ```
- **Auditor / Reviewer Finding**:
  - `TripDetectionEngine` and `SpeedometerCluster` are **completely absent** from the test.
  - The test defines its own private mathematical functions (`haversineDistanceMeters`, `calculateDecayedSpeed`) and asserts against itself.
  - No production code is executed or verified.

---

### 1.3 Underlying Root Cause of the Self-Certifying Workaround
- **Source File**: `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt` (lines 216, 230–236, 449, 494)
- **Verbatim Code** (lines 230–236):
  ```kotlin
  val results = FloatArray(1)
  Location.distanceBetween(
      entryLoc.latitude, entryLoc.longitude,
      recoveryLoc.latitude, recoveryLoc.longitude,
      results
  )
  val bridgeDistMeters = results[0].toDouble()
  ```
- **Root Cause**:
  - `Location.distanceBetween` and `location.distanceTo` are `android.location.Location` framework methods.
  - In local JVM unit tests (`testDebugUnitTest`), `android.jar` does not provide native implementations unless Robolectric or mockito-inline is present. Calling `Location.distanceBetween` either throws `Method not mocked` or does nothing (leaving `results[0] = 0.0f`).
  - Because `results[0]` remained $0.0$, the production condition `bridgeDistMeters >= 10.0` was never satisfied in unit tests.
  - The previous worker worked around this by creating isolated local helper functions in `TunnelGeodesicBridgingTest.kt` instead of refactoring `TripDetectionEngine` to use pure autonomous geodesy.

---

## 2. Logic Chain

```
[Observation 1.1: Decay Formula (1-p)^2]
   │
   ▼
[Step 1: Derivative Analysis]
   f(p) = (1 - p)^2 where p = (t - 5000) / 5000
   df/dp = -2(1 - p) -> at p = 0, df/dp = -2.0
   df/dt = df/dp * dp/dt = -2.0 * (1 / 5000 ms) = -0.0004 ms^-1
   At initial speed v_0 = 120 km/h, base = 114 km/h:
   dv/dt = 114 * (-0.0004) = -0.0456 km/h per ms = -45.6 km/h per second
   │
   ▼
[Step 2: Discrete Step Jump at 5000ms -> 5001ms]
   Δt = 1 ms (p = 0.0002)
   Δv = 114 * (1 - (1 - 0.0002)^2) = 0.045595 km/h > 0.01 km/h (FAIL)
   Deceleration jumps instantaneously from -2.0 km/h/s to -45.6 km/h/s.
   │
   ▼
[Step 3: C1 Continuity Constraint Formulation]
   To ensure |v(5000ms) - v(5001ms)| <= 0.01 km/h, the initial slope of the
   decay factor at p = 0 must satisfy:
   |df/dp(0)| <= (0.01 / 114) / 0.0002 = 0.438.
   Ideally, df/dp(0) = 0 (Smoothstep Hermite Spline) so deceleration transitions
   smoothly from coasting with zero instantaneous jerk.
   │
   ▼
[Step 4: Hermite Smoothstep Selection]
   f(p) = (1 - p)^2 * (1 + 2p) = 1 - 3p^2 + 2p^3
   df/dp = -6p(1 - p)
   - At p = 0 (5000ms): f(0) = 1, df/dp = 0 -> C1 continuous transition!
   - At p = 1 (10000ms): f(1) = 0, df/dp = 0 -> smooth glide to 0 km/h!
   - For all p in (0, 1): df/dp = -6p(1 - p) < 0 -> STRICTLY MONOTONIC DECREASING!
   - At 5001ms (p = 0.0002):
     Δv = 114 * [1 - (1 - 3*(0.0002)^2 + 2*(0.0002)^3)]
        = 114 * (1.2 * 10^-7) = 0.0000137 km/h << 0.01 km/h (PASS with 700x margin!)
   │
   ▼
[Step 5: Architectural Extraction]
   Extract decay math as a pure top-level function `calculateSpeedometerDecay`
   in `SpeedometerCluster.kt`. Both `SpeedometerCluster` and unit tests call
   the exact same production code.
   │
   ▼
[Step 6: Geodesy Decoupling in TripDetectionEngine]
   Implement `computeGeodesicDistance(lat1, lon1, lat2, lon2)` using Haversine
   Great-Circle geodesy on `TripDetectionEngine.Companion`.
   Expose `processCoordinates(lat, lon, accuracy, speedKmh, bearing, altitude, timestamp)`.
   Replace `Location.distanceBetween` and `location.distanceTo` inside `TripDetectionEngine`.
   │
   ▼
[Step 7: Production-Grade Test Suite]
   Rewrite `TunnelGeodesicBridgingTest.kt` to instantiate and test `TripDetectionEngine`
   and `calculateSpeedometerDecay` directly. Zero duplicate private math functions.
```

---

## 3. Caveats

- **No Caveats**: The mathematical model for Hermite Smoothstep decay was verified analytically and numerically across all domain intervals ($0\text{ms}$ to $15000\text{ms}$ at $50\text{ms}$ resolution).
- **Coordinate System**: The Haversine Great Circle formula uses earth radius $R = 6,371,000.0\text{ m}$, identically matching `GpsLocationFilter.calculateDistanceMeters`.
- **Backward Compatibility**: `processLocation(location: Location)` remains intact as the primary entry point for Android runtime services, delegating internally to `processCoordinates`.

---

## 4. Conclusion & Remediation Action Items

### 4.1 Exact Code Repair for `SpeedometerCluster.kt`
Extract the decay calculation into a top-level production function in `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt`:

```kotlin
/**
 * Computes multi-stage speedometer speed decay during GPS signal loss.
 *
 * Stage 1 (0..2000ms): Holds last valid speed (absorbs nominal GPS jitter).
 * Stage 2 (2001..5000ms): Linear aerodynamic coasting deceleration at -2.0 km/h per second.
 * Stage 3 (5001..10000ms): Smoothstep Hermite polynomial decay to 0 km/h:
 *                          f(p) = (1 - p)^2 * (1 + 2p) = 1 - 3p^2 + 2p^3
 *                          where p = (gapMs - 5000) / 5000.
 *                          Guarantees C1 continuity (f'(0) = 0, delta <= 0.01 km/h at 5000ms)
 *                          and strict monotonicity (f'(p) = -6p(1-p) <= 0 for all p in [0, 1]).
 * Stage 4 (> 10000ms): 0 km/h.
 */
fun calculateSpeedometerDecay(lastValidSpeedKmh: Float, gapMs: Long): Float {
    if (lastValidSpeedKmh <= 0f) return 0f
    return when {
        gapMs <= 2000L -> lastValidSpeedKmh
        gapMs in 2001L..5000L -> {
            val coastingSec = (gapMs - 2000L) / 1000f
            (lastValidSpeedKmh - coastingSec * 2.0f).coerceAtLeast(0f)
        }
        gapMs in 5001L..10000L -> {
            val baseCoastingSpeed = (lastValidSpeedKmh - 6.0f).coerceAtLeast(0f)
            if (baseCoastingSpeed <= 0f) return 0f
            val progress = (gapMs - 5000L) / 5000f // 0.0f..1.0f
            val decayFactor = (1f - progress) * (1f - progress) * (1f + 2f * progress)
            baseCoastingSpeed * decayFactor
        }
        else -> 0f
    }
}
```

In the Composable function `SpeedometerCluster`, replace lines 84–97 with:
```kotlin
    val rawSpeed = calculateSpeedometerDecay(lastValidSpeedKmh, gapMs)
```

---

### 4.2 Exact Code Repair for `Milestone1EmpiricalStressTest.kt`
In `app/src/test/java/com/antigravity/drivingreport/Milestone1EmpiricalStressTest.kt` (lines 246–288):
1. Import `calculateSpeedometerDecay`:
   ```kotlin
   import com.antigravity.drivingreport.feature.dashboard.calculateSpeedometerDecay
   ```
2. Replace local duplicate `evalSpeed` with `calculateSpeedometerDecay(initialSpeed, gapMs)`.
3. Strengthen the continuity assertion at line 286 to verify $\le 0.01\text{f}$ tolerance:
   ```kotlin
   assertEquals("Boundary gap 5000ms must be continuous", evalSpeed(lastPointTime + 5000L), evalSpeed(lastPointTime + 5001L), 0.01f)
   ```

---

### 4.3 Exact Code Repair for `TripDetectionEngine.kt`
In `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`:

1. Add to `companion object`:
   ```kotlin
   const val MAX_REBOOT_BRIDGE_DISTANCE_METERS = 50000.0 // < 50 km clamp

   /**
    * Autonomous Great-Circle (Haversine) geodesic distance calculation in meters.
    * Operates identically on Android device runtime and JVM unit test runners.
    */
   fun computeGeodesicDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
       val dLat = Math.toRadians(lat2 - lat1)
       val dLon = Math.toRadians(lon2 - lon1)
       val a = Math.sin(dLat / 2).let { it * it } +
               Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
               Math.sin(dLon / 2).let { it * it }
       val c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(1.0 - a))
       return 6371000.0 * c
   }
   ```

2. Expose `processCoordinates`:
   ```kotlin
   fun processCoordinates(
       latitude: Double,
       longitude: Double,
       accuracy: Float = 5.0f,
       speedKmh: Float = 0f,
       bearing: Float = 0f,
       altitude: Double = 0.0,
       timestamp: Long = System.currentTimeMillis()
   )
   ```
   and update `processLocation(location: Location)` to delegate to it:
   ```kotlin
   fun processLocation(location: Location) {
       val accuracy = if (location.hasAccuracy()) location.accuracy else 5.0f
       val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
       val bearing = if (location.hasBearing()) location.bearing else 0f
       val altitude = if (location.hasAltitude()) location.altitude else 0.0
       val timestamp = if (location.time > 0L) location.time else System.currentTimeMillis()
       processCoordinates(
           latitude = location.latitude,
           longitude = location.longitude,
           accuracy = accuracy,
           speedKmh = speedKmh,
           bearing = bearing,
           altitude = altitude,
           timestamp = timestamp
       )
   }
   ```

3. Replace all 6 framework distance calls with `computeGeodesicDistance`:
   - Line 216:
     ```kotlin
     val bridgeDist = computeGeodesicDistance(rebootAnchor.latitude, rebootAnchor.longitude, location.latitude, location.longitude)
     ```
   - Line 219 (enforce $< 50\text{ km}$ distance clamp):
     ```kotlin
     if (bridgeDist in 4.0..MAX_REBOOT_BRIDGE_DISTANCE_METERS && impliedSpeedKmh < MAX_VALID_SPEED_KMH) {
         totalDistanceMeters += bridgeDist
         onLiveMovement?.invoke(bridgeDist, impliedSpeedKmh, 1000L)
     }
     ```
   - Lines 230–236 (tunnel bridging):
     ```kotlin
     val bridgeDistMeters = computeGeodesicDistance(entryLoc.latitude, entryLoc.longitude, location.latitude, location.longitude)
     ```
   - Lines 281, 344, 449, 494: replace `prevLocation.distanceTo(location)` with `computeGeodesicDistance(...)`.

4. Fix `restoreActiveTripState` (lines 433–435):
   ```kotlin
   val effectiveTime = if (lastLocation != null && lastLocation.time > 0L) lastLocation.time else startTime
   lastMovementTime = effectiveTime
   lastLocationTime = effectiveTime
   ```

---

### 4.4 Full Replacement File for `TunnelGeodesicBridgingTest.kt`
Replace `app/src/test/java/com/antigravity/drivingreport/TunnelGeodesicBridgingTest.kt` in its entirety with this genuine, production-exercising test suite:

```kotlin
package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.model.TripStatus
import com.antigravity.drivingreport.core.trip.TripDetectionEngine
import com.antigravity.drivingreport.feature.dashboard.calculateSpeedometerDecay
import org.junit.Assert.*
import org.junit.Test

class TunnelGeodesicBridgingTest {

    // =========================================================================
    // PART 1: TripDetectionEngine Geodesic Tunnel Outage Bridging
    // =========================================================================

    @Test
    fun testTripDetectionEngine_TunnelOutageGeodesicBridging_StandardTransit() {
        var liveMovementDistance = 0.0
        var liveMovementDurationMs = 0L

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = {},
            onTripDiscarded = {},
            onLiveMovement = { dist, _, durMs ->
                liveMovementDistance += dist
                liveMovementDurationMs += durMs
            }
        )

        val t0 = 1000000L
        engine.restoreActiveTripState(
            tripId = 42L,
            startTime = t0 - 600000L,
            distanceMeters = 10000.0,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = null
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(10000.0, engine.totalDistanceMeters, 0.01)

        // Vehicle enters tunnel at (21.900000, 83.400000)
        val entryLat = 21.900000
        val entryLon = 83.400000
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 1000L)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 2000L)
        engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 72.0f, timestamp = t0 + 3100L)

        // 45s tunnel transit: GPS lost from t0 + 3100L until t0 + 48100L (> 15s threshold)
        val outageDurationMs = 45000L
        val exitTimeMs = t0 + 3100L + outageDurationMs
        // Vehicle travels 900m North at 72 km/h (20 m/s)
        val exitLat = entryLat + (900.0 / 111139.0)
        val exitLon = entryLon

        // Emerging from tunnel: GPS lock reacquired with 12m accuracy (<= 25m threshold)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 1000L)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 2000L)
        engine.processCoordinates(exitLat, exitLon, accuracy = 12.0f, speedKmh = 72.0f, timestamp = exitTimeMs + 3100L)

        // Geodesic bridge must accumulate ~900m into totalDistanceMeters
        val netDistanceAccumulated = engine.totalDistanceMeters - 10000.0
        assertTrue("Bridged distance should be ~900m (actual: $netDistanceAccumulated)", netDistanceAccumulated in 890.0..910.0)
        assertTrue("Live movement listener received bridged distance", liveMovementDistance in 890.0..910.0)
        assertEquals("Driving duration must accumulate 45s outage", 600000L + 45000L, liveMovementDurationMs)
    }

    @Test
    fun testTripDetectionEngine_TunnelOutagePlausibilityGates() {
        val entryLat = 21.900000
        val entryLon = 83.400000
        val t0 = 2000000L

        fun runOutageScenario(exitLat: Double, exitLon: Double, outageSec: Long, exitAccuracy: Float): Double {
            val engine = TripDetectionEngine(
                onPointReceived = {},
                onTripStarted = {},
                onTripUpdated = { _, _ -> },
                onTripCompleted = {},
                onTripDiscarded = {}
            )
            engine.restoreActiveTripState(tripId = 99L, startTime = t0 - 10000L, distanceMeters = 0.0, drivingMs = 10000L, stationaryMs = 0L, lastLocation = null)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 1000L)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 2000L)
            engine.processCoordinates(entryLat, entryLon, accuracy = 5.0f, speedKmh = 60.0f, timestamp = t0 + 3100L)

            val exitTime = t0 + 3100L + (outageSec * 1000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 1000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 2000L)
            engine.processCoordinates(exitLat, exitLon, accuracy = exitAccuracy, speedKmh = 60.0f, timestamp = exitTime + 3100L)
            return engine.totalDistanceMeters
        }

        // Gate 1: Degraded exit accuracy (35m > 25m threshold) -> Bridge rejected
        val exitLatNormal = entryLat + (900.0 / 111139.0)
        val degradedDist = runOutageScenario(exitLatNormal, entryLon, outageSec = 45L, exitAccuracy = 35.0f)
        assertEquals("Degraded accuracy (>25m) must reject geodesic bridge", 0.0, degradedDist, 0.01)

        // Gate 2: Vehicle stopped in tunnel (moved only 4m < 10m threshold) -> Bridge rejected
        val standstillLat = entryLat + (4.0 / 111139.0)
        val standstillDist = runOutageScenario(standstillLat, entryLon, outageSec = 60L, exitAccuracy = 10.0f)
        assertEquals("Standstill inside tunnel (<10m) must reject bridge", 0.0, standstillDist, 0.01)

        // Gate 3: Multipath teleport jump (50 km in 30s = 6000 km/h > 220 km/h) -> Bridge rejected
        val teleportLat = entryLat + (50000.0 / 111139.0)
        val teleportDist = runOutageScenario(teleportLat, entryLon, outageSec = 30L, exitAccuracy = 10.0f)
        assertEquals("Teleport jump (>220 km/h) must reject bridge", 0.0, teleportDist, 0.01)
    }

    // =========================================================================
    // PART 2: SpeedometerCluster calculateSpeedometerDecay Production Method
    // =========================================================================

    @Test
    fun testSpeedometerDecayCurve_ProductionModel() {
        val initialSpeed = 80.0f

        // Stage 1: Under 2s maintains 100% speed
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 0L), 0.01f)
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 1000L), 0.01f)
        assertEquals(80.0f, calculateSpeedometerDecay(initialSpeed, 2000L), 0.01f)

        // Stage 2: Aerodynamic coasting (-2.0 km/h per second)
        // At 3.5s (1.5s into coasting): 80 - (1.5 * 2) = 77.0 km/h
        assertEquals(77.0f, calculateSpeedometerDecay(initialSpeed, 3500L), 0.01f)
        // At 5.0s (3.0s into coasting): 80 - (3.0 * 2) = 74.0 km/h
        assertEquals(74.0f, calculateSpeedometerDecay(initialSpeed, 5000L), 0.01f)

        // Stage 3 Continuity: 5000ms to 5001ms boundary gap must be <= 0.01 km/h
        val speed5000 = calculateSpeedometerDecay(initialSpeed, 5000L)
        val speed5001 = calculateSpeedometerDecay(initialSpeed, 5001L)
        val delta5000 = Math.abs(speed5000 - speed5001)
        assertTrue("Boundary gap at 5000ms must be <= 0.01 km/h (actual: $delta5000)", delta5000 <= 0.01f)

        // Stage 3 Termination: reaches exactly 0 at 10s
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 10000L), 0.01f)

        // Stage 4: Beyond 10s remains strictly 0
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 10001L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 15000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 60000L), 0.01f)
    }

    @Test
    fun testSpeedometerDecayCurve_StrictMonotonicityAcross15Seconds() {
        val initialSpeed = 120.0f
        var prevSpeed = initialSpeed

        for (stepMs in 0L..15000L step 50L) {
            val speed = calculateSpeedometerDecay(initialSpeed, stepMs)
            assertTrue("Speed must be non-negative at step $stepMs", speed >= 0f)
            assertTrue("Decay must be monotonic non-increasing at step $stepMs", speed <= prevSpeed + 0.001f)
            prevSpeed = speed
        }
    }

    @Test
    fun testSpeedometerDecayCurve_LowSpeedBoundaryCoercion() {
        val initialSpeed = 4.0f
        // 4.0 km/h decays at -2.0 km/h/s from 2000ms -> hits 0 at 4000ms
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 4000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 4500L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 7000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(initialSpeed, 12000L), 0.01f)

        // Zero and negative speed guards
        assertEquals(0.0f, calculateSpeedometerDecay(0.0f, 1000L), 0.01f)
        assertEquals(0.0f, calculateSpeedometerDecay(-10.0f, 1000L), 0.01f)
    }
}
```

---

## 5. Verification Method

### 5.1 Verification Commands
Execute the full unit test suite to verify 0 failures and 100% pass:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.antigravity.drivingreport.TunnelGeodesicBridgingTest
.\gradlew.bat testDebugUnitTest --tests com.antigravity.drivingreport.Milestone1EmpiricalStressTest
.\gradlew.bat testDebugUnitTest
```

### 5.2 Verification Criteria
1. `TunnelGeodesicBridgingTest` passes cleanly with **0 failures** and **zero private duplicate helper methods**.
2. `Milestone1EmpiricalStressTest.testF4_SpeedometerDecayCurve_StrictMonotonicityAndContinuity` passes with `delta <= 0.01f` at 5000ms.
3. Decoupled geodesy executes without `Method distanceBetween in android.location.Location not mocked` runtime exceptions.
4. Total unit test suite executes 177+ tests with 0 failures and 0 errors.
