# Changelog

All notable changes to the **Copilot** Automotive Cockpit & Infotainment Launcher will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

## [1.36.0] - 2026-11-16

### Fixed
- **Infotainment Idle CPU Consumption Reduced from ~141% to 0% – 6%**:
  - **Eliminated Infinite Map Redraw Loop**: Removed recursive `mapView.postInvalidateDelayed(33L)` inside `PulsingAuraOverlay.draw()` that was triggering an unconstrained 30 FPS redraw loop across the entire osmdroid map canvas, forcing constant tile re-projection and color-filter execution.
  - **Recomposition & RenderThread Decoupling**: Extracted status beacon dot into a lightweight composable (`StatusBeaconDot`) and replaced continuous 60 FPS `rememberInfiniteTransition` with a clean static beacon dot during stationary states, eliminating non-stop Choreographer vsync draw triggers on the window.
  - **Stationary GPS Navigator Optimization**: Added stationary movement detection in `SmoothMapNavigator.updateTarget()` (detecting speed $< 1.5\text{ km/h}$ and sub-meter coordinate displacement) that cancels running 60 FPS dead-reckoning animators and updates marker position statically rather than scheduling continuous frame invalidations while parked.
  - **Compass Sensor Event Throttling**: Throttled hardware rotation sensor updates in `LiveMapView` while stationary ($500\text{ms}$ interval with $\ge 5^\circ$ deadband) and restricted sensor listener registration strictly to active `isHeadingUpMode`, preventing sensor noise from triggering redundant map orientation redraws and recompositions.
  - **Layer Type Optimization**: Switched osmdroid `MapView` from offscreen `LAYER_TYPE_HARDWARE` to `LAYER_TYPE_NONE`, eliminating intermediate framebuffer texture allocation and double-buffering overhead on `RenderThread`.
  - **Conditional Trip Flash Transition**: Guarded `flashPulseTransition` in `CurrentTripCard` to only instantiate and tick during active trip lifecycle events (`isFlashingGreen || isFlashingRed`), keeping the Compose animation clock completely idle during normal driving and parked states.

## [1.35.0] - 2026-11-16

### Added
- **Gradient Glass Cockpit Theme (Derived from Splash Screen Colors)**:
  - Added new `CockpitThemeMode.GRADIENT_GLASS` theme utilizing the exact color palette and aesthetic from the Welcome Splash screen across the entire app.
  - Multi-stop automotive vertical gradient background (`#02040A` -> `#071026` -> `#040A18` -> `#010307`) integrated seamlessly into `MainActivity`, `DashboardScreen`, `AppDrawerScreen`, `TripHistoryScreen`, `FuelHistoryScreen`, and `LauncherSettingsDialog`.
  - Frosted glassmorphism card surfaces with specular cyan top reflection (`Color(0x3300F0FF)` fading into frosted sapphire `#08142A` and midnight `#030918`) and glowing translucent electric cyan borders (`Color(0x4D00F0FF)`).
  - Luminous Splash Cyan telemetry accents (`#00F0FF`, `#00D2FF`, `#00E5FF`), soft cyan glow pills (`Color(0x2800F0FF)`), ice-white primary typography (`#F4FAFF`), and aerospace blue secondary text (`#7FAEE8`).
- **Cockpit Theme Selector in Settings -> General**:
  - Added **Gradient Glass** as an interactive theme option in Settings -> General alongside **Apex Studio**, complete with dynamic preview swatches and subtitle description.
  - Integrated animated color transitions (`animateColorAsState`) so theme switching across the dashboard and dialogs occurs fluidly without restarts or stutters.
- **Frosted Glass Cockpit Dock & Navigation Pills**:
  - Rebuilt the main launcher `CockpitDock` with a unified frosted glass container utilizing `dockBrush` (`#3500F0FF` top specular sheen $\rightarrow$ `#CC07132B` frosted sapphire $\rightarrow$ `#F2020510` midnight foundation) and glowing glass border `dockBorder`.
  - Upgraded each `DockAction` pill ("Trips", "Fuels", "Apps", "Settings") with `dockActionBrush`, glowing frosted circular icon capsules (`accentSoft` background and cyan border), and high-contrast `#F4FAFF` typography.
  - Aligned internal card telemetry docks (in `SpeedometerCluster`, `FuelTankCard`, and `CurrentTripCard`) to use `dockActionBrush` for complete glassmorphic consistency.
- **Unit Test Suite Expansion**:
  - Expanded `CockpitThemeSystemTest` to test `GRADIENT_GLASS` theme resolution, token integrity, default state verification, and safe fallbacks.
  - Expanded `CockpitThemeSystemTest` to test `GRADIENT_GLASS` theme resolution, token integrity, default state verification, and safe fallbacks.

### Changed
- **Default Cockpit Theme Updated**:
  - Set default theme to `gradient_glass` in `AppSettingsManager.SettingsState` and SharedPreferences initialization for new sessions, with backwards-compatible alias mapping.

