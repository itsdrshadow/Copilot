# DrivingReport 🚗💨
**Next-Generation Automotive Cockpit & Intelligent Fuel Management System**  
*Optimized specifically for Android 11 (API 30) Infotainment & Automotive Head Units*

---

## 🌟 Key Highlights & Architecture

### 1. 100% Standalone & Offline Edge System
- **Zero Internet Requirement**: Designed to operate without mobile data or internet connectivity.
- **Hardware GNSS Satellite Tracking**: Direct integration with vehicle/head unit GPS hardware chip; tracks real-time speed, distance, odometer, and trip metrics even in remote areas with zero cell reception.
- **Local Embedded Room Database**: All journeys, fuel events, odometer calibrations, and stations are securely persisted locally on-device in SQLite.

---

### 2. Android 11 (API 30) Automotive Optimization
- **Target SDK**: Configured with `minSdk = 26`, `targetSdk = 30`, and `compileSdk = 34`.
- **Ignition & QuickBoot Auto-Launch**: Broadcast receivers for `BOOT_COMPLETED`, `LOCKED_BOOT_COMPLETED`, and `QUICKBOOT_POWERON` automatically start telemetry when the car ignition is turned on.
- **Landscape Cockpit UX**: High-contrast dark cockpit aesthetic designed for automotive touchscreens with tactile buttons and high-visibility typography.
- **Continuous Foreground Service**: `DrivingTrackingService` runs as a high-priority automotive foreground service with `foregroundServiceType="location"`, immune to OS memory killers.
- **App Drawer Capabilities**: Pre-configured with `QUERY_ALL_PACKAGES` for seamless Android 11 launcher app discovery.

---

### 3. Fuel AI Self-Learning Engine (`FuelAIEngine.kt`)
- **Multi-Source Fusion**: Merges physics fuel decay, speed efficiency curves, idle fuel rates, and instrument cluster (MID) bar levels.
- **10-Pt MID Gauge Hysteresis**: Accurately models non-linear tank float physics where bars do not transition at uniform liters.
- **Self-Learning Calibration**:
  - Outlier rejection via **Interquartile Range (IQR)** to filter anomalous spikes.
  - Exponentially Weighted Moving Average (**EWMA**) normalized to avoid mathematical divergence (`MAX_RECENCY_EXPONENT = 6.0`).
  - Iterative regression that improves accuracy with each logged tank refill.
- **Dynamic Idle Consumption**: Continuous decay modeling based on vehicle standstill durations.

---

### 4. Automatic Petrol Pump Geofencing (`StationPriceManager.kt`)
- **Haversine Geofencing (~280m)**: When pulling up to a previously visited fuel station, the app detects the GPS coordinates offline and automatically populates the last known fuel rate (₹/L).
- **Background Auto-Update**: Changing the price during a refuel automatically updates the local geocache (`station_fuel_prices.json` + Room DB) in the background with zero user friction.

---

### 5. Cockpit UI Components (`feature/fuel` & `feature/trips`)
- **`FuelEntrySheet.kt`**:
  - Full-height expansive tactile numeric keypad.
  - 10-Point MID rounded radial speedometer gauge with auto-scaling `BoxWithConstraints`.
  - Top-right telemetry badges (Derived Liters, Predicted Tank, Estimated Driving Range).
  - High-vis tactile Cancel and Confirm Refuel action buttons.
- **`FuelHistorySheet.kt`**:
  - Chronological refuel logs with mini 10-point MID segment visualizers matching dashboard bars.
  - Monospace telemetry metrics for Liters, Total Bill (₹), Rate (@ ₹/L), Odometer (KM), and Calculated Real Mileage (`⚡ km/L`).
  - 4-tile summary cards (`FILL-UPS`, `TOTAL FUEL`, `TOTAL BILL`, `AVG REAL MILEAGE`).
- **`TripHistoryScreen.kt`**:
  - Interactive fast-forward animated route replay on OSMDroid vector canvas.
  - Driver Score badges, distance, average speed, max speed, and trip efficiency telemetry.

---

## 📂 Source Code Structure

```
DrivingReport/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # Permissions, foreground services, boot receivers
│   │   ├── java/com/antigravity/drivingreport/
│   │   │   ├── MainActivity.kt          # Main cockpit & launcher activity
│   │   │   ├── DrivingReportApp.kt      # Application initialization & singletons
│   │   │   ├── core/
│   │   │   │   ├── database/            # Room Database, DAOs, & Entities
│   │   │   │   ├── fuel/
│   │   │   │   │   ├── FuelAIEngine.kt         # Statistical AI self-learning fuel model
│   │   │   │   │   ├── FuelRepository.kt       # Mutex-synchronized state manager
│   │   │   │   │   └── StationPriceManager.kt  # GPS geofenced station price memory
│   │   │   │   ├── location/            # GPS engine, tracking service, boot receiver
│   │   │   │   └── tripengine/          # Trip classification & detection logic
│   │   │   └── feature/
│   │   │       ├── dashboard/           # Main automotive cockpit dashboard screen
│   │   │       ├── fuel/                # Add Fuel Entry & Fuel History screens
│   │   │       ├── maps/                # Live GPS navigation & map canvas
│   │   │       └── trips/               # Trip History & Animated Replay screen
│   │   └── res/                         # Vector drawables, strings, layouts
│   └── build.gradle.kts                 # Android app build configuration
├── build.gradle.kts                     # Root build configuration
├── settings.gradle.kts                  # Gradle project settings
└── gradle/                              # Gradle wrapper
```

---

## 🛠️ How to Build & Install

### 1. Build Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### 2. Build Release APK (Production-Signed)
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

### 3. Install on Android 11 Head Unit via ADB
```bash
# Connect device via USB or Wi-Fi ADB
adb install -r app/build/outputs/apk/release/app-release.apk

# Launch MainActivity
adb shell am start -n com.antigravity.drivingreport/.MainActivity
```

---

## 🔄 Future AI Development Continuity
When sharing this GitHub repository link in future sessions:
- The entire fuel learning model (`FuelAIEngine.kt`), database architecture, and UI structure will be immediately recognized and ready for continuous extension.
