# Trip Lifecycle & Geofence Authority Validation (v1.18.0)

**Document:** `audit/TRIP_LIFECYCLE_VALIDATION.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)

---

## 1. Single Source of Truth for Trip Boundary: Geofence

In DrivingReport, trip start and trip end are strictly governed by geographic geofence perimeters. No secondary heuristics (such as speed dropping to 0 km/h, idle duration, or GPS loss) can prematurely start or finalize a trip.

### 1.1 Trip Start Flow
1. **Initial State:** Vehicle is `INSIDE_GEOFENCE` (e.g. Home / Parking Lot).
2. **Movement Inside Boundary:** Speed may increase, but vehicle remains within geofence radius. Trip state remains un-started.
3. **Boundary Departure:** Vehicle crosses the geofence perimeter ($d > \text{Radius} + \text{Hysteresis}$).
4. **Debounce Confirmation:** Outward trajectory confirmed with sustained distance and speed $\ge 3.0$ km/h.
5. **Execution:**
   - Generates unique persistent `tripId`.
   - Records origin geofence name in trip summary.
   - Triggers **10-second green visual flash** on dashboard timer (`TRIP STARTED`).
   - Starts elapsed timer ticking continuously from the exact departure timestamp.
   - Logs authoritative `TRIP_START` timeline event.

### 1.2 Active Journey State
- Journey continues through multiple driving and idle states:
  - `MOVING`: Vehicle speed $\ge$ configured threshold (default 2.5 km/h).
  - `STATIONARY / IDLE`: Vehicle stopped $> 30$s (traffic lights, stops).
  - `MOTION_RESUMED`: Vehicle begins moving again.
  - `ENGINE_OFF` vs `ENGINE_UNKNOWN`: Documented via verified hardware disconnect or system power events.
- **Multi-Day Support:** Active trip continues seamlessly across midnight without counter reset. Duration is always computed from departure timestamp:
  $$\text{Elapsed Duration} = \text{System Wall Time} - \text{Trip Departure Time}$$

### 1.3 Trip End Flow
1. **Entry Crossing:** Vehicle crosses boundary of any enabled geofence zone ($d \le \text{Radius}$).
2. **Deep Penetration / Dwell:** Deep penetration ($d \le \text{Radius} - 5\text{m}$) or consecutive verified fixes inside the zone confirms arrival.
3. **Execution:**
   - Finalizes trip metrics (distance, driving time, idle time, peak speed, avg speed, fuel consumed, driver score).
   - Records destination geofence name.
   - Commits trip to Room database and permanent JSON store.
   - Triggers **10-second red visual flash** on dashboard timer (`TRIP ENDED`).
   - Transitions to **persistent red state** (`TRIP COMPLETED`) which remains visible on the dashboard until the next trip departure.
