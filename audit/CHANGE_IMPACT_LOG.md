# Change Impact Log (v1.18.0)

**Document:** `audit/CHANGE_IMPACT_LOG.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)  
**Objective:** Track all architectural and source code modifications, their rationale, affected files, and regression safety boundaries.

---

## 1. Modifications Record

| Component / Subsystem | Files Modified | Change Summary | Safety & Non-Regression Guarantee |
|---|---|---|---|
| **Build System** | `app/build.gradle.kts` | Bumped `versionCode` to 33, `versionName` to `"1.18.0"`. | Normal version progression; backward-compatible release signing. |
| **Room Database** | `AppDatabase.kt` | Added `getTransitionsForTrip`, `getTransitionsBetween` to `VehicleStateDao`; added `getEventsBetween` to `FuelLedgerDao`. | Purely additive queries; schema version unchanged; 0 data migration impact. |
| **Trip Repository & ViewModel** | `TripRepository.kt`, `MainViewModel.kt` | Added `getTransitionsForTrip` and `getFuelEventsForTrip` delegation methods. | Read-only async methods; does not alter state mutating pipelines. |
| **Fuel Intelligence** | `FuelRepository.kt` | Added `getFuelEventsBetween` and updated `updateVehicleConfig` to scale `gaugeCalibration`. | Preserves immutable ledger and existing fuel calculations. |
| **Fuel Gauge Calibration** | `FuelGaugeCalibrationEngine.kt`, `FuelStateEstimator.kt` | Added `updateDesignCapacity` and bidirectional monotonic curve constraint. | Preserves empirical observations; ensures $0 \le v_0 < v_1 < \dots < v_{10}$. |
| **Trip Timeline UI** | `TripHistoryScreen.kt` | Integrated refuels (`RefuelRecorded`) and verified hardware ignition cuts (`EnginePowerEvent`) into trip timeline; added transition and fuel event loaders. | Fully backward-compatible; falls back cleanly if points or events are empty. |
| **Trip Status & Timer Visuals** | `LiveCurrentTripCard.kt` | Adjusted trip completed flash duration to 10 seconds (`< 10_000L`), followed by persistent red state until next departure. | Timer calculation and ticking loop untouched. |
| **Settings Architecture** | `AppSettingsManager.kt`, `LauncherSettingsDialog.kt` | Expanded typed `SettingsState` with physical and tracking thresholds; linked vehicle configuration settings. | Non-destructive preferences storage; survives reboots and app updates. |
| **Driving Tracking Service** | `DrivingTrackingService.kt`, `VehicleStateManager.kt`, `TripGeofenceManager.kt` | Replaced hardcoded literals with reactive `AppSettingsManager.settingsState` queries for dwell time, speed threshold, and geofence radius. | Eliminates magic numbers; dynamic threshold adjustment without service restart. |
| **Test Automation** | `VehicleJourneyAndFuelEngineValidationTest.kt` | Added 6 automated unit tests covering non-linear calibration, slosh rejection, missed refills, Add Fuel overfills, and timer formats. | Verified 100% test pass rate across entire suite (67/67). |
