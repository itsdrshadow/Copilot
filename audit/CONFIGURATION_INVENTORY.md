# Configuration & Parameters Inventory

**Document:** `audit/CONFIGURATION_INVENTORY.md`  
**Application:** DrivingReport Automotive Infotainment (`com.antigravity.drivingreport`)  
**Version:** v1.18.0 (Build 33)  
**Objective:** Catalogue all operational constants, thresholds, vehicle parameters, and magic numbers across tracking, geofence, vehicle state, fuel intelligence, and UI subsystems; document their disposition, target configuration location in `AppSettingsManager`, and validation constraints.

---

## 1. Parameter Inventory Matrix

| Component / Class | Parameter Name | Current Hardcoded Value | Configurable? | Target Setting Location in `AppSettingsManager` | Default Value | Validation & Boundary Constraints | Impact on Vehicle Journey & System |
|---|---|---|---|---|---|---|---|
| **Fuel Gauge / Repository** | `designCapacityLiters` / Tank Capacity | `48.0` L | **Yes** | `AppSettingsManager.tankCapacityLiters` | `48.0` L | $10.0 \le C \le 200.0$ L | Scales 10-point fuel gauge calibration curve, range calculations, and overfill warnings. |
| **Fuel AI / Mileage Engine** | `baselineVehicleMileageKmpl` | `18.0` km/L | **Yes** | `AppSettingsManager.expectedMileageKmpl` | `18.0` km/L | $4.0 \le M \le 60.0$ km/L | Prior expected fuel economy until empirical model gains high confidence ($\ge 0.70$). |
| **Trip Geofence Manager** | `DEFAULT_RADIUS_METERS` | `200.0` m | **Yes** | `AppSettingsManager.defaultGeofenceRadiusMeters` | `200.0` m | $50.0 \le R \le 2000.0$ m | Defines default trip start/finish perimeter for home/parking zones. |
| **Vehicle State Manager** | `MOVEMENT_SPEED_THRESHOLD_KMH` | `3.0` km/h | **Yes** | `AppSettingsManager.stationarySpeedThresholdKmh` | `2.5` km/h | $1.0 \le S \le 10.0$ km/h | Threshold below which vehicle is classified as stationary/idle rather than moving. |
| **Vehicle State / Service** | Idle Detection Timeout | `30000` ms (30s) | **Yes** | `AppSettingsManager.idleDurationThresholdSeconds` | `30` s | $10 \le T \le 300$ s | Time vehicle must remain stopped before transitioning from MOVING to IDLE/STATIONARY. |
| **Driving Tracking Service** | Fuel Station Prompt Dwell | `30000` ms (30s) | **Yes** | `AppSettingsManager.stationPromptDwellSeconds` | `30` s | $15 \le T \le 180$ s | Duration vehicle must remain stationary near a fuel station before prompting Add Refuel. |
| **Driving Tracking Service** | Station Prompt Cooldown | `1800000` ms (30 min) | **Yes** | `AppSettingsManager.stationPromptCooldownMinutes` | `30` min | $5 \le T \le 120$ min | Prevents spamming refuel prompt repeatedly during same fuel station visit. |
| **Geofence Trip Engine** | `MAX_ACCURACY_THRESHOLD_METERS` | `25.0` m | Fixed System Guard | N/A (Internal Safety Guard) | `25.0` m | $10.0 \le A \le 50.0$ m | Rejects GPS drift fixes with high horizontal error from triggering false geofence crossing. |
| **Geofence Trip Engine** | `DEBOUNCE_HYSTERESIS_METERS` | `15.0` m | Fixed System Guard | N/A (Internal Safety Guard) | `15.0` m | $5.0 \le H \le 50.0$ m | Hysteresis buffer beyond boundary radius to eliminate flip-flopping at geofence edge. |
| **Fuel Gauge Calibration** | `barConfidences` prior baseline | `35` % | Adaptive Internal | N/A (Self-Tuning) | `35` % | $0 \le P \le 100$ % | Initial Bayesian prior weight for factory geometric tank profile. |
| **Missed Entry Intelligence** | Discrepancy Vol Threshold | `3.0` L | Dynamic Guard | `AppSettingsManager.missedRefillThresholdLiters` | `3.0` L | $1.0 \le V \le 15.0$ L | Volume threshold required between observed and computed fuel to infer unlogged refuel. |
| **Station Price Manager** | `MAX_MATCH_DISTANCE_METERS` | `280.0` m | Spatial Geo Guard | N/A (Spatial Index) | `280.0` m | $50.0 \le D \le 500.0$ m | Geographic radius to correlate current coordinates with known historical fuel pump price records. |

---

## 2. Configuration Architecture in `AppSettingsManager`

All configurable parameters above are centralized into `AppSettingsManager.SettingsState`:
```kotlin
data class SettingsState(
    // Vehicle Physical
    val carName: String = "My Car",
    val fuelType: String = "Petrol",
    val tankCapacityLiters: Double = 48.0,
    val expectedMileageKmpl: Double = 18.0,

    // Geofence & Trip
    val defaultGeofenceRadiusMeters: Float = 200.0f,
    val autoTripDetection: Boolean = true,
    val stationarySpeedThresholdKmh: Float = 2.5f,
    val idleDurationThresholdSeconds: Int = 30,

    // Station & Watchdog
    val stationStopWatchdog: Boolean = true,
    val stationPromptDwellSeconds: Int = 30,
    val stationPromptCooldownMinutes: Int = 30,
    val missedRefillThresholdLiters: Double = 3.0,

    // UI & System
    val screenAlwaysOn: Boolean = true,
    val autoMinimizeDrawer: Boolean = false,
    val language: String = "English (Automotive Standard)",
    val dynamicSpeedLimits: Boolean = true,
    val overspeedAlerts: Boolean = true,
    val nearLimitWarning: Boolean = true,
    val driverScoring: Boolean = true,
    val mapDarkMode: Boolean = true,
    val mapBuildingFootprints: Boolean = true
)
```

### Reactive Propagation Path:
1. User modifies parameter in Settings UI (`LauncherSettingsDialog.kt`).
2. `AppSettingsManager` updates `SharedPreferences` and emits updated `SettingsState` via `StateFlow<SettingsState>`.
3. Background services and singletons (`DrivingTrackingService`, `FuelRepository`, `TripGeofenceManager`, `VehicleStateManager`) observe `settingsState` and update their runtime thresholds dynamically.
4. **Zero Data Loss:** Modifying parameters never alters existing trip logs, fuel ledger entries, or historical odometer records.
