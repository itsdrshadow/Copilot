# NOTES — `improve-logic` branch (v1.43.0)

Companion docs: `AUDIT.md` (state before), `MAP_SETUP.md` (building and installing the India map),
`CHANGELOG.md` (user-facing changes).

## What changed, by phase

| Phase | Commit | Summary |
|---|---|---|
| 0 Audit | `979f34f` | `AUDIT.md`: architecture, schema, 20 gaps against the spec. |
| 1 Geofence trips | `6b8df54` | Pure `GeofenceTripDetector`: exit beyond radius + 15 m, entry at radius, accuracy < 30 m, 3 consecutive fixes, any fence to any fence. `GeofenceTripLifecycleEngine` delegates to it and keeps the pre-trip trail. `MotionTimeTracker`: idle = < 2 km/h for > 30 s; idle never ends a trip; moving + idle = wall clock. Manual start/end (long-press the CURRENT TRIP header). Battery-optimisation exemption prompt. Default radius is now 75 m. **Idle fuel burn removed.** |
| 2 Speed / state / odometer | `e7ea570` | `SpeedFilter` (Doppler only, accuracy-gated EMA, snaps to 0, decays when stale). `DrivingStateClassifier` plus `LongitudinalAccelEstimator`, which learns the phone's forward axis by correlating the linear-acceleration vector with GPS dv/dt, so it works in any mounting orientation; falls back to GPS dv/dt. The label sits under the speed. `OdometerAccumulator`: haversine segments, ignores poor accuracy, stationary jitter and teleports. Odometer correction *k* is learned at every real-odometer entry. |
| 3 Fuel model | `d180041` | Pure `FuelModel`: tank = anchor litres − km since anchor / learned km/L. `GaugeCurve` uses weighted isotonic regression (PAVA) over 21 half-point knots, with censored full/empty readings giving the top and bottom buffers. `MileageEstimator` is a weighted ratio of sums over the last 3 segments and anything else within 30 days, full-to-full weighted 1.0 and gauge-to-gauge 0.5, with a confidence score. Design and actual capacity are kept separate. "Full tank" flag in the refill dialog. Continuous bar gauge with no number shown. Half-point calibration popup. New table `fuel_model_state` (DB v9). |
| 4 Unlogged fuel | `fb6fb48` | `UnloggedFuel`: debt, PROVISIONAL rows in the fuel log, reconciliation at the next refill, allocation by km, sanity check (more than the tank can hold is rejected), quiet notice only. The blocking missed-refuel alert is removed. |
| 5 Trip estimate / history | `dc61828` | `TripFuelEstimator` (speed and acceleration load × learned km/L, idle excluded, never feeds the tank). Trips now store brake/accel counts and best mileage (DB v10). |
| 6 Trail overlap | `172e865` | The live map now actually draws the trail and a vehicle puck. `TrailOverlap` colours by ~15 m grid passes (1 blue, 2 orange, 3+ red; the opposite lane counts as the same road; jitter is not a pass) and simplifies with Douglas–Peucker. Trip playback is coloured the same way. |
| 7 Per-road mileage | `5059a31` | `RoadMileage`: trip tank-model fuel spread over ~40 m cells by driving load, aggregated over passes (`road_cells`, DB v11). Map toggle (fuel-pump button) colours cells red → green; tap shows km/L, passes and average speed. |
| 8 Offline maps | `ef86dda` | In-process PMTiles tile server (`PmTilesReader` + `MapNetworkGuard`), SAF import, URL download with resume and free-space checks, bundled sprite and glyph folder, network guard. Also fixes the MapView cold-launch bug and restores the original CRLF/LF line endings. |

All logic lives in `core/logic/**` and `core/map/PmTilesReader.kt` as pure Kotlin with JVM unit tests
(`app/src/test/.../logic/*`). **369 unit tests pass.** The spec's named test cases are:

| Spec test | Where |
|---|---|
| geofence edge jitter | `GeofenceTripDetectorTest` — edge jitter / debounce reset / poor accuracy |
| home→office, office→home | `GeofenceTripDetectorTest` |
| idle stop and resume | `MotionTimeTrackerTest`, `GeofenceTripDetectorTest` (long stop never ends trip) |
| 20 L / 400 km / forgotten 10 L refill | `UnloggedFuelTest` |
| impossible 136 L | `UnloggedFuelTest` |
| uneven gauge curve | `FuelModelTest` |
| odometer correction after real-odometer entry | `SpeedStateOdometerTest` |
| airplane-mode launch | `OfflineMapAirplaneModeTest` (JVM), plus the emulator run below |

### Emulator verification (AGENTS.md §3)
On the API 30 x86_64 emulator (GLES 2.0 only), with airplane mode ON: installed over the existing
install (Room migrations 8 → 11 ran on real data), imported a synthetic `india.pmtiles`, then
force-stopped and cold-launched. Result: the process stays alive and the crash buffer is empty.
MapLibre fetched tiles only from `tiles.copilot.invalid` (HTTP 200, served in-process), there were
**0** `BLOCKED map network request` lines, and the screenshot shows the water layer from the file
plus the vehicle puck. The SAF picker itself could not be driven by `adb input` on this image
(DocumentsUI ignored injected taps), so the file went in through the app's storage-scan import.
The import code path is the same apart from the picker.

## Assumptions / safest defaults chosen

1. **MapLibre is not upgraded.** PMTiles needs ≥ 11.8.0, but 11.x requires OpenGL ES 3.0 and crashed
   the app on the GLES 2 emulator. The previous pin comment also cites head-unit GPUs. Tiles are
   therefore served in-process to 10.3.2. Upgrading later is a one-line source change (see MAP_SETUP.md).
