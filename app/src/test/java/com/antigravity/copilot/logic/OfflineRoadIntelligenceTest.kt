package com.antigravity.copilot.logic

import com.antigravity.copilot.core.location.SpeedLimitRules
import com.antigravity.copilot.core.map.intel.LonLat
import com.antigravity.copilot.core.map.intel.PlaceCandidate
import com.antigravity.copilot.core.map.intel.PlaceMemory
import com.antigravity.copilot.core.map.intel.RoadGeometry
import com.antigravity.copilot.core.map.intel.RoadLine
import com.antigravity.copilot.core.map.intel.StickyValue
import com.antigravity.copilot.core.map.intel.TurnDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoadIntelligenceTest {

    private val origin = LonLat(83.0, 21.9) // Chhattisgarh

    /** Polyline from [start] following (bearing, metres) legs. */
    private fun walk(start: LonLat, vararg legs: Pair<Double, Double>): List<LonLat> {
        val pts = mutableListOf(start)
        for ((bearing, dist) in legs) {
            // 10 m steps, like real tile geometry.
            var left = dist
            while (left > 0) {
                val step = minOf(10.0, left)
                pts += RoadGeometry.offset(pts.last(), bearing, step)
                left -= step
            }
        }
        return pts
    }

    // ---------------- maxspeed parsing ----------------

    @Test
    fun `maxspeed formats parse to km per h`() {
        assertEquals(60, SpeedLimitRules.parseMaxspeed("60"))
        assertEquals(60, SpeedLimitRules.parseMaxspeed("60 km/h"))
        assertEquals(60, SpeedLimitRules.parseMaxspeed(" 60kmh "))
        assertEquals(64, SpeedLimitRules.parseMaxspeed("40 mph"))
        assertEquals(40, SpeedLimitRules.parseMaxspeed("60;40"))
        assertEquals(50, SpeedLimitRules.parseMaxspeed("IN:urban"))
        assertNull(SpeedLimitRules.parseMaxspeed("none"))
        assertNull(SpeedLimitRules.parseMaxspeed("signals"))
        assertNull(SpeedLimitRules.parseMaxspeed(""))
        assertNull(SpeedLimitRules.parseMaxspeed(null))
        assertNull(SpeedLimitRules.parseMaxspeed("999"))
    }

    @Test
    fun `road class estimates follow MoRTH and refs refine them`() {
        assertEquals(120, SpeedLimitRules.estimateForRoad("motorway").limitKmh)
        assertEquals(100, SpeedLimitRules.estimateForRoad("trunk").limitKmh)
        assertEquals(100, SpeedLimitRules.estimateForRoad("primary", ref = "NH-49").limitKmh)
        assertEquals(80, SpeedLimitRules.estimateForRoad("primary").limitKmh)
        assertEquals(40, SpeedLimitRules.estimateForRoad("minor").limitKmh)
        assertEquals(60, SpeedLimitRules.estimateForRoad("motorway", isRamp = true).limitKmh)
        assertEquals(50, SpeedLimitRules.estimateForRoad(null).limitKmh)
    }

    // ---------------- overspeed hysteresis ----------------

    @Test
    fun `overspeed needs two readings above limit plus buffer and clears at the limit`() {
        val d = SpeedLimitRules.OverspeedDebouncer()
        d.update(53f, 50); assertFalse("within +3 buffer", d.isOverspeed)
        d.update(60f, 50); assertFalse("one spike is not enough", d.isOverspeed)
        d.update(48f, 50); assertFalse(d.isOverspeed)
        d.update(60f, 50); d.update(61f, 50); assertTrue(d.isOverspeed)
        d.update(52f, 50); assertTrue("hysteresis: still over between limit and limit+3", d.isOverspeed)
        d.update(50f, 50); assertFalse(d.isOverspeed)
        assertTrue("near limit at 92 %", d.isNearLimit)
    }

    @Test
    fun `a limit change never turns overspeed on by itself`() {
        val d = SpeedLimitRules.OverspeedDebouncer()
        d.update(70f, 80)
        d.onLimitChanged(70f, 40)
        d.onLimitChanged(70f, 40)
        assertFalse(d.isOverspeed)
        d.update(70f, 40); d.update(70f, 40)
        assertTrue(d.isOverspeed)
        d.onLimitChanged(70f, 80)
        assertFalse("raised limit clears it", d.isOverspeed)
    }

    // ---------------- road matching ----------------

    @Test
    fun `heading filter rejects a closer crossing road`() {
        val northSouth = RoadLine(walk(RoadGeometry.offset(origin, 270.0, 8.0), 0.0 to 100.0), roadClass = "trunk")
        val eastWest = RoadLine(walk(RoadGeometry.offset(origin, 180.0, 1.0), 90.0 to 50.0).let { listOf(RoadGeometry.offset(it.first(), 270.0, 50.0)) + it }, roadClass = "minor")
        val car = RoadGeometry.offset(origin, 0.0, 30.0)
        val m = RoadGeometry.matchRoad(car, headingDeg = 2.0, speedKmh = 60.0, lines = listOf(eastWest, northSouth))
        assertNotNull(m)
        assertEquals("trunk", m!!.line.roadClass)
        // Driving south on a two-way road still matches (axis comparison).
        assertEquals("trunk", RoadGeometry.matchRoad(car, 182.0, 60.0, listOf(eastWest, northSouth))!!.line.roadClass)
        // Only a perpendicular overpass nearby: no match rather than a wrong one.
        assertNull(RoadGeometry.matchRoad(car, 0.0, 60.0, listOf(eastWest)))
    }

    @Test
    fun `parallel service road loses to the previous road`() {
        val main = RoadLine(walk(origin, 0.0 to 200.0), roadClass = "trunk")
        val service = RoadLine(walk(RoadGeometry.offset(origin, 90.0, 9.0), 0.0 to 200.0), roadClass = "service")
        val car = RoadGeometry.offset(RoadGeometry.offset(origin, 0.0, 50.0), 90.0, 5.0) // slightly nearer the service road
        val m = RoadGeometry.matchRoad(car, 0.0, 70.0, listOf(service, main), previous = main)
        assertEquals("trunk", m!!.line.roadClass)
    }

    // ---------------- sharp turns ----------------

    @Test
    fun `right angle bend ahead is detected with direction and distance`() {
        val road = RoadLine(walk(origin, 0.0 to 300.0, 90.0 to 200.0), roadClass = "primary")
        val car = RoadGeometry.offset(origin, 0.0, 200.0) // bend is 100 m ahead
        val m = RoadGeometry.matchRoad(car, 0.0, 60.0, listOf(road))!!
        val path = RoadGeometry.forwardPath(car, 0.0, m, emptyList(), 190.0)
        val turn = RoadGeometry.detectSharpTurn(path, maxDistanceM = 150.0)
        assertNotNull(turn)
        assertEquals(TurnDirection.RIGHT, turn!!.direction)
        assertEquals(100.0, turn.distanceM, 12.0)
        assertTrue(turn.angleDeg >= 80.0)
    }

    @Test
    fun `left hairpin across two tile pieces is stitched and detected`() {
        val a = walk(origin, 0.0 to 120.0)
        val b = walk(a.last(), 0.0 to 30.0, 300.0 to 60.0, 240.0 to 60.0)
        val first = RoadLine(a, roadClass = "secondary")
        val second = RoadLine(b.asReversed(), roadClass = "secondary") // stored in reverse order
        val car = RoadGeometry.offset(origin, 0.0, 40.0)
        val m = RoadGeometry.matchRoad(car, 0.0, 50.0, listOf(first))!!
        val path = RoadGeometry.forwardPath(car, 0.0, m, listOf(first, second), 200.0)
        val turn = RoadGeometry.detectSharpTurn(path, maxDistanceM = 150.0)
        assertEquals(TurnDirection.LEFT, turn!!.direction)
        assertTrue(turn.distanceM in 80.0..150.0)
    }

    @Test
    fun `gentle highway curve is not a sharp turn`() {
        // 30° spread over 150 m.
        val road = RoadLine(walk(origin, 0.0 to 100.0, 10.0 to 50.0, 20.0 to 50.0, 30.0 to 100.0), roadClass = "trunk")
        val m = RoadGeometry.matchRoad(origin, 0.0, 90.0, listOf(road))!!
        val path = RoadGeometry.forwardPath(origin, 0.0, m, emptyList(), 190.0)
        assertNull(RoadGeometry.detectSharpTurn(path, maxDistanceM = 150.0))
    }

    @Test
    fun `junction continuation prefers straight same-class road`() {
        val a = RoadLine(walk(origin, 0.0 to 100.0), roadClass = "primary")
        val straight = RoadLine(walk(a.points.last(), 0.0 to 150.0), roadClass = "primary")
        val side = RoadLine(walk(a.points.last(), 90.0 to 150.0), roadClass = "minor")
        val car = RoadGeometry.offset(origin, 0.0, 20.0)
        val m = RoadGeometry.matchRoad(car, 0.0, 70.0, listOf(a))!!
        val path = RoadGeometry.forwardPath(car, 0.0, m, listOf(side, straight), 190.0)
        assertNull("a side street at a junction is not a sharp turn", RoadGeometry.detectSharpTurn(path, 150.0))
    }

    @Test
    fun `look-ahead scales with speed`() {
        assertEquals(80.0, RoadGeometry.lookAheadM(30.0), 0.01)
        assertEquals(150.0, RoadGeometry.lookAheadM(120.0), 0.01)
        assertTrue(RoadGeometry.lookAheadM(70.0) in 100.0..130.0)
    }

    // ---------------- labels ----------------

    @Test
    fun `refs are normalised and the pill composes`() {
        assertEquals("NH-49", RoadGeometry.formatRef("NH49"))
        assertEquals("NH-49", RoadGeometry.formatRef("nh 49"))
        assertEquals("SH-1", RoadGeometry.formatRef("SH1;NH49"))
        assertEquals("NH-130A", RoadGeometry.formatRef("NH 130A"))
        assertEquals("AH47", RoadGeometry.formatRef("AH47").let { RoadGeometry.formatRef(it) }?.replace("-", ""))
        assertNull(RoadGeometry.formatRef(" "))
        assertEquals("NH-49", RoadGeometry.roadLabel("NH49", "Raigarh Road", "trunk"))
        assertEquals("Raigarh Road", RoadGeometry.roadLabel(null, "Raigarh Road", "trunk"))
        assertEquals("Highway", RoadGeometry.roadLabel(null, null, "trunk"))
        assertEquals("NH-49 • Near Kharsia", RoadGeometry.pillText("NH-49", "Kharsia"))
        assertEquals("Near Kharsia", RoadGeometry.pillText(null, "Kharsia"))
        assertEquals("NH-49", RoadGeometry.pillText("NH-49", null))
        assertNull(RoadGeometry.pillText(null, null))
    }

    @Test
    fun `a town beats a slightly closer hamlet`() {
        val hamlet = PlaceCandidate("Tiny", "hamlet", RoadGeometry.offset(origin, 0.0, 800.0))
        val town = PlaceCandidate("Kharsia", "town", RoadGeometry.offset(origin, 90.0, 1500.0))
        assertEquals("Kharsia", RoadGeometry.choosePlace(origin, listOf(hamlet, town))!!.name)
    }

    @Test
    fun `sticky label needs two confirmations and survives brief gaps`() {
        val v = StickyValue<String>(confirm = 2, dropAfterMisses = 3)
        v.offer("NH-49"); assertEquals("NH-49", v.value)
        v.offer("Street"); assertEquals("one tile-edge blip does not switch", "NH-49", v.value)
        v.offer("NH-49"); v.offer("SH-1"); v.offer("SH-1"); assertEquals("SH-1", v.value)
        v.offer(null); v.offer(null); assertEquals("SH-1", v.value)
        v.offer(null); assertNull(v.value)
    }

    @Test
    fun `place memory remembers places across restarts and filters by distance`() {
        val f = java.io.File.createTempFile("places", ".tsv").apply { delete() }
        try {
            val near = PlaceCandidate("Kharsia", "town", RoadGeometry.offset(origin, 0.0, 2_000.0))
            val far = PlaceCandidate("Raigarh", "city", RoadGeometry.offset(origin, 90.0, 40_000.0))
            val mem = PlaceMemory(f)
            mem.load()
            assertEquals(2, mem.addAll(listOf(near, far)))
            assertEquals("duplicates are not new", 0, mem.addAll(listOf(near)))
            mem.saveIfDirty()

            val restarted = PlaceMemory(f).apply { load() }
            assertEquals(2, restarted.size())
            assertEquals(listOf("Kharsia"), restarted.near(origin, 8_000.0).map { it.name })
        } finally {
            f.delete()
        }
    }

    @Test
    fun `place memory is bounded`() {
        val mem = PlaceMemory(null, capacity = 3)
        mem.addAll((1..5).map { PlaceCandidate("P$it", "village", RoadGeometry.offset(origin, 0.0, it * 100.0)) })
        assertEquals(3, mem.size())
    }
}
