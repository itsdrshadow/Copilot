# Changelog

All notable changes to the **Copilot** Automotive Cockpit & Infotainment Launcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.31.1] - 2026-11-15

### Changed
- **Current Trip Telemetry Display (Single-Page Automotive View)**: Replaced the split-screen layout in `SmartTripSummaryScreen` with a clean, unified, high-density telemetry layout optimized for 10.1-inch landscape cockpit displays. Telemetry data now presents in a balanced two-column dashboard (Primary Telemetry & Motion Breakdown on the left, Fuel & Running Cost with Route Corridor & Milestones on the right) fitting completely on a single page without cramped scrolling.
- **Enhanced Fuel & Running Cost Readouts**: Integrated `TRIP MILEAGE` (KM/L) directly into `FuelAndEfficiencyCard` alongside estimated fuel burnt (L) and trip cost (₹), complemented by cruising ratio telemetry and measurement method indicators.
- **Unified Corridor & Timeline Milestones**: Consolidated route departure/arrival times, stops count, GPS accuracy rating, refuel highlights, and chronological milestones into a streamlined card (`TripCorridorAndDetailsCard`).

### Fixed
- **DrivingTrackingService Cached Location Query Guard**: Wrapped cached location manager queries (`queryLocationManagerCachedFix`) with a `SecurityException` handler to prevent crashes on fresh device setups or emulators before runtime location permissions are granted.

### Added
- **10.1-inch Tablet Android Virtual Device (AVD)**: Created and verified an Android 11 (API 30, x86_64) tablet emulator (`Tablet_10_1_API_30`) running WXGA 1280x800 landscape resolution at 160 dpi with KVM hardware acceleration and 2048 MB RAM.
- **Linux Android Development Environment**: Configured Eclipse Temurin OpenJDK 17, Android Studio IDE with system desktop launcher, Android SDK Platforms 34 & 30, Build-Tools 34.0.0, Platform-Tools, and Android Emulator.

---

## [1.31.0] - 2026-11-15

### Added
- **Speedometer-Style Numeric Typography Tokens (`AutomotiveDesignTokens.NumericTypography`)**: Created centralized typography tokens standardizing automotive font family (`FontFamily.SansSerif`), `FontWeight.Black`, and tabular figures (`fontFeatureSettings = "tnum"`) across the launcher.
- **Unified Telemetry Readouts**: Applied standardized speedometer-style numeric typography to live speed, odometer readings, delta since calibration, stopping distance calculations, fuel quantities and tank levels, estimated driving range, trip distance, duration timers, digital clock, and trip summary statistics.
- **Standardized Numeric Typography Test Suite**: Added verification unit tests in `AutomotiveDesignTokensTest` ensuring strict enforcement of tabular figures, typography weights, and token geometry.

### Changed
- **Driver-Legible Automotive Typography & Touch Targets**: Eliminated undersized micro-typography across cards (increased secondary labels from 8.5sp–9.5sp to 11.5sp–13sp) to ensure effortless readability at 10.1-inch landscape driving distance.
- **Adaptive Number Text & Metric Value Standardization**: Updated `AdaptiveNumberText` and `AdaptiveMetricValueText` to automatically inherit speedometer font family and tabular numerals by default.
- **App Drawer & Cockpit Docks Polish**: Enhanced touch target dimensions, icon alignment, and visual hierarchy across telemetry docks and quick-action navigation pills.

### Fixed
- **Inconsistent Numeral Styling**: Permanently eradicated font-family mismatch between monospace and sans-serif digits across fuel dialogs, trip summaries, timeline playback, and odometer dialogs.
- **Dead Code & Compiler Warnings**: Removed unused variables (`isCurrent`), replaced deprecated icons with `Icons.AutoMirrored` variants, and upgraded deprecated dividers to Compose M3 layout standards.

---

## [1.30.0] - 2026-11-15

