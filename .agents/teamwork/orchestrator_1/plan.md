# Orchestration Plan: Car Infotainment Launcher Stabilization

## Goal
Perform targeted application improvement, bug fixing, edge-case handling, and stabilization on the existing car infotainment launcher application without rebuilding or redesigning existing UI. Ensure all 7 subsystems are audited, hardened, tested, and verified.

## Phases

### Phase 0: Initial Codebase Survey (Current)
- Dispatch 3 parallel Explorers to survey the project:
  - Explorer 1 (`survey_exp1`): Fuel tracking & mileage calculations, trip detection & trip lifecycle, GPS tracking & location accuracy.
  - Explorer 2 (`survey_exp2`): Odometer updates & history, fuel gauge/remaining fuel/estimated range, offline map functionality.
  - Explorer 3 (`survey_exp3`): Backup & restore, database persistence & schema, app restart/reboot recovery, existing test infrastructure & build verification tools.
- Aggregate survey reports into `PROJECT.md` with full architecture mapping, feature inventory, and milestone decomposition.

### Phase 1: Milestone Decomposition & Interface Contracts
- Formulate 3-5 implementation milestones covering all 7 subsystems.
- Define interface contracts between interconnected modules.
- Setup E2E Testing Track in parallel.

### Phase 2: Implementation Track Execution
- **Milestone 1: Telemetry, GPS & Trip Lifecycle (Features 1–10)**: [COMPLETED - Gate Result: PASS]
  - Hermite Smoothstep decay continuity ($C^1$).
  - Autonomous Great-Circle Haversine geodesy.
  - `< 50 km` reboot bridging clamp and temporal anchoring.
  - `SnapshotTrailList` JVM `java.util.List` contract and lifecycle memory reallocation.
  - Room SQLite foreign key sequencing await.
  - Zero integrity violations; all tests passing green.
- **Milestone 2: Odometer, Fuel Intelligence & Gauge/Range Hardening (Features 11–18)**: [IN_PROGRESS]
  - Step a: Dispatch 3 parallel Explorers:
    - `m2_exp1`: Features 11 & 12 — Odometer double-counting audit, historical query isolation (`TripDao.getAllHistoricalTrips` filter `status = 'COMPLETED' AND isQualifying = 1`), baseline calibration synchronization between `FuelRepository` and `OdometerManager`.
    - `m2_exp2`: Features 13, 14, 17 — DTE remaining range algorithm in `FuelRepository` using `rangeMileage` (learned average / baseline) instead of 1-second instant efficiency; 0.85 low-fuel reserve multiplier; multi-tank interval consolidation on fill-up/reset.
    - `m2_exp3`: Features 15, 16, 18 — 10-bar LED fuel gauge dynamic update in `FuelStateEstimator` (`gaugeCalibration.getBarForLiters`), 60-second rolling median `filterSloshAndSlope()` in `syncMidAndOdometer()`, and reboot fuel state recovery of `cumulativeTripConsumedLiters` from Room DB.
  - Step b: Implement via `m2_worker`.
  - Step c: Review via 2 Reviewers (`m2_reviewer1`, `m2_reviewer2`).
  - Step d: Empirical challenge via 2 Challengers (`m2_challenger1`, `m2_challenger2`).
  - Step e: Forensic audit via `m2_auditor`.
  - Step f: Gate evaluation.
- **Milestone 3: Storage, Persistence, Backup/Restore & Map Engine (Features 19–28)**: [PENDING]
- **Parallel E2E Testing Track**: [COMPLETED - `TEST_READY.md` published with 150/150 tests passing]

### Phase 3: Final Milestone & Adversarial Hardening
- Pass 100% of E2E tests (Tiers 1-4).
- Adversarial hardening (Tier 5) with Challenger -> Worker -> Reviewer -> Auditor.

### Phase 4: Final Verification & Reporting
- Full test pass verification.
- Comprehensive final report without generating APK/AAB or installing.
- Conclude with exact prompt: "Improvements and verification are complete. Would you like me to generate the final build?"
