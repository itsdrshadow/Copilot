# Test Infrastructure Specification: Car Infotainment Launcher (`com.antigravity.drivingreport`)

## 1. Test Philosophy: Opaque-Box & Requirement-Driven

The DrivingReport testing architecture adheres strictly to **opaque-box, requirement-driven verification**:
- **Black/Opaque-Box Principles**: Tests exercise public interfaces, subsystem contracts, and observable behavior rather than internal private implementation details. Tests verify outcomes against authoritative requirements defined in `PROJECT.md` and `ORIGINAL_REQUEST.md`.
- **Zero Mock Facades**: Avoid brittle mock-everything patterns that give false confidence. Real domain engines (`GpsLocationFilter`, `FuelStateEstimator`, `AdaptiveMileageEngine`, `FuelGaugeCalibrationEngine`, `MissedEntryIntelligence`, `OdometerManager`, `TimeFormatUtils`, `NumberFormatUtils`, `FullBackupManager`) are exercised directly with realistic automotive inputs.
- **Deterministic & Hermetic**: Every test case initializes its own state, executes deterministically without network dependencies or system clock flakiness, and cleans up resources.
- **Progressive Testability**: Tests rely only on verified subsystem contracts, maintaining full isolation and immediate executability on standard JVM CI runners without requiring an attached Android device or emulator.

---

## 2. Runner Invocation & Verification Commands

All test tiers are authored under standard Gradle JVM unit test configurations and execute via:

```powershell
# Run the entire JVM unit test suite (all 4 tiers + existing regression tests)
.\gradlew.bat testDebugUnitTest

# Run specific test suites
.\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.Tier1FeatureCoverageTest"
.\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.Tier2BoundaryAndCornerTest"
.\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.Tier3CrossFeatureCombinationsTest"
.\gradlew.bat testDebugUnitTest --tests "com.antigravity.drivingreport.Tier4RealWorldScenariosTest"
```

Test results and HTML reports are generated at:
`app/build/reports/tests/testDebugUnitTest/index.html`

---

## 3. Subsystem Architecture & 30-Feature Inventory Mapping

The project comprises 7 core subsystems and 30 discrete features mapped to our test suites:

| Subsystem | Features Covered | Primary Production Modules | Test Verification Target |
|:----------|:-----------------|:---------------------------|:-------------------------|
| **1. Telemetry & GPS Ingestion** | F1, F2, F4 | `GpsLocationFilter`, `DrivingTrackingService` | `Tier1FeatureCoverageTest`, `Tier2BoundaryAndCornerTest` |
| **2. Trip Lifecycle & Detection** | F3, F5, F6, F7, F8, F9, F10 | `TripDetectionEngine`, `TripRepository`, `GeofenceTripLifecycleEngine`, `TimeFormatUtils` | `Tier1FeatureCoverageTest`, `Tier3CrossFeatureCombinationsTest`, `Tier4RealWorldScenariosTest` |
| **3. Odometer & Mileage** | F11, F12 | `OdometerManager`, `TripDao`, `FuelRepository` | `Tier1FeatureCoverageTest`, `Tier2BoundaryAndCornerTest`, `Tier3CrossFeatureCombinationsTest` |
| **4. Fuel Intelligence & Gauge** | F13, F14, F15, F16, F17, F18 | `FuelRepository`, `FuelStateEstimator`, `FuelGaugeCalibrationEngine`, `AdaptiveMileageEngine`, `MissedEntryIntelligence` | `Tier1FeatureCoverageTest`, `Tier2BoundaryAndCornerTest`, `Tier3CrossFeatureCombinationsTest`, `Tier4RealWorldScenariosTest` |
| **5. Storage, Backup & Persistence** | F19, F20, F21, F22, F23 | `FullBackupManager`, `AppDatabase`, `PersistentDataManager`, `VehicleStateManager` | `Tier1FeatureCoverageTest`, `Tier2BoundaryAndCornerTest`, `Tier4RealWorldScenariosTest` |
| **6. Mapping & Geodesy** | F24, F25, F26, F27, F28 | `OfflineMapManager`, `IndiaOfflineMapManager`, `LiveMapView` | `Tier1FeatureCoverageTest`, `Tier2BoundaryAndCornerTest`, `Tier4RealWorldScenariosTest` |
| **7. E2E & Hardening** | F29, F30 | 4-Tier Test Framework & Adversarial Scenarios | `Tier1FeatureCoverageTest` through `Tier4RealWorldScenariosTest` |

