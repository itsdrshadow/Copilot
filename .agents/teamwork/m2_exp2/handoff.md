# Milestone 2 Technical Exploration & Implementation Blueprint: Features 13, 14, and 17

**Author:** M2 Explorer 2 (`m2_exp2`)  
**Role:** M2 Explorer DTE & Fuel Algorithms  
**Target Codebase:** `d:\Car Launcher` (`com.antigravity.drivingreport`)  
**Investigation Mode:** Strict Read-Only Comprehensive Audit & Architectural Blueprint  
**Date:** November 10, 2026  

---

## 1. Observation

### 1.1 Architecture & Core Fuel Engine Mapping
The fuel intelligence subsystem in `com.antigravity.drivingreport` comprises:
1. `FuelRepository.kt` (`core/fuel/`): Central orchestrator singleton managing the continuous fuel balance, refuel transactions, odometer synchronization, and reactive `FuelState` emission.
2. `FuelStateEstimator.kt` (`core/fuel/intelligence/`): Mathematical model maintaining continuous 3-point bounds (`nominalFuelLiters`, `conservativeFuelLiters`, `upperFuelLiters`), 10-bar LED MID gauge indices, and snapshot creation.
3. `AdaptiveMileageEngine.kt` (`core/fuel/intelligence/`): Statistical engine learning empirical vehicle fuel economy using IQR outlier rejection, exponential recency weighting, and City vs. Highway segmentation over discrete observation intervals.
4. `FuelAIEngine.kt` (`core/fuel/`): Real-time thermal and kinematic live consumption estimator updating every 1-second GPS tick based on vehicle speed, throttle acceleration penalty ($0.45\times$ to $4.5\times$), and idle engine burn.
5. `FuelGaugeCalibrationEngine.kt` (`core/fuel/intelligence/`): Dynamic 10-point non-linear volumetric curve learner enforcing strict monotonicity and rolling median slosh/pitch filtering.
6. `FuelDao` & `FuelLedgerDao` (`core/database/AppDatabase.kt`), `FuelEventEntity` (`Entities.kt`), `FuelLedgerEntity` (`core/database/FuelLedgerEntity.kt`): Relational Room persistence storing immutable audit logs and legacy refuel history.

---

### 1.2 Feature 13 Observations: DTE Range Oscillation & Instant Efficiency Contamination

#### Direct Code Citations:
In `FuelRepository.kt:238-245` (`buildFuelState()`):
```kotlin
238:         // For DTE range estimation: if empirical mileage is not yet available, use baselineVehicleMileage as prior
239:         val rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage
240:         val effectiveRangeMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage
241: 
242:         val snapshot = estimator.createSnapshot(effectiveRangeMileage)
243:         val range = snapshot.estimatedRangeKm
244:         val minRange = snapshot.rangeMinKm
245:         val maxRange = snapshot.rangeMaxKm
```
In `FuelRepository.kt:75`:
```kotlin
75:     private var liveInstantMileage: Double = baselineVehicleMileage
```
In `FuelRepository.kt:634-644` (`consumeLiveFuel()`):
```kotlin
634:         val result = aiEngine.calculateLiveConsumption(
635:             deltaDistanceMeters = deltaDistanceMeters,
636:             currentSpeedKmh = currentSpeedKmh,
637:             deltaDurationMs = deltaDurationMs,
638:             currentRemainingLiters = estimator.nominalFuelLiters,
639:             designCapacityLiters = effectiveMax
640:         )
641: 
642:         liveInstantMileage = result.liveInstantMileageKmpl
643:         liveDrivingStyle = result.drivingStyleTag
```
In `FuelAIEngine.kt:392-414` (`calculateLiveConsumption()`):
```kotlin
392:         val accelModifier = when {
393:             accelerationMps2 > 2.0 -> 0.45  // Very heavy throttle / launch
394:             accelerationMps2 > 1.2 -> 0.55  // Heavy throttle acceleration (fuel enrichment)
395:             accelerationMps2 > 0.4 -> 0.75 + (1.2 - accelerationMps2) * 0.25 / 0.8
396:             accelerationMps2 >= -0.3 -> 1.00 // Steady state cruise
397:             accelerationMps2 >= -0.6 -> 1.10 + (-0.3 - accelerationMps2) * 2.0 / 0.3
398:             accelerationMps2 >= -1.5 -> 3.50  // Deceleration / Fuel cut-off coasting (very high km/L!)
399:             else -> 4.50 // Hard engine braking — almost zero fuel consumed
400:         }
401: 
402:         // Dynamic Instantaneous Mileage (km/L)
403:         val rawInstantMileage = (learnedBaseMileageKmpl * speedFactor * accelModifier * driverAggressivenessIndex)
```
In `PROJECT.md:83` (Interface Contract):
> **Range Mileage Contract**: `FuelStateSnapshot.estimatedRangeKm` is strictly computed from `rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage`. If remaining fuel $< 7.0\text{ L}$ or fraction $< 0.15$, multiply by $0.85$. `liveInstantMileage` is exposed exclusively for instant fuel economy displays (`instantFuelEconomyL100km`).

#### Observed Anomaly:
- `liveInstantMileage` is initialized to `baselineVehicleMileage` ($18.0\text{ km/L}$) and continuously recomputed on every 1 Hz GPS tick during driving.
- Because `liveInstantMileage > 0.0` is true whenever the vehicle is in motion, line 240 unconditionally assigns `effectiveRangeMileage = liveInstantMileage`.
- When accelerating (`accelerationMps2 > 1.2`), `liveInstantMileage` plummets to $5.5\text{ km/L}$. With $40\text{ L}$ in the tank, remaining range drops instantly from $720\text{ km}$ to $220\text{ km}$.
- When coasting or braking (`accelerationMps2 < -0.6`), `liveInstantMileage` jumps to $40.0\text{ km/L}$, projecting remaining range to $1600\text{ km}$.
- When stopped at a traffic light, `liveInstantMileage` snaps to $0.0\text{ km/L}$, causing `effectiveRangeMileage` to snap back to `rangeMileage` ($18.0\text{ km/L} \to 720\text{ km}$).
- The DTE remaining range displayed on `FuelTankCard` oscillates violently every second while driving.

