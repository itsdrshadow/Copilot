# Data Consistency & Source-of-Truth Audit: DrivingReport Android Application

**Application:** DrivingReport Automotive Infotainment & Cockpit Launcher (`com.antigravity.drivingreport`)  
**Audited Version:** v1.17.1 (versionCode: 31, targetSdk: 30, compileSdk: 34)  
**Audit Scope:** Cross-module data flows, persistence boundaries, single-source-of-truth verification, synchronization mechanisms, and divergence risks.  
**Audit Date:** 2026-11-09  
**Audit Status:** STRICT READ-ONLY COMPREHENSIVE TECHNICAL AUDIT  

---

## 1. Executive Summary: Data Consistency & Integrity

A robust automotive application requires that every metric displayed to the driver has a **single unambiguous source of truth** across the entire stack.

The data consistency audit revealed **significant fragmentation and architectural divergence** across storage layers, repositories, and UI cards:

1. **Odometer Divergence & Backup Loss:**
   The dashboard odometer relies on `OdometerManager` (`driving_report_odometer_prefs`), but `PersistentDataManager` only backs up and restores `driving_report_fuel_prefs`. After an automatic restore on reinstall, the driver's verified dash odometer reading is **permanently reset to 0 km**, while the fuel repository retains a decoupled baseline.
2. **Three Competing Mileage Truths:**
   The application simultaneously runs three conflicting mileage values:
   - Configured & Learned Mileage: `AdaptiveMileageEngine` ($18.0 \to \text{learned}$)
   - Hardcoded Trip Detection Mileage: `TripDetectionEngine.BASE_MILEAGE_KMPL = 15.0`
   - Hardcoded Historical Fallbacks: `TripRepository` & `DashboardScreen` ($18.2\text{ and }18.4\text{ km/L}$)
3. **Split Fuel Consumption Pipelines:**
   Every single meter driven calculates fuel consumption **twice** through independent, unlinked formulas:
   - Pipeline 1 (`TripDetectionEngine`): $\text{Fuel} = \Delta d / 15.0$ (Linear)
   - Pipeline 2 (`FuelRepository`): $\text{Fuel} = \text{aiEngine.calculateLiveConsumption()}$ (Kinematic/BSFC)
   The driver sees different fuel consumption numbers on the Trip Card vs the Fuel System Card for the same drive!
4. **SharedPreferences Multi-File Fragmentation:**
   The application distributes state across **9 distinct SharedPreferences files** without an atomic cross-file commit mechanism, creating state desynchronization if the head unit cuts power during writes.

---

## 2. Complete Cross-Module Data Flow Tracing

### 2.1 Trace 1: GPS Fix $\to$ Trip Distance $\to$ Odometer $\to$ UI

```
[GPS Fix from FusedLocation / LocationManager]
                 │
                 ▼
     [DrivingTrackingService]
                 │
                 ├──► [GeofenceTripLifecycleEngine.processLocation]
                 │         │ (Verifies 25m accuracy & boundary crossing)
                 │         └──► onTripStartConfirmed / onTripArrivalConfirmed
                 │
                 └──► [TripDetectionEngine.processLocation]
                           │
                           ├── 1. Speed Doppler & Geodesic Delta
                           ├── 2. Anti-Jitter Gate: (speed >= 2.5 km/h && dist >= 0.8m)
                           ├── 3. Distance Accumulation: totalDistanceMeters += dist
                           │
                           ▼
                  [onTripUpdated(summary, point)]
                           │
                           ├──► [TripRepository.onTripUpdated]
                           │         ├── Updates _currentTrip StateFlow
                           │         ├── Buffers TripPointEntity in pendingPoints (non-thread-safe!)
                           │         └── Periodically flushes to Room `trips` and `trip_points`
                           │
                           └──► [DrivingTrackingService]
                                     └── fuelRepository.consumeLiveFuel(deltaDistance)
```

