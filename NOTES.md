# NOTES — `raahi-ui` branch (v2.1.0): Raahi UI theme

UI-only rework, plus the fuel/odometer/map fixes requested during the work (listed separately
below). App name, icon, applicationId, database schema, tracking, geofence and map logic are
unchanged. All 384 unit tests pass.

## Screens and toolkit
The UI is 100 % **Jetpack Compose**; there are no XML layouts (only `MainActivity` + `RecoveryActivity`).

| Screen | Files |
|---|---|
| Drive (dashboard) | `feature/dashboard/DashboardScreen.kt`, `DrivePanel.kt`, `SpeedometerCluster.kt`, `FuelTankCard.kt`, `CurrentTripCard.kt`, `SmartMusicClockCard.kt`, `feature/maps/LiveMapView.kt` |
| Maps (full-screen map) | same map panel, expanded in place (tab 1) |
| Trips list + Trip summary | `feature/trips/TripHistoryScreen.kt`, `TripDetailViews.kt`, `TripSummaryPanel.kt`, `TripPlaybackMapView.kt`, `TripCharts.kt` |
| Fuel | `feature/fuel/FuelHistoryScreen.kt`, `FuelScreenPanels.kt` |
| Log refuel dialog | `feature/fuel/FuelEntryDialog.kt`, `FuelGaugeCard.kt` |
| Refuel-station prompt | `feature/fuel/RefuelStationPromptDialog.kt` |
| Settings (General / Vehicle / Geofences / Data / About) | `feature/settings/LauncherSettingsDialog.kt`, `GeofenceSettingsContent.kt` |
| Offline maps manager | `feature/maps/OfflineMapsManagerScreen.kt` |
| App drawer | `feature/launcher/AppDrawerScreen.kt` |
| Splash | `feature/splash/*` |
| Crash recovery | `core/recovery/RecoveryActivity.kt` |

## What changed (commits)
| Commit | Step |
|---|---|
| `UI: theme` | One theme file `core/designsystem/colors/CockpitColors.kt` (Raahi Night + Day, map palette), `Theme.kt` (Auto/Night/Day), `DayNight.kt` (light sensor → clock fallback), `typography/AutomotiveTypography.kt` (Barlow Semi Condensed + Inter, bundled), `components/RaahiComponents.kt` (card, pill, segment bar, stat cells, buttons, info line, dialog host). |
| `UI: navigation` | `RaahiNavRail` (76 dp). Drive / Trips / Fuel / Maps / Apps, Settings pinned bottom. Dock removed. |
| `UI: drive` | Drive screen, map styling, odometer popup, gauge calibration popup, zoom curve, map frame cap. |
| `UI: fuel dialogs + fuel/odometer logic fixes` | Log refuel redesign and the fuel fixes below. |
| `UI: fuel` | Fuel screen layout, Auto chip, fuel-log distance fix. |
| `UI: rest of app` | `Palette` → role mapping onto Raahi tokens; remaining literals mapped; black/white button labels. |
| `UI: trip summary` | Trail/Overlap toggle + summary panel. |
| final | Small-screen fixes, backup disk-write reduction, docs, version 2.1.0. |

