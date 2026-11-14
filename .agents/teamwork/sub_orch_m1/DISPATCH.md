# DISPATCH — sub_orch_m1

## Milestone 1: Telemetry, GPS Ingestion & Trip Lifecycle Engine

Working Directory: d:\Car Launcher\.agents\teamwork\sub_orch_m1\
Scope Document: d:\Car Launcher\.agents\teamwork\sub_orch_m1\SCOPE.md
Authoritative User Request: d:\Car Launcher\.agents\teamwork\ORIGINAL_REQUEST.md
Master Project Plan: d:\Car Launcher\PROJECT.md

### Assigned Scope (Features 1–10)
1. **GPS Filtering at Ingestion Layer**: Integrate `GpsLocationFilter` in `DrivingTrackingService` to reject stationary jitter (<2.5m) and multipath spikes (>240 km/h) before passing to engine and DB.
2. **Service Listener Deduplication**: Prevent redundant location callback registrations and eliminate duplicate initial cached fixes on multiple `onStartCommand` calls in `DrivingTrackingService`.
3. **Tunnel & Signal Loss Geodesic Bridging**: Calculate geodesic bridge distance across signal outages (>15s) in `TripDetectionEngine` instead of dropping tunnel distance during warmup.
4. **Speedometer Dropout Graceful Decay**: Replace abrupt 0 km/h snap on 5-second fix gap in `SpeedometerCluster.kt` with a smooth deceleration decay curve.
5. **Trip Idle Watchdog Geofence Sync**: Prevent premature trip termination at 5-minute stops outside geofences by conditioning `isGeofenceControlled` on app settings and geofence state.
6. **Unclamped Time Delta Protection**: Clamp `dt = now - lastLocationTime` in `TripDetectionEngine.handleActiveTripMovement` to prevent clock sync jumps or long gaps from corrupting `drivingDurationMs` and average speed.
7. **Foreign Key Constraint Sequencing**: Sequence `onTripStarted` (`TripEntity` insert) to complete before `attachDeparturePoints` (`TripPointEntity` insert) to prevent `SQLiteConstraintException`.
8. **Multi-day Trip Duration Alignment**: Align duration calculation between `CurrentTripCard` and `TripHistoryScreen` to consistently represent running vs engine-off elapsed time.
9. **Trail Points Memory Allocation Optimization**: Replace 1 Hz full `ArrayList(activeTrailPoints)` allocation in `TripRepository` with memory-efficient incremental or unmodifiable updates.
10. **Mid-Trip Reboot First-Fix Distance Accumulation**: Restore last known location anchor in `TripDetectionEngine.restoreActiveTripState` to accumulate movement between reboot and first fix.

### File Write Boundaries (Exclusive Ownership)
- `app/src/main/java/com/antigravity/drivingreport/core/location/DrivingTrackingService.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/location/BootReceiver.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/trip/TripDetectionEngine.kt`
- `app/src/main/java/com/antigravity/drivingreport/core/trip/GeofenceTripLifecycleEngine.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/SpeedometerCluster.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/dashboard/CurrentTripCard.kt`
- `app/src/main/java/com/antigravity/drivingreport/feature/trips/TripHistoryScreen.kt`
- Associated tests in `app/src/test/java/com/antigravity/drivingreport/`

### Execution Procedure
Follow the Project Pattern: Assess -> Decompose or Iterate (Explorer -> Worker -> Reviewer -> Challenger -> Forensic Auditor -> Gate).
Report results back to parent orchestrator via `send_message`.