#### Odometer Update Chain:
1. `DashboardScreen` reads `allTrips` from Room + `currentTrip.distanceKm` to compute `totalLifetimeKm`.
2. `LiveAllTimeStatsCard` calls `OdometerManager.calculateLiveOdometer(totalLifetimeKm)`.
3. `OdometerManager` computes:
   $$\text{Delta} = \text{totalLifetimeKm} - \text{baselineLifetimeKmAtUpdate}$$
   $$\text{LiveOdometer} = \text{baselineOdometerKm} + \text{Delta}$$
4. `FuelRepository.getEffectiveOdometer()` calls `OdometerManager.calculateLiveOdometer(latestAppLifetimeKm)`.

#### Divergence Risks Identified:
- `latestAppLifetimeKm` in `FuelRepository` is updated only when `syncOdometerDistance()` or `calibrateOdometer()` is called from the ViewModel or UI. If the background service runs while the UI is not active, `FuelRepository`'s internal odometer snapshot lags behind the live odometer!
- In `OdometerManager.kt`, values are stored as `Float` in SharedPreferences:
  `prefs.edit().putFloat("baseline_odometer_km", readingKm.toFloat()).apply()`
  At $158,490.55\text{ km}$, 32-bit float truncation introduces rounding drift.

---

### 2.2 Trace 2: Odometer $\to$ Mileage Engine $\to$ Fuel State $\to$ DTE

```
[User logs Refuel or Syncs MID]
                 │
                 ▼
     [FuelRepository.recordRefuel / syncMidAndOdometer]
                 │
                 ├── 1. `estimator.applyRefuel(addedLiters, midBar, effectiveOdo)`
                 │         ├── Expands maxCapacityLiters if overfilled
                 │         ├── Clamps nominalFuelLiters = current + added
                 │         └── Sets lastReliableFuelAnchorLiters & lastReliableOdometerAnchorKm
                 │
                 ├── 2. `gaugeCalibration.learnCalibrationPoint(midBar, nominalFuel, 0.30)`
                 │         └── Adapts volumetric curve with monotonicity enforcement
                 │
                 ├── 3. `mileageEngine.processAnchorInterval(...)`
                 │         ├── deltaDistance = endOdo - startOdo
                 │         ├── fuelConsumed = (startFuel + addedFuel) - endFuel
                 │         ├── rawMileage = deltaDistance / fuelConsumed
                 │         ├── filterOutliersIQR()
                 │         └── Exponential recency weighted average
                 │
                 ├── 4. `FuelLedgerEntity` created & inserted into Room `fuel_ledger_events`
                 ├── 5. `FuelEventEntity` created & inserted into Room `fuel_events` (legacy)
                 ├── 6. `StationPriceManager.rememberStationPrice(...)`
                 ├── 7. `persistValuesImmediate()` writes to `driving_report_fuel_prefs`
                 └── 8. `_fuelState.value = buildFuelState()` emits updated StateFlow to UI
```

#### Divergence Risks Identified:
- **Two database tables for refuels:** `fuel_ledger_events` (enterprise immutable audit log) and `fuel_events` (legacy table). Both are updated on every refuel. If an update fails halfway, the two tables become desynchronized.
- **De-synchronization of Fuel Price:** `FuelRepository` maintains `fuelPrice` (default 96.5), `StationPriceManager` maintains a JSON map of prices per station in `driving_report_station_prices`, and `TripDetectionEngine` maintains a hardcoded constant `FUEL_PRICE_PER_LITER = 96.5`. If the price changes, `TripDetectionEngine` never receives the update!

---

### 2.3 Trace 3: Refuel Entry UI $\to$ Gauge Preview $\to$ Persistence

