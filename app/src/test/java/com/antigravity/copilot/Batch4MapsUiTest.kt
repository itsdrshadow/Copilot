package com.antigravity.copilot

import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.location.GeoCell
import com.antigravity.copilot.core.location.RateLimit
import com.antigravity.copilot.core.location.SpeedLimitRules
import com.antigravity.copilot.core.logic.Fence
import com.antigravity.copilot.core.logic.GeoFix
import com.antigravity.copilot.core.logic.GeoMath
import com.antigravity.copilot.core.logic.GeofenceEvent
import com.antigravity.copilot.core.logic.GeofenceTripDetector
import com.antigravity.copilot.core.logic.LatLon
import com.antigravity.copilot.core.logic.TrailOverlap
import com.antigravity.copilot.core.logic.TrailRunCache
import com.antigravity.copilot.core.logic.TripTagger
import com.antigravity.copilot.core.map.DownloadIntegrity
import com.antigravity.copilot.core.map.intel.FuelPumpSuggester
import com.antigravity.copilot.core.map.intel.HazardKind
import com.antigravity.copilot.core.map.intel.LonLat
import com.antigravity.copilot.core.map.intel.RoadHazardRules
import com.antigravity.copilot.core.map.intel.TilePoi
import com.antigravity.copilot.core.map.tiles.MvtDecoder
import com.antigravity.copilot.core.map.tiles.PmtilesReader
import com.antigravity.copilot.core.map.tiles.TileMath
import com.antigravity.copilot.core.media.MediaTimeUnits
import com.antigravity.copilot.core.road.AnomalyKind
import com.antigravity.copilot.core.road.AnomalySeverity
import com.antigravity.copilot.core.road.RoadAnomalyPattern
import com.antigravity.copilot.core.trip.GpxWriter
import com.antigravity.copilot.core.trip.TripMerge
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/** Batch 4 audit fixes: geofence (C1/C2), media (Q1), potholes (K1/K2), tiles (J1), hazards (I3),
 * pumps (H6), speed limit (I2), lookups (R1), downloads (J2), tags (B8), trip edits (D6/J3), trail (D5). */
class Batch4MapsUiTest {

    // ---------------- C1 / C2 ----------------

    private val home = Fence(1, "Home", 21.0, 83.0, 75.0)
    private val shop = Fence(2, "Shop", 21.02, 83.0, 75.0)
    private val office = Fence(3, "Office", 21.05, 83.0, 75.0)

    private fun north(m: Double, t: Long, v: Double) = GeoFix(21.0 + m / GeoMath.M_PER_DEG_LAT, 83.0, 5.0, t, v)

    @Test
    fun c1_drivingThroughAFenceAt60_doesNotEndTheTrip() {
        val d = GeofenceTripDetector()
        val fences = listOf(home, shop, office)
        repeat(3) { d.onFix(north(0.0, it * 1000L, 0.0), fences) }
        var t = 10_000L
        val events = mutableListOf<GeofenceEvent>()
        var m = 0.0
        val toOffice = GeoMath.haversineM(home.lat, home.lon, office.lat, office.lon)
        while (m <= toOffice) { // 60 km/h = 16.7 m/s, 1 Hz: ~9 fixes inside the shop fence
            d.onFix(north(m, t, 60.0), fences)?.let { events += it }
            m += 16.7; t += 1000
        }
        repeat(3) { d.onFix(north(toOffice, t, 0.0), fences)?.let { events += it }; t += 1000 }
        assertEquals("one journey = one trip", 2, events.size)
        assertEquals(office.id, (events[1] as GeofenceEvent.TripEnded).fence?.id)
    }

    @Test
    fun c1_dwellInsideFenceEndsTripEvenWithoutSlowFix() {
        val d = GeofenceTripDetector()
        d.restore(inTrip = true, currentFence = null)
        var ended: GeofenceEvent? = null
        for (i in 0..25) d.onFix(GeoFix(office.lat, office.lon, 5.0, i * 1000L, 20.0), listOf(office))?.let { ended = it }
        assertTrue(ended is GeofenceEvent.TripEnded)
    }

    @Test
    fun c2_unknownPosition_sustainedDrivingStartsTrip() {
        val d = GeofenceTripDetector()
        val far = Fence(9, "Far", 22.0, 84.0, 75.0)
        var started: GeofenceEvent? = null
        for (i in 0..70) d.onFix(north(i * 10.0, i * 1000L, 36.0), listOf(far))?.let { started = it }
        assertTrue(started is GeofenceEvent.TripStarted)
        assertNull((started as GeofenceEvent.TripStarted).fence)
        assertTrue(d.inTrip)
        // Crawling around a car park never starts one.
        val d2 = GeofenceTripDetector()
        for (i in 0..200) assertNull(d2.onFix(north((i % 20) * 3.0, i * 1000L, 8.0), listOf(far)))
    }