### Added
- **Jetpack DataStore Persistent Settings Engine (`SettingsDataStore`)**: Integrated Jetpack Preferences DataStore providing crash-safe, atomic disk writes, thread-safe transactional updates, and reactive flow emissions for all 27 cockpit configurations and automotive settings.
- **Enterprise Storage Migration Manager (`StorageMigrationManager`)**: Implemented version-safe startup orchestration maintaining an independent `CURRENT_STORAGE_VERSION = 1` across app updates; handles safe pre-validation, non-destructive schema migrations, database integrity checks (`PRAGMA quick_check`), and guaranteed user settings preservation.
- **Comprehensive Automotive Backup Snapshotting**: Enhanced `PersistentDataManager` and `FullBackupManager` to mirror all 27 user settings, appearance preferences, vehicle specifications, last completed trip references, and odometer calibrations to durable mirror storage.
- **Android Auto Backup & Data Extraction XML Rules**: Added `backup_rules.xml` and `data_extraction_rules.xml` defining explicit retention policies for Room databases, DataStore stores, and preferences across OS-level backups and app updates.
- **Automated Storage & Migration Verification Test Suite (`PersistentStorageAndMigrationTest`)**: Added full unit test coverage validating sequential Room migrations (v1 through v8), storage version independence, preference defaults priority, and DataStore key integrity.

### Changed
- **Dual-Backed App Settings (`AppSettingsManager`)**: Refactored `AppSettingsManager` to synchronize in-memory StateFlow with Jetpack DataStore asynchronously while maintaining synchronous initial state and backward-compatible SharedPreferences bridging.
- **Safe Application Startup (`CopilotApp`)**: Orchestrated ordered startup sequence ensuring `StorageMigrationManager` completes migration and settings hydration before the UI renders or foreground tracking services launch, preventing default-value overwrite race conditions.

### Fixed
- **App Update Data Loss Root Cause**: Permanently eliminated data and settings reset on APK upgrade by eradicating startup race conditions, removing destructive migration fallbacks, and ensuring existing user configurations take strict precedence over initial defaults.
- **Room Database Destructive Fallback Elimination**: Removed `fallbackToDestructiveMigrationOnDowngrade` in `AppDatabase`, guaranteeing user databases and historical driving records are never wiped or destroyed during version transitions.

---

## [1.29.1] - 2026-11-15

### Changed
- **Current Trip Card Tap Target**: Removed the separate "SUMMARY" pill button from the card header; made the entire `CurrentTripCard` directly clickable (`onClick = onOpenTripSummary`) for effortless one-touch access to trip reports while driving.
- **Trip Summary Big Numbers Telemetry Redesign**: Re-engineered `SmartTripSummaryScreen` into a high-impact, glanceable automotive dashboard featuring large prominent numbers (`42sp`–`46sp` for Total Distance, Trip Duration, Average Speed, and Peak Speed). Added structured breakdown cards for driving vs stopped motion, driver rating score, fuel burn, and cruising efficiency.

### Removed
- **Verbose AI Insight Descriptions**: Completely purged paragraph-length text descriptions and explanatory AI cards (`AiTripInsightsCard`) in favor of direct, large-format numerical metrics optimized for automotive head-units.

---

## [1.29.0] - 2026-11-15

### Added
- **Centralized Automotive Design System (`AutomotiveDesignTokens`)**: Introduced an 8dp-based spatial grid, driver-safe touch targets (64–88dp), prominent glanceable typography hierarchy (speed: 82sp, clock: 62sp, hero: 44sp, secondary: 34sp), consistent container radii (20dp card, 16dp container, 14dp button, 24dp pill), and standardized dock dimensions (48dp height).
- **Driver-Safe High-Contrast Automotive Theme**: Preserved dark cockpit contrast with vibrant semantic accents (Electric Cyan `#38BDF8`, Emerald `#22C993`, Amber `#F59E0B`, Coral/Red `#EF4444`) and deep navy surfaces (`#080E1B` / `#111D30`) optimized for 10.1-inch landscape automotive displays.
- **Automotive Design Tokens Test Suite (`AutomotiveDesignTokensTest`)**: Added comprehensive unit test coverage verifying spatial tokens, touch target minimums (>=64dp), typography invariants, 6x5 app drawer grid, and clean settings persistence.

### Changed
- **Fixed Automotive Infotainment Layout**: Redesigned all major screens and components—including Dashboard, Speedometer Cluster, Fuel Tank Card, Current Trip Card, Smart Music & Clock Card, App Drawer, Smart Trip Summary, Settings, and Fuel Entry dialogs—to use unified fixed tokens instead of dynamic font multipliers.
- **App Drawer & Dock Architecture**: Replaced scaling with fixed 48dp app drawer icons, 12sp labels, 48dp navigation pills, and standardized 48dp card docks with 16dp internal card padding.

