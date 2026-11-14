# DrivingReport Architecture & Project Memory

**Version:** 1.18.0 (Build 33)  
**Package:** `com.antigravity.drivingreport`  
**Target Environment:** Android 11+ (API 30+), Landscape Infotainment (1280x800) & Mobile Portrait  
**Architecture Pattern:** Clean Architecture + Layered Reactive State (Jetpack Compose, Room Database, StateFlow/SharedFlow, Coroutines, Android Foreground Services).

---

## 1. System Philosophy: The Vehicle Journey as ONE Interconnected System

DrivingReport is not a collection of disconnected modules. The vehicle journey forms a continuous physical lifecycle:
1. **Departure:** The vehicle exits its designated parking/home geofence. The Geofence Engine registers authoritative exit, creates a new Trip, triggers a 10-second green visual flash on the dashboard timer, and starts duration counting from the persisted exit timestamp.
2. **Active Journey:** GPS points, vehicle operational state (MOVING, STATIONARY, ENGINE_UNKNOWN, ENGINE_OFF if verified), speed, distance, and timestamps are recorded continuously.
3. **Fuel Depletion:** Distance accumulated monotonically depletes remaining fuel based on real-time adaptive fuel economy ($L = \text{Distance} / \text{Mileage}$).
4. **Refueling Opportunity:** When stationary near a fuel station or fuel pump for $\ge 30$s, prompt user to log refuel. User enters Amount Paid (₹) and Price/L (₹) which calculates Litres.
5. **Non-Linear Gauge Mapping:** Fuel gauge bar readings (0–10 points) are mapped through a calibrated monotonic non-linear curve, never assuming equal 10% steps.
6. **Arrival & Completion:** The vehicle enters its home geofence. The Geofence Engine confirms arrival, finalizes the trip, commits all summary metrics, and transitions the dashboard timer to a red completion flash (10s) followed by a persistent red indicator until the next trip begins.

---

## 2. Authoritative Data Ownership Matrix

To prevent cross-module interference and eliminate regressions, each domain has a single authoritative owner:

| Domain | Authoritative Owner | Secondary Observers | Forbidden Actions |
|---|---|---|---|
| **Trip Start & End** | `GeofenceTripLifecycleEngine` / `TripGeofenceManager` | `DrivingTrackingService`, Cockpit UI | GPS loss or idle timeout must **never** terminate an active trip. |
| **Operational State (Moving/Idle)** | `VehicleStateManager` | `DrivingTrackingService`, Timeline UI | Never infer `ENGINE_OFF` without confirmed hardware/OBD or device shutdown. Use `STATIONARY` / `ENGINE_UNKNOWN`. |
| **Trip Timeline Events** | `TripTimelineDao` / Structured Transitions | `TripHistoryScreen` | Do not rely solely on ad-hoc runtime parsing of raw GPS points for timeline. Maintain structured immutable timeline events. |
| **Fuel Ledger & Remaining Litres** | `FuelRepository` via `FuelLedgerEntity` | Dashboard, Fuel Screen, Odometer | Never allow raw balance overwrites without an immutable ledger entry. |
| **Learned Fuel Economy (km/L)** | `AdaptiveMileageEngine` | Dashboard Range, Trip Cost | Do not overwrite user baseline setting; maintain separate baseline vs learned empirical mileage. |
| **Gauge Calibration (0–10)** | `FuelGaugeCalibrationEngine` | Dashboard Fuel Gauge, Fuel Screen | Never assume linear 10% steps. Ensure strictly monotonic interpolation ($0 \le p_0 < p_1 < \dots < p_{10} \le \text{Capacity}$). |
| **Application & Vehicle Configuration** | `AppSettingsManager` | Services, DAOs, UI ViewModels | Hardcoded magic numbers in companion objects are strictly prohibited. Configuration flows through settings. |

---

## 3. State Machines

### 3.1 Trip Lifecycle Finite State Machine (FSM)

```
[INSIDE_GEOFENCE] 
       │
       ▼ (Distance > Radius + Dwell Confirmed)
[DEPARTURE_PENDING]
       │
       ▼ (Geofence Exit Event)
[TRIP_ACTIVE] ◄────────────────────────────────────────┐
       │                                               │
       │ (Distance <= Radius)                          │ (Exits Radius again)
       ▼                                               │
[ARRIVAL_PENDING] ─────────────────────────────────────┘
       │
       ▼ (Dwell time inside Geofence confirmed)
[TRIP_COMPLETED] (Triggers Red Flash -> Persistent Red until Departure)
```

