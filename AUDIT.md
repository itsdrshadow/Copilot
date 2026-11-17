# AUDIT — state of the app before the `improve-logic` branch

Date: 2026-11-17 · Base commit `bb70b87` (v1.42.3, versionCode 74) · Baseline `:app:testDebugUnitTest` = green.

## 1. Architecture

| Layer | What is there |
|---|---|
| UI | Jetpack Compose, single `MainActivity`, cockpit dashboard (`feature/dashboard`), trips, fuel, maps, settings, launcher. |
| DI | Hilt (`di/AppModule`, `RepositoryModule`). Several legacy singletons still exposed via `getInstance()` (`FuelRepository`, `TripGeofenceManager`, `AppDatabase`). |
| Tracking | `core/location/DrivingTrackingService` — foreground service, `foregroundServiceType="location"`, partial wake lock, fused provider + `LocationManager` fallback. Every fix → `GpsLocationFilter` (Kalman-ish) → `GeofenceTripLifecycleEngine` **and** `TripDetectionEngine`. |
| Trips | `core/trip/GeofenceTripLifecycleEngine` decides start/end; `TripDetectionEngine` accumulates distance, durations, speed, brake/accel counts; `TripRepository` persists trips + points. |
| Fuel | `core/fuel/FuelRepository` (1 000+ lines) orchestrates `FuelStateEstimator`, `FuelGaugeCalibrationEngine`, `AdaptiveMileageEngine`, `MissedEntryIntelligence` and the legacy `FuelAIEngine`. State in SharedPreferences + `fuel_ledger_events` + legacy `fuel_events`. |
| Odometer | `core/odometer/OdometerManager` (SharedPreferences): `baseline + (lifetimeKm − lifetimeAtBaseline)`. |
| Maps | MapLibre Android **10.3.2** (`com.mapbox.mapboxsdk.*`), `CarMapController`, `LiveMapPanel`, `OfflineMapManager` + `MapDownloadWorker` (OkHttp, WorkManager), GraphHopper routing. Style `assets/offline_style/style.json`. |
| Backup | `core/persistence/FullBackupManager` — ZIP of JSON (all Room tables + typed SharedPreferences) with manifest, restore via SAF. |

## 2. DB schema (Room, version 8, `exportSchema=false`)

`vehicles`, `trips` (start/end, distanceMeters, drivingDurationMs, stationaryDurationMs, avg/max speed, estimatedFuelLiters, calculatedMileageKmpl, driverScore, names, status, isQualifying), `trip_points` (FK→trips, lat/lon/alt/speed/bearing/accuracy/timestamp), `fuel_events` (legacy), `vehicle_state_transitions`, `fuel_ledger_events` (event-sourced fuel ledger), `trip_geofences` (name, lat/lon, radiusMeters default **200**, isEnabled).
Migrations 1→8 exist and are additive; each statement is wrapped in `try/catch` that silently swallows failures (risk: a failed migration leaves a schema Room then rejects).

## 3. How things work today

**Trips.** `GeofenceTripLifecycleEngine` is a hand-written state machine on the location stream (not `GeofencingClient` — good). Exit: outside `radius` → `DEPARTURE_PENDING`, then confirmed when `dist ≥ radius+15` **or** speed ≥ 3 km/h **or** 2 points — i.e. effectively 1–2 fixes, not 3. Entry: one fix ≥5 m inside the radius ends the trip immediately; otherwise 2 fixes or "stationary". Accuracy gate 25 m. Trip duration = start→end wall clock; driving vs stationary time accumulated in `TripDetectionEngine` (stationary after 15 s below 2.5 km/h). No manual start/end override API.

**Speed.** `TripDetectionEngine` fuses Doppler speed with position-derived speed and *takes the max* while accelerating (overshoots). `GpsLocationFilter` also smooths. No accuracy-weighted smoothing on the displayed value specifically.

**Driving state.** Only counters: harsh brake `a < −3.2`, rapid accel `a > 3.0` from GPS speed derivative. No IDLE/ACCELERATING/CRUISING/BRAKING label, no linear-acceleration sensor use.

**Odometer.** Trip distance is a haversine sum with an anti-drift gate (good, not speed×time). `OdometerManager` shows `baseline + delta` — no correction factor *k*; entering the real odometer simply re-baselines.

**Fuel.** Tank level is decremented **live, per GPS tick, by `FuelAIEngine.calculateLiveConsumption`** — a kinematic model that includes an **idle burn of 0.75 L/h** whenever speed < 1.5 km/h. The current-trip estimate therefore feeds back into the tank level. Gauge has 11 integer bars (0..10), calibration engine learns per-bar fractions with an EMA (not an isotonic fit), no top/bottom buffers, `design_capacity` and `max_capacity` exist. Mileage from `AdaptiveMileageEngine` (IQR + recency weighted over up to 50 intervals).

