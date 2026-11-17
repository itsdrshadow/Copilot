package com.antigravity.copilot.core.logic

/**
 * Single source of truth for every tunable threshold used by the pure trip / fuel / map logic
 * in `core.logic`. Values are the spec defaults; change them here only.
 */
object Config {
    // ---------------- Geofence trips (Phase 1) ----------------
    const val GEOFENCE_DEFAULT_RADIUS_M = 75.0
    const val GEOFENCE_MIN_RADIUS_M = 30.0
    const val GEOFENCE_MAX_RADIUS_M = 2000.0
    /** Exit is confirmed only beyond radius + this hysteresis; entry at radius. */
    const val GEOFENCE_EXIT_HYSTERESIS_M = 15.0
    /** Fixes with accuracy >= this are ignored for fence decisions. */
    const val GEOFENCE_MAX_ACCURACY_M = 30.0
    /** Consecutive qualifying fixes needed to confirm an exit or an entry. */
    const val GEOFENCE_DEBOUNCE_FIXES = 3

    // ---------------- Idle / moving (Phase 1) ----------------
    const val IDLE_SPEED_KMH = 2.0
    const val IDLE_MIN_DURATION_MS = 30_000L
    /** Gaps longer than this between fixes are not attributed to moving or idle time beyond the cap. */
    const val MAX_TIME_STEP_MS = 10_000L

    // ---------------- Speed (Phase 2) ----------------
    const val SPEED_MAX_ACCURACY_M = 25.0
    /** EMA weight of the newest Doppler sample (1.0 = no smoothing). */
    const val SPEED_SMOOTHING_ALPHA = 0.6
    /** Below this the displayed speed snaps to 0. */
    const val SPEED_ZERO_SNAP_KMH = 1.0
    const val SPEED_MAX_PLAUSIBLE_KMH = 250.0
    /** If no usable fix arrives for this long the displayed speed decays to 0. */
    const val SPEED_STALE_MS = 4_000L

    // ---------------- Driving state (Phase 2) ----------------
    const val ACCEL_THRESHOLD_MPS2 = 1.2
    const val BRAKE_THRESHOLD_MPS2 = -1.5
    const val CRUISE_MAX_ABS_ACCEL_MPS2 = 0.3
    const val CRUISE_MIN_SPEED_KMH = 20.0
    const val CRUISE_HOLD_MS = 5_000L
    /** Weight of the sensor in the sensor/GPS acceleration fusion (0..1). */
    const val ACCEL_SENSOR_WEIGHT = 0.7
    /** Low-pass weight of the newest longitudinal acceleration sample. */
    const val ACCEL_SMOOTHING_ALPHA = 0.35
    /** A brake/accel "event" is counted once per excursion and needs this long to re-arm. */
    const val EVENT_REARM_MS = 2_000L

    // ---------------- Odometer (Phase 2) ----------------
    const val ODO_MAX_ACCURACY_M = 25.0
    /** Segments shorter than max(this, accuracy-based jitter) while slow are treated as jitter. */
    const val ODO_MIN_SEGMENT_M = 3.0
    const val ODO_STATIONARY_SPEED_KMH = 2.0
    const val ODO_MAX_IMPLIED_SPEED_KMH = 220.0
    /** EMA weight of a new k observation. */
    const val ODO_K_SMOOTHING = 0.5
    const val ODO_K_MIN = 0.85
    const val ODO_K_MAX = 1.15
    /** Ignore k observations from intervals shorter than this (app km). */
    const val ODO_K_MIN_INTERVAL_KM = 20.0

    // ---------------- Fuel model (Phase 3) ----------------
    const val GAUGE_MAX_POINT = 10.0
    const val GAUGE_STEP = 0.5
    const val DEFAULT_TOP_BUFFER_L = 3.0
    const val DEFAULT_BOTTOM_BUFFER_L = 5.0
    /** Weight of the linear prior per knot, in "observations". */
    const val GAUGE_PRIOR_WEIGHT = 1.0
    const val ACTUAL_CAPACITY_MIN_FACTOR = 0.85
    const val ACTUAL_CAPACITY_MAX_FACTOR = 1.25
    const val ACTUAL_CAPACITY_SMOOTHING = 0.4
    const val DEFAULT_MILEAGE_KMPL = 15.0
    const val MILEAGE_MIN_KMPL = 4.0
    const val MILEAGE_MAX_KMPL = 45.0
    const val MILEAGE_WINDOW_SEGMENTS = 3
    const val MILEAGE_WINDOW_DAYS = 30
    const val MILEAGE_MIN_SEGMENT_KM = 20.0
    const val MILEAGE_MIN_SEGMENT_L = 1.5
    /** Gauge-to-gauge segments are less reliable than full-to-full. */
    const val MILEAGE_WEIGHT_FULL_TO_FULL = 1.0
    const val MILEAGE_WEIGHT_GAUGE_TO_GAUGE = 0.5
    /** Penalty applied to confidence when an impossible reconciliation is seen. */
    const val MILEAGE_CONFIDENCE_PENALTY = 0.25

    // ---------------- Unlogged fuel (Phase 4) ----------------
    /** Remainders smaller than this are rounding noise. */
    const val UNLOGGED_EPSILON_L = 0.2
    /** On an impossible reconciliation, mileage is lowered by at most this fraction. */
    const val UNLOGGED_MAX_MILEAGE_CORRECTION = 0.15

    // ---------------- Trip estimate (Phase 5) ----------------
    /** Extra fuel factor per m/s² of positive acceleration (load model). */
    const val TRIP_ACCEL_LOAD_PER_MPS2 = 0.25
    /** Optimal steady speed for the load curve. */
    const val TRIP_OPTIMAL_SPEED_KMH = 55.0
    const val TRIP_SPEED_LOAD_LOW = 0.35
    const val TRIP_SPEED_LOAD_HIGH = 0.45

    // ---------------- Map trail (Phase 6) ----------------
    const val OVERLAP_GRID_M = 15.0
    const val TRAIL_SIMPLIFY_TOLERANCE_M = 4.0
    const val TRAIL_MAX_RENDER_POINTS = 4_000

    // ---------------- Per-road mileage (Phase 7) ----------------
    const val ROAD_CELL_M = 40.0
    const val ROAD_MIN_TRIP_KM = 0.5
    const val ROAD_MIN_CELL_KM = 0.01
}