### Added
- **Car Ignition Deep Sleep & 12V Battery Protection in DrivingTrackingService**:
  - Added automotive ignition power-cut detector for `Intent.ACTION_POWER_DISCONNECTED` that safely releases partial wakeLock (`wakeLock?.release()`) and stops GPS hardware tracking updates (`stopLocationUpdates()`), preventing vehicle 12V battery drain and permitting Android infotainment head units to enter deep sleep.
  - Preserves ongoing journey continuity by transitioning active trips to `TEMPORARY_STOP` and persisting them to Room SQLite (`persistActiveTrip`) so that multi-day and multi-stop journeys remain intact without accidental termination or discard.
  - Automatically re-acquires partial wakeLock (`PowerManager.PARTIAL_WAKE_LOCK`) and resumes GPS hardware updates (`requestLocationUpdates()`) upon engine ignition restoration (`Intent.ACTION_POWER_CONNECTED`).
  - Protected `drivingDurationMs` and `stationaryDurationMs` against false time accumulation during car engine-off / deep sleep states (whether 1 hour, overnight, or 5+ days), resuming accurate telemetry seamlessly upon drive-off.
- **Room SQLite Multi-Stop Trip Restoration**:
  - Updated `TripDao.getActiveTrip()` to query `status IN ('ACTIVE_TRIP', 'TEMPORARY_STOP')` enabling seamless trip state recovery across vehicle reboots and service recycles.
- **Comprehensive Ignition Protection Test Suite**:
  - Created `CarIgnitionDeepSleepBatteryProtectionTest` covering multi-day deep sleep immunity, 1-hour dwell timing, stationary duration freezing, and seamless route continuation.
- **On-Map Dark Mode / Light Mode Toggle Icon**: Added an interactive lighting mode button (`Icons.Default.DarkMode` / `Icons.Default.LightMode`) directly to the right-hand ergonomic map controls column in `LiveMapView`, enabling the driver to switch with one tap between daytime bright roadmap and anti-glare cockpit dark mode.
- **High-Contrast Dark Cockpit Map Filter (`DARK_MODE_FILTER`)**: Integrated midnight-blue inverted color matrix filter in `OfflineMapManager` that preserves road and highway legibility while matching cockpit aesthetic and preventing eye fatigue during night driving.
- **High-Contrast Normal Road Map Engine (`Google_Roadmap`)**: Added `OfflineMapManager.NORMAL_ROADMAP_SOURCE` delivering high-contrast vector-styled road tiles with clear highways, local streets, road names, and town labels, replacing dark satellite imagery for superior driving legibility.

### Changed
- **Optimized All-India Offline Road Map System for Strict <= 10 GB Budget**:
  - Enforced a strict 10 GB hard cache limit (`tileFileSystemCacheMaxBytes = 10000L * 1024 * 1024`) and 9 GB trim threshold in `OfflineMapManager`.
  - Partitioned `india_national` package to Zooms 0–12 (~3.66 GB, 174,399 tiles), covering complete national highways, expressways, state corridors, and city networks across entire India without internet.
  - Optimized regional packages (Central, North, West, South, East) to Zooms 0–13 (~0.85 GB to ~2.2 GB each) for fine district and street navigation.
  - Updated tile estimation model to reflect ~22 KB average roadmap tile size for accurate storage telemetry.
  - Updated `IndiaOfflineMapDialog` storage status indicator to display `STORAGE: X MB / 10 GB MAX`.
- **Switched to Normal Road Map System**:
  - Replaced satellite imagery with standard high-visibility road map tiles across `LiveMapView`, `TripPlaybackMapView`, `GeofenceMiniMapView`, and `IndiaOfflineMapManager`.
  - Updated offline map download tile pipeline to stream Google Normal Road Map tiles with fallback to OpenStreetMap Mapnik and Humanitarian HOT tiles.
  - Updated `IndiaOfflineMapDialog` branding and headers to "India HD Offline Road Map System".
- **Unlocked Full Optical Zoom Spectrum (Zoom 0 to Max 20)**:
  - Removed the temporary Zoom 13 limit; set `minZoomLevel = 0.0` and `maxZoomLevel = 20.0` across all map views.
  - Restored standard driving camera focus zoom to 17.5 for crisp street and junction navigation.
  - Enabled unrestricted pinch-to-zoom and button zooming across the entire zoom range.

## [1.34.0] - 2026-11-15

### Added
- **Full-Screen Automotive Settings Screen**: Replaced the floating 92% dialog container with a responsive, 100% full-screen layout featuring fluid slide-and-fade entrance/exit animations, hardware back handler, and a prominent 48dp circular back button.
- **Dedicated Automotive Navigation Rail**: Rebuilt the settings navigation rail (270dp width, 60dp touch target height, 18sp bold text) providing ergonomic access to essential automotive preferences.

