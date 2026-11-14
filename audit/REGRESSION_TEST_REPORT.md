# Regression Test Report (v1.18.0)

**Document:** `audit/REGRESSION_TEST_REPORT.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)  
**Execution Environment:** Android SDK 34 / Java 17 / Gradle 8.9 / Robolectric / JUnit 4

---

## 1. Test Execution Summary

- **Total Test Classes:** 14
- **Total Tests Executed:** 67
- **Tests Passed:** 67 (100%)
- **Tests Failed:** 0 (0%)
- **Tests Skipped:** 0
- **Build Duration:** 33 seconds
- **Regression Status:** **ZERO REGRESSIONS (0)**

---

## 2. Test Suite Breakdown

| Suite / Class Name | Test Count | Pass | Fail | Primary Verification Domain |
|---|---|---|---|---|
| `VehicleJourneyAndFuelEngineValidationTest` | 6 | 6 | 0 | Non-linear gauge monotonicity, slosh rejection, missed refills, timer formatting, dynamic capacity scaling. |
| `AdaptiveFuelIntelligenceTest` | 8 | 8 | 0 | Bayesian empirical mileage learning, partial refill interval processing, outlier rejection. |
| `AdaptiveMetricsEngineTest` | 5 | 5 | 0 | Auto-adaptive text size reduction and card width constraint calculation. |
| `AnalyticsCalculationsTest` | 5 | 5 | 0 | Multi-year trip summary aggregations, driving time sum, average speed math. |
| `BackupRestoreTypeSafetyTest` | 4 | 4 | 0 | JSON backup parsing, corrupt payload recovery, atomic write safety. |
| `FuelCalculationConsistencyTest` | 6 | 6 | 0 | Monotonic ledger balance, 3-point uncertainty envelope, physical tank limits. |
| `FullBackupManagerTest` | 4 | 4 | 0 | Full system backup archive generation and table restoration. |
| `GeofenceTripLifecycleTest` | 6 | 6 | 0 | Geofence departure start, arrival end, anti-jitter hysteresis, false exit cancellation. |
| `GpsConcurrencyAndArbitrationTest` | 4 | 4 | 0 | Mutex thread-safety under 100Hz concurrent telemetry injection. |
| `MissedEntryExtremeBoundaryTest` | 5 | 5 | 0 | Extreme boundary handling for missed refills, 0L / 200L boundary clamps. |
| `NumberFormatUtilsTest` | 6 | 6 | 0 | Global 2-decimal precision rule, trailing zero truncation, currency formatting. |
| `OdometerManagerTest` | 3 | 3 | 0 | Odometer calibration delta tracking and cumulative recorded distance. |
| `OdometerPersistenceTest` | 3 | 3 | 0 | Double-precision bit preservation across shared preferences resets. |
| `VehicleStateDomainTest` | 2 | 2 | 0 | Verified hardware ignition cutoff vs inferred operational moving/idle states. |
| **Total** | **67** | **67** | **0** | **100% Passing** |