    // ---------------- Q1 ----------------

    @Test
    fun q1_positionFollowsDurationUnit() {
        assertEquals(7_200L, MediaTimeUnits.positionToMillis(7_200, "position", durationInSeconds = false))
        assertEquals("unknown duration: Android ms", 7_200L, MediaTimeUnits.positionToMillis(7_200, "position", null))
        assertEquals(120_000L, MediaTimeUnits.positionToMillis(120, "position", durationInSeconds = true))
        assertTrue(MediaTimeUnits.isSecondsDuration(240, "duration"))
        assertFalse(MediaTimeUnits.isSecondsDuration(240_000, "duration"))
        assertFalse(MediaTimeUnits.isSecondsDuration(240, "duration_ms"))
        assertEquals(500L, MediaTimeUnits.positionToMillis(500, "position_ms", true))
    }

    // ---------------- K1 / K2 ----------------

    @Test
    fun k1_singleJoltDoesNotAlert_secondPassOrSevereDoes() {
        assertFalse(RoadAnomalyPattern.isAlertWorthy(2, AnomalySeverity.YELLOW))
        assertTrue(RoadAnomalyPattern.isAlertWorthy(3, AnomalySeverity.YELLOW))
        assertTrue(RoadAnomalyPattern.isAlertWorthy(1, AnomalySeverity.RED))
        assertFalse("rear axle of the same pass", RoadAnomalyPattern.isSeparatePass(10_000, 10_400))
        assertTrue(RoadAnomalyPattern.isSeparatePass(10_000, 200_000))
    }

    @Test
    fun k2_axlePairIsBreaker_directionAwareMerge() {
        assertEquals(AnomalyKind.SPEED_BREAKER, RoadAnomalyPattern.classifyPair(4.0f, 3.5f, 350))
        assertEquals(AnomalyKind.POTHOLE, RoadAnomalyPattern.classifyPair(6.0f, 1.0f, 350))
        assertEquals(AnomalyKind.POTHOLE, RoadAnomalyPattern.classifyPair(4.0f, 4.0f, 3000))
        assertTrue(RoadAnomalyPattern.canMerge(90f, 100f))
        assertFalse("opposite carriageway", RoadAnomalyPattern.canMerge(90f, 270f))
        assertTrue(RoadAnomalyPattern.canMerge(-1f, 270f))
    }

    // ---------------- J1: PMTiles + MVT ----------------

    private fun varint(out: ByteArrayOutputStream, v: Long) {
        var x = v
        while (true) {
            if (x and 0x7FL.inv() == 0L) { out.write(x.toInt()); return }
            out.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7
        }
    }
    private fun field(out: ByteArrayOutputStream, num: Int, bytes: ByteArray) { varint(out, (num shl 3 or 2).toLong()); varint(out, bytes.size.toLong()); out.write(bytes) }
    private fun zz(v: Int) = ((v shl 1) xor (v shr 31)).toLong()
    private fun packed(vals: List<Long>) = ByteArrayOutputStream().also { o -> vals.forEach { varint(o, it) } }.toByteArray()

    /** One "transportation" layer with a primary road line through the tile centre. */
    private fun sampleTile(): ByteArray {
        val feature = ByteArrayOutputStream().apply {
            field(this, 2, packed(listOf(0, 0, 1, 1))) // tags: class=primary, ref=NH49
            varint(this, (3 shl 3).toLong()); varint(this, 2) // LINESTRING
            field(this, 4, packed(listOf(9L or (1L shl 3), zz(2048), zz(0), 2L or (1L shl 3), zz(0), zz(4096))))
        }.toByteArray()
        fun str(s: String) = ByteArrayOutputStream().apply { field(this, 1, s.toByteArray()) }.toByteArray()
        val layer = ByteArrayOutputStream().apply {
            field(this, 1, "transportation".toByteArray())
            field(this, 2, feature)
            field(this, 3, "class".toByteArray()); field(this, 3, "ref".toByteArray())
            field(this, 4, str("primary")); field(this, 4, str("NH49"))
            varint(this, (5 shl 3).toLong()); varint(this, 4096)
        }.toByteArray()
        return ByteArrayOutputStream().apply { field(this, 3, layer) }.toByteArray()
    }

