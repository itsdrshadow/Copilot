This is a single self-contained fix; keep it small and focused. Fix the map trip trail so it includes the points from where the vehicle started moving inside the geofence, and ensure the active trip completes automatically when returning to the geofence.

Working directory: d:\Car Launcher
Integrity mode: development

## Requirements

### R1. Retain and Display Pre-Trip Movement Trail
When a trip starts upon geofence departure, the map trip trail must prepend the buffered GPS points from when the vehicle first began moving inside the geofence (origin point to geofence boundary), rather than starting only at the geofence exit boundary.

### R2. Geofence Arrival Trip Completion
When an active trip enters an enabled geofence boundary, the trip must reliably transition to completed in accordance with the geofence trip lifecycle specifications.

## Acceptance Criteria

### Pre-Trip Trail
- [ ] When a trip transitions from inside geofence to active on geofence departure, the trip's recorded trail points include the movement points from initial departure inside the geofence.
- [ ] The map renders the continuous route starting from the initial departure location inside the geofence through the exit boundary and onwards.

### Geofence Arrival
- [ ] Returning inside an enabled geofence during an active trip detects geofence arrival and finalizes/completes the trip.
- [ ] Trip active state is cleared and completion events/state changes are saved to persistent storage.

### Verification
- [ ] Existing and new unit tests in the repository (e.g. `./gradlew test`) pass, confirming pre-trip trail prepending and geofence arrival trip completion logic without regressions.