### Detailed 30-Feature Matrix:
1. **F1 (GPS Ingestion Filtering)**: Stationary jitter dead-zone (<2.5m) and multipath spike rejection (>240 km/h) in `GpsLocationFilter`.
2. **F2 (Service Listener Deduplication)**: Redundant callback arbitration and duplicate initial fix prevention.
3. **F3 (Tunnel & Outage Geodesic Bridging)**: Geodesic distance interpolation across GPS blackouts (>15s).
4. **F4 (Speedometer Dropout Deceleration Decay)**: Smooth deceleration profile instead of abrupt 0 km/h snap on fix gaps.
5. **F5 (Trip Idle Watchdog Geofence Sync)**: Conditioning 5-minute stationary trip completion on geofence state.
6. **F6 (Unclamped Time Delta Protection)**: Clamping $\Delta t$ to prevent clock jumps from corrupting driving duration and average speed.
7. **F7 (Foreign Key Constraint Sequencing)**: Guaranteeing `TripEntity` persistence precedes `TripPointEntity` insertion.
8. **F8 (Multi-day Trip Duration Alignment)**: Unified formatting and semantic representation of running vs engine-off elapsed time.
9. **F9 (Trail Points Memory Optimization)**: Memory-conscious unmodifiable/incremental trail point management.
10. **F10 (Mid-Trip Reboot First-Fix Accumulation)**: Location anchor restoration to track movement during reboot.
11. **F11 (Active & Historical Trip Double-Counting Fix)**: Exclusion of active trips from historical distance summation.
12. **F12 (Odometer Baseline Calibration Reconciliation)**: Single source of truth in `OdometerManager` with 64-bit precision.
13. **F13 (DTE Range Oscillation Fix)**: Range computed from learned empirical/baseline mileage, isolated from 1-second instantaneous fluctuations.
14. **F14 (10-Segment LED Fuel Gauge Monotonicity)**: Gauge bar decrements dynamically with fuel consumption via calibration mapping.
15. **F15 (Fuel Slosh & Slope Filter Activation)**: Rolling median filter rejecting transient float slosh and road pitch.
16. **F16 (Conservative Low-Fuel Reserve Range Multiplier)**: 0.85 safety buffer applied when nominal fuel $< 7.0\text{ L}$ or $< 15\%$.
17. **F17 (Active Trip Fuel Restoration on Reboot)**: Cumulative trip fuel restored from persistent vehicle state/database.
18. **F18 (Gauge Calibration Decoupled from Drift)**: Preventing software estimation drift from skewing physical tank calibration curve.
19. **F19 (Atomic Backup Restore with Rollback)**: Transactional restore across 6 Room DAOs with pre-restore safety snapshot.
20. **F20 (Schema Version Gating in Backup/Restore)**: Enforcing schema version check (v8) before restoring archives.
21. **F21 (Post-Restore Live Repository State Reload)**: State hydration for singletons (`TripRepository`, `OdometerManager`, `FuelRepository`).
22. **F22 (Database Coroutine Exception Handling)**: Structured error handling and graceful fallback on DB write failures.
23. **F23 (Flash Wear & Disk I/O Throttling)**: Debounced disk persistence for high-frequency GPS telemetry.
24. **F24 (Offline Map Tile Image Validation)**: Magic bytes verification (JPEG/PNG) and size checks preventing corrupt tile cache.
25. **F26 (Captive Portal Rejection)**: HTML captive portal responses identified and rejected from map cache.
26. **F25 (SQLite Tile Writer Resource Management)**: Resource closing and lock management.
27. **F27 (LiveMapView Pulsing Aura Background Pause)**: 30 FPS render loop paused when in background.
28. **F28 (Unused Native Library Elimination)**: Lean startup without unneeded native SDK loads.
29. **F29 (Comprehensive 4-Tier E2E Test Suite)**: Automated testing framework validating complete system behavior.
30. **F30 (Adversarial Coverage Hardening)**: Boundary, extreme conditions, and adversarial inputs.

