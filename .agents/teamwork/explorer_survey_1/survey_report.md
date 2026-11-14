# Codebase & Build Architecture Survey Report

**Project**: DrivingReport Automotive Infotainment & Cockpit Launcher  
**Explorer Subagent**: `explorer_survey_1`  
**Date**: 2026-11-10  
**Repository Working Directory**: `d:\Car Launcher`  
**Target Environment**: Android 11 (API 30) Landscape Automotive Head Unit ($1280 \times 800$)

---

## Executive Summary

The **DrivingReport** repository hosts a standalone, offline-first Android automotive cockpit launcher application built with Kotlin, Jetpack Compose, Room SQLite, OSMDroid, MapLibre GL, and Google Play Services Location. The application operates without cloud dependencies, serving as both the vehicle's primary HOME launcher and a telemetry/fuel intelligence engine.

- **Build Health**: `./gradlew testDebugUnitTest` executes **92 unit tests across 19 test classes with a 100% pass rate (0 failures, 0 regressions)** in 0.443s.
- **APK Packaging**: `./gradlew assembleDebug` compiles cleanly with exit code 0, generating `app-debug.apk` (60.6 MB).
- **Core Architecture**: Unidirectional Data Flow (UDF) with `MainViewModel`, Room SQLite database (`AppDatabase` v8), high-priority automotive foreground service (`DrivingTrackingService`), and multi-process crash isolation (`RecoveryActivity` on `:recovery`).

---

## 1. Repository Structure & Module Architecture

### 1.1 Project Structure
The repository is structured as a single-module Android application project (`:app`):

```
d:\Car Launcher\
├── .agents/teamwork/             # Multi-agent coordination metadata & reports
├── app/                          # Core application module
│   ├── build.gradle.kts          # Module-level Gradle configuration
│   ├── proguard-rules.pro        # ProGuard / R8 rules
│   ├── src/
│   │   ├── main/
│   │   │   ├── AndroidManifest.xml   # Entry points, foreground service, boot filters
│   │   │   ├── java/com/antigravity/drivingreport/
│   │   │   │   ├── DrivingReportApp.kt     # Application class & bootstrap lifecycle
│   │   │   │   ├── MainActivity.kt         # Edge-to-edge Launcher activity & UI router
│   │   │   │   ├── MainViewModel.kt        # StateFlow holder & UDF coordinator
│   │   │   │   ├── core/                   # Shared architectural core layers
│   │   │   │   │   ├── database/           # Room DB, Entities, DAOs, Migrations
│   │   │   │   │   ├── designsystem/       # CockpitTheme, Color tokens, Components
│   │   │   │   │   ├── fuel/               # Fuel AI engine, Repository, Station prices
│   │   │   │   │   │   └── intelligence/   # Adaptive mileage, math, gauge calibration
│   │   │   │   │   ├── location/           # GPS tracking service, BootReceiver, resolvers
│   │   │   │   │   ├── map/                # Offline Map Manager, styles, India maps
│   │   │   │   │   ├── metrics/            # Adaptive metrics engine
│   │   │   │   │   ├── model/              # Domain models (VehicleState, TripSummary)
│   │   │   │   │   ├── odometer/           # Hardware/telemetry odometer persistence
│   │   │   │   │   ├── persistence/        # ZIP backup/restore, factory reset
│   │   │   │   │   ├── recovery/           # CrashGuardian, isolated RecoveryActivity
│   │   │   │   │   ├── settings/           # AppSettingsManager, SharedPreferences
│   │   │   │   │   ├── trip/               # Geofence lifecycle, trip detection engine
│   │   │   │   │   └── util/               # Decimal & number formatting utils
│   │   │   │   └── feature/                # UI screens and dialog components
│   │   │   │       ├── dashboard/          # Cockpit HUD, Speedometer, Fuel/Odo cards
│   │   │   │       ├── fuel/               # Fuel entry keypad, Refuel prompt, History
│   │   │   │       ├── launcher/           # App drawer dialog, LauncherManager
│   │   │   │       ├── maps/               # Live map panel, India offline downloader
│   │   │   │       ├── settings/           # Launcher & Geofence settings dialogs
│   │   │   │       └── trips/              # Trip history & animated route replay
│   │   │   └── res/                        # Vector drawables, mipmaps, strings, styles
│   │   └── test/java/com/antigravity/drivingreport/ # 19 unit test classes (92 tests)
├── build.gradle.kts              # Root project plugin definitions
├── settings.gradle.kts           # Root settings & repository management
├── gradle.properties             # JVM arguments & AndroidX flags
├── local.properties              # Local Android SDK directory
├── gradlew / gradlew.bat         # Gradle wrapper executables
└── gradle/wrapper/               # Gradle wrapper distribution (v8.9)
```