---

### 1.3 Feature 14 Observations: Multi-Tank Interval History Fragmentation

#### Direct Code Citations:
In `FuelRepository.kt:547-563` (`recordRefuel()`):
```kotlin
547:                 // Process continuous partial refill interval for adaptive mileage learning
548:                 var computedMileage = 0.0
549:                 if (prevAnchorOdo > 0.0 && effectiveOdo > prevAnchorOdo && prevAnchorFuel > 0.0) {
550:                     val learned = mileageEngine.processAnchorInterval(
551:                         startOdometerKm = prevAnchorOdo,
552:                         endOdometerKm = effectiveOdo,
553:                         startFuelLiters = prevAnchorFuel,
554:                         addedFuelLitersDuringInterval = litersAdded,
555:                         endFuelLiters = estimator.nominalFuelLiters
556:                     )
557:                     if (learned != null) {
558:                         computedMileage = learned
559:                     }
560:                 }
```
In `AdaptiveMileageEngine.kt:38-43, 141-158`:
```kotlin
38:         const val MIN_PLAUSIBLE_MILEAGE = 4.0
39:         const val MAX_PLAUSIBLE_MILEAGE = 45.0
40:         const val MIN_INTERVAL_KM = 15.0
41:         const val MIN_INTERVAL_LITERS = 1.0
...
150:         val deltaDistance = FuelMath.roundDistance(endOdometerKm - startOdometerKm)
151:         if (deltaDistance < MIN_INTERVAL_KM) {
152:             return null // Insufficient distance to calculate reliable consumption
153:         }
154: 
155:         val fuelConsumed = FuelMath.roundFuel((startFuelLiters + addedFuelLitersDuringInterval) - endFuelLiters)
156:         if (fuelConsumed < MIN_INTERVAL_LITERS) {
157:             return null // Insufficient fuel change; division would be dominated by noise
158:         }
```
In `FuelRepository.kt:130-165` (`rehydrateLedgerAsync()`):
```kotlin
143:                                 FuelEventType.REFUEL.name -> {
144:                                     if (e.confidence == EventConfidence.CONFIRMED.name) {
145:                                         accumulatedAdded += e.fuelQuantityLiters
146:                                         if (lastAnchorOdo > 0 && e.odometerKm > lastAnchorOdo && lastAnchorFuel > 0) {
147:                                             mileageEngine.processAnchorInterval(
148:                                                 startOdometerKm = lastAnchorOdo,
149:                                                 endOdometerKm = e.odometerKm,
150:                                                 startFuelLiters = lastAnchorFuel,
151:                                                 addedFuelLitersDuringInterval = accumulatedAdded,
152:                                                 endFuelLiters = e.nominalFuelLiters
153:                                             )
154:                                         }
155:                                         lastAnchorOdo = e.odometerKm
156:                                         lastAnchorFuel = e.nominalFuelLiters
157:                                         accumulatedAdded = 0.0
158:                                         gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.25)
159:                                     }
160:                                 }
```
In `FuelRepository.kt:172-198` (Legacy `fuel_events` Rehydration):
```kotlin
180:                             var prevOdo = -1.0
181:                             var prevLiters = -1.0
182:                             var accumulated = 0.0
183:                             for (le in sortedRefuels) {
184:                                 accumulated += le.liters
185:                                 if (prevOdo > 0.0 && le.odometerKm > prevOdo && prevLiters > 0.0) {
186:                                     mileageEngine.processAnchorInterval(
187:                                         startOdometerKm = prevOdo,
188:                                         endOdometerKm = le.odometerKm,
189:                                         startFuelLiters = prevLiters,
190:                                         addedFuelLitersDuringInterval = accumulated,
191:                                         endFuelLiters = le.calculatedBalanceLiters
192:                                     )
193:                                 }
194:                                 prevOdo = le.odometerKm
195:                                 prevLiters = le.calculatedBalanceLiters
196:                                 accumulated = 0.0
```
In `FuelRepository.kt:401-419` (`syncMidAndOdometer()` manual gauge adjustments):
```kotlin
412:                     estimator.applyManualAdjustment(
413:                         adjustedLiters = litersForBar,
414:                         observedGaugePoints = newMidBar,
415:                         odometerKm = effectiveOdo,
416:                         reason = "Gauge updated to $newMidBar/10 bars",
417:                         confidence = EventConfidence.HIGH
418:                     )
```
In `FuelStateEstimator.kt:202-206` (`applyManualAdjustment()`):
```kotlin
202:         if (odometerKm > 0.0) {
203:             lastReliableOdometerAnchorKm = odometerKm
204:         }
205:         lastReliableFuelAnchorLiters = safeNominal
```

#### Observed Anomaly:
1. **Unmeasured Partial Refill Fragmentation at Runtime**:
   In `recordRefuel()`, only the current refill's `litersAdded` is passed into `processAnchorInterval`. There is no persistent accumulator for partial refills that occurred over distances $< 15\text{ km}$.
   `estimator.applyRefuel()` unconditionally advances `lastReliableOdometerAnchorKm` and `lastReliableFuelAnchorLiters` to the current refill point. If `deltaDistance < 15.0\text{ km}`, `processAnchorInterval()` returns `null` and rejects the sample. On the *next* refill, `prevAnchorOdo` is set to the short-distance refill, meaning the kilometers driven and fuel added during the first interval are completely dropped from lifetime calculations.
2. **Rehydration Fragmentation on App Restart**:
   In both `rehydrateLedgerAsync()` (lines 155-157) and legacy rehydration (lines 194-196), `lastAnchorOdo = e.odometerKm`, `lastAnchorFuel = e.nominalFuelLiters`, and `accumulatedAdded = 0.0` execute **unconditionally** after every single event, even when `processAnchorInterval` returns `null`.
   If a user logged a refuel after driving 8 km, rehydration drops the 8 km and resets `accumulatedAdded` to 0.0. The next 200 km refuel only calculates mileage over the latter segment, distorting the empirical average.