## Defaults chosen (no questions asked, per the brief)
- **Screenshots:** the brief mentions attached screenshots of the target UI, but none reached me. The layout follows the written spec exactly.
- **Default appearance:** the stored setting is respected. A fresh install starts on Auto. This device already had `night` stored by the old version, so it stays on Night (the spec's default) until the driver picks Auto or Day.
- **Auto day/night:** day above 400 lux, night below 40 lux, no change in between, and a change must hold for 8 s (tunnels, overpasses). With no light sensor it follows the clock: day is 06:30–18:30.
- **Day theme contrast:** the spec's Day cyan, amber and green measure 2.7–3.5:1 against white, below the 4.5:1 target. They are kept as specified for fills, bars and icons. Coloured *text* uses darker tokens (`accentText`, `warningText`, `positiveText`, `criticalText`) that pass 4.5:1. Night passes everywhere. All of this is pinned by `CockpitThemeSystemTest`.
- **Button labels:** always near-black or white, chosen by contrast (`contentColorOn`). This was your request.
- **Overlap colours:**
  - **Live Drive map:** a single pass is the cyan trail, 2 passes orange, 3+ red.
  - **Trip summary Overlap layer:** the spec's blue / orange / red, mapped to the existing pass classes (1 / 2 / 3+).
  - The pass logic (`TrailOverlap` / `PassColor`) is unchanged.
- **Mileage map layer on the trip summary:** there is no per-trip mileage data, so the toggle exists in code (`TripMapLayer.MILEAGE`) but stays hidden (`TRIP_MILEAGE_LAYER_AVAILABLE = false`). The *live* map's road-mileage overlay (menu › Mileage overlay) keeps working and has a red → amber → green legend.
- **Features with no place in the new Drive layout, kept in secondary areas:**
  - **Rotating trip metrics** (score, cost, peak braking, fuel used): tap the trip pill or the trip strip.
  - **Manual start/end trip:** long-press the trip pill.
  - **Music player:** tap the clock.
  - **Odometer calibration:** tap Odometer in the trip strip.
  - **Gauge calibration:** tap the fuel bar.
  - **3D, mileage overlay, offline maps, full screen:** the map ⋮ menu.
  - **Apps drawer:** its own rail item.
- **Calibrate gauge card on the Fuel screen:** your later instruction was that calibration happens only from the home-screen gauge. So the Fuel screen shows a read-only gauge card with "Go to Drive". The Log refuel gauge is display-only.
- **Add fuel form on the Fuel screen:** opens the same Log refuel dialog as the Drive card, so the refuel maths lives in one place.
- **Rail labels** follow the font scale up to 1.15×, never below 12 sp, so "Settings" fits the 76 dp rail at 1.3×.
- **Speed-limit sign colours** (white / red / black) are fixed by road-sign convention, not by the theme. The splash animation keeps a fixed dark brand palette (`BrandPalette`).
- Google Sans was removed. Barlow Semi Condensed 500/600 and Inter 400/500 are in `res/font`, with their OFL licences in `assets/licenses`.

## Logic fixes requested during the work
1. **First odometer calibration and negative litres:** `FuelModel.isCalibrationJump` + `rebaseOdometer`. Any real reading whose jump would push the tank below 0 L, or the first reading, re-bases the model instead of burning fuel. The "verified" flag alone was unreliable, because backup restore and legacy migration set it. Tested in `FuelModelTest`, and verified on the emulator: 45,000 → 90,000 km kept 48 L.
2. **Log refuel:** saves "gauge not observed" (`-1`). Before, an auto-computed whole-bar value overrode the litres and trained the gauge curve on its own estimate. The odometer is sent only if typed. On a full fill the projection shows capacity. Verified: ₹2000 at ₹96.50 → 20.73 L, and the tank went 8.8 → 29.5 L.
3. **Fuel log summary:** odometer recalibration steps (> 45 km per litre) are not counted as distance (`FuelHistoryDistanceTest`).
4. **Map lag** (measured on the emulator with a simulated 40 km/h drive):

   | Metric | Before | After |
   |---|---|---|
   | CPU | ~165 % | ~98 % |
   | Median frame | 57 ms | 34 ms |
   | 90th-percentile frame | 93 ms | 46 ms |
   | Slow main-thread frames | 57 % | 10 % |
   | RSS | ~240 MB | ~250 MB (steady) |

   Causes and fixes: the map now renders at max 30 fps (`setMaximumFps`), and puck GeoJSON updates are capped at 30 Hz (they ran every 60 Hz animation frame on the main thread). The remaining "slow draw issue" frames come from emulator GPU emulation.
5. **Zoom at low speed:** `autoZoomForSpeed` gives 17.5 up to 15 km/h, 17.0 at 40, 16.2 at 70, 15.2 at 100 and 14.8 at 130+. It used to be 16.0 at 30 km/h. The speed EMA τ is 4 s and the zoom dead-band is 0.08.
6. **Disk:** backups skip the write when the content is unchanged, and paths the OS refuses (`/sdcard/Copilot` on Android 11) are no longer retried every 30 s.
7. **Trail:** a throttled update is now re-run, so the trail reaches the car after you stop.

## Verification
- Emulator (API 30, 1280×800 tablet, landscape): Night and Day; small phone emulated at 640×360 dp at 1.3× font scale, with no clipped text after the rail-label and legend fixes; tablet at 1.3×. The app launches, the process stays alive, and the crash buffer is empty.
- Screenshots: `docs/raahi/` (`before_*.png` from v2.0.0, `after_*.png` from v2.1.0).
- Known, not caused by this change: the first tap after launch can be swallowed by Android's immersive-mode hint.

---

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