### 1.2 Module Summary Table

| Module | Namespace | Role | Key Dependencies |
| :--- | :--- | :--- | :--- |
| **`:app`** | `com.antigravity.drivingreport` | Automotive Infotainment & Cockpit Launcher | Jetpack Compose (BOM 2024.02.01), Room 2.6.1, OSMDroid 6.1.18, MapLibre 11.0.0, Play Services Location 21.1.0, Coroutines 1.8.0, Gson 2.10.1 |

---

## 2. Build Configuration, Gradle Settings & Versions

### 2.1 Toolchain Versions

| Component | Configured Version | Notes |
| :--- | :--- | :--- |
| **Gradle** | `8.9` | Declared in `gradle/wrapper/gradle-wrapper.properties` |
| **Android Gradle Plugin (AGP)** | `8.5.2` | Declared in root `build.gradle.kts` |
| **Kotlin** | `1.9.22` | Android plugin & KAPT plugin (`org.jetbrains.kotlin.android`, `kapt`) |
| **Kotlin Compiler Extension** | `1.5.8` | Compose compiler extension version matching Kotlin 1.9.22 |
| **Java Compatibility** | `Java 17` (`JavaVersion.VERSION_17`) | JVM target `"17"` in `kotlinOptions` |
| **Active JVM Runtime** | `17.0.20.1` | Eclipse Adoptium OpenJDK 17.0.20.1+1 on Windows 11 64-bit |

### 2.2 Android SDK Configuration

| Setting | Value | Rationale |
| :--- | :--- | :--- |
| `compileSdk` | `34` (Android 14) | Provides access to latest Android APIs, insets, and system properties |
| `minSdk` | `26` (Android 8.0 Oreo) | Ensures compatibility with older automotive head units running Android 8+ |
| `targetSdk` | `30` (Android 11) | Specific optimization target for Blaupunkt / Android 11 automotive head units |
| `versionCode` | `33` | Production build version integer |
| `versionName` | `1.18.0` | Production release semantic version string |
| `testInstrumentationRunner`| `androidx.test.runner.AndroidJUnitRunner` | Instrumentation test runner |

### 2.3 Dependency Analysis

```kotlin
// UI & Jetpack Compose
platform("androidx.compose:compose-bom:2024.02.01")
"androidx.compose.ui:ui"
"androidx.compose.ui:ui-graphics"
"androidx.compose.ui:ui-tooling-preview"
"androidx.compose.material3:material3"
"androidx.compose.material:material-icons-extended"
"androidx.navigation:navigation-compose:2.7.7"
"androidx.activity:activity-compose:1.8.2"

// Architecture & Lifecycle
"androidx.core:core-ktx:1.12.0"
"androidx.lifecycle:lifecycle-runtime-ktx:2.7.0"
"androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0"

// Persistence (Room Database v8)
"androidx.room:room-runtime:2.6.1"
"androidx.room:room-ktx:2.6.1"
kapt("androidx.room:room-compiler:2.6.1")

// Maps & Geo
"org.osmdroid:osmdroid-android:6.1.18"      // Primary offline tile canvas
"org.maplibre.gl:android-sdk:11.0.0"       // OpenGL hardware-accelerated vector map
"com.google.android.gms:play-services-location:21.1.0" // Fused Location Provider

// Asynchronous & Data
"org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0"
"com.google.code.gson:gson:2.10.1"

// Testing
"junit:junit:4.13.2"
"org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0"
```