2. The map schema is **OpenMapTiles**, because the existing style uses it. MAP_SETUP recommends
   Planetiler. Protomaps-schema files need a different style.
3. **Spec wording "lower mileage and confidence" for impossible refills:** confidence is always
   lowered. Mileage is nudged by at most 15 % *in the direction the data implies* (via
   `mileageBias`, which heals by half with each new valid segment). If "lower" was meant literally,
   change `UnloggedFuel.nudgeMileage`.
4. **Provisional unlogged entries book only the below-zero part** of the trip that crosses zero, so
   that the provisional total always equals −raw estimate. Later trips book the full
   `trip_km / mileage`, exactly as in the spec.
5. **"Prefer the smaller correction":** when there is no debt and a surplus is explainable by a
   ≤ 15 % mileage error, it is treated as mileage error (no phantom refill). Otherwise it is logged
   as an automatic unlogged refill.
6. A refill with **neither a gauge reading nor "full tank"** cannot be reconciled. Provisional
   entries then wait for the next refill that has a gauge reading or a full tank.
7. **Driving-state events:** brake and accel counts are the 1.2 / −1.5 m/s² classifier excursions
   (2 s re-arm). The older harsh-brake (−3.2) and harsh-accel (+3.0) counters still feed the driver
   score.
8. **Idle accounting:** trip idle time at completion = wall clock − moving time, so GPS-less gaps
   (ignition off) count as idle and tunnel bridges count as moving.
9. Geofences are not a trip start when the app starts **outside all fences**. Use manual start, or
   the trip begins at the next fence exit.
10. The legacy engines (`FuelStateEstimator`, `AdaptiveMileageEngine`, `FuelAIEngine`) still run for
    the existing ledger and history screens. **The displayed tank, range, gauge and mileage come only
    from `FuelModel`.** The live kinematic engine no longer deducts from the tank.
11. The trip-fuel load model is normalised (÷1.15) so that typical mixed driving averages the
    learned whole-tank mileage.
12. The per-road map draws cells as coloured dots (CircleLayer), not road-snapped line segments.
13. `usesCleartextTraffic` stays `true` so a user-set LAN download URL (`http://pc:8000/…`) works.
    The map renderer itself cannot reach the network.
14. **Version:** one bump for the whole branch, 1.42.3 → **1.43.0** (versionCode 75), tag `v1.43.0`.
    Each phase commit adds its own CHANGELOG lines.

## Config constants (`core/logic/Config.kt`)

| Group | Constants (default) |
|---|---|
| Geofence | default radius 75 m, min 30 m, max 2000 m, exit hysteresis 15 m, max accuracy 30 m, debounce 3 fixes |
| Idle | idle < 2.0 km/h for > 30 s; max time step 10 s |
| Speed | max accuracy 25 m, EMA α 0.6, zero-snap 1 km/h, plausible ≤ 250 km/h, stale after 4 s |
| Driving state | accel > +1.2 m/s², brake < −1.5 m/s², cruise \|a\| < 0.3 m/s² and > 20 km/h for 5 s; sensor weight 0.7; accel EMA 0.35; event re-arm 2 s |
| Odometer | max accuracy 25 m, min segment 3 m, stationary < 2 km/h, max implied 220 km/h; k EMA 0.5, clamp 0.85–1.15, min interval 20 km |
| Fuel | gauge 0–10 step 0.5; default top buffer 3 L, bottom 5 L; prior weight 1; actual capacity 0.85–1.25 × design (EMA 0.4); default 15 km/L, plausible 4–45; window 3 segments / 30 days; min segment 20 km, 1.5 L; weights F2F 1.0, G2G 0.5; confidence penalty 0.25 |
| Unlogged | epsilon 0.2 L, max mileage correction 15 % |
| Trip estimate | optimal 55 km/h, low/high speed load 0.35/0.45, accel load 0.25 per m/s² |
| Trail | overlap grid 15 m, simplify 4 m, max render points 4000 |
| Per-road | cell 40 m, min trip 0.5 km, min cell 0.01 km |

## DB migrations (all additive and strict, with no swallowed exceptions)

* 8 → 9: `fuel_model_state` (single-row JSON).
* 9 → 10: `trips.brakeCount`, `trips.accelCount`, `trips.bestMileageKmpl`.
* 10 → 11: `road_cells`.

JSON backup and restore (`FullBackupManager`) now includes `fuel_model_state.json` and
`road_cells.json`. Factory reset clears both tables.

## Known gaps / follow-ups

* **On a real head unit:** confirm map rendering and the GLES version
  (`adb shell getprop ro.opengles.version`). With ≥ 196608 (GLES 3.0) you can switch to MapLibre ≥ 11.8.
* **No labels:** glyph PBFs are not bundled, and the style has no text layers (see MAP_SETUP.md).
* **Routing graph:** the GraphHopper "All-India Routing Graph" package still points at a
  `graphhopper.com` URL that is not a public download. Routing is out of scope and unchanged.
* **No instrumented tests:** Room migrations were exercised by upgrading the emulator install, not by
  `MigrationTestHelper` (`exportSchema=false`). Consider enabling schema export.
* **Untested in a real car:** the sensor-axis learner needs real driving to calibrate (about 6 m/s²
  of accumulated evidence) and falls back to GPS dv/dt until then.
* **No push:** `git push` was skipped because the repository has no remote.
* The trail and per-road layers recompute on a background thread at most every 2 s. On very long
  trips (> 16 k points) they decimate uniformly before colouring.