    private fun gzip(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    @Test
    fun j1_hilbertTileIds() {
        assertEquals(0L, TileMath.zxyToTileId(0, 0, 0))
        assertEquals(1L, TileMath.zxyToTileId(1, 0, 0))
        assertEquals(2L, TileMath.zxyToTileId(1, 0, 1))
        assertEquals(3L, TileMath.zxyToTileId(1, 1, 1))
        assertEquals(4L, TileMath.zxyToTileId(1, 1, 0))
        assertEquals(5L, TileMath.zxyToTileId(2, 0, 0))
    }

    @Test
    fun j1_decodesRoadFromSyntheticPmtiles() {
        val z = 14
        val x = TileMath.lonToTileX(83.4, z)
        val y = TileMath.latToTileY(21.9, z)
        val tile = gzip(sampleTile())
        val dir = ByteArrayOutputStream().apply {
            varint(this, 1); varint(this, TileMath.zxyToTileId(z, x, y)); varint(this, 1); varint(this, tile.size.toLong()); varint(this, 1)
        }.toByteArray().let(::gzip)
        val header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PMTiles".toByteArray()); put(3)
            putLong(8, 127); putLong(16, dir.size.toLong())
            putLong(24, 0); putLong(32, 0)
            putLong(40, 0); putLong(48, 0)
            putLong(56, 127L + dir.size); putLong(64, tile.size.toLong())
            put(97, 2); put(98, 2); put(99, 1); put(100, 0); put(101, 14)
        }.array()
        val f = File.createTempFile("sample", ".pmtiles").apply { deleteOnExit(); writeBytes(header + dir + tile) }
        PmtilesReader(f).use { r ->
            assertEquals(14, r.header.maxZoom)
            val raw = r.tile(z, x, y)
            assertNotNull(raw)
            val layers = MvtDecoder.decode(raw!!, z, x, y, setOf("transportation"))
            val road = layers["transportation"]!!.single()
            assertEquals("primary", road.str("class"))
            assertEquals("NH49", road.str("ref"))
            assertEquals(2, road.parts.single().size)
            assertNull(r.tile(z, x + 1, y))
        }
        assertNull(DownloadIntegrity.pmtilesSectionsProblem(header, f.length()))
        assertNotNull("truncated", DownloadIntegrity.pmtilesSectionsProblem(header, f.length() - 5))
    }

    // ---------------- I2 / I3 / H6 ----------------

    @Test
    fun i2_parallelRoadInWrongDirectionIsIgnored() {
        val northSouth = SpeedLimitRules.WayCandidate(60, listOf(21.899 to 83.4, 21.901 to 83.4), false)
        val eastWestCloser = SpeedLimitRules.WayCandidate(30, listOf(21.9 to 83.3999, 21.9 to 83.4001), false)
        // Car heading north on the N-S road: the closer E-W street must not donate its limit.
        assertEquals(60, SpeedLimitRules.bestWay(21.9, 83.40005, 0.0, listOf(northSouth, eastWestCloser))?.limitKmh)
        assertEquals("no heading: nearest", 30, SpeedLimitRules.bestWay(21.9, 83.40005, Double.NaN, listOf(northSouth, eastWestCloser))?.limitKmh)
    }

    @Test
    fun i3_schoolAheadInCone_notBehind() {
        val car = LonLat(83.4, 21.9)
        val ahead = TilePoi("DPS", "school", "school", LonLat(83.4, 21.9 + 150.0 / 111_195.0))
        val behind = TilePoi("Camera", "speed_camera", null, LonLat(83.4, 21.9 - 100.0 / 111_195.0))
        val hit = RoadHazardRules.ahead(car, 0.0, listOf(ahead, behind))
        assertEquals(HazardKind.SCHOOL_ZONE, hit?.kind)
        assertEquals(HazardKind.SPEED_CAMERA, RoadHazardRules.ahead(car, 180.0, listOf(ahead, behind))?.kind)
    }

    @Test
    fun h6_prefersRatedPumpAhead() {
        val car = LonLat(83.4, 21.9)
        val behindNear = FuelPumpSuggester.Candidate("Behind", LonLat(83.4, 21.9 - 1500.0 / 111_195.0), null)
        val aheadFar = FuelPumpSuggester.Candidate("Ahead", LonLat(83.4, 21.9 + 2500.0 / 111_195.0), "EXCELLENT")
        val s = FuelPumpSuggester.suggest(car, 0.0, listOf(behindNear, aheadFar))
        assertEquals("Ahead", s?.name)
        assertTrue(s!!.ahead)
        assertNull(FuelPumpSuggester.suggest(car, 0.0, listOf(FuelPumpSuggester.Candidate("x", LonLat(84.0, 23.0), null))))
    }

    // ---------------- R1 / J2 ----------------