### Removed
- **Dynamic 4-Level UI Scaling**: Completely eliminated `UiScaleMode` (`Small`, `Default`, `Large`, `Extra Large`), `CockpitScaleTokens`, `LocalCockpitScale`, `LocalUiScaleMode`, `Typography.scaled()`, preferences keys (`KEY_UI_SCALE`, `uiScale`), and UI scale selector settings.
- **`isVeryCompact` Logic & Compact Workarounds**: Completely eradicated `isVeryCompact` flags, conditional layout branches, compact dimensions, and obsolete responsive fallbacks across all launcher composables and dialogs.

---

## [1.28.0] - 2026-11-15

### Added
- **Smart Trip Summary Full-Screen Page**: Introduced a modern automotive report screen (`SmartTripSummaryScreen`) presenting complete trip intelligence upon trip completion, with quick glance telemetry badges, full trip metrics, speed bands, elevation profiling, and GPS quality indicators.
- **Interactive Offline Satellite Route Map**: Integrated `SmartTripMapView` using offline satellite tiles, displaying start/finish pins, intermediate stationary stops, route trajectory, and interactive zoom/pan controls.
- **Deterministic AI Trip Insights**: Added `SmartTripAnalyticsEngine` computing transparent driver efficiency scores, speed band breakdowns, stationary dwell impact, historical trip comparisons, and actionable fuel-saving observations grounded purely in measured telemetry.
- **Integrated Fuel & Refueling Report**: Correlated trip duration and geographic route with `FuelRepository` logs to detect and display refueling events (litres added, time, location, cost) alongside sensor-backed or calculated consumption figures.
- **Chronological Trip Timeline**: Added an event timeline documenting journey start, major stops with durations, refueling intervals, and completion timestamps.
- **Persistent Last Completed Trip Architecture**: Implemented persistent pointer reference in `TripRepository` and `AppDatabase` preserving the latest completed trip across infotainment power cycles and app restarts (Scenario B default launcher destination), while maintaining permanent historical trip records.
- **Automatic 5-Second Transition & Lifecycle Recovery**: Added automatic 5-second post-completion transition to Smart Trip Summary (Scenario A/D), automatic return to Cockpit Dashboard when a new trip commences (Scenario C), and manual access via Dashboard pills and All Apps drawer.

### Changed
- **Stationary Debounce & Traffic Jam Detection**: Enhanced `TripDetectionEngine` with stationary anchor tracking and configurable debounce thresholds (`stationaryCompletionThresholdSeconds`, `stationaryDriftRadiusMeters`), preventing false trip termination during traffic lights, congested bottlenecks, and temporary halts.

---

## [1.27.2] - 2026-11-15

### Added
- **Speedometer Cluster Odometer & Since Cal. Integration**: Embedded vehicle odometer (`ODOMETER`) and calibration delta (`SINCE CAL.`) in the `SpeedometerCluster` bottom dock, replacing obsolete stop distance and best eco widgets. Added interactive `SpeedometerOdometerCalibrationDialog` on tap for instantaneous odometer offset adjustments.
- **Permanent GraphicEq Media Pill**: Introduced a permanent automotive equalizer pill (`Icons.Default.GraphicEq`) inside `SmartMusicClockCard`, visibly indicating media status across both idle and active playback modes with a modern automotive icon.

### Changed
- **Unified Dark Cockpit Theme**: Standardized outer background, elevated dock surfaces (`cardElevated`), borders, and corner radiuses identically across `SpeedometerCluster`, `FuelTankCard`, `CurrentTripCard`, and `SmartMusicClockCard`.
- **10-Bar Fuel Gauge Synchronization**: Refined full tank LED MID bar fallback to accurately illuminate all 10 segments when tank level is at 100%.

---

## [1.27.1] - 2026-11-15