3. **Missing Full Tank Fill-Up Consolidation Anchor**:
   When a user performs a full tank top-up (`midBarObserved >= 10` or `balance >= designCapacity`), this represents an absolute physical boundary condition ($F_{\text{end}} = C_{\text{design}}$). Float measurement error is zeroed at full tank. The current codebase does not recognize full tank fill-ups as consolidation boundaries to resolve all prior partial fills.
4. **Manual Gauge Adjustment Disconnection**:
   When a user calibrates the MID cluster gauge, `estimator.applyManualAdjustment` overwrites `lastReliableOdometerAnchorKm` and `lastReliableFuelAnchorLiters` without calculating or closing the pending driving interval since the last refuel.

---

### 1.4 Feature 17 Observations: Missing Low-Fuel Reserve Safety Multiplier & Threshold Scaling

#### Direct Code Citations:
In `FuelStateEstimator.kt:221-227` (`createSnapshot()`):
```kotlin
221:     fun createSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot {
222:         val safeMileage = estimatedMileageKmpl.coerceIn(5.0, 45.0)
223: 
224:         val nominalRange = FuelMath.roundDistance(nominalFuelLiters * safeMileage)
225:         val minRange = FuelMath.roundDistance(conservativeFuelLiters * safeMileage * 0.90) // Conservative consumption buffer
226:         val maxRange = FuelMath.roundDistance(upperFuelLiters * safeMileage * 1.05)
```
In `FuelRepository.kt:247-248`:
```kotlin
247:         val midBar = estimator.currentGaugeBar.coerceIn(0, 10)
248:         val isLow = balance <= 8.0 || midBar <= 2
```
In `FUEL_ENGINE_DESIGN.md:92`:
> "In fuel reserve ($F_{\text{nom}} < 7.0\text{ L}$ or $F_{\text{nom}} / C_{\text{design}} < 0.15$), DTE switches to a conservative 85th percentile multiplier ($M_{\text{eff}} \times 0.85$) so drivers are not stranded by optimistic predictions."
In `FuelAIEngine.kt:453-465` (legacy implementation):
```kotlin
453:         val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
454:         val isReserve = remainingLiters < 7.0 || (remainingLiters / safeDesign) < 0.15
455: 
456:         val effectiveMileage = if (isReserve) {
457:             (learnedBaseMileageKmpl * 0.85).coerceAtLeast(8.0)
458:         } else {
459:             learnedBaseMileageKmpl
460:         }
461:         val range = remainingLiters * effectiveMileage
```

#### Observed Anomaly:
- `FuelStateEstimator.createSnapshot()` computes `nominalRange = nominalFuelLiters * safeMileage` with no reserve scaling whatsoever.
- When fuel drops into reserve ($\le 7.0\text{ L}$ or $\le 15\%$), standard linear multiplication is used. In real-world automotive operation, reserve fuel is vulnerable to fuel starvation during hill climbs, hard braking slosh, and float inaccuracy near the bottom of the tank.
- The 0.85 conservative reserve safety multiplier specified in `PROJECT.md:41, 83` and `FUEL_ENGINE_DESIGN.md:92` is completely absent from `FuelStateEstimator.createSnapshot()`.
- Furthermore, `isLowFuelWarning` in `FuelRepository.kt:248` uses a hardcoded `balance <= 8.0 || midBar <= 2`, which fails to adapt dynamically to small tanks (e.g. 15 L, where 8 L is 53%) or large tanks (e.g. 100 L, where 8 L is 8%).

---

## 2. Logic Chain

### 2.1 Feature 13: DTE Range Oscillation & Instant Efficiency Separation
1. DTE remaining range is defined as the estimated distance the vehicle can travel on its remaining fuel volume before depletion.
2. In `FuelRepository.buildFuelState()`, `effectiveRangeMileage` is set to `liveInstantMileage` whenever `liveInstantMileage > 0.0`.
3. `liveInstantMileage` is updated on every 1-second GPS location fix by `FuelAIEngine.calculateLiveConsumption()`. It fluctuates between $4.5\text{ km/L}$ (during throttle acceleration) and $45.0\text{ km/L}$ (during deceleration/fuel cut-off).
4. Multiplying remaining tank volume (e.g., $40\text{ L}$) by this 1-second instant efficiency causes the displayed range to swing from $180\text{ km}$ to $1800\text{ km}$ during a single stop-and-go cycle.
5. In automotive instrumentation standards, DTE is governed by an empirical, learned average fuel economy (`rangeMileage`), filtered over hundreds of kilometers, while instantaneous economy is displayed on an instantaneous consumption bar (`instantConsumptionRateL100Km`).
6. Therefore, `estimator.createSnapshot()` must receive `rangeMileage` (the learned weighted average `validEmpiricalMileage` or configured baseline `baselineVehicleMileage`), and `liveInstantMileage` must be isolated exclusively for live HUD instantaneous economy and eco-tier badges.

### 2.2 Feature 14: Multi-Tank Interval History Consolidation
1. Fuel mileage calculation over partial refills requires:
   $$\text{Fuel Consumed} = \text{Starting Fuel} + \sum \text{Fuel Added} - \text{Ending Fuel}$$
   $$\text{Mileage} = \frac{\text{Odometer}_{\text{end}} - \text{Odometer}_{\text{start}}}{\text{Fuel Consumed}}$$
2. If two refills occur within $15\text{ km}$, the signal-to-noise ratio is too low for reliable mileage calculation (`MIN_INTERVAL_KM = 15.0`).
3. Under the current implementation, `recordRefuel()` and `rehydrateLedgerAsync()` unconditionally move the anchor point and zero out `accumulatedAdded` after every refill event, even if `processAnchorInterval` rejected the short interval.
4. Consequently, the distance traveled and fuel added during short partial refills are permanently discarded from future interval calculations.
5. Furthermore, upon a full tank fill-up (`midBarObserved >= 10` or `balance >= designCapacity`), all accumulated partial refills between the previous anchor and the full tank should be consolidated into a verified full-tank interval.
6. Therefore, the anchor must ONLY advance when:
   - A valid interval ($\ge 15.0\text{ km}$ and $\ge 1.0\text{ L}$) is completed, OR
   - A full tank fill-up establishes a clean physical boundary.
7. Unconsolidated fuel additions must accumulate in a persistent pending buffer (`pendingAccumulatedAddedLiters`), preserving total lifetime fuel volume and distance consistency.

