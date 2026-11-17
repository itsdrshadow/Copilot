package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.MotionState
import com.antigravity.copilot.core.logic.MotionTimeTracker
import org.junit.Assert.assertEquals
import org.junit.Test

class MotionTimeTrackerTest {

    @Test
    fun `short stop stays driving, long stop becomes idle, resume continues`() {
        val m = MotionTimeTracker()
        var t = 0L
        // 60 s driving
        repeat(61) { m.onSample(t, 40.0); t += 1000 }
        // 20 s traffic light (< 30 s) -> still DRIVING
        repeat(20) { assertEquals(MotionState.DRIVING, m.onSample(t, 0.0)); t += 1000 }
        repeat(10) { m.onSample(t, 30.0); t += 1000 }
        // 120 s parked outside a fence
        val idleStart = t
        repeat(121) { m.onSample(t, 0.5); t += 1000 }
        assertEquals(MotionState.IDLE, m.onSample(t, 0.5)); t += 1000
        // Resume driving: same tracker continues
        repeat(30) { m.onSample(t, 50.0); t += 1000 }
        assertEquals(MotionState.DRIVING, m.onSample(t, 50.0))

        val total = t - 0L
        assertEquals(total, m.movingMs + m.idleMs)
        // Idle ~= the 122 s stop (first slow sample's own dt is attributed to the stop)
        assertEquals(122_000.0, m.idleMs.toDouble(), 2_000.0)
        assertEquals(idleStart > 0, true)
    }

    @Test
    fun `final idle includes GPS-less gaps so moving + idle equals wall clock`() {
        val m = MotionTimeTracker()
        var t = 0L
        repeat(11) { m.onSample(t, 40.0); t += 1000 }
        m.markGap()
        t += 600_000 // 10 min parked with GPS off
        repeat(11) { m.onSample(t, 40.0); t += 1000 }
        val wall = t - 1000
        assertEquals(20_000L, m.movingMs)
        assertEquals(wall - 20_000L, m.finalIdleMs(wall))
    }
}