### Fixed
- **Restored Original Speedometer & Fuel Tank Cards**: Restored the original standalone `SpeedometerCluster` (with 240° dial, speed limit, speed trend badge) and standalone `FuelTankCard` (with "+ ADD FUEL" button, OEM capacity, 10-bar LED MID gauge, Est. Range, and Current Mileage km/L) in the balanced 2x2 cockpit dashboard grid alongside `CurrentTripCard` and `SmartMusicClockCard`.
- **UI Scaling Text Shrinkage Bug**: Fixed root-cause regression in `DashboardScreen` where selecting `UiScaleMode.EXTRA_LARGE` inadvertently forced `isVeryCompact = true`, causing card typography and layouts to collapse into tiny compact mode instead of scaling up.
- **Automotive Typography & Glanceability**: Elevated typography across speedometer digits, digital clock, and telemetry metrics for effortless readability from normal driving distance.
- **10-Segment LED MID Gauge & Calibration**: Restored original 10-point color palette (red, amber, cyan) with full mid-bar calibration dialog trigger.

---

## [1.27.0] - 2026-11-14

### Added
- **Unified Vehicle Information Card**: Consolidated Speedometer, Fuel System, and Odometer into a single automotive telemetry card featuring a 240° precision dial, prominent speed readout, fuel tank gauge with DTE range, and live calibrated odometer tracking.
- **Smart Music Player & Permanent Clock Card**: Replaced the old standalone Odometer card with an intelligent dual-mode card. When media is idle, presents a large glanceable digital clock; when media is active, seamlessly displays clock alongside track metadata, album art, progress bar, and active media session controls.
- **Automotive Media Session Integration**: Integrated `MediaSessionManager` with active controller prioritization, transport controls (Play/Pause, Skip Next/Prev, Seek), and zero-IPC-polling elapsed realtime progress calculation.

### Changed
- **Cockpit Dashboard Telemetry Layout**: Rebalanced the telemetry quadrant into a unified top-row Vehicle Dashboard Card and bottom-row Current Trip + Music/Clock layout with responsive scaling support across Small (0.85×), Default (1.00×), Large (1.15×), and Extra Large (1.30×).

### Removed
- **Standalone Odometer Card & Media Pill**: Removed legacy standalone `OdometerCard` and obsolete `MediaPlaybackPill`, reclaiming UI real estate and eliminating redundant state computation.

---

## [1.26.0] - 2026-11-14

### Added
- **UI & Text Scaling System**: Added a centralized, responsive scaling system under **Settings → Appearance** offering four optimized levels: Small (0.85×), Default (1.00×), Large (1.15×), and Extra Large (1.30×).
- **Appearance Settings Tab**: Introduced a dedicated `Appearance` category in `LauncherSettingsDialog` featuring an intuitive visual selector card deck for UI scaling with instant live preview and theme swatches.
- **Immediate Reactive Scaling & Persistence**: Scales typography, icon targets, cards, padding, and navigation docks across the entire launcher reactively without requiring an app restart, persisted across reboots via `AppSettingsManager`.

### Changed
- **Responsive Layout & Overflow Protection**: Integrated adaptive bounds calculation preventing clipping, text overlap, and layout overflow across all screens including Cockpit dashboard, Live Map, App Drawer, Trips, Fuels, and system dialogs at all 4 scale tiers.
- **App Drawer Grid & Dock Optimization**: App drawer 6×5 grid icons and labels adapt dynamically with scaling while maintaining touch usability and screen space constraints.

---

## [1.25.3] - 2026-11-14

### Fixed
- **App Drawer Navigation to Trip & Fuel History**: Resolved navigation masking where clicking `Trips` or `Fuels` in the app drawer header selected the corresponding tab (`TripHistoryScreen` / `FuelHistoryScreen`) without dismissing the overlay drawer. Now automatically closes the app drawer to immediately present Trip History and Fuel History.
- **Top Dock Quick Button Label**: Renamed the top dock action pill to `Fuels` for direct alignment with Cockpit, Trips, and Settings navigation.

---

## [1.25.2] - 2026-11-14

### Changed
- **6×5 App Drawer Grid**: Converted the application drawer into a high-density 6-column × 5-row grid (30 apps per page) optimized for widescreen automotive displays.
- **Enlarged Action Buttons**: Increased touch targets, icon sizes (19-22dp), and text labels (13.5-15sp) for Cockpit, Trips, Fuel Log, and Settings pill buttons in the top dock for enhanced driver glanceability.

---

## [1.25.1] - 2026-11-14