```
[User opens Add Fuel Dialog]
                 │
                 ├── Reads `fuelState.remainingLiters`, `fuelState.designCapacityLiters`
                 ├── Auto-detects station & pre-fills remembered price from `StationPriceManager`
                 ├── User enters Amount (e.g. ₹1447.50)
                 │         └── Computes Dispensed Liters = Amount / PricePerLiter (e.g. 15.0 L)
                 │
                 ├── Gauge Preview (`autoPostRefillBar`):
                 │         ├── predictedFinalTank = remainingLiters + dispensedLiters
                 │         └── autoPostRefillBar = gaugeCalibration.getBarForLiters(clampedFinalTank)
                 │
                 ├── Dial renders current fuel in green/amber + refill addition in bright blue
                 │
                 └── [User taps CONFIRM REFUEL]
                           │
                           ├── Calls `MainViewModel.recordRefuel(...)`
                           ├── Calls `FuelRepository.recordRefuel(...)`
                           ├── Triggers `PersistentDataManager.backupAsync()`
                           └── Dismisses dialog
```

#### Data Consistency Evaluation:
- The UI calculation of post-refill gauge correctly queries `gaugeCalibration.getBarForLiters(clampedFinalTank)` from the active repository instance.
- The Rupee symbol alignment uses `PlatformTextStyle(includeFontPadding = false)` and matched baseline typography, preventing visual clipping.
- **Vulnerability:** If the user enters a large refill that exceeds `designCapacity`, `isOverfill` is correctly detected, but the preview dial clamps to 10 bars. In `FuelRepository`, `estimator.maxCapacityLiters` expands dynamically to accommodate the overfill. This behavior is consistent.

---

## 3. Single-Source-of-Truth (SSOT) Analysis

| Metric | Source of Truth 1 | Source of Truth 2 | Source of Truth 3 | Consistency Status |
|---|---|---|---|---|
| **Odometer** | `OdometerManager` (`driving_report_odometer_prefs`) | `FuelRepository` (`driving_report_fuel_prefs`) | Room `trips` sum | **SPLIT / VULNERABLE** |
| **Vehicle Mileage** | `AdaptiveMileageEngine.overallEstimatedMileage` | `TripDetectionEngine.BASE_MILEAGE_KMPL = 15.0` | `TripRepository` fallback $18.2\text{ km/L}$ | **BROKEN / INCONSISTENT** |
| **Trip Fuel Consumed** | `TripSummary.estimatedFuelConsumedLiters` ($\text{dist}/15.0$) | `FuelRepository.cumulativeTripConsumedLiters` (Kinematic AI) | Room `trips.estimatedFuelLiters` | **SPLIT / DIVERGENT** |
| **Fuel Price** | `StationPriceManager` (GPS-matched station record) | `FuelRepository.fuelPrice` | `TripDetectionEngine.FUEL_PRICE_PER_LITER = 96.5` | **DESYNCHRONIZED** |
| **Tank Remaining Fuel** | `FuelStateEstimator.nominalFuelLiters` | `driving_report_fuel_prefs` (`remaining_liters`) | `PersistentDataManager` backup JSON | **CONSISTENT** |
| **Cluster Gauge Bar** | `FuelStateEstimator.currentGaugeBar` | `FuelGaugeCalibrationEngine.getBarForLiters()` | `FuelState.currentMidBar` | **CONSISTENT** |
| **Geofences** | Room `trip_geofences` table | `TripGeofenceManager` memory cache | `FullBackupManager` `trip_geofences.json` | **CONSISTENT** |
| **Vehicle State** | `VehicleStateManager` (_metrics StateFlow) | `vehicle_state_prefs` | Room `vehicle_state_transitions` | **CONSISTENT** |

---

## 4. SharedPreferences Storage Architecture Audit

The application maintains **9 separate SharedPreferences files**:

```
data/data/com.antigravity.drivingreport/shared_prefs/
├── driving_report_settings_prefs.xml       (General, Speed limits, Display settings)
├── driving_report_fuel_prefs.xml           (Tank capacity, remaining fuel, fuel price)
├── fuel_ai_model_prefs.xml                 (Learned AI mileage, aggressiveness, confidence)
├── driving_report_station_prices.xml       (JSON list of remembered pump prices by GPS)
├── driving_report_last_pos.xml             (Last known latitude/longitude for cold start)
├── driving_report_odometer_prefs.xml       (Baseline odometer reading & lifetime km)
├── vehicle_state_prefs.xml                 (Engine off / idle / moving durations & state)
├── trip_visual_lifecycle_prefs.xml         (Green/red flash timestamp and event type)
└── MapboxSharedPreferences.xml             (Map telemetry & telemetry opt-out)
```

