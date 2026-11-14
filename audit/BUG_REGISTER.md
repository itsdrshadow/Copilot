# DrivingReport Bug Register & Defect Resolution (v1.18.0)

**Document:** `audit/BUG_REGISTER.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)  
**Status:** All 23 Identified Findings Confirmed Resolved (18 Legacy + 5 Architectural)

---

## 1. Summary of Defect Resolutions

| Bug ID | Severity | Subsystem | Description | Root Cause | Resolution | Status |
|---|---|---|---|---|---|---|
| **V18-01** | High | UI / Trip Timer | Trip completion red flash lasted only 3 seconds instead of 10 seconds. | Hardcoded `< 3_000L` in `LiveCurrentTripCard.kt`. | Updated to `< 10_000L`, followed by persistent red state until next departure. | **VERIFIED FIXED** |
| **V18-02** | High | Trip History / Timeline | Trip timeline was missing refuel events and verified hardware engine off transitions. | `computeTimelineEvents` in `TripHistoryScreen` parsed only raw GPS points. | Integrated `getTransitionsForTrip` and `getFuelEventsForTrip` into timeline view model. | **VERIFIED FIXED** |
| **V18-03** | Medium | Fuel Intelligence / Calibration | Calibration engine design capacity was immutable `val`, preventing dynamic vehicle reconfiguration. | `designCapacityLiters` declared as immutable `val`. | Converted to `var`, added `updateDesignCapacity()`, and enforced bidirectional monotonicity. | **VERIFIED FIXED** |
| **V18-04** | Medium | Fuel Intelligence / Add Fuel | Vehicle capacity and baseline mileage settings in `LauncherSettingsDialog` did not update `AppSettingsManager` typed state. | Invoked only legacy `updateVehicleNameAndFuel`. | Integrated `AppSettingsManager.updateVehiclePhysicalSpecs` and connected to `FuelRepository`. | **VERIFIED FIXED** |
| **V18-05** | Low | Settings / Architecture | Tracking parameters (dwell time, speed threshold) were hardcoded magic numbers in tracking service. | Constant companion literals used in service tick loop. | Replaced literals with reactive `AppSettingsManager.settingsState` queries. | **VERIFIED FIXED** |
| **V18-06** | Medium | Concurrency / Stability | Potential NPE race condition in 1-second speedometer decay watchdog due to unsynchronized `latestEmittedPoint!!`. | State modified by location listener concurrent with background timer tick. | Replaced with local snapshot `val lastPt = latestEmittedPoint` and null-safety. | **VERIFIED FIXED** |
| **V18-07** | Medium | GPS / Telemetry Engine | Potential NPE from `lastAcceptedLocation!!` assertions during Doppler bearing and speed calculations. | Unchecked non-null assertions on mutable state. | Bound to immutable local `val prevLocation = lastAcceptedLocation`. | **VERIFIED FIXED** |
| **V18-08** | Medium | Telemetry / Math | `evaluateTripCompletion` used tick-count-biased `speedSum / count` instead of physical `distance / drivingHours`. | Inconsistent formula between live telemetry and trip finalization. | Aligned trip completion average speed with exact geodesic distance / driving duration. | **VERIFIED FIXED** |
| **V18-09** | High | Database / Data Integrity | Deleting a trip orphaned thousands of raw GPS points and vehicle state transitions in Room SQLite. | `deleteTrip` executed only `DELETE FROM trips WHERE id = :id`. | Added `deletePointsForTrip` and `deleteTransitionsForTrip` in atomic transaction. | **VERIFIED FIXED** |
| **V18-10** | Medium | Calculations / Consistency | Lifetime driving duration lost seconds per trip due to integer division before summing (`duration / 60000`). | Cumulative minute truncation across historical trips. | Summed total milliseconds across all trips before converting to hours and minutes. | **VERIFIED FIXED** |
| **V18-11** | Low | UI / Animations | Swiping backwards in metric carousel left `swipeDirection = -1`, making future auto-advances slide backwards. | Direction state was not reset upon timer auto-advance. | Reset `swipeDirection = 1` on each auto-advance in both Current Trip and All-Time cards. | **VERIFIED FIXED** |
| **V18-12** | Medium | Mobile UI / Usability | On phone portrait screens (< 760dp), trip detail container and route replay were inaccessible. | Detail container was strictly gated under `!isCompactWidth`. | Added mobile full-screen dialog overlay when tapping any journey card in compact mode. | **VERIFIED FIXED** |

---

## 2. Legacy Technical Audit Findings (18/18 Resolved)

All 18 findings identified in the previous technical audit (BUG-01 to BUG-18) remain verified and passing with regression tests in place:
1. BUG-01: Number format precision standardized via `NumberFormatUtils` (max 2 decimals, trailing zero cleanup).
2. BUG-02: Trip timer continuous multi-day elapsed calculation from persisted start timestamp.
3. BUG-03: Geofence departure and arrival debounce anti-jitter hysteresis.
4. BUG-04: Full factory reset comprehensive wipe of all database tables, shared prefs, and persistent backup files.
5. BUG-05: Adaptive mileage Bayesian learning engine with IQR outlier rejection.
6. BUG-06: Odometer baseline double-precision bit storage.
7. BUG-07: Responsive card number auto-sizing (`AdaptiveMetricsEngine`).
8. BUG-08: Removal of redundant odometer chip on home dashboard.
9. BUG-09: Speedometer decay watchdog to prevent ghost speeds upon GPS signal interruption.
10. BUG-10: Stationary watchdog at fuel stations (30s dwell) with 30-minute / 200m cooldown.
11. BUG-11: Missed refill detection with provisional confidence and zero fake receipts.
12. BUG-12: Speed limit statutory database caching and fallback hierarchy.
13. BUG-13: Permanent JSON data backup and atomic file write.
14. BUG-14: Immutable fuel ledger audit log with monotonic balance integrity.
15. BUG-15: Reverse geocoding offline-first fallback hierarchy.
16. BUG-16: GPS concurrency mutex arbitration between high-frequency location fixes and UI rendering.
17. BUG-17: Driver scoring acceleration/braking penalties calibrated to vehicle kinematics.
18. BUG-18: Multi-year trip history analytics aggregation and month grouping.
