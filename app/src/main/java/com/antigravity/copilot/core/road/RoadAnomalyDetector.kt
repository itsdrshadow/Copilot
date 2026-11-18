package com.antigravity.copilot.core.road

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log

/**
 * Pothole / speed-breaker jerk sensor. Listens to TYPE_LINEAR_ACCELERATION at SENSOR_DELAY_GAME
 * on its own HandlerThread (never the UI thread) plus TYPE_GRAVITY at UI rate for orientation.
 * Without a linear-acceleration sensor (common on budget head units) it high-passes the raw
 * accelerometer instead.
 *
 * Gating (cheap, per sample): GPS speed ≥ 10 km/h, accuracy ≤ 15 m (0 = not reported), fix
 * younger than 3 s, 5 s wake-up guard after [start] / [rearm], 3 s cooldown between triggers.
 */
class RoadAnomalyDetector(
    context: Context,
    private val onHit: (JerkHit) -> Unit
) {
    private data class Fix(
        val lat: Double, val lon: Double, val speedKmh: Float, val bearing: Float,
        val accuracy: Float, val elapsedMs: Long
    )

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val core = RoadJerkCore()
    @Volatile private var fix: Fix? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listening = false

    // Raw-accelerometer fallback state (sensor thread only).
    private var rawMode = false
    private val g = FloatArray(3)
    private var gInit = false

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            when (e.sensor.type) {
                Sensor.TYPE_GRAVITY -> core.onGravity(e.values[0], e.values[1], e.values[2])
                Sensor.TYPE_LINEAR_ACCELERATION -> feed(e.values[0], e.values[1], e.values[2], now)
                Sensor.TYPE_ACCELEROMETER -> if (rawMode) {
                    if (!gInit) { e.values.copyInto(g, 0, 0, 3); gInit = true }
                    for (i in 0..2) g[i] += 0.02f * (e.values[i] - g[i])
                    core.onGravity(g[0], g[1], g[2])
                    feed(e.values[0] - g[0], e.values[1] - g[1], e.values[2] - g[2], now)
                }
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun feed(x: Float, y: Float, z: Float, now: Long) {
        val f = fix
        val gate = f != null &&
            f.speedKmh >= RoadAnomalyConfig.MIN_DETECT_SPEED_KMH &&
            f.accuracy <= RoadAnomalyConfig.MAX_ACCURACY_M &&
            now - f.elapsedMs <= RoadAnomalyConfig.MAX_FIX_AGE_MS
        val peak = core.onLinear(x, y, z, now, gate) ?: return
        val sev = RoadAnomalyMath.classify(peak) ?: return
        val fx = f ?: return
        // Dead-reckon the (≤1 s old) fix forward to where the wheel actually hit.
        val dtMs = (now - fx.elapsedMs).coerceIn(0L, RoadAnomalyConfig.MAX_EXTRAPOLATION_MS)
        val (lat, lon) = RoadAnomalyMath.offset(fx.lat, fx.lon, fx.bearing.toDouble(), fx.speedKmh / 3.6 * dtMs / 1000.0)
        onHit(JerkHit(lat, lon, peak, sev, fx.bearing, now))
    }

    /** Latest GPS fix; called from the location pipeline. */
    fun updateFix(lat: Double, lon: Double, speedKmh: Float, bearing: Float, accuracy: Float) {
        fix = Fix(lat, lon, speedKmh, bearing, accuracy, SystemClock.elapsedRealtime())
    }

    @Synchronized
    fun start() {
        val sm = sensorManager ?: return
        if (listening) { rearm(); return }
        val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val raw = if (linear == null) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        if (linear == null && raw == null) {
            Log.w(TAG, "No accelerometer: pothole detection disabled")
            return
        }
        val t = HandlerThread("RoadAnomalySensor").apply { start() }
        val h = Handler(t.looper)
        thread = t; handler = h
        rawMode = linear == null
        gInit = false
        h.post { core.arm(SystemClock.elapsedRealtime()) }
        if (linear != null) {
            sm.registerListener(listener, linear, SensorManager.SENSOR_DELAY_GAME, h)
            sm.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, h) }
        } else {
            sm.registerListener(listener, raw, SensorManager.SENSOR_DELAY_GAME, h)
        }
        listening = true
        Log.i(TAG, "Pothole detector started (${if (rawMode) "raw accel fallback" else "linear accel"})")
    }

    /** Ignition / screen wake: restart the 5 s guard so hardware init spikes are ignored. */
    fun rearm() {
        handler?.post { core.arm(SystemClock.elapsedRealtime()) }
    }

    @Synchronized
    fun stop() {
        if (!listening) return
        try { sensorManager?.unregisterListener(listener) } catch (_: Exception) {}
        thread?.quitSafely()
        thread = null; handler = null
        listening = false
    }

    private companion object { const val TAG = "RoadAnomalyDetector" }
}
