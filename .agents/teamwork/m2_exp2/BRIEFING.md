# BRIEFING — 2026-11-10T16:35:00Z

## Mission
Investigate Milestone 2 Features 13 (DTE Range Oscillation & Instant Efficiency Fix), 14 (Multi-Tank Interval History Consolidation), and 17 (Low-Fuel Reserve Buffer & Dynamic Threshold Scaling). Produce blueprint and handoff report.

## 🔒 My Identity
- Archetype: Teamwork explorer
- Roles: M2 Explorer DTE & Fuel Algorithms
- Working directory: d:\Car Launcher\.agents\teamwork\m2_exp2\
- Original parent: f0595b2d-c260-4a04-8608-64a6202e797a
- Milestone: Milestone 2 (Features 13, 14, 17)

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Produce exhaustive, step-by-step code modification blueprint with exact file paths, line numbers, before/after code blocks
- Formulate verification commands and targeted unit test specifications
- Follow 5-component handoff report protocol

## Current Parent
- Conversation ID: f0595b2d-c260-4a04-8608-64a6202e797a
- Updated: 2026-11-10T16:35:00Z

## Investigation State
- **Explored paths**:
  - `FuelRepository.kt` (lines 1-878)
  - `FuelStateEstimator.kt` (lines 1-267)
  - `AdaptiveMileageEngine.kt` (lines 1-294)
  - `FuelGaugeCalibrationEngine.kt` (lines 1-153)
  - `MissedEntryIntelligence.kt` (lines 1-157)
  - `FuelAIEngine.kt` (lines 1-595)
  - `FuelMath.kt` (lines 1-104)
  - `FuelLedgerEvent.kt` (lines 1-81)
  - `FuelLedgerEntity.kt` (lines 1-106)
  - `AppDatabase.kt` (`FuelDao`, `FuelLedgerDao`)
  - `Entities.kt` (`FuelEventEntity`)
  - `Models.kt` (`FuelState`)
  - `FuelTankCard.kt`, `FuelHistoryScreen.kt`, `DashboardScreen.kt`, `MainActivity.kt`
  - `VehicleJourneyAndFuelEngineValidationTest.kt`
- **Key findings**:
  1. Feature 13: `FuelRepository.kt:240-242` passes `effectiveRangeMileage = if (liveInstantMileage > 0.0) liveInstantMileage else rangeMileage` to `estimator.createSnapshot()`. Because `liveInstantMileage` is 1-second kinematic consumption that swings between 4 and 45 km/L on acceleration/hills/stops, DTE remaining range oscillates wildly by hundreds of km every second. Fix: Bind `createSnapshot()` strictly to `rangeMileage`, reserving `liveInstantMileage` exclusively for instant L/100km and HUD tier displays.
  2. Feature 14: `FuelRepository.recordRefuel` does not track unmeasured intermediate fuel additions, and `rehydrateLedgerAsync()` unconditionally resets `lastAnchorOdo`, `lastAnchorFuel`, and `accumulatedAdded = 0.0` even when `processAnchorInterval` returns `null` for intervals $< 15\text{ km}$. This causes interval fragmentation, dropping partial refill fuel volumes and distances from multi-tank history. Fix: Implement persistent pending anchor accumulation across partial refills, consolidating multi-tank intervals until $\ge 15\text{ km}$ and $\ge 1.0\text{ L}$ or until full-tank fill-up (`isFullTank`), and gate rehydration anchor advancement on valid interval completion.
  3. Feature 17: `FuelStateEstimator.createSnapshot()` multiplies `nominalFuelLiters * safeMileage` with zero reserve buffer. When fuel drops below 7.0 L or 15% capacity, range remains overly optimistic. Fix: Add dynamic reserve detection (`nominalFuelLiters < 7.0 || fraction < 0.15`) and apply 0.85 safety multiplier to `nominalRange`, `minRange`, and `maxRange`. Align `isLowFuelWarning` in `FuelRepository.buildFuelState()` to `balance < 7.0 || fraction < 0.15 || midBar <= 2`.
- **Unexplored areas**: None within M2 scope.

## Key Decisions Made
- Clear demarcation between `rangeMileage` (DTE calculation) and `liveInstantMileage` (instantaneous HUD metrics).
- Strict condition for anchor advancement during partial refill consolidation to avoid losing short-interval mileage.
- Dynamic reserve threshold: `nominalFuelLiters < 7.0 || (nominalFuelLiters / safeDesign) < 0.15` with 0.85 multiplier.

## Artifact Index
- `DISPATCH.md` — Inbound message log
- `BRIEFING.md` — Persistent working memory
- `progress.md` — Liveness heartbeat and task checklist
- `handoff.md` — Complete 5-component handoff report and modification blueprint