- **Invariants:**
  - `tripId` is generated once upon departure confirmation (`UUID.randomUUID().toString()`).
  - Active trip survives app kills, foreground service restarts, and OS reboots by checking `TripRepository.getActiveTrip()`.
  - Multi-day trips continue across midnight; duration is calculated as `System.currentTimeMillis() - trip.startTimeMillis`.
  - Timer visual cues: 10s Green Flash on trip start; 10s Red Flash on trip completion; Persistent Red when trip completed and waiting inside geofence.

### 3.2 Vehicle Operational State FSM

```
              ┌───────────────┐
              │ ENGINE_OFF /  │
              │  BOOT_INIT    │
              └───────┬───────┘
                      │ GPS Speed > stationaryThreshold (default 2.5 km/h)
                      ▼
               ┌─────────────┐
        ┌─────►│   MOVING    ├─────┐
        │      └──────┬──────┘     │
        │             │            │ GPS Speed < stationaryThreshold
        │             │            ▼ for idleThreshold (default 30s)
Speed > │      ┌──────▼──────┐     │
2.5km/h │      │ STATIONARY/ ├─────┤
        │      │    IDLE     │     │ Engine Stop Signal / Device Shutdown
        │      └──────┬──────┘     ▼
        │             │      ┌─────────────┐
        │             └─────►│ ENGINE_OFF  │ (Verified HW only)
        └────────────────────┤   (or UNK)  │
                             └─────────────┘
```

- **Timeline Event Structure:**
  - `TRIP_START`: Geofence exit confirmed.
  - `MOTION_STARTED`: Vehicle exceeds stationary speed threshold.
  - `STATIONARY`: Vehicle stopped for $>30$s (records coordinates, timestamp, address if geocoded).
  - `MOTION_RESUMED`: Vehicle moves again after stationary period.
  - `ENGINE_OFF` / `ENGINE_UNKNOWN`: Documented engine state.
  - `REFUEL_RECORDED`: Added fuel while stationary.
  - `GEOFENCE_RETURN`: Inside geofence boundary.
  - `TRIP_COMPLETED`: Trip concluded and committed.

### 3.3 Fuel Intelligence & Non-Linear Calibration

- **Tank Capacity:** $C$ (default 48.0 L, user configurable in Settings).
- **Consumption Rate:** $c_t = \Delta d / \text{Mileage}_{\text{effective}}$, where:
  $$\text{Mileage}_{\text{effective}} = \begin{cases} \text{Learned Mileage} & \text{if empirical confidence} \ge 0.70 \\ \text{Baseline Mileage} & \text{otherwise} \end{cases}$$
- **Non-Linear 10-Point Calibration Curve:**
  - Array of 11 volume thresholds: $P = [v_0, v_1, v_2, \dots, v_{10}]$, where $v_0 = 0.0$ and $v_{10} = C$.
  - Monotonic constraint: $v_i < v_{i+1}$ for all $0 \le i < 10$.
  - Non-linear distribution accommodates tank geometry (e.g., fuel neck delay, bottom taper).
  - Calibration engine updates empirical points on full tank refuels and reserve low fuel warnings.
- **Missed Refill Intelligence:**
  - When gauge jumps upwards without a logged refill event, calculate $\Delta V = V_{\text{observed}} - V_{\text{computed}}$.
  - If $\Delta V > \text{threshold}$ (default 3.0 L), flag as `INFERRED_REFILL`.
  - Inferred refills store plausible range $[V_{\min}, V_{\max}]$ and flag `isManualConfirmed = false`.
  - Never fabricate fake vendor names, pump IDs, or monetary payments.

---

## 4. Single Source of Truth & Zero Regressions Rules

1. **Database Schema Stability:**
   - Room migrations must be explicit or backward compatible.
   - User data (`VehicleEntity`, `TripEntity`, `FuelEventEntity`, `FuelLedgerEntity`, `VehicleStateTransitionEntity`) must never be dropped or cleared on app upgrades.
2. **Settings Reactive Propagation:**
   - Modifying Settings (Tank Capacity, Expected Mileage, Geofence Radius, Idle Timeout) updates `AppSettingsManager`, which emits via `StateFlow` so background services update active parameters immediately without restarting.
3. **No Redundant Parsers:**
   - All modules consume trip timeline and state transitions from Room DAO queries, not disjoint ad-hoc approximations.
4. **Number Formatting:**
   - Centralized `NumberFormatter.kt` is the sole authority for UI presentation formatting: $\le 2$ decimal places, trailing zero cleanup, locale-safe Indian Rupee (₹), km, L, and km/h.