### Changed
- **1×1 App Icon Redesign**: Converted app drawer items to compact 1×1 centered launcher icons with app titles underneath, removing bulky rectangular card boxes and borders for a native launcher look and feel.
- **Enlarged Top Navigation Dock**: Expanded `AppDrawerHeader` into a taller, more prominent top dock bar.
- **Icon + Text Pill Buttons**: Replaced icon-only action buttons in the top dock with clear, driver-readable Icon + Text pill buttons (`Cockpit`, `Trips`, `Fuel Log`, `Settings`).

### Removed
- **Home Screen Apps Indicator**: Removed the right-edge swipe indicator affordance from `DashboardScreen.kt`, leaving the home screen completely clean while preserving left-swipe drawer opening.

---

## [1.25.0] - 2026-11-14

### Added
- **3×4 Horizontal App Drawer**: Introduced `AppDrawerScreen.kt` featuring a strict 3-column × 4-row grid (12 applications per page) with smooth horizontal swiping, glowing pill page indicators, search filtering, and quick navigation shortcuts.
- **Left-Swipe Gesture Navigation**: Swiping left anywhere from the home screen smoothly transitions to All Applications, with a glanceable automotive edge affordance.
- **Dynamic Application Detection & Package Watcher**: Added runtime `BroadcastReceiver` in `MainActivity` for `ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REMOVED`, `ACTION_PACKAGE_CHANGED`, and `ACTION_PACKAGE_REPLACED` to dynamically refresh the app drawer without stale caches or duplicates.
- **Automotive Grid Alignment**: Balanced placeholder cells ensure that final partially filled pages maintain exact column widths, row heights, and top-left alignment without stretching or centering distortion.

### Changed
- **Full-Screen Home Screen Redesign**: Reclaimed 84dp+ of vertical space across 10.1-inch automotive displays, expanding `LiveMapPanel` and the 2×2 telemetry cards (`SpeedometerCluster`, `FuelTankCard`, `CurrentTripCard`, `OdometerCard`) to fill the full viewport.
- **SemVer Minor Bump**: Upgraded version to `1.25.0` (versionCode `42`) in `app/build.gradle.kts`.

### Removed
- **Bottom Dock Elimination**: Completely eliminated `PremiumBottomDock`, `PremiumDockClock`, and `PremiumDockItem` composables, containers, spacers, and dock-specific navigation states from `DashboardScreen.kt`.
- **Legacy Dialog Drawer**: Removed obsolete `AppDrawerDialog.kt` and its vertical scrolling grid.

---

## [1.24.1] - 2026-11-14

### Changed
- **Streamlined Navigation & Controls**: Removed Statistics toggle chips from top header bars in both `TripHistoryScreen` and `FuelHistoryScreen` for an uncluttered and direct records browsing experience.
- **Direct Feed Presentation**: `TripHistoryScreen` and `FuelHistoryScreen` now display the chronological feeds and yearly summary banners directly without mode toggling overhead.

### Removed
- **Dedicated Statistics Dashboards**: Completely removed `TripStatisticsDashboard.kt` (~77 KB) and `FuelStatisticsDashboard.kt` (~73 KB) along with all associated sub-composables, data classes, and chart visualizers.
- **Dead Parameters & Imports**: Removed unused `trips` parameter from `FuelHistoryScreen` and cleaned unreferenced icon and database entity imports.

---

## [1.24.0] - 2026-11-14

### Changed
- **Rebrand to Copilot**: Updated application identity, brand typography in `AutomotiveBrandLogo`, splash screen reveal title, launcher app name (`strings.xml`), foreground service notification channels, and settings dialogs to "Copilot".
- **Package Name Migration**: Refactored namespace and `applicationId` to `com.antigravity.copilot` across Gradle build configuration, AndroidManifest, and entire Kotlin package tree (111 source files).
- **Core Architecture Modernization**: Renamed application entry point to `CopilotApp` and design theme system to `CopilotTheme` (with backward-compatible typealiases for seamless test coverage).
- **Storage & Backup Directory Modernization**: Updated persistent backup archives and crash logging to `/sdcard/Copilot/Backups` with automatic fallback migration detection for legacy `/sdcard/DrivingReport` stores.

---

## [1.23.1] - 2026-11-14

### Changed
- **SemVer Version Bump**: Upgraded version to `1.23.1` (versionCode `39`) following semantic versioning guidelines for patch cleanup release.
- **Dependency Optimization**: Removed redundant `org.maplibre.gl:android-sdk:11.0.0` dependency from `app/build.gradle.kts`, relying strictly on lightweight hardware-accelerated OSMDroid native engine and reducing final APK footprint.

