package com.antigravity.copilot.logic

import com.antigravity.copilot.core.logic.Config
import com.antigravity.copilot.core.logic.Fence
import com.antigravity.copilot.core.logic.FencePhase
import com.antigravity.copilot.core.logic.GeoFix
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.GeofenceEvent
import com.antigravity.copilot.core.logic.GeofenceTripDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceTripDetectorTest {

    private val home = Fence(1, "Home", 21.0000, 83.0000, 75.0)
    private val office = Fence(2, "Office", 21.0500, 83.0000, 75.0)
    private val fences = listOf(home, office)

    /** Point [metres] north of [f]'s centre. */
    private fun north(f: Fence, metres: Double, t: Long, acc: Double = 5.0) =
        GeoFix(f.lat + metres / GeoMath.M_PER_DEG_LAT, f.lon, acc, t, 30.0)

    private fun parkedAtHome(): GeofenceTripDetector {
        val d = GeofenceTripDetector()
        repeat(3) { d.onFix(north(home, 0.0, it * 1000L), fences) }
        assertEquals(FencePhase.INSIDE_FENCE, d.phase)
        assertEquals(home.id, d.currentFence?.id)
        return d
    }

    @Test
    fun `edge jitter between radius and radius plus hysteresis never starts a trip`() {
        val d = parkedAtHome()
        var t = 10_000L
        // Bounce around 70..89 m — beyond the 75 m radius but inside radius + 15 m.
        repeat(50) { i ->
            val m = if (i % 2 == 0) 70.0 else 89.0
            assertNull(d.onFix(north(home, m, t), fences))
            t += 1000
        }
        assertFalse(d.inTrip)
    }

    @Test
    fun `two outside fixes then one inside resets the debounce`() {
        val d = parkedAtHome()
        assertNull(d.onFix(north(home, 100.0, 10_000), fences))
        assertNull(d.onFix(north(home, 100.0, 11_000), fences))
        assertNull(d.onFix(north(home, 60.0, 12_000), fences)) // back inside -> reset
        assertNull(d.onFix(north(home, 100.0, 13_000), fences))
        assertNull(d.onFix(north(home, 100.0, 14_000), fences))
        assertFalse(d.inTrip)
        val ev = d.onFix(north(home, 100.0, 15_000), fences)
        assertTrue(ev is GeofenceEvent.TripStarted)
        assertEquals(13_000L, ev!!.timeMs) // first fix of the confirming run
    }

    @Test
    fun `poor accuracy fixes are ignored and do not count`() {
        val d = parkedAtHome()
        repeat(10) { assertNull(d.onFix(north(home, 200.0, 10_000L + it * 1000, acc = Config.GEOFENCE_MAX_ACCURACY_M), fences)) }
        assertFalse(d.inTrip)
        // ... nor do they reset a run in progress
        d.onFix(north(home, 200.0, 30_000), fences)
        d.onFix(north(home, 200.0, 31_000, acc = 80.0), fences)
        d.onFix(north(home, 200.0, 32_000), fences)
        assertTrue(d.onFix(north(home, 200.0, 33_000), fences) is GeofenceEvent.TripStarted)
    }

    @Test
    fun `home to office starts at home exit and ends at office entry`() {
        val d = parkedAtHome()
        var t = 10_000L
        var started: GeofenceEvent.TripStarted? = null
        var ended: GeofenceEvent.TripEnded? = null
        // Drive north from home to office in 50 m steps.
        var m = 0.0
        val total = GeoMath.haversineM(home.lat, home.lon, office.lat, office.lon)
        while (m <= total) {
            when (val ev = d.onFix(north(home, m, t), fences)) {
                is GeofenceEvent.TripStarted -> started = ev
                is GeofenceEvent.TripEnded -> ended = ev
                null -> {}
            }
            m += 50.0
            t += 3000
        }
        // Park at the office centre.
        repeat(3) { (d.onFix(north(office, 0.0, t), fences) as? GeofenceEvent.TripEnded)?.let { ended = it }; t += 1000 }
        assertNotNull(started)
        assertEquals(home.id, started!!.fence?.id)
        assertNotNull(ended)
        assertEquals(office.id, ended!!.fence?.id)
        assertFalse(d.inTrip)
        assertEquals(office.id, d.currentFence?.id)
    }

    @Test
    fun `office to home works symmetrically`() {
        val d = GeofenceTripDetector()
        repeat(3) { d.onFix(north(office, 0.0, it * 1000L), fences) }
        var t = 10_000L
        val events = mutableListOf<GeofenceEvent>()
        var m = 0.0
        val total = GeoMath.haversineM(home.lat, home.lon, office.lat, office.lon)
        while (m <= total) {
            d.onFix(north(office, -m, t), fences)?.let { events += it }
            m += 50.0
            t += 3000
        }
        repeat(3) { d.onFix(north(home, 0.0, t), fences)?.let { events += it }; t += 1000 }
        assertEquals(2, events.size)
        assertEquals(office.id, (events[0] as GeofenceEvent.TripStarted).fence?.id)
        assertEquals(home.id, (events[1] as GeofenceEvent.TripEnded).fence?.id)
    }

    @Test
    fun `returning to the same fence ends the trip`() {
        val d = parkedAtHome()
        var t = 10_000L
        repeat(3) { d.onFix(north(home, 300.0, t), fences); t += 1000 }
        assertTrue(d.inTrip)
        val evs = (0 until 3).mapNotNull { d.onFix(north(home, 10.0, t + it * 1000), fences) }
        assertEquals(1, evs.size)
        assertEquals(home.id, (evs[0] as GeofenceEvent.TripEnded).fence?.id)
    }

    @Test
    fun `long stop outside fences never ends trip`() {
        val d = parkedAtHome()
        var t = 10_000L
        repeat(3) { d.onFix(north(home, 500.0, t), fences); t += 1000 }
        assertTrue(d.inTrip)
        repeat(3600) { assertNull(d.onFix(north(home, 2000.0, t).copy(speedKmh = 0.0), fences)); t += 1000 }
        assertTrue(d.inTrip)
    }

    @Test
    fun `unknown position never starts a trip, manual override does`() {
        val d = GeofenceTripDetector()
        repeat(10) { assertNull(d.onFix(north(home, 5000.0, it * 1000L), fences)) }
        assertFalse(d.inTrip)
        val start = d.manualStart(20_000)
        assertTrue(start is GeofenceEvent.TripStarted && start.manual)
        val end = d.manualEnd(30_000, north(home, 5000.0, 30_000), fences)
        assertTrue(end is GeofenceEvent.TripEnded && end.manual && end.fence == null)
        assertEquals(FencePhase.UNKNOWN, d.phase)
    }
}