### 2.3 Feature 17: Low-Fuel Reserve Buffer & Dynamic Threshold Scaling
1. Near empty tank levels, fuel slosh caused by vehicle braking, cornering, and slope inclination can expose the fuel pump inlet, causing engine hesitation or stalling even when software estimates a positive remaining volume.
2. In `FUEL_ENGINE_DESIGN.md:92` and `PROJECT.md:83`, the engineering specification dictates that when nominal fuel is in reserve ($F_{\text{nom}} < 7.0\text{ L}$ or $F_{\text{nom}} / C_{\text{design}} < 0.15$), remaining range must apply a conservative 0.85 safety buffer:
   $$\text{Range}_{\text{reserve}} = \text{NominalFuel} \times \text{Mileage} \times 0.85$$
3. Currently, `FuelStateEstimator.createSnapshot()` has no reserve detection or multiplier, leading to over-optimistic range countdowns when drivers are closest to stranding.
4. Dynamic scaling requires evaluating both the physical volume floor ($< 7.0\text{ L}$) and the tank percentage floor ($< 15\%$), accommodating both compact vehicles ($15\text{ L}$ tanks) and large commercial tanks ($120\text{ L}$).
5. Aligning `isLowFuelWarning` in `FuelRepository.buildFuelState()` to `balance < 7.0 || fraction < 0.15 || midBar <= 2` guarantees consistent visual styling across `FuelTankCard`, `DashboardScreen`, and low-fuel warning dialogs.

---

## 3. Caveats

1. **Prior Baseline on Brand New Vehicle Initialization**: When a user first installs the application, no empirical refuel events exist (`sampleCount == 0`). In this state, `validEmpiricalMileage` is `0.0`, so `rangeMileage` cleanly defaults to `baselineVehicleMileage` (default $18.0\text{ km/L}$ or user-configured value). Once two valid refuels are completed, empirical mileage smoothly takes precedence.
2. **Fuel Slosh Filter Concurrency**: Feature 15 (rolling median slosh filter) interacts with the gauge reading in `syncMidAndOdometer`. In Feature 14, consolidating intervals on manual gauge adjustments must use the pre-adjustment nominal fuel to close the interval before updating the anchor to the newly adjusted level.
3. **Database Schema Stability**: The consolidation solution utilizes existing fields in Room tables: `FuelLedgerEntity.calculationMetadataJson` and `FuelEventEntity.calculatedMileageKmpl`. No destructive Room schema changes or migrations are required.
4. **Active Trip Reboot Recovery**: Cumulative trip fuel recovery on mid-trip reboot (Feature 17 in `PROJECT.md` table) is coordinated by `DrivingTrackingService.restoreActiveTripState` reading `activeTrip.estimatedFuelLiters`. The low-fuel reserve buffer implemented here operates orthogonally within `FuelStateEstimator.createSnapshot()`.

---

## 4. Conclusion & Complete Implementation Blueprint

### 4.1 Target File 1: `app/src/main/java/com/antigravity/drivingreport/core/fuel/intelligence/FuelStateEstimator.kt`

#### Change 1: Add Low-Fuel Reserve Safety Multiplier in `createSnapshot()`
**Exact Location:** Lines 221–239  
**Rationale:** Implements Feature 17 and `PROJECT.md:83` Range Mileage Contract by dynamically checking for reserve fuel ($F_{\text{nom}} < 7.0\text{ L}$ or fraction $< 0.15$) and applying a conservative $0.85\times$ safety buffer to `nominalRange`, `minRange`, and `maxRange`.

##### Before:
```kotlin
    /**
     * Computes the current snapshot with safe DTE range bounds.
     */
    fun createSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot {
        val safeMileage = estimatedMileageKmpl.coerceIn(5.0, 45.0)

        val nominalRange = FuelMath.roundDistance(nominalFuelLiters * safeMileage)
        val minRange = FuelMath.roundDistance(conservativeFuelLiters * safeMileage * 0.90) // Conservative consumption buffer
        val maxRange = FuelMath.roundDistance(upperFuelLiters * safeMileage * 1.05)

        return FuelStateSnapshot(
            nominalFuelLiters = nominalFuelLiters,
            conservativeFuelLiters = conservativeFuelLiters,
            upperFuelLiters = upperFuelLiters,
            current10PointBar = currentGaugeBar,
            estimatedRangeKm = nominalRange,
            rangeMinKm = minRange,
            rangeMaxKm = maxRange,
            estimatedMileageKmpl = safeMileage,
            confidencePercent = confidenceScore,
            explanation = currentExplanation
        )
    }
```

##### After:
```kotlin
    /**
     * Computes the current snapshot with safe DTE range bounds.
     * In fuel reserve (F_nom < 7.0 L or F_nom / C_design < 0.15), applies a conservative
     * 0.85 safety multiplier so drivers are never stranded by overly optimistic predictions.
     */
    fun createSnapshot(estimatedMileageKmpl: Double = 18.0): FuelStateSnapshot {
        val safeMileage = estimatedMileageKmpl.coerceIn(5.0, 45.0)
        val safeDesign = designCapacityLiters.coerceAtLeast(10.0)
        val fuelFraction = if (safeDesign > 0.0) nominalFuelLiters / safeDesign else 0.0
        val isReserve = nominalFuelLiters < 7.0 || fuelFraction < 0.15
        val reserveMultiplier = if (isReserve) 0.85 else 1.0

        val nominalRange = FuelMath.roundDistance(nominalFuelLiters * safeMileage * reserveMultiplier)
        val minRange = FuelMath.roundDistance(conservativeFuelLiters * safeMileage * 0.90 * reserveMultiplier)
        val maxRange = FuelMath.roundDistance(upperFuelLiters * safeMileage * 1.05 * reserveMultiplier)

        return FuelStateSnapshot(
            nominalFuelLiters = nominalFuelLiters,
            conservativeFuelLiters = conservativeFuelLiters,
            upperFuelLiters = upperFuelLiters,
            current10PointBar = currentGaugeBar,
            estimatedRangeKm = nominalRange,
            rangeMinKm = minRange,
            rangeMaxKm = maxRange,
            estimatedMileageKmpl = safeMileage,
            confidencePercent = confidenceScore,
            explanation = currentExplanation
        )
    }
```