### Changed
- **Enlarged Glanceable Typography**: Rebuilt the settings interface adopting cockpit typography standards ("bada bada dikhe"): Screen Title scaled to 24sp Black, card titles to 20sp Bold, setting labels to 17-18sp Bold, action buttons to 52-54dp height with 16-17sp Bold text, and large input fields.
- **Streamlined General Settings**: Consolidated the General tab into a high-contrast Cockpit Color Theme selector (Apex Studio) and an Automotive Runtime Environment status card.

### Removed
- **Display & Screen Behavior**: Removed display timeout and auto-minimize drawer toggles from the General settings tab; screen always remains on by default for in-vehicle driving.
- **Localization Section**: Removed redundant measurement unit options from the General settings tab; metric system (km, km/h, km/L) remains standard.
- **Appearance Tab**: Completely removed the dedicated Appearance tab.
- **Expected Baseline Mileage**: Completely removed the redundant Expected Mileage text field from the Vehicle settings tab.
- **Tracking Settings Tab**: Completely removed the Tracking tab; kinematics, auto trip detection, and 1Hz sensor fusion remain always active in the background.
- **Maps Settings Tab**: Completely removed the Maps settings tab; satellite hybrid maps, building footprints, and permanent caching remain always enabled.
- **Speed & Safety Tab**: Completely removed the Speed & Safety tab; dynamic speed limits, overspeed visual warnings, and driver safety scoring remain always active in the background.

## [1.33.0] - 2026-11-15

### Added
- **Expanded Rotating Trip Metrics (8 Driver Telemetry Cards)**: Added Average Speed (`KM/H`), Fuel Consumed (`L`), Trip Expense (`₹`), and Driving Safety Score (`PTS`) alongside Trip Distance, Max Speed, Trip Economy, and Peak Braking for a comprehensive in-cockpit telemetry suite.
- **Tap-to-Advance Metric Carousel**: Made the `CurrentTripCard` hero metric card and indicator dots tap-interactive, advancing immediately to the next metric and resetting the 30-second dwell timer.

### Changed
- **Extended Carousel Rotation Timing**: Increased the automatic rotating metrics dwell time from 7-10 seconds to 30 seconds (`30_000L`) for safer, distraction-free reading while driving.
- **Responsive Telemetry Dots**: Adjusted dot indicators to cleanly accommodate all 8 metrics with ergonomic touch targets on automotive displays.

### Fixed
- **Pre-Trip Mileage Display Bug**: Fixed `TripSummary.calculatedMileageKmpl` default from hardcoded `15.0` to `0.0`, ensuring pre-trip and stationary state displays `--` instead of an erroneous 15.0 km/L.
- **Fuel System Perpetual "Learning" State**: Fixed `FuelRepository` and `FuelTankCard` so the fuel system resolves vehicle baseline mileage labeled `EST. MILEAGE` when zero tank refuels are recorded, preventing the perpetual "Learning" display.

## [1.32.0] - 2026-11-15

### Removed
- **Voice Guidance & Smart Audio**: Completely eradicated `AutomotiveVoiceAssistant`, TextToSpeech hooks, spoken greeting / arrival / fatigue / refuel announcements, and the "Voice Guidance & Smart Audio" configuration card from settings and background tracking services.
- **Trip Summary Screen & Auto-Transitions**: Completely removed `SmartTripSummaryScreen`, navigation tab 4, auto-switching jobs on trip completion, and smart summary action triggers from `TripHistoryScreen` and `AppDrawerScreen`.
- **Current Trip Tap Interaction**: Removed tap navigation and clickable behavior on `CurrentTripCard`, ensuring it functions purely as an in-cockpit telemetry instrument card.

### Changed
- **App Drawer Header & Back Navigation**: Replaced the "Cockpit" text button on the top-left with a circular back button (`ArrowBack`) and removed the close button beside the search bar, leaving a clean search layout and intuitive back navigation.
- **Speedometer Delta Distance Readout**: Replaced the "Synced" label in the Speedometer gauge with the exact extra kilometers driven since the last odometer sync/calibration (`+X.X km`).
- **Cockpit Layout & Touch Usability**: Expanded the bottom navigation dock height to 64dp with 28dp icons, 17sp bold text, and 16dp horizontal padding for effortless driver tapping while driving.
- **Cockpit 45/55 Proportion & Telemetry Sizing**: Rebalanced the cockpit screen proportion to allocate 45% to Live Map and 55% to Driver Telemetry. Enlarged primary metric readouts across all cards (Fuel liters to 38sp, Tank percentage to 36sp, Odometer & Range to 20sp, and expanded Speedometer gauge radius).
- **Cockpit Navigation**: Added a bottom dock with Trips, Fuels, Apps, and Settings. Moved the Trips, Fuels, and Settings shortcuts out of the app drawer and removed the cockpit swipe-left shortcut for opening All Apps.

### Fixed
- **Settings SharedPreferences Type Safety**: Added safe float parsing in `AppSettingsManager` to prevent `ClassCastException` when upgrading preferences where numeric values were previously saved as integer types.

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