### Removed
- **Obsolete UI Components**: Safely removed deprecated prototype composables `FuelStatisticsView`, `InsightCard`, `formatScaleValue`, `AutomotiveFuelChartCanvas`, and `FuelHistoryDialog` from `FuelHistoryScreen.kt` (superseded by modern `FuelStatisticsDashboard.kt`).
- **Dead Visualizer Logic**: Removed unreachable `AnnualTripStatisticsView` and `AnnualInsightCard` from `TripHistoryScreen.kt` (superseded by modern `TripStatisticsDashboard.kt`).
- **Unused Core Engines & Models**: Removed unreferenced `DarkMapStyle.kt`, `SolarEphemerisEngine.kt`, and obsolete `DrivingScoreReport` model from `Models.kt`.
- **Unused Drawables**: Removed dead vector asset `@drawable/ic_navigation_arrow.xml`.
- **Redundant Imports**: Purged 100+ unused imports across all production and unit test source files to enforce strict codebase hygiene.

---

## [1.23.0] - 2026-11-14

### Added
- **Automotive Brand Logo Reveal Stage**: Initial brand presentation in `WelcomeSplashScreen` featuring the newly designed aerodynamic apex winged crest (`AutomotiveBrandLogo`), luminous telemetry ring, and metallic/neon typography.
- **Cinematic Extended Splash Sequence**: Extended duration to 6.8 seconds with multi-stage timing (Brand Logo -> Neon Trails & Car Silhouette -> Welcome Glow -> Smooth Exit), giving a realistic OEM automotive startup experience with instant tap/back-to-skip support.
- **Brand Launcher App Icon**: Redesigned `@drawable/ic_launcher_foreground` and `@drawable/ic_launcher_background` into a premium hypercar winged emblem with luminous cyan telemetry.

---

## [1.22.0] - 2026-11-14

### Added
- **Futuristic Car Silhouette Splash Screen**: Hardware-accelerated neon car silhouette canvas animation (`CarSilhouetteCanvas`) with radial horizon flare, specular ground reflection, side light trails, and glowing typography.
- **Splash Lifecycle Architecture**: Introduced `SplashConfig`, `SplashState`, and `SplashViewModel` with configurable stage timings, rotation-safe state handling, smooth exit scale/fade transition, and tap/back-to-skip support.
- **Automotive Cockpit Integration**: Integrated seamless splash overlay in `MainActivity` with smooth entrance into the main cockpit dashboard.
- **Project Discipline Rules**: Enforced mandatory emulator build, installation (`adb install -r`), and open/launch (`am start`) verification upon task completion.

---

## [1.21.0] - 2026-11-14

### Changed
- **Apex Studio Cockpit Theme**: Renamed "Premium Studio" to "Apex Studio" with luminous cyan telemetry, deep navy studio contrast, and backwards-compatible token mapping.

### Removed
- **Vehicle Service & Maintenance**: Completely removed the service button from the HomeScreen dock bar, along with all maintenance manager models, dialogs, and due service alerts.
- **Calm Teal Theme**: Completely removed Calm Teal night and day palettes from design system and settings.
- **Light Theme**: Completely removed day/light theme modes from the codebase; the entire launcher is now permanently optimized as a glare-free dark automotive cockpit.

---

## [1.20.0] - 2026-11-14

### Initial Baseline Release
- **Cockpit Dashboard & Gauges**: Complete automotive instrument cluster with dynamic glow, stopping distance, best mileage speed, and all-time statistics.
- **Trip Lifecycle & Multi-Geofence**: Central vehicle journey lifecycle engine with geofence departure/arrival detection and persistent journey timeline.
- **Adaptive Fuel Intelligence Engine**: Continuous 3-point fuel estimator, Room ledger, empirical gauge calibration, and missed entry detection.
- **India Offline Map Engine**: Vector and raster offline map rendering with anti-jitter GPS filtering and lookahead camera.
- **Automotive Audio & TTS Assistant**: Natural voice TTS integration and media playback controller.
- **Vehicle Maintenance & Health Monitor**: Mileage-based service alerts and vehicle inspection tracking.
- **Voice & Audio Settings**: Dedicated Voice & Audio tab with AI neural natural voice models.