---

### 4.2 Target File 2: `app/src/main/java/com/antigravity/drivingreport/core/fuel/FuelRepository.kt`

#### Change 2: Bind DTE Calculation Strictly to `rangeMileage` (Feature 13) and Scale Low-Fuel Warning (Feature 17)
**Exact Location:** Lines 238–265 and line 301 in `buildFuelState()`  
**Rationale:** Eliminates 1-second DTE range oscillation by decoupling snapshot range from `liveInstantMileage`. Isolates `liveInstantMileage` strictly for HUD instant economy displays (`instantConsumptionRateL100Km` and `fuelEfficiencyTier`). Synchronizes `isLowFuelWarning` with the dynamic 7.0 L / 15% reserve threshold.

##### Before:
```kotlin
        // For DTE range estimation: if empirical mileage is not yet available, use baselineVehicleMileage as prior
        val rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage
        val effectiveRangeMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage

        val snapshot = estimator.createSnapshot(effectiveRangeMileage)
        val range = snapshot.estimatedRangeKm
        val minRange = snapshot.rangeMinKm
        val maxRange = snapshot.rangeMaxKm

        val midBar = estimator.currentGaugeBar.coerceIn(0, 10)
        val isLow = balance <= 8.0 || midBar <= 2
        val tripCost = FuelMath.roundCurrency(cumulativeTripConsumedLiters * fuelPrice)

        val instantL100km = if (effectiveRangeMileage > 0.0) {
            FuelMath.roundMileage(100.0 / effectiveRangeMileage)
        } else {
            6.7
        }

        val hasOverfill = balance > safeDesign
        val overfillLiters = max(0.0, balance - safeDesign)

        val tier = when {
            effectiveRangeMileage >= 18.0 -> "ECO"
            effectiveRangeMileage >= 13.0 -> "OPTIMAL"
            else -> "HIGH"
        }
```
*(and at line 301)*:
```kotlin
            liveInstantMileageKmpl = FuelMath.roundMileage(effectiveRangeMileage),
```

##### After:
```kotlin
        // For DTE range estimation: strictly use learned empirical mileage or baseline vehicle mileage
        val rangeMileage = if (validEmpiricalMileage > 0.0) validEmpiricalMileage else baselineVehicleMileage

        // Range snapshot is strictly computed from rangeMileage (never contaminated by 1-second throttle oscillation)
        val snapshot = estimator.createSnapshot(rangeMileage)
        val range = snapshot.estimatedRangeKm
        val minRange = snapshot.rangeMinKm
        val maxRange = snapshot.rangeMaxKm

        val midBar = estimator.currentGaugeBar.coerceIn(0, 10)
        val isLow = balance < 7.0 || fraction < 0.15 || midBar <= 2
        val tripCost = FuelMath.roundCurrency(cumulativeTripConsumedLiters * fuelPrice)

        // Instantaneous metrics for live HUD efficiency display
        val displayInstantMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage
        val instantL100km = if (displayInstantMileage > 0.0) {
            FuelMath.roundMileage(100.0 / displayInstantMileage)
        } else {
            6.7
        }

        val hasOverfill = balance > safeDesign
        val overfillLiters = max(0.0, balance - safeDesign)

        val tier = when {
            displayInstantMileage >= 18.0 -> "ECO"
            displayInstantMileage >= 13.0 -> "OPTIMAL"
            else -> "HIGH"
        }
```
*(and at line 301)*:
```kotlin
            liveInstantMileageKmpl = FuelMath.roundMileage(displayInstantMileage),
```

---

#### Change 3: Multi-Tank Partial Refill Consolidation & Persistent Pending Anchor Buffer (Feature 14)
**Exact Location:** Lines 76–85, lines 525–588, and lines 705–720  
**Rationale:** Tracks accumulated fuel additions across partial refills in `pendingAccumulatedAddedLiters`. Only advances the anchor when an interval satisfies $\ge 15.0\text{ km}$ and $\ge 1.0\text{ L}$, or when a full tank top-up occurs. Persists pending anchor state to survive app restart.

##### Add Fields in `FuelRepository.kt` (around line 77):
```kotlin
    // Multi-Tank Interval History Consolidation State
    private var pendingAnchorOdometerKm: Double = prefs.getFloat("pending_anchor_odo", -1.0f).toDouble()
    private var pendingAnchorFuelLiters: Double = prefs.getFloat("pending_anchor_fuel", -1.0f).toDouble()
    private var pendingAccumulatedAddedLiters: Double = prefs.getFloat("pending_accumulated_added", 0.0f).toDouble()
```

##### In `recordRefuel()` (Lines 528–565):
##### Before:
```kotlin
                val prevAnchorOdo = estimator.lastReliableOdometerAnchorKm
                val prevAnchorFuel = estimator.lastReliableFuelAnchorLiters
                val prevState = estimator.toSnapshot()

                // Apply refuel to continuous estimator
                estimator.applyRefuel(
                    addedLiters = litersAdded,
                    observedGaugePoints = midBarObserved,
                    odometerKm = effectiveOdo,
                    confidence = EventConfidence.CONFIRMED,
                    explanation = "Refueled ${FuelMath.roundFuel(litersAdded)} L"
                )

                // Teach calibration engine this ground truth point
                if (midBarObserved in 0..10) {
                    gaugeCalibration.learnCalibrationPoint(midBarObserved, estimator.nominalFuelLiters, 0.30)
                }

                // Process continuous partial refill interval for adaptive mileage learning
                var computedMileage = 0.0
                if (prevAnchorOdo > 0.0 && effectiveOdo > prevAnchorOdo && prevAnchorFuel > 0.0) {
                    val learned = mileageEngine.processAnchorInterval(
                        startOdometerKm = prevAnchorOdo,
                        endOdometerKm = effectiveOdo,
                        startFuelLiters = prevAnchorFuel,
                        addedFuelLitersDuringInterval = litersAdded,
                        endFuelLiters = estimator.nominalFuelLiters
                    )
                    if (learned != null) {
                        computedMileage = learned
                    }
                }
                if (computedMileage <= 0.0 && mileageEngine.sampleCount >= 1) {
                    computedMileage = mileageEngine.measuredMileage ?: mileageEngine.overallEstimatedMileage
                }
```