---

## 4. 4-Tier Coverage Plan

### Tier 1: Feature Coverage (Happy-Path Unit Tests)
- **Objective**: Verify that each of the 30 features fulfills its primary contract in isolation under normal operating conditions.
- **Coverage**: Discrete tests for every feature: GPS filtering, calibration calculations, distance accumulation, time formatting, manifest generation, tile math, and odometer precision.

### Tier 2: Boundary & Corner Cases (Stress & Limits)
- **Objective**: Subject each subsystem to edge conditions, extreme parameters, and invalid data combinations.
- **Coverage**:
  - Zero, negative, and extreme fuel quantities (>200 L)
  - Speeds exceeding physical maximums (>240 km/h, 350 km/h)
  - Negative time deltas and forward clock jumps (NTP clock sync adjustments)
  - Extreme geographic coordinates ($\pm 90^\circ$ latitude, $\pm 180^\circ$ longitude, $(0,0)$ null island)
  - Zero-distance trips, empty backups, corrupted manifest JSON, truncated tile streams
  - High odometer values ($> 100,000$ km) testing 64-bit IEEE 754 precision.

### Tier 3: Cross-Feature Combinations (Pairwise & Inter-Module Interactions)
- **Objective**: Validate behavior when two or more subsystems interact concurrently or transition states simultaneously.
- **Coverage**:
  - Mid-trip device reboot + fuel consumption restoration + first-fix distance accumulation (F10 + F17)
  - Severe GPS blackout during active trip combined with low-fuel reserve threshold trigger (F3 + F16)
  - Geofence departure with simultaneous unrecorded fuel stop detection (F5 + F18)
  - Live active trip running concurrently with incoming backup archive restoration (F11 + F19 + F21)
  - Simultaneous odometer recalibration while vehicle is moving at highway speed (F12 + F13)
  - Ignition power-cut disconnect during 5-minute stationary traffic dwell (F5 + F8).

### Tier 4: Real-World Scenarios (End-to-End Automotive Driving Workloads)
- **Objective**: Execute multi-step realistic driving journeys from engine start to destination arrival.
- **Coverage**:
  - **Multi-day Cross-Country Journey**: 1,200 km multi-day trip with intermediate stops, hotel overnight parking, engine restarts, and proper time duration accounting.
  - **Urban Congestion & Fuel Station Dwell**: Rush-hour stop-and-go driving, 4-minute stationary stop at traffic lights (no false refuel prompt), followed by a 45-second dwell at an IndianOil fuel station triggering the verified refuel prompt and subsequent partial refill.
  - **Mountain Tunnel Blackout Transit**: High-speed driving into a mountain tunnel with zero GPS fixes for 45 seconds, followed by emerging into daylight with rapid satellite lock and smooth geodesic distance interpolation without speedometer spikes.
  - **Captive Portal Wi-Fi Interception**: Attempted offline map download in a roadside hotel with a captive portal returning an HTML 200 OK login page; verification that magic bytes validation immediately rejects the payload and prevents corrupting the SQLite map cache.
  - **Full Disaster Recovery Round-Trip**: Live operational vehicle state -> full portable ZIP backup generation -> database corruption simulation -> transactional restore with manifest version gating and live singleton rehydration.
