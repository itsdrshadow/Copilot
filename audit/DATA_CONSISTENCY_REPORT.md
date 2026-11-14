# Data Consistency & Persistence Integrity Report (v1.18.0)

**Document:** `audit/DATA_CONSISTENCY_REPORT.md`  
**Package:** `com.antigravity.drivingreport`  
**Release Version:** v1.18.0 (Build 33)

---

## 1. Single Source of Truth Audit

| Subsystem / Data Field | Authoritative Source of Truth | Secondary Read Observers | Mutation Controls & Invariants |
|---|---|---|---|
| **Trip Start / End Lifecycle** | `GeofenceTripLifecycleEngine` | `DrivingTrackingService`, Dashboard UI | Exclusively triggered by boundary crossing with hysteresis; stops or GPS jitter never finalize a trip. |
| **Operational Vehicle State** | `VehicleStateManager` | `DrivingTrackingService`, Timeline UI | Engine Off is strictly restricted to confirmed hardware power cuts or OS shutdown; never inferred from GPS stops. |
| **Fuel Balance & History** | `FuelLedgerEntity` via `FuelRepository` | Fuel State, Cockpit Gauge, Trip Summary | All balance updates require an immutable ledger entry. Reversals create explicit offset events. |
| **Fuel Gauge Curve (0..10)** | `FuelGaugeCalibrationEngine` | Dashboard Fuel Gauge, Refuel Sheet | Strictly monotonic ($v_0 < v_1 < \dots < v_{10}$). Recomputed dynamically on confirmed refuels. |
| **Learned Fuel Economy** | `AdaptiveMileageEngine` | Dashboard Range, Trip Fuel Estimation | Dual-track architecture: preserves user baseline while learning weighted empirical economy. |
| **Cumulative Odometer** | `OdometerManager` | Odometer Card, Refuel Entries, Analytics | Stored as raw 64-bit double bits; monotonic progression guaranteed across resets. |
| **System Settings** | `AppSettingsManager` | Services, DAOs, UI Dialogs | Typed reactive StateFlow model persisted via SharedPreferences; zero data clearing on config change. |

---

## 2. Integrity Invariants Verified

1. **Non-Negative Fuel Balance:**
   - Conservative and nominal fuel levels are clamped to $[0.0, \text{Capacity}]$.
   - Consumption subtraction never drives remaining liters below 0.0.
2. **Immutable Ledger Audit Trail:**
   - Deleting or editing a refuel never deletes ledger history; it writes an explicit reversal or correction event.
3. **Multi-Day Trip Continuity:**
   - `activeTripStartTime` is persisted in preferences and Room database.
   - Upon app recovery or reboot, elapsed duration is computed directly from departure timestamp, preserving continuity across midnight.
4. **Number Precision Compliance:**
   - All displayed numbers are processed through `NumberFormatUtils` ensuring a maximum of 2 decimal places, locale-aware grouping, and trailing zero cleanup.