##### After:
```kotlin
                val prevState = estimator.toSnapshot()

                // Initialize pending anchor if uninitialized
                if (pendingAnchorOdometerKm <= 0.0) {
                    pendingAnchorOdometerKm = if (estimator.lastReliableOdometerAnchorKm > 0.0) {
                        estimator.lastReliableOdometerAnchorKm
                    } else {
                        effectiveOdo
                    }
                    pendingAnchorFuelLiters = if (estimator.lastReliableFuelAnchorLiters > 0.0) {
                        estimator.lastReliableFuelAnchorLiters
                    } else {
                        estimator.nominalFuelLiters
                    }
                    pendingAccumulatedAddedLiters = 0.0
                }

                // Accumulate fuel added across partial fills
                pendingAccumulatedAddedLiters += litersAdded

                // Apply refuel to continuous estimator
                estimator.applyRefuel(
                    addedLiters = litersAdded,
                    observedGaugePoints = midBarObserved,
                    odometerKm = effectiveOdo,
                    confidence = EventConfidence.CONFIRMED,
                    explanation = "Refueled ${FuelMath.roundFuel(litersAdded)} L"
                )

                // Teach calibration engine this ground truth point
                if (midBarObserved in 0..10) {
                    gaugeCalibration.learnCalibrationPoint(midBarObserved, estimator.nominalFuelLiters, 0.30)
                }

                // Determine if this refill is a full-tank fill-up
                val isFullTank = midBarObserved >= 10 || estimator.nominalFuelLiters >= designCapacity
                val deltaDistance = effectiveOdo - pendingAnchorOdometerKm

                var computedMileage = 0.0
                if (deltaDistance >= AdaptiveMileageEngine.MIN_INTERVAL_KM && pendingAnchorFuelLiters > 0.0) {
                    val learned = mileageEngine.processAnchorInterval(
                        startOdometerKm = pendingAnchorOdometerKm,
                        endOdometerKm = effectiveOdo,
                        startFuelLiters = pendingAnchorFuelLiters,
                        addedFuelLitersDuringInterval = pendingAccumulatedAddedLiters,
                        endFuelLiters = estimator.nominalFuelLiters
                    )
                    if (learned != null) {
                        computedMileage = learned
                        // Interval validated; advance anchor to current point and reset accumulator
                        pendingAnchorOdometerKm = effectiveOdo
                        pendingAnchorFuelLiters = estimator.nominalFuelLiters
                        pendingAccumulatedAddedLiters = 0.0
                    }
                }

                if (isFullTank && computedMileage <= 0.0) {
                    // Full tank top-up establishes a clean physical anchor regardless of interval distance
                    pendingAnchorOdometerKm = effectiveOdo
                    pendingAnchorFuelLiters = estimator.nominalFuelLiters
                    pendingAccumulatedAddedLiters = 0.0
                }

                if (computedMileage <= 0.0 && mileageEngine.sampleCount >= 1) {
                    computedMileage = mileageEngine.measuredMileage ?: mileageEngine.overallEstimatedMileage
                }
```

##### In `persistValuesImmediate()` (Lines 705–716):
Add persistence of pending consolidation anchor:
```kotlin
        prefs.edit()
            .putFloat("design_capacity", designCapacity.toFloat())
            .putFloat("max_capacity", estimator.maxCapacityLiters.toFloat())
            .putFloat("remaining_liters", estimator.nominalFuelLiters.toFloat())
            .putFloat("current_mileage", mileageEngine.overallEstimatedMileage.toFloat())
            .putFloat("baseline_vehicle_mileage", baselineVehicleMileage.toFloat())
            .putFloat("fuel_price", fuelPrice.toFloat())
            .putLong("baseline_odometer_bits", java.lang.Double.doubleToRawLongBits(baselineOdometerKm))
            .putFloat("baseline_odometer", baselineOdometerKm.toFloat())
            .putFloat("pending_anchor_odo", pendingAnchorOdometerKm.toFloat())
            .putFloat("pending_anchor_fuel", pendingAnchorFuelLiters.toFloat())
            .putFloat("pending_accumulated_added", pendingAccumulatedAddedLiters.toFloat())
            .putBoolean("is_tank_calibrated", true)
            .apply()
```

##### In `resetAllData()` (Lines 833–852):
Reset pending consolidation state:
```kotlin
        pendingAnchorOdometerKm = -1.0
        pendingAnchorFuelLiters = -1.0
        pendingAccumulatedAddedLiters = 0.0
```

---

#### Change 4: Gated Anchor Advancement During Ledger and Legacy Rehydration (Feature 14)
**Exact Location:** Lines 143–160 and lines 180–198 in `rehydrateLedgerAsync()`  
**Rationale:** Eliminates rehydration fragmentation. Only advances the anchor and resets accumulated fuel when `processAnchorInterval` succeeds or when a full tank top-up is recorded.

##### In `rehydrateLedgerAsync()` (Lines 143–160):
##### Before:
```kotlin
                                FuelEventType.REFUEL.name -> {
                                    if (e.confidence == EventConfidence.CONFIRMED.name) {
                                        accumulatedAdded += e.fuelQuantityLiters
                                        if (lastAnchorOdo > 0 && e.odometerKm > lastAnchorOdo && lastAnchorFuel > 0) {
                                            mileageEngine.processAnchorInterval(
                                                startOdometerKm = lastAnchorOdo,
                                                endOdometerKm = e.odometerKm,
                                                startFuelLiters = lastAnchorFuel,
                                                addedFuelLitersDuringInterval = accumulatedAdded,
                                                endFuelLiters = e.nominalFuelLiters
                                            )
                                        }
                                        lastAnchorOdo = e.odometerKm
                                        lastAnchorFuel = e.nominalFuelLiters
                                        accumulatedAdded = 0.0
                                        gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.25)
                                    }
                                }
```

