# Changelog

All notable changes to the **Copilot** Automotive Cockpit & Infotainment Launcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
