package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.DrivingState
import com.antigravity.copilot.core.logic.DrivingStateClassifier
import com.antigravity.copilot.core.logic.GeoFix
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.LongitudinalAccelEstimator
import com.antigravity.copilot.core.logic.OdometerAccumulator
import com.antigravity.copilot.core.logic.OdometerCorrection
import com.antigravity.copilot.core.logic.SpeedFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SpeedStateOdometerTest {

    // ---------------- Speed ----------------

    @Test
    fun `speed filter uses doppler, rejects poor accuracy and never overshoots`() {
        val f = SpeedFilter()
        assertEquals(36.0, f.onFix(10.0, 5.0, 0), 1e-9)
        // A bad-accuracy spike must be ignored
        assertEquals(36.0, f.onFix(40.0, 80.0, 1000), 1e-9)
        // Step to 54 km/h is smoothed, never above the new value
        val v = f.onFix(15.0, 5.0, 2000)
        assertTrue(v in 36.0..54.0)
        repeat(10) { f.onFix(15.0, 5.0, 3000L + it * 1000) }
        assertEquals(54.0, f.speedKmh, 0.1)
    }

    @Test
    fun `speed snaps to zero when stopped and decays when fixes go stale`() {
        val f = SpeedFilter()
        f.onFix(8.0, 5.0, 0)
        repeat(8) { f.onFix(0.1, 5.0, 1000L + it * 1000) }
        assertEquals(0.0, f.speedKmh, 1e-9)
        f.onFix(8.0, 5.0, 20_000)
        assertEquals(0.0, f.tick(30_000), 1e-9)
    }

    // ---------------- Driving state ----------------

    @Test
    fun `gps-only classification idle accelerating cruising braking`() {
        val c = DrivingStateClassifier()
        var t = 0L
        assertEquals(DrivingState.IDLE, c.onSample(t, 0.0))
        // Accelerate 0 -> 54 km/h at ~2.5 m/s²
        var v = 0.0
        val seen = mutableSetOf<DrivingState>()
        while (v < 54.0) { t += 1000; v += 9.0; seen += c.onSample(t, v) }
        assertTrue(DrivingState.ACCELERATING in seen)
        // Hold 54 km/h for 10 s -> CRUISING
        repeat(12) { t += 1000; c.onSample(t, 54.0) }
        assertEquals(DrivingState.CRUISING, c.state)
        // Brake at ~3 m/s²
        while (v > 5.0) { t += 1000; v -= 10.8; c.onSample(t, v.coerceAtLeast(0.0)) }
        assertTrue(c.brakeEvents >= 1)
        assertTrue(c.accelEvents >= 1)
        t += 1000
        assertEquals(DrivingState.IDLE, c.onSample(t, 0.0))
    }

    @Test
    fun `sensor axis is learned regardless of phone orientation`() {
        // Phone mounted at an arbitrary angle: forward axis in device frame.
        val fwd = doubleArrayOf(0.48, -0.6, 0.64).let { a -> val n = sqrt(a.sumOf { it * it }); a.map { it / n } }
        val lateral = doubleArrayOf(0.8, 0.6, 0.0)
        val est = LongitudinalAccelEstimator()
        var last: Double? = null
        val profile = listOf(2.0, 2.0, 1.5, -2.5, -2.0, 1.0, 0.0, -1.0, 2.0, -2.0, 1.5, -1.5)
        repeat(4) {
            for ((i, a) in profile.withIndex()) {
                val turn = if (i % 3 == 0) 1.5 else -0.7 // lateral noise
                repeat(10) {
                    est.onSensor(fwd[0] * a + lateral[0] * turn, fwd[1] * a + lateral[1] * turn, fwd[2] * a + lateral[2] * turn)
                }
                last = est.onGps(a)
            }
        }
        assertTrue(est.isCalibrated)
        // After calibration a pure forward push reads positive, a brake reads negative.
        repeat(10) { est.onSensor(fwd[0] * 1.8, fwd[1] * 1.8, fwd[2] * 1.8) }
        val fwdRead = est.onGps(0.0)
        assertNotNull(fwdRead)
        assertTrue("forward read $fwdRead", fwdRead!! > 1.3)
        repeat(10) { est.onSensor(-fwd[0] * 2.0, -fwd[1] * 2.0, -fwd[2] * 2.0) }
        assertTrue(est.onGps(0.0)!! < -1.5)
        assertNotNull(last)
    }

    // ---------------- Odometer ----------------

    private fun fix(lat: Double, lon: Double, t: Long, acc: Double = 5.0, v: Double = 40.0) = GeoFix(lat, lon, acc, t, v)

    @Test
    fun `stationary jitter adds no distance`() {
        val o = OdometerAccumulator()
        var t = 0L
        repeat(600) { i ->
            val j = (if (i % 2 == 0) 1 else -1) * 4.0 / GeoMath.M_PER_DEG_LAT // ±4 m
            o.onFix(fix(21.0 + j, 83.0, t, acc = 10.0, v = 0.3)); t += 1000
        }
        assertEquals(0.0, o.totalM, 1e-9)
    }

    @Test
    fun `straight drive sums haversine segments and ignores poor accuracy and teleports`() {
        val o = OdometerAccumulator()
        var t = 0L
        for (i in 0..100) { // 100 x 10 m = 1000 m
            o.onFix(fix(21.0 + i * 10.0 / GeoMath.M_PER_DEG_LAT, 83.0, t)); t += 1000
            if (i == 50) {
                o.onFix(fix(21.5, 83.5, t + 10, acc = 80.0)) // poor accuracy: ignored
                o.onFix(fix(22.0, 84.0, t + 20))             // teleport: re-anchor, no distance
                o.onFix(fix(21.0 + i * 10.0 / GeoMath.M_PER_DEG_LAT, 83.0, t + 30)) // back on track
            }
        }
        assertEquals(1000.0, o.totalM, 5.0)
    }

    @Test
    fun `odometer correction after real odometer entry`() {
        var c = OdometerCorrection()
        c = c.withRealReading(50_000.0, 0.0)      // first refill: base only
        assertEquals(50_000.0, c.shown(0.0), 1e-9)
        assertEquals(1.0, c.k, 1e-9)
        // App measured 400 km, real odometer moved 412 km -> k = 1.03
        c = c.withRealReading(50_412.0, 400.0)
        assertEquals(1.03, c.k, 1e-9)
        assertEquals(50_412.0, c.shown(400.0), 1e-9)
        assertEquals(50_412.0 + 1.03 * 100.0, c.shown(500.0), 1e-9)
        // Next interval says 1.01 -> smoothed between
        c = c.withRealReading(50_412.0 + 303.0, 700.0)
        assertEquals((1.01 + 1.03) / 2, c.k, 1e-9)
        // Absurd entry is clamped, short interval is ignored for k
        val before = c.k
        c = c.withRealReading(c.baseRealKm + 5.0, 705.0)
        assertEquals(before, c.k, 1e-9)
    }
}