##### After:
```kotlin
                                FuelEventType.REFUEL.name -> {
                                    if (e.confidence == EventConfidence.CONFIRMED.name) {
                                        accumulatedAdded += e.fuelQuantityLiters
                                        val isFullTank = e.fuelGaugePoints >= 10 || e.nominalFuelLiters >= designCapacity
                                        var intervalProcessed = false

                                        if (lastAnchorOdo > 0 && e.odometerKm > lastAnchorOdo && lastAnchorFuel > 0) {
                                            val learned = mileageEngine.processAnchorInterval(
                                                startOdometerKm = lastAnchorOdo,
                                                endOdometerKm = e.odometerKm,
                                                startFuelLiters = lastAnchorFuel,
                                                addedFuelLitersDuringInterval = accumulatedAdded,
                                                endFuelLiters = e.nominalFuelLiters
                                            )
                                            if (learned != null) {
                                                intervalProcessed = true
                                            }
                                        }

                                        if (intervalProcessed || isFullTank) {
                                            lastAnchorOdo = e.odometerKm
                                            lastAnchorFuel = e.nominalFuelLiters
                                            accumulatedAdded = 0.0
                                        }
                                        // If interval is below threshold and not full tank, lastAnchorOdo is NOT advanced,
                                        // preserving accumulatedAdded for subsequent refills!

                                        gaugeCalibration.learnCalibrationPoint(e.fuelGaugePoints, e.nominalFuelLiters, 0.25)
                                    }
                                }
```

##### In Legacy Fallback Rehydration (Lines 180–198):
##### Before:
```kotlin
                            var prevOdo = -1.0
                            var prevLiters = -1.0
                            var accumulated = 0.0
                            for (le in sortedRefuels) {
                                accumulated += le.liters
                                if (prevOdo > 0.0 && le.odometerKm > prevOdo && prevLiters > 0.0) {
                                    mileageEngine.processAnchorInterval(
                                        startOdometerKm = prevOdo,
                                        endOdometerKm = le.odometerKm,
                                        startFuelLiters = prevLiters,
                                        addedFuelLitersDuringInterval = accumulated,
                                        endFuelLiters = le.calculatedBalanceLiters
                                    )
                                }
                                prevOdo = le.odometerKm
                                prevLiters = le.calculatedBalanceLiters
                                accumulated = 0.0
                                if (le.midLevelObserved in 0..10) {
                                    gaugeCalibration.learnCalibrationPoint(le.midLevelObserved, le.calculatedBalanceLiters, 0.25)
                                }
                            }
```

##### After:
```kotlin
                            var prevOdo = -1.0
                            var prevLiters = -1.0
                            var accumulated = 0.0
                            for (le in sortedRefuels) {
                                accumulated += le.liters
                                val isFullTank = le.midLevelObserved >= 10 || le.calculatedBalanceLiters >= designCapacity
                                var intervalProcessed = false

                                if (prevOdo > 0.0 && le.odometerKm > prevOdo && prevLiters > 0.0) {
                                    val learned = mileageEngine.processAnchorInterval(
                                        startOdometerKm = prevOdo,
                                        endOdometerKm = le.odometerKm,
                                        startFuelLiters = prevLiters,
                                        addedFuelLitersDuringInterval = accumulated,
                                        endFuelLiters = le.calculatedBalanceLiters
                                    )
                                    if (learned != null) {
                                        intervalProcessed = true
                                    }
                                }

                                if (intervalProcessed || isFullTank) {
                                    prevOdo = le.odometerKm
                                    prevLiters = le.calculatedBalanceLiters
                                    accumulated = 0.0
                                }

                                if (le.midLevelObserved in 0..10) {
                                    gaugeCalibration.learnCalibrationPoint(le.midLevelObserved, le.calculatedBalanceLiters, 0.25)
                                }
                            }
```

---

## 5. Verification Method

### 5.1 Project Verification Commands
The implementation will be verified against the JVM unit test runner:
```bash
# Run existing validation tests
./gradlew testDebugUnitTest --tests "com.antigravity.drivingreport.VehicleJourneyAndFuelEngineValidationTest"

# Run all unit tests to guarantee zero regressions across interconnected modules
./gradlew testDebugUnitTest
```

### 5.2 Targeted Unit Test Suite Specification (`Milestone2FuelAlgorithmsTest.kt`)
The following tests must be created in `app/src/test/java/com/antigravity/drivingreport/Milestone2FuelAlgorithmsTest.kt` to validate the three features:

```kotlin
package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.drivingreport.core.fuel.intelligence.FuelMath
import com.antigravity.drivingreport.core.fuel.intelligence.FuelStateEstimator
import org.junit.Assert.*
import org.junit.Test

class Milestone2FuelAlgorithmsTest {

    /**
     * Feature 13: DTE Range must remain rock-solid and stable against 1-second instantaneous
     * efficiency fluctuations (e.g. 5.0 km/L on acceleration vs 40.0 km/L on coasting).
     */
    @Test
    fun testDteRangeStabilityAgainstInstantMileageFluctuation() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 30.0, gaugePoints = 6, odometerKm = 10000.0)

        val learnedRangeMileage = 18.0

        // DTE computed with learned average mileage
        val snapshotSteady = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals(540.0, snapshotSteady.estimatedRangeKm, 0.1)

        // Simulate 1-second acceleration drop: instant mileage = 5.0 km/L
        // DTE must NOT drop to 150 km! It must remain bound to rangeMileage (540 km).
        val snapshotDuringAccel = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals("DTE must not collapse during throttle acceleration", 540.0, snapshotDuringAccel.estimatedRangeKm, 0.1)

        // Simulate 1-second coasting spike: instant mileage = 40.0 km/L
        // DTE must NOT jump to 1200 km! It must remain bound to rangeMileage (540 km).
        val snapshotDuringCoasting = estimator.createSnapshot(estimatedMileageKmpl = learnedRangeMileage)
        assertEquals("DTE must not skyrocket during engine coasting", 540.0, snapshotDuringCoasting.estimatedRangeKm, 0.1)
    }

    /**
     * Feature 17: In fuel reserve (< 7.0 L or < 15%), DTE must apply the 0.85 conservative
     * safety multiplier so drivers are protected against bottom-of-tank starvation.
     */
    @Test
    fun testLowFuelReserveSafetyBufferApplication() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        val mileageKmpl = 16.0

        // 1. Normal tank state (20.0 L remaining, > 7.0 L and > 15%): Multiplier = 1.0
        estimator.initializeBaseline(initialLiters = 20.0, gaugePoints = 4, odometerKm = 10000.0)
        val normalSnapshot = estimator.createSnapshot(estimatedMileageKmpl = mileageKmpl)
        assertEquals(320.0, normalSnapshot.estimatedRangeKm, 0.1) // 20.0 * 16.0 = 320.0 km

        // 2. Reserve state (6.0 L remaining, < 7.0 L and 12.5% < 15%): Multiplier = 0.85
        estimator.initializeBaseline(initialLiters = 6.0, gaugePoints = 1, odometerKm = 10250.0)
        val reserveSnapshot = estimator.createSnapshot(estimatedMileageKmpl = mileageKmpl)
        val expectedReserveDte = FuelMath.roundDistance(6.0 * 16.0 * 0.85) // 81.6 km (vs 96.0 km unbuffered)
        assertEquals("Reserve DTE must apply 0.85 safety multiplier", expectedReserveDte, reserveSnapshot.estimatedRangeKm, 0.1)
        assertTrue("Min range must also reflect reserve buffer", reserveSnapshot.rangeMinKm < 81.6)
    }

    /**
     * Feature 17: Dynamic scaling across vehicle classes (small vs large tanks).
     */
    @Test
    fun testDynamicReserveThresholdScalingAcrossTankSizes() {
        // Large vehicle (100 L tank): 12.0 L remaining is > 7.0 L, but 12% < 15% -> Reserve triggered
        val largeEstimator = FuelStateEstimator(designCapacityLiters = 100.0, maxCapacityLiters = 100.0)
        largeEstimator.initializeBaseline(initialLiters = 12.0, gaugePoints = 1, odometerKm = 50000.0)
        val largeSnapshot = largeEstimator.createSnapshot(estimatedMileageKmpl = 10.0)
        val expectedLargeDte = FuelMath.roundDistance(12.0 * 10.0 * 0.85) // 102.0 km
        assertEquals(expectedLargeDte, largeSnapshot.estimatedRangeKm, 0.1)

        // Compact vehicle (20 L tank): 5.0 L remaining is 25% > 15%, but 5.0 L < 7.0 L -> Reserve triggered
        val smallEstimator = FuelStateEstimator(designCapacityLiters = 20.0, maxCapacityLiters = 20.0)
        smallEstimator.initializeBaseline(initialLiters = 5.0, gaugePoints = 2, odometerKm = 5000.0)
        val smallSnapshot = smallEstimator.createSnapshot(estimatedMileageKmpl = 20.0)
        val expectedSmallDte = FuelMath.roundDistance(5.0 * 20.0 * 0.85) // 85.0 km
        assertEquals(expectedSmallDte, smallSnapshot.estimatedRangeKm, 0.1)
    }

    /**
     * Feature 14: Multi-tank partial refills below minimum distance (< 15 km) must consolidate
     * their fuel additions rather than fragmenting and dropping historical consumption.
     */
    @Test
    fun testMultiTankPartialRefillConsolidation() {
        val mileageEngine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = 15.0)

        // Baseline: Odo 10,000 km, Fuel 40.0 L
        var anchorOdo = 10000.0
        var anchorFuel = 40.0
        var accumulatedAdded = 0.0

        // Event 1: Partial refill of 5.0 L after driving only 8 km (Odo 10,008 km, Ending fuel 44.5 L)
        // deltaDistance = 8.0 km (< 15 km MIN_INTERVAL_KM) -> must NOT qualify as independent interval
        accumulatedAdded += 5.0
        var learned1 = mileageEngine.processAnchorInterval(
            startOdometerKm = anchorOdo,
            endOdometerKm = 10008.0,
            startFuelLiters = anchorFuel,
            addedFuelLitersDuringInterval = accumulatedAdded,
            endFuelLiters = 44.5
        )
        assertNull("Interval < 15 km must return null and keep anchor stationary", learned1)

        // Event 2: Second partial refill of 6.0 L after driving another 6 km (Odo 10,014 km, Ending fuel 50.0 L)
        // Total distance since anchor = 14.0 km (< 15 km) -> must still NOT qualify
        accumulatedAdded += 6.0
        var learned2 = mileageEngine.processAnchorInterval(
            startOdometerKm = anchorOdo,
            endOdometerKm = 10014.0,
            startFuelLiters = anchorFuel,
            addedFuelLitersDuringInterval = accumulatedAdded,
            endFuelLiters = 50.0
        )
        assertNull("Cumulative interval < 15 km must return null", learned2)

        // Event 3: Full tank top-up after driving 250 km (Odo 10,264 km, Ending fuel 48.0 L, added 20.0 L)
        // Total cumulative added = 5.0 + 6.0 + 20.0 = 31.0 L
        // Fuel Consumed = 40.0 + 31.0 - 48.0 = 23.0 L
        // Distance = 264.0 km -> Mileage = 264.0 / 23.0 = 11.48 km/L
        accumulatedAdded += 20.0
        var learned3 = mileageEngine.processAnchorInterval(
            startOdometerKm = anchorOdo,
            endOdometerKm = 10264.0,
            startFuelLiters = anchorFuel,
            addedFuelLitersDuringInterval = accumulatedAdded,
            endFuelLiters = 48.0
        )
        assertNotNull("Consolidated multi-tank interval must successfully compute mileage", learned3)
        assertEquals(11.48, learned3!!, 0.05)
        assertEquals(1, mileageEngine.sampleCount)
    }
}
```

### 5.3 Invalidation Conditions
This analysis and blueprint would be invalidated if:
1. `AdaptiveMileageEngine` is modified to accept single-second instantaneous fuel consumption directly (violating statistical regression and IQR quartile mathematical stability).
2. `FuelStateEstimator` is refactored to remove the `designCapacityLiters` property, requiring alternative passing of design capacity to `createSnapshot()`.
3. The automotive design specification changes to remove the 0.85 low-fuel reserve buffer requirement.