---

## 3. Application Entry Points & Component Map

The application is registered in `AndroidManifest.xml` with specialized automotive and launcher attributes:

| Component | Class Name | Process | Type | Purpose / Intent Filters |
| :--- | :--- | :--- | :--- | :--- |
| **Application** | `DrivingReportApp` | Main | `Application` | Initializes `CrashGuardian`, performs background persistent recovery (`PersistentDataManager.restoreIfNeeded`), initializes MapLibre & OSMDroid offline config, starts `DrivingTrackingService`. |
| **Home Activity** | `MainActivity` | Main | `Activity` (`singleTask`, `directBootAware`) | **Primary Launcher Home**: Handled intents:<br>• `ACTION_MAIN`<br>• `CATEGORY_HOME`<br>• `CATEGORY_DEFAULT`<br>• `CATEGORY_LAUNCHER`<br>Immersive sticky edge-to-edge UI, permission arbitration, UDF screen router. |
| **Recovery Activity** | `RecoveryActivity` | `:recovery` (Isolated JVM) | `Activity` (`singleInstance`, `excludeFromRecents`) | **Automotive Crash Guardian**: Runs in separate OS process. Intercepts repeated uncaught crash loops. Features a 10s auto-fallback countdown to Blaupunkt stock OEM launcher, safe-mode restart, and hardware reboot. |
| **Foreground Service** | `DrivingTrackingService` | Main | `Service` (`location`, `directBootAware`) | **Telemetry & Journey Engine**: Continuous foreground service. Fuses FusedLocationClient (1s updates) with fallback to native LocationManager. Integrates `TripDetectionEngine`, `GeofenceTripLifecycleEngine`, power disconnection listener, fuel station stop detector, and speedometer decay watchdog. |
| **Boot Receiver** | `BootReceiver` | Main | `BroadcastReceiver` (`directBootAware`) | **Auto-Start on Ignition/Power On**: Filters:<br>• `BOOT_COMPLETED`<br>• `LOCKED_BOOT_COMPLETED`<br>• `android.intent.action.QUICKBOOT_POWERON`<br>• `com.htc.intent.action.QUICKBOOT_POWERON`<br>Starts `DrivingTrackingService` and launches `MainActivity`. |

### 3.1 Hardware Power & Ignition Listeners
Inside `DrivingTrackingService`, a dynamic `BroadcastReceiver` listens for vehicle hardware ignition state changes:
- `ACTION_POWER_CONNECTED`: Signals vehicle alternator charging / ACC active.
- `ACTION_POWER_DISCONNECTED`: Notifies vehicle state manager and switches active journey to temporary stop without discarding ongoing multi-day geofence trips.
- `ACTION_SHUTDOWN`, `ACTION_REBOOT`, `QUICKBOOT_POWEROFF`: Immediately finalizes ongoing trip with exact shutdown timestamp and persists data to SQLite.

---

## 4. Test Suite Analysis & Execution Baseline

### 4.1 Test Suite Inventory
The test suite resides in `app/src/test/java/com/antigravity/drivingreport/`. There are currently **no instrumentation tests in `app/src/androidTest`**; all automated testing is implemented as high-coverage JVM unit tests with coroutine test dispatchers.