    @Test
    fun r1_rateLimitAndCells() {
        assertEquals(0L, RateLimit.waitMs(null, 1000, 1100))
        assertEquals(600L, RateLimit.waitMs(1000, 1500, 1100))
        assertEquals(0L, RateLimit.waitMs(1000, 5000, 1100))
        assertEquals(GeoCell.key("place", 21.90001, 83.40001, 200.0), GeoCell.key("place", 21.90002, 83.40002, 200.0))
        assertNotEquals(GeoCell.key("place", 21.9, 83.4, 200.0), GeoCell.key("place", 21.91, 83.4, 200.0))
        assertTrue(GeoCell.isFresh(1000, 2000, 5000))
        assertFalse(GeoCell.isFresh(1000, 10_000, 5000))
    }

    @Test
    fun j2_sizeAndChecksumRules() {
        assertNull(DownloadIntegrity.sizeProblem(100, 100))
        assertNotNull(DownloadIntegrity.sizeProblem(90, 100))
        assertNull(DownloadIntegrity.sizeProblem(90, 0))
        val hash = "a".repeat(64)
        assertEquals(hash, DownloadIntegrity.parseSha256Sidecar("$hash  india.pmtiles\n"))
        assertNull(DownloadIntegrity.parseSha256Sidecar("<html>404</html>"))
    }

    // ---------------- B8 / D6 / J3 / D5 ----------------

    @Test
    fun b8_tags() {
        val h = TripTagger.SpeedHistogram()
        h.add(80.0, 600_000); h.add(20.0, 60_000)
        assertEquals(TripTagger.HIGHWAY, TripTagger.speedTag(h))
        val c = TripTagger.SpeedHistogram().apply { add(20.0, 600_000); add(45.0, 60_000) }
        assertEquals(TripTagger.CITY, TripTagger.speedTag(c))
        assertEquals("Commute · City", TripTagger.tag("City", "Home", "Office", 3))
        assertEquals("City", TripTagger.tag("City", "Home", "Office", 1))
        assertEquals("", TripTagger.speedTag(TripTagger.SpeedHistogram()))
    }

    private fun trip(id: Long, start: Long, end: Long, km: Double, from: String, to: String) =
        TripEntity(id = id, startTime = start, endTime = end, distanceMeters = km * 1000, drivingDurationMs = end - start,
            estimatedFuelLiters = km / 15, startLocationName = from, endLocationName = to, status = "COMPLETED", driverScore = 90)

    @Test
    fun d6_mergeCombinesTotals() {
        val a = trip(1, 0, 600_000, 5.0, "Home", "Shop")
        val b = trip(2, 900_000, 1_500_000, 7.0, "Shop", "Office")
        val m = TripMerge.merge(a, b)
        assertEquals(1L, m.id)
        assertEquals(12_000.0, m.distanceMeters, 1e-9)
        assertEquals("Home", m.startLocationName)
        assertEquals("Office", m.endLocationName)
        assertEquals(1_500_000L, m.endTime)
        assertEquals("gap becomes idle", 300_000L, m.stationaryDurationMs)
        assertTrue(m.isQualifying)
    }

    @Test
    fun j3_gpxContainsTrackPoints() {
        val t = trip(1, 1_700_000_000_000, 1_700_000_600_000, 5.0, "A & B", "C")
        val pts = listOf(
            TripPointEntity(tripId = 1, latitude = 21.9, longitude = 83.4, speedKmh = 36f, bearing = 0f, accuracy = 5f, timestamp = 1_700_000_000_000),
            TripPointEntity(tripId = 1, latitude = 21.901, longitude = 83.4, speedKmh = 36f, bearing = 0f, accuracy = 5f, timestamp = 1_700_000_001_000)
        )
        val gpx = GpxWriter.write(t, pts)
        assertEquals(2, Regex("<trkpt ").findAll(gpx).count())
        assertTrue(gpx.contains("A &amp; B"))
        assertTrue(gpx.contains("lat=\"21.9000000\""))
        assertTrue(GpxWriter.fileName(t).endsWith(".gpx"))
    }

    @Test
    fun d5_trailCache_onlySimplifiesTheTail_andMatchesFullRecompute() {
        val pts = (0 until 300).map { LatLon(21.9 + it * 10.0 / 111_195.0, 83.4 + (it % 7) * 1e-5) }
        val cache = TrailRunCache()
        cache.runs(pts.take(200))
        val before = cache.simplifiedRuns
        val incremental = cache.runs(pts)
        assertTrue("only the changed tail is simplified again", cache.simplifiedRuns - before <= 1)
        assertEquals(TrailOverlap.runs(pts).map { it.points }, incremental.map { it.points })
    }
}
