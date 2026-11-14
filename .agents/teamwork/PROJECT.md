# Project: DrivingReport Automotive Infotainment & Cockpit Launcher

## Architecture
- **Application Type**: Single-module Android application (`:app`) acting as the Automotive Launcher and Cockpit Telemetry Hub.
- **Language & Toolchain**: Kotlin 1.9.22, Java 17, Gradle 8.9, Android Gradle Plugin 8.5.2.
- **UI Framework**: Jetpack Compose BOM 2024.02.01 with Material 3 design system. Target resolution: 1280x800 landscape Automotive Head Unit.
- **Data & Persistence**: Room 2.6.1 (`DrivingReportDatabase` schema v8), Encrypted/SharedPreferences (`AppSettingsManager`, `FuelRepository`).
- **Telemetry & Location**: Foreground service `DrivingTrackingService` with fused location, offline reverse geocoding, and map rendering via OSMDroid/MapLibre.
- **Crash Isolation**: Multi-process recovery architecture (`:recovery` process) via `RecoveryActivity` and `CrashGuardian`.

## Feature Inventory
Every feature from the Survey phase appears here with its assigned milestone:
| # | Feature / Issue | Description | Milestone | Source |
|---|-----------------|-------------|-----------|--------|
| 1 | `BUG-LOGIC-001` | $O(N^2)$ trail array copying on live GPS updates in `TripRepository.kt:294` | M1 (DONE) | explorer_survey_3 |
| 2 | `BUG-LOGIC-002` | Full route traversal & GeoPoint re-allocation on UI thread in `LiveMapView.kt:177` | M1 (DONE) | explorer_survey_3 |
| 3 | `BUG-LOGIC-003` | Offline blocking HTTP socket timeouts in `SpeedLimitEngine.kt:161-242` | M1 (DONE) | explorer_survey_3 |
| 4 | `BUG-LOGIC-004` / `FINDING-M1-001` | `CurrentTripCard.kt` 1-sec ticker & flash pulse dismiss fix | M2 | reviewer_m1_2 |
| 5 | `BUG-LOGIC-006` | Backup manifest version desynchronization in `FullBackupManager.kt:36-37` | M1 (DONE) | explorer_survey_3 |
| 6 | `BUG-LOGIC-008` | Duplicate sequential SharedPreferences write in `FuelRepository.kt:351-352` | M1 (DONE) | explorer_survey_3 |
| 7 | `BUG-LOGIC-010` | Missing ContextCompat.RECEIVER_NOT_EXPORTED flag in `DrivingTrackingService.kt:251` | M1 (DONE) | explorer_survey_3 |
| 8 | `BUG-UI-001` | Speedometer false zero-speed drop (1850ms timeout) in `DashboardScreen.kt:68` | M2 | explorer_survey_2 |
| 9 | `BUG-UI-002` | Proportional numeral font jitter on speed readout in `SpeedometerCluster.kt:305` | M2 | explorer_survey_2 |
| 10 | `BUG-UI-003` | Sub-standard touch targets (<48dp) in Fuel MID, Odometer calibration, and carousel dots | M2 | explorer_survey_2 |
| 11 | `BUG-UI-004` | Raw literal UTF-8 `±` character in `LiveMapView.kt:914` | M2 | explorer_survey_2 |
| 12 | `BUG-UI-005` | `android:screenOrientation="fullSensor"` causing orientation flips in `AndroidManifest.xml:55` | M2 | explorer_survey_2 |
| 13 | `BUG-LOGIC-005` | Route replay object allocation churn in `TripHistoryScreen.kt:2944` | M2 | explorer_survey_3 |
| 14 | `BUG-LOGIC-007` | Hardcoded fallback mileage literal `18.0` in `DashboardScreen.kt:94` | M2 | explorer_survey_3 |
| 15 | `BUG-LOGIC-009` | Silent navigation intent launch failure in `LauncherManager.kt:86` | M2 | explorer_survey_3 |
| 16 | `CRITIQUE-001` | In-depth 1280x800 Automotive Head Unit UI/UX critical review (`CRITIQUE_AND_SUGGESTIONS.md`) | M3 (DONE) | explorer_survey_2 |
| 17 | `TEST-BUILD-001` | Automated test suite execution & verification (`./gradlew testDebugUnitTest`) | M4 | explorer_survey_1 |
| 18 | `TEST-BUILD-002` | Debug APK build verification (`./gradlew assembleDebug`) | M4 | explorer_survey_1 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Services & Data Layer Bug Fixes | Fix BUG-LOGIC-001, 002, 003, 006, 008, 010 in core repo & service files | none | DONE |
| M2 | Cockpit UI, Dashboard Ergonomics & Card Fixes | Fix BUG-UI-001 to 005, BUG-LOGIC-005, 007, 009, and FINDING-M1-001 | M1 | IN_PROGRESS |
| M3 | Automotive Head Unit Critical Review | Deliver `CRITIQUE_AND_SUGGESTIONS.md` in repository root with /grill-me recommendations | none | DONE |
| M4 | Final Verification, Unit Tests & Build Integrity | Run and pass `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` | M1, M2, M3 | PLANNED |

## Interface Contracts
### Core Data ↔ Cockpit UI
- `TripRepository._tripTrail`: `StateFlow<List<TripPoint>>` emits memory-efficient immutable snapshots; UI renders incrementally.
- `SpeedLimitEngine`: queries network only when `isNetworkConnected(context) == true`; falls back immediately to kinematic limits.
- `DashboardScreen` & `SpeedometerCluster`: speed validity threshold synchronized to 4000-5000ms.
- `CurrentTripCard`: timer ticks when trip is active OR during 10s visual flash window, then cleanly idles.
- `FullBackupManager`: synchronized to version code 33 and version name "1.18.0".

## Code Layout
- `app/src/main/java/com/antigravity/drivingreport/core/`: Core background services, location, telemetry, persistence, fuel math.
- `app/src/main/java/com/antigravity/drivingreport/feature/`: UI feature modules (dashboard, maps, fuel, trips, launcher, settings).
- `app/src/main/AndroidManifest.xml`: Application manifest, permissions, receivers, activities.
- `app/src/test/java/com/antigravity/drivingreport/`: JVM Unit test suites.