| # | Test Class | Test Count | Domain Area Covered |
| :-: | :--- | :-: | :--- |
| 1 | `AdaptiveFuelIntelligenceTest.kt` | 24 | 24-point edge-case matrix: baseline initialization, partial refills, continuous consumption formula, missed refuels, IQR outlier rejection, EWMA weighting, decimal drift |
| 2 | `AdaptiveMetricsEngineTest.kt` | 2 | Driver metrics, trip scoring, and adaptive telemetry smoothing |
| 3 | `AnalyticsCalculationsTest.kt` | 3 | Lifetime averages, distance summation, and fuel efficiency metrics |
| 4 | `BackupRestoreTypeSafetyTest.kt` | 3 | JSON type safety, field deserialization, and schema migration integrity |
| 5 | `BestMileageSpeedEngineTest.kt` | 6 | Optimal speed calculations, speed-efficiency curves, and ICE thermal sweet spot |
| 6 | `CockpitThemeSystemTest.kt` | 5 | Color palettes, high-contrast dark themes, and token contrast ratios |
| 7 | `FuelCalculationConsistencyTest.kt` | 2 | Deterministic fuel consumption calculations across variable time steps |
| 8 | `FuelMileageDisplayResolutionTest.kt` | 5 | Display truncation, precision formatting, and learning state transitions |
| 9 | `FullBackupManagerTest.kt` | 2 | ZIP archive creation, checksum verification, and manifest inspection |
| 10 | `GeofenceTripLifecycleTest.kt` | 6 | Geofence departure/arrival, debounce hysteresis, pre-trip buffer, flash visual states, multi-day timers |
| 11 | `GpsConcurrencyAndArbitrationTest.kt` | 2 | FusedLocation vs LocationManager arbitration and anti-jitter deduplication |
| 12 | `MissedEntryExtremeBoundaryTest.kt` | 3 | Tank capacity limits, zero price handling, and abnormal float jumps |
| 13 | `NumberFormatUtilsTest.kt` | 7 | String formatting for speed, mileage, currency, and volume |
| 14 | `OdometerManagerTest.kt` | 2 | Odometer calibration offsets and monotonicity |
| 15 | `OdometerPersistenceTest.kt` | 3 | Persistent storage of calibrated odometer baseline in SharedPreferences and JSON mirror |
| 16 | `SatelliteMapAndMultiPassTrailTest.kt` | 3 | Route coordinates, tile bounds, and multi-pass trail layering |
| 17 | `StoppingDistanceEngineTest.kt` | 6 | Kinetic stopping distance, braking deceleration physics, and reaction times |
| 18 | `VehicleJourneyAndFuelEngineValidationTest.kt` | 6 | Integrated trip-to-fuel reconciliation and state transition pipelines |
| 19 | `VehicleStateDomainTest.kt` | 2 | Finite state transitions (`IDLE`, `MOVING`, `ENGINE_OFF`, `STOP_CANDIDATE`) |
| **TOTAL** | **19 Test Classes** | **92 Tests** | **100% Pass Rate (92 Passed, 0 Failed, 0 Ignored)** |

### 4.2 Verified Test Commands
- **Run Unit Tests (Debug)**:
  ```powershell
  .\gradlew testDebugUnitTest
  ```
  Result: **92 tests, 0 failures, 0 ignored** in **0.443s**. HTML report generated at `app/build/reports/tests/testDebugUnitTest/index.html`.
- **Run Unit Tests (Release)**:
  ```powershell
  .\gradlew testReleaseUnitTest
  ```
  Result: **BUILD SUCCESSFUL**, 92 tests, 0 failures, 0 ignored.
- **Run Unit Tests (All Variants)**:
  ```powershell
  .\gradlew test
  ```
  Result: **BUILD SUCCESSFUL**, all unit tests pass across both Debug and Release variants.
- **Assemble Debug Package**:
  ```powershell
  .\gradlew assembleDebug
  ```
  Result: **BUILD SUCCESSFUL** in 1m 27s. Output: `app/build/outputs/apk/debug/app-debug.apk` (60.6 MB).

---

## 5. Build Warnings, Configuration Issues & Observations

