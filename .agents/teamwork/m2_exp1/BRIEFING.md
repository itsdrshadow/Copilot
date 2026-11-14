# BRIEFING — 2026-11-10T16:33:00Z

## Mission
Investigate Milestone 2 Features 11 & 12 (Odometer double-counting, historical query isolation, dual baseline calibration synchronization) and produce an exhaustive blueprint report.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: M2 Explorer Odometer & Calibration
- Working directory: d:\Car Launcher\.agents\teamwork\m2_exp1
- Original parent: f0595b2d-c260-4a04-8608-64a6202e797a
- Milestone: Milestone 2 — Features 11 & 12

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Do NOT modify production source code or test files
- Write only to .agents/teamwork/m2_exp1/
- Produce an exhaustive, step-by-step code modification blueprint with exact file paths, line numbers, and before/after code blocks
- Formulate verification commands and targeted unit test specifications
- Follow 5-component handoff report (Observation, Logic Chain, Caveats, Conclusion, Verification Method)

## Current Parent
- Conversation ID: f0595b2d-c260-4a04-8608-64a6202e797a
- Updated: 2026-11-10T16:33:00Z

## Investigation State
- **Explored paths**:
  - `AppDatabase.kt` (`TripDao`, queries, indices)
  - `Entities.kt` (`TripEntity`, `FuelEventEntity`, `VehicleEntity`)
  - `TripRepository.kt` (`onTripStarted`, `onTripUpdated`, `onTripCompleted`, `onTripDiscarded`, `currentTrip`, `qualifyingTrips`)
  - `OdometerManager.kt` (`setBaselineOdometer`, `calculateDeltaKm`, `calculateLiveOdometer`)
  - `FuelRepository.kt` (`calibrateOdometer`, `getEffectiveOdometer`, `syncOdometerDistance`, `buildFuelState`)
  - `MainActivity.kt` (`recordedDistanceKm`, `syncOdometer`)
  - `DashboardScreen.kt` (`totalLifetimeKm`, `totalTripsCount`, `totalHours`, `lifetimeAvgSpeed`, `totalLifetimeFuelLiters`)
  - `OdometerCard.kt` (`OdometerCard`, `PremiumOdometerCalibrationDialog`)
  - `CurrentTripCard.kt` (`CurrentTripCard`, lifecycle animations)
  - `SpeedometerCluster.kt` (`bestEcoResult`, `allTrips`)
  - `PersistentDataManager.kt` (legacy backup and restore)
  - `FullBackupManager.kt` (archive backup and restore)
  - `OdometerManagerTest.kt` (existing unit test)
- **Key findings**:
  - Feature 11: Three interconnected roots for odometer double counting:
    1. SQL query in `TripDao.getAllHistoricalTrips()` was `WHERE status = 'COMPLETED' OR isQualifying = 1 OR distanceMeters > 0`. As soon as an active trip accumulated distance, it was returned in historical queries, causing it to be counted in both `allTrips` and `currentTrip`.
    2. `TripRepository.onTripCompleted()` did not reset `_currentTrip.value = TripSummary.empty()`, keeping the completed trip's distance in memory while Room DB added the completed row.
    3. UI rollups in `MainActivity.kt` and `DashboardScreen.kt` added `currentTrip.distanceKm` without gating on active trip status (`ACTIVE_TRIP`, `TEMPORARY_STOP`, `MOVEMENT_CANDIDATE`).
  - Feature 12: Dual calibration and baseline discrepancy:
    1. Divergent definitions of baseline: `OdometerManager` stores physical dash reading at calibration and adds delta (`lifetime - baselineLifetimeAtUpdate`), while `FuelRepository` stored `newReading - currentLifetime` in `driving_report_fuel_prefs`.
    2. Dual calibration paths: `OdometerCard` called `OdometerManager.setBaselineOdometer()` directly, bypassing `FuelRepository.calibrateOdometer()`.
    3. `OdometerManager` must be the single source of truth; `FuelRepository` must delegate `calibrateOdometer` and `getEffectiveOdometer` to `OdometerManager`.
- **Unexplored areas**: None within the scope of Features 11 & 12.

## Key Decisions Made
- Formulate defense-in-depth architecture: fix SQL query in Room DAO, add `TripSummary.empty()`, reset `_currentTrip` on completion, and gate UI distance addition on active trip status.
- Unify calibration architecture so `OdometerManager` is the single source of truth, removing duplicate baseline storage in `FuelRepository`.

## Artifact Index
- DISPATCH.md — incoming dispatch records
- progress.md — liveness heartbeat
- BRIEFING.md — persistent working memory
- handoff.md — 5-component comprehensive investigation and blueprint report