**Missed refuels.** `MissedEntryIntelligence` raises an *inferred refuel* alert that the user must confirm/dismiss (blocking UX). No "debt" concept; estimate is clamped at 0 inside `FuelAIEngine`.

**Map / offline download.** See §5.

## 4. Bugs and gaps vs the spec

| # | Area | Finding | Fixed in |
|---|---|---|---|
| 1 | Geofence | Default radius 200 m (spec 75 m); accuracy gate 25 m (spec < 30 m); debounce is 1–2 fixes (spec 3 consecutive); entry ends trip on a single fix. | Phase 1 |
| 2 | Geofence | No manual start/end override. | Phase 1 |
| 3 | Idle | Trip stationary threshold 15 s / 2.5 km/h (spec 30 s / 2 km/h). | Phase 1 |
| 4 | Fuel | **Idle fuel burn (0.75 L/h)** in `FuelAIEngine` — forbidden by spec. | Phase 1/3 |
| 5 | Speed | Displayed speed takes `max(doppler, derived)` → overshoot; no accuracy filter for the display path. | Phase 2 |
| 6 | Driving state | No state label; no linear-accel sensor. | Phase 2 |
| 7 | Odometer | No correction factor *k*. | Phase 2 |
| 8 | Fuel | Tank level driven by live kinematic model instead of `anchor − km/mileage`; trip estimate feeds back into tank. | Phase 3/5 |
| 9 | Gauge | Integer bars, EMA fractions (not monotone-by-construction), no top/bottom buffer, no learned actual capacity separate from design. | Phase 3 |
| 10 | Mileage | No confidence-scored weighted window (last ~3 segments / 30 days). | Phase 3 |
| 11 | Unlogged fuel | No debt / provisional unlogged consumption / auto reconciliation; sanity check for impossible amounts missing. | Phase 4 |
| 12 | Trip history | Brake/accel counts and best mileage are computed live but **not persisted** in `trips`. | Phase 5 |
| 13 | Live map | `LiveMapPanel` receives `trail` but **never draws it**; overlap colouring only exists as a simulated unit test. | Phase 6 |
| 14 | Map tiles | Style uses `mbtiles://` — **MapLibre Native 10.3.2 has no MBTiles/PMTiles file source** (string absent from `libmapbox-gl.so`), so even a correctly copied file renders nothing. | Phase 8 |
| 15 | Map download | URLs `download.geofabrik.de/.../*-latest.mbtiles` **do not exist** (Geofabrik publishes `.osm.pbf` / shapefiles only) → HTTP 404. Demo pack points at `raw.githubusercontent.com`. GraphHopper URL is not a public download. | Phase 8 |
| 16 | Map | `usesCleartextTraffic="true"`; `INTERNET` used by map; no guard against network requests from MapLibre. Glyphs/sprites not bundled. | Phase 8 |
| 17 | Service | No prompt for battery-optimisation exemption (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). | Phase 1 |
| 18 | DB | `exportSchema=false`, migrations swallow exceptions. | noted (new migration is strict) |
| 19 | Config | Thresholds scattered across ~10 companion objects. | `core/logic/Config.kt` |
| 20 | Per-road mileage | Not present. | Phase 7 |

## 5. Why "download" fails (map)

1. **404s**: Geofabrik does not serve MBTiles; those URLs never existed.
2. **Renderer cannot read local files**: MapLibre Native Android 10.3.2 has no `mbtiles://`/`pmtiles://` support. PMTiles arrived in **MapLibre Native Android 11.8.0** (verified: `pmtiles://` present in 11.8.8 `libmaplibre.so`, absent in 11.7.1). 11.x renames packages `com.mapbox.mapboxsdk` → `org.maplibre.android` (3 files affected).
3. **Tile-provider terms**: bulk downloading from `tile.openstreetmap.org` is forbidden; any `OfflineManager` region for all of India would also hit the 6 000-tile default limit and many GB of raster tiles.
4. **Storage**: files land in `getExternalFilesDir()` (fine, no permission needed); legacy `MANAGE_EXTERNAL_STORAGE` / USB scan path is fragile on scoped storage.

**Decision**: upgrade to `org.maplibre.gl:android-sdk:11.8.8` (minimum version with PMTiles, still ships `armeabi-v7a`), render from a local `india.pmtiles` via `pmtiles://file://…`, bundle style/glyphs/sprites in assets, import via SAF.