During the build and exploration process, several notable configuration warnings, environmental quirks, and potential risks were observed:

### 5.1 Windows File Lock & Kotlin Daemon Crashes
- **Observation**: During concurrent or interrupted builds on Windows, the Kotlin Daemon occasionally failed with `DaemonCrashedException` and `SocketException: Connection reset by peer`, leaving lingering `java.exe` processes holding locks on `app\build\tmp\kotlin-classes\debug\com`.
- **Observation**: KAPT incremental compilation threw:
  `FileNotFoundException: ...\app\build\tmp\kapt3\incrementalData\debug\com\antigravity\drivingreport\core\location\FuelStationDetectionResult.class`
- **Mitigation / Remedy**: Running `.\gradlew --stop` followed by `.\gradlew clean` completely resolves all corrupted caches and produces clean builds.
- **Root Cause & Fix Recommendation**: Adding `kotlin.incremental=false` or providing sufficient memory arguments in `gradle.properties` (`kotlin.daemon.jvmargs=-Xmx2048m`) prevents buffer overflows in `lookups.tab`.

### 5.2 Compiler Warnings Identified in Source Code
1. **Unused Parameter Warnings**:
   - `core/designsystem/Theme.kt:311`: Parameter `themeMode` is never used.
   - `feature/settings/LauncherSettingsDialog.kt:79`: Parameter `onPreviewTheme` is never used.
2. **Deprecated API Usage**:
   - `core/recovery/CrashGuardian.kt:237`: `context.packageManager.clearPackagePreferredActivities(context.packageName)` is deprecated in API 29+.
   - `feature/settings/LauncherSettingsDialog.kt:949`: `Icons.Default.ExitToApp` is deprecated; recommends `Icons.AutoMirrored.Filled.ExitToApp`.
3. **KAPT Annotation Processor Warnings**:
   - `warning: The following options were not recognized by any processor: '[room.schemaLocation, kapt.kotlin.generated]'` (Room schema location option configured in `kapt` block, but Room compiler processor arguments should be configured via `kapt { arguments { arg(...) } }` or `room.schemaLocation` in `defaultConfig.kapt`).
4. **Native Library Strip Warning**:
   - `> Task :app:stripDebugDebugSymbols`: `Unable to strip the following libraries, packaging them as they are: libmaplibre.so.` (Harmless for debug builds, but should be verified in release packaging).
5. **Division by Zero Warning in Test**:
   - `AnalyticsCalculationsTest.kt:17, 22`: Static compiler warning regarding `Division by zero` in intentional edge-case unit test assertions.

### 5.3 SDK Target Strategy
- `compileSdk = 34`, `targetSdk = 30`, `minSdk = 26`.
- `targetSdk = 30` is deliberately selected for Android 11 automotive head units (e.g., Blaupunkt) to ensure full access to `QUERY_ALL_PACKAGES` without Android 12+ foreground service and splash screen behavioral restrictions, while `compileSdk = 34` provides modern Android layout and window insets support.

---

## 6. Recommendations for Downstream Agents

1. **For Fixer / Developer Agents**:
   - When running unit tests during verification, prefer `./gradlew testDebugUnitTest` for faster turnaround (compiles only debug variant in ~30s rather than compiling both debug and release).
   - If a build fails with `DaemonCrashedException` or `IOException: Could not delete ...`, run `.\gradlew --stop` and `.\gradlew clean`.
   - Consider resolving the deprecated `ExitToApp` icon and unused parameter compiler warnings.
2. **For Critique / Review Agents**:
   - Focus recommendations on the 1280x800 landscape layout in `DashboardScreen.kt`, specifically examining `LiveMapPanel` (~52% width) vs the 2x2 Telemetry grid (~48% width) and the bottom dock (`dockHeight = 78.dp`).
   - Review driver ergonomics, contrast readability in bright daylight vs night driving, touch target sizes (minimum 48dp on automotive head units), and one-handed quick refuel interactions.