### Critical Findings:
1. **No Atomic Transactions Across Pref Files:** When a refuel occurs, `driving_report_fuel_prefs`, `driving_report_station_prices`, and `driving_report_odometer_prefs` are written independently using asynchronous `.apply()`. If vehicle ignition cuts 12V power during writes, partial state is persisted, corrupting data integrity.
2. **Backup Disconnect:**
   - `PersistentDataManager` backs up ONLY `driving_report_fuel_prefs` and `driving_report_station_prices`. It completely omits `driving_report_odometer_prefs`!
   - `FullBackupManager` backs up `settings`, `fuel`, `odometer`, and `station_prices`, but completely omits `fuel_ai_model_prefs`, `vehicle_state_prefs`, and `trip_visual_lifecycle_prefs`.
3. **Float vs Double Serialization Bug in `FullBackupManager`:**
   When restoring `preferences.json` via GSON, all numbers deserialize as `Double`. The restore loop executes `is Double -> editor.putFloat(k, v.toFloat())`. Any preference expected as `Int` or `Long` throws a fatal `ClassCastException` on subsequent reads.

---

## 5. Data Consistency Matrix

| Data Entity | In-Memory (StateFlow) | Room Database | SharedPreferences | Persistent Backup JSON | Full Backup ZIP | Reinstall Safe? | Factory Reset Safe? |
|---|---|---|---|---|---|---|---|
| **Trips** | `TripRepository._currentTrip` | `trips` table | ❌ | `trips` array | `trips.json` | ✅ | ✅ (Purged) |
| **Trip Points** | `TripRepository._tripTrail` | `trip_points` table | ❌ | ❌ (Omitted) | `trip_points.json` | ⚠️ Points lost in auto-restore | ✅ (Purged) |
| **Fuel Refills** | `FuelRepository.fuelHistory` | `fuel_events` table | ❌ | `fuel_events` array | `fuel_events.json` | ✅ | ✅ (Purged) |
| **Fuel Ledger** | `FuelRepository.fuelLedgerHistory` | `fuel_ledger_events` | ❌ | `fuel_ledger_events` | `fuel_ledger_events.json` | ✅ | ✅ (Purged) |
| **Tank Remaining** | `FuelRepository.fuelState` | ❌ | `driving_report_fuel_prefs` | `prefs.remaining_liters` | `preferences.json` | ✅ | ✅ (Purged) |
| **Odometer** | `OdometerManager.odometerConfig` | ❌ | `driving_report_odometer_prefs` | ❌ **(NOT BACKED UP!)** | `preferences.json` | ❌ **(LOST ON RESTORE)** | ✅ (Purged) |
| **AI Model** | `FuelAIEngine` | ❌ | `fuel_ai_model_prefs` | ❌ | ❌ **(NOT IN ZIP!)** | ❌ (Reset to defaults) | ✅ (Purged) |
| **Station Rates** | `StationPriceManager.cachedStations` | ❌ | `driving_report_station_prices` | `station_prices` | `preferences.json` | ✅ | ✅ (Purged) |
| **Geofences** | `TripGeofenceManager` | `trip_geofences` table | ❌ | ❌ | `trip_geofences.json` | ⚠️ Lost in auto-restore | ✅ (Purged) |
| **Vehicle State** | `VehicleStateManager._metrics` | `vehicle_state_transitions` | `vehicle_state_prefs` | ❌ | `vehicle_state_transitions.json` | ⚠️ Lost in auto-restore | ✅ (Purged) |
| **Vehicle Table** | ❌ (Unused) | `vehicles` table | ❌ | ❌ | ❌ | ❌ (Dead table) | ❌ **(NOT PURGED!)** |
