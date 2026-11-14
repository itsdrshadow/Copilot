# DISPATCH — sub_orch_m2

## Milestone 2: Odometer Reconciliation, Fuel Intelligence & Gauge/Range Hardening

Working Directory: d:\Car Launcher\.agents\teamwork\sub_orch_m2\
Scope Document: d:\Car Launcher\.agents\teamwork\sub_orch_m2\SCOPE.md
Authoritative User Request: d:\Car Launcher\.agents\teamwork\ORIGINAL_REQUEST.md
Master Project Plan: d:\Car Launcher\PROJECT.md

### Assigned Scope (Features 11–18)
11. **Active & Completed Trip Double-Counting Fix**: Exclude active trips from `TripDao.getAllHistoricalTrips()` and reset `_currentTrip` distance on completion to prevent duplicate lifetime km summation in `MainActivity.kt` and `DashboardScreen.kt`.
12. **Odometer Baseline Calibration Reconciliation**: Harmonize baseline calibration definitions between `FuelRepository` and `OdometerManager`; fix legacy restore subtracting lifetime km again.
13. **DTE Range Oscillation Fix**: Bind DTE remaining range in `FuelRepository` strictly to learned empirical / baseline `rangeMileage` instead of 1-second `liveInstantMileage`.
14. **10-Segment LED Fuel Gauge Monotonicity & Updates**: Update `currentGaugeBar` dynamically via `gaugeCalibration.getBarForLiters()` in `FuelStateEstimator` / `buildFuelState()` so the cluster gauge decrements during driving.
15. **Fuel Slosh & Slope Filter Activation**: Connect 60-second rolling median `filterSloshAndSlope()` in production `syncMidAndOdometer()` to damp road pitch and float slosh.
16. **Conservative Low-Fuel Reserve Range Multiplier**: Apply the specified 0.85 conservative safety multiplier in `createSnapshot()` when nominal fuel is below 7.0 L or 15% tank capacity.
17. **Active Trip Fuel Restoration on Reboot**: Restore `cumulativeTripConsumedLiters` from Room DB `activeTrip.estimatedFuelLiters` in `DrivingTrackingService.onCreate()` / `restoreActiveTripState()`.
18. **Decouple Gauge Calibration from Estimate Drift**: Prevent software estimation drift from corrupting physical gauge calibration curve in `learnCalibrationPoint`.

### File Write Boundaries (Exclusive Ownership)
- `app/src/main/java/com/antigravity/drivingreport/core/database/AppDatabase.kt` (`TripDao.getAllHistoricalTrips` query)
- `app/src/main/java/com/antigravity/drivingreport/core/trip/TripRepository.kt` (`onTripCompleted` cleanup & historical queries)
- `app/src/main/java/com/antigravity/drivingreport/core/odometer/OdometerManager.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/fuel/` (all files: `FuelRepository.kt`, `FuelStateEstimator.kt`, `FuelGaugeCalibrationEngine.kt`, `AdaptiveMileageEngine.kt`, `MissedEntryIntelligence.kt`, etc.)
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/OdometerCard.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/FuelTankCard.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/DashboardScreen.kt`
- `app/src/main/java/com/antigravity/drivingreport/MainActivity.kt`
- Associated tests in `app/src/test/java/com/antigravity/drivingreport/`

### Execution Procedure
Follow the Project Pattern: Assess -> Decompose or Iterate (Explorer -> Worker -> Reviewer -> Challenger -> Forensic Auditor -> Gate).
Report results back to parent orchestrator via `send_message`.
