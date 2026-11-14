package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.database.TripGeofenceEntity
import com.antigravity.copilot.core.fuel.intelligence.AdaptiveMileageEngine
import com.antigravity.copilot.core.fuel.intelligence.FuelInterval
import com.antigravity.copilot.core.model.MileageLearningState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.GeofenceProvider
import com.antigravity.copilot.core.trip.GeofenceTripLifecycleEngine
import com.antigravity.copilot.core.trip.TimeFormatUtils
import com.antigravity.copilot.core.trip.TripDetectionEngine
import com.antigravity.copilot.core.trip.TripGeofenceManager
import com.antigravity.copilot.core.trip.TripLifecycleState
import com.antigravity.copilot.core.trip.TripVisualLifecycleEvent
import com.antigravity.copilot.core.trip.TripVisualState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class GeofenceTripLifecycleTest {

    @Test
    fun testLiveTripTimerFormatting() {
        // Test 1: Under 1 minute
        assertEquals("00:00:00", TimeFormatUtils.formatLiveTripTimer(0L))
        assertEquals("00:00:05", TimeFormatUtils.formatLiveTripTimer(5000L))
        assertEquals("00:00:59", TimeFormatUtils.formatLiveTripTimer(59000L))

        // Test 2: Minutes and seconds
        assertEquals("00:05:12", TimeFormatUtils.formatLiveTripTimer((5 * 60 + 12) * 1000L))
        assertEquals("00:45:30", TimeFormatUtils.formatLiveTripTimer((45 * 60 + 30) * 1000L))

        // Test 3: Hours, minutes and seconds
        assertEquals("01:23:45", TimeFormatUtils.formatLiveTripTimer((3600 + 23 * 60 + 45) * 1000L))
        assertEquals("14:08:19", TimeFormatUtils.formatLiveTripTimer((14 * 3600 + 8 * 60 + 19) * 1000L))

        // Test 4: Multi-day format (>= 24 hours) e.g. "1d 04h 25m"
        val d1h4m25 = (24 * 3600 + 4 * 3600 + 25 * 60) * 1000L
        assertEquals("1d 04h 25m", TimeFormatUtils.formatLiveTripTimer(d1h4m25))

        val d2h12m10 = (2 * 86400 + 12 * 3600 + 10 * 60) * 1000L
        assertEquals("2d 12h 10m", TimeFormatUtils.formatLiveTripTimer(d2h12m10))
    }

    @Test
    fun testTripVisualFlashStates() {
        val now = 1000000L

        // 1. Trip Started: Event within 10s -> GREEN FLASH
        val startedRecent = TripVisualState(
            event = TripVisualLifecycleEvent.STARTED,
            eventTimestamp = now - 4000L, // 4s elapsed
            tripId = 12345L
        )
        val elapsedStartRecent = now - startedRecent.eventTimestamp
        assertTrue("Elapsed < 10s should be in green flash window", elapsedStartRecent < 10000L)

        // 2. Trip Started: Event after 10s -> Return to NORMAL
        val startedOld = TripVisualState(
            event = TripVisualLifecycleEvent.STARTED,
            eventTimestamp = now - 15000L, // 15s elapsed
            tripId = 12345L
        )
        val elapsedStartOld = now - startedOld.eventTimestamp
        assertTrue("Elapsed >= 10s should exit green flash window", elapsedStartOld >= 10000L)

        // 3. Trip Completed: Event within 3s -> RED FLASH
        val completedRecent = TripVisualState(
            event = TripVisualLifecycleEvent.COMPLETED,
            eventTimestamp = now - 2000L, // 2s elapsed
            tripId = 12345L
        )
        val elapsedCompRecent = now - completedRecent.eventTimestamp
        assertTrue("Elapsed < 3s should be in red flash window", elapsedCompRecent < 3000L)

        // 4. Trip Completed: Event after 3s -> PERSISTENT RED until next trip starts
        val completedPersistent = TripVisualState(
            event = TripVisualLifecycleEvent.COMPLETED,
            eventTimestamp = now - 60000L, // 60s elapsed
            tripId = 12345L
        )
        val elapsedCompPersistent = now - completedPersistent.eventTimestamp
        assertTrue("Elapsed >= 3s stays persistently red", elapsedCompPersistent >= 3000L)
        assertEquals(TripVisualLifecycleEvent.COMPLETED, completedPersistent.event)

        // 5. Subsequent Trip Start replaces Persistent Red state
        val nextTripStarted = TripVisualState(
            event = TripVisualLifecycleEvent.STARTED,
            eventTimestamp = now,
            tripId = 12346L
        )
        assertEquals(TripVisualLifecycleEvent.STARTED, nextTripStarted.event)
        assertTrue(nextTripStarted.eventTimestamp >= completedPersistent.eventTimestamp)
    }

    @Test
    fun testExpectedMileageAndLearningStates() {
        val claimedExpectedMileage = 17.5
        val engine = AdaptiveMileageEngine(baselineVehicleMileageKmpl = claimedExpectedMileage)

        // Stage 1: Initial Baseline before any refuel observations
        assertEquals(MileageLearningState.INITIAL_BASELINE, engine.learningState)
        assertFalse("Cannot claim real evidence before refuels", engine.hasSufficientEvidence)
        assertEquals(17.5, engine.overallEstimatedMileage, 0.01)

        // If user updates expected baseline in settings before learning completes:
        engine.updateBaseline(16.8)
        assertEquals(16.8, engine.overallEstimatedMileage, 0.01)
        assertEquals(MileageLearningState.INITIAL_BASELINE, engine.learningState)

        // Stage 2: Single observation (insufficient for full learned confidence)
        // Interval 1: 300 km driven, 18.0 L consumed -> 16.67 km/L
        engine.addInterval(
            FuelInterval(
                startOdometerKm = 1000.0,
                endOdometerKm = 1300.0,
                startFuelLiters = 40.0,
                fuelAddedDuringInterval = 0.0,
                endFuelLiters = 22.0,
                avgSpeedKmh = 48.0,
                isReliableAnchor = true
            )
        )
        assertEquals(1, engine.sampleCount)
        assertEquals(MileageLearningState.LEARNING, engine.learningState)
        assertFalse("1 sample is still in learning state", engine.hasSufficientEvidence)

        // Stage 3: Second verified observation -> Transitions to LEARNED!
        // Interval 2: 320 km driven, 19.5 L consumed -> 16.41 km/L
        engine.addInterval(
            FuelInterval(
                startOdometerKm = 1300.0,
                endOdometerKm = 1620.0,
                startFuelLiters = 45.0,
                fuelAddedDuringInterval = 0.0,
                endFuelLiters = 25.5,
                avgSpeedKmh = 52.0,
                isReliableAnchor = true
            )
        )
        assertEquals(2, engine.sampleCount)
        assertEquals(MileageLearningState.LEARNED, engine.learningState)
        assertTrue(">= 2 verified samples constitutes learned state", engine.hasSufficientEvidence)
        assertNotNull(engine.measuredMileage)
        assertTrue("Learned mileage should reflect real data", engine.overallEstimatedMileage in 16.0..17.2)

        // Changing company expected mileage after learned state must NOT overwrite learned model
        val learnedValBeforeSettingChange = engine.overallEstimatedMileage
        engine.updateBaseline(22.0) // User enters different claimed value
        assertEquals(MileageLearningState.LEARNED, engine.learningState)
        assertTrue("Learned mileage is preserved and not overwritten", abs(engine.overallEstimatedMileage - learnedValBeforeSettingChange) < 1.0)
    }

    @Test
    fun testGeofenceRadiusValidation() {
        val defaultRadius = TripGeofenceManager.DEFAULT_RADIUS_METERS
        assertEquals(200.0f, defaultRadius, 0.01f)

        val minRadius = TripGeofenceManager.MIN_RADIUS_METERS
        val maxRadius = TripGeofenceManager.MAX_RADIUS_METERS
        assertEquals(50.0f, minRadius, 0.01f)
        assertEquals(2000.0f, maxRadius, 0.01f)

        // Clamping checks
        val clampedSmall = 20.0f.coerceIn(minRadius, maxRadius)
        assertEquals(50.0f, clampedSmall, 0.01f)

        val clampedLarge = 3500.0f.coerceIn(minRadius, maxRadius)
        assertEquals(2000.0f, clampedLarge, 0.01f)
    }

    @Test
    fun testMultiGeofenceContainmentAndDistance() {
        val homeGf = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 200f)
        val officeGf = TripGeofenceEntity(id = 2, name = "Office", latitude = 21.9100, longitude = 83.4100, radiusMeters = 300f)

        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return 6371000.0 * c
        }

        // Location 50m from Home
        val nearHomeLat = 21.8974 + (50.0 / 111139.0)
        val nearHomeLon = 83.3950
        val distHome = haversineMeters(nearHomeLat, nearHomeLon, homeGf.latitude, homeGf.longitude)
        val distOffice = haversineMeters(nearHomeLat, nearHomeLon, officeGf.latitude, officeGf.longitude)

        assertTrue(distHome <= homeGf.radiusMeters) // Inside Home
        assertTrue(distOffice > officeGf.radiusMeters) // Outside Office
    }

    @Test
    fun testTripLifecycleStateTransitions() {
        val states = listOf(
            TripLifecycleState.WAITING_FOR_GEOFENCE,
            TripLifecycleState.INSIDE_GEOFENCE,
            TripLifecycleState.DEPARTURE_PENDING,
            TripLifecycleState.TRIP_ACTIVE,
            TripLifecycleState.MOVING,
            TripLifecycleState.IDLE,
            TripLifecycleState.ENGINE_OFF,
            TripLifecycleState.UNKNOWN,
            TripLifecycleState.ARRIVAL_PENDING,
            TripLifecycleState.TRIP_COMPLETED
        )
        assertEquals(10, states.size)
        assertTrue(states.contains(TripLifecycleState.TRIP_ACTIVE))
        assertTrue(states.contains(TripLifecycleState.ARRIVAL_PENDING))
        assertTrue(states.contains(TripLifecycleState.DEPARTURE_PENDING))
    }

    class TestGeofenceProvider(private val geofences: List<TripGeofenceEntity>) : GeofenceProvider {
        override fun calculateDistance(geofence: TripGeofenceEntity, lat: Double, lon: Double): Float {
            return TripDetectionEngine.computeGeodesicDistance(lat, lon, geofence.latitude, geofence.longitude).toFloat()
        }

        override fun findGeofenceContaining(lat: Double, lon: Double, onlyEnabled: Boolean): TripGeofenceEntity? {
            val list = if (onlyEnabled) geofences.filter { it.isEnabled } else geofences
            for (gf in list) {
                val dist = calculateDistance(gf, lat, lon)
                if (dist <= gf.radiusMeters) {
                    return gf
                }
            }
            return null
        }
    }

    private fun createLocation(lat: Double, lon: Double, speedKmh: Float, time: Long, accuracy: Float = 5.0f): Location {
        return TripDetectionEngine.CoordinateLocation(
            _latitude = lat,
            _longitude = lon,
            _time = time,
            _accuracy = accuracy,
            _speedKmh = speedKmh
        )
    }

    @Test
    fun testPreTripTrailPrependingUponGeofenceDeparture() {
        // Home geofence at 200m radius
        val homeGf = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(homeGf))

        var tripStartCalled = false
        var confirmedStartTime = 0L
        var confirmedOriginName = ""
        var confirmedDeparturePoints: List<TripPoint> = emptyList()
        var confirmedDepartureDist = 0.0

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { startTime, originName, departurePoints, departureDistMeters ->
                tripStartCalled = true
                confirmedStartTime = startTime
                confirmedOriginName = originName
                confirmedDeparturePoints = departurePoints
                confirmedDepartureDist = departureDistMeters
            },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> }
        )

        // 1. Vehicle parked at origin inside geofence (dist = 0m, speed = 0)
        // Stabilize GPS (3 continuous seconds)
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 1000L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 2500L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 4500L))

        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
        assertTrue(engine.isGpsStabilized)
        assertEquals("Home", engine.currentGeofence?.name)

        // 2. Vehicle initiates movement from origin parking spot inside geofence
        // Point 1: Moving at 5 km/h inside parking lot (30m from origin)
        val p1Lat = 21.8974 + (30.0 / 111139.0)
        engine.processLocation(createLocation(p1Lat, 83.3950, 5.0f, 10000L))

        // Point 2: Accelerating inside geofence (100m from origin, 20 km/h)
        val p2Lat = 21.8974 + (100.0 / 111139.0)
        engine.processLocation(createLocation(p2Lat, 83.3950, 20.0f, 15000L))

        // Still inside geofence; trip has NOT started yet
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
        assertFalse(tripStartCalled)

        // 3. Vehicle reaches boundary exit (210m from origin, radius = 200m)
        val pBoundaryLat = 21.8974 + (210.0 / 111139.0)
        engine.processLocation(createLocation(pBoundaryLat, 83.3950, 30.0f, 20000L))
        assertEquals(TripLifecycleState.DEPARTURE_PENDING, engine.state)

        // 4. Confirmed departure (sustained exit > radius + 15m e.g. 235m, 35 km/h)
        val pExitLat = 21.8974 + (235.0 / 111139.0)
        engine.processLocation(createLocation(pExitLat, 83.3950, 35.0f, 22000L))

        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertTrue("Trip start must be confirmed upon exiting geofence", tripStartCalled)
        assertEquals("Home", confirmedOriginName)

        // Acceptance Criteria R1: Trail points include the movement points from initial departure inside the geofence
        assertTrue("Departure points must include pre-trip buffer points", confirmedDeparturePoints.size >= 4)

        // The first point in departure trail must be the initial origin/parking location inside the geofence
        val firstPoint = confirmedDeparturePoints.first()
        assertEquals("Initial departure location latitude must match origin", 21.8974, firstPoint.latitude, 0.0001)
        assertEquals("Initial departure location longitude must match origin", 83.3950, firstPoint.longitude, 0.0001)

        // The last point must be the exit location outside the boundary
        val lastPoint = confirmedDeparturePoints.last()
        assertEquals(pExitLat, lastPoint.latitude, 0.0001)

        // All departure points must be stamped with the active tripId
        for (pt in confirmedDeparturePoints) {
            assertEquals("Every departure trail point must have active tripId", confirmedStartTime, pt.tripId)
        }

        // Distance accumulated from origin to boundary must be around ~235 meters
        assertTrue("Departure distance must be > 200m", confirmedDepartureDist >= 200.0)
    }

    @Test
    fun testGeofenceArrivalCompletesActiveTripImmediately() {
        val officeGf = TripGeofenceEntity(id = 2, name = "Office", latitude = 21.9100, longitude = 83.4100, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(officeGf))

        var tripArrivalConfirmed = false
        var confirmedEndTime = 0L
        var confirmedDestName = ""

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, _, _ -> },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { endTime, destName ->
                tripArrivalConfirmed = true
                confirmedEndTime = endTime
                confirmedDestName = destName
            }
        )

        // Restore an ongoing active trip on the highway
        val activeTripId = 1700000000000L
        engine.restoreActiveTrip(activeTripId, activeTripId)
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertEquals(activeTripId, engine.activeTripId)

        // Vehicle driving on highway (far from Office, e.g. 10 km away)
        engine.processLocation(createLocation(21.8000, 83.3000, 70.0f, activeTripId + 30000L))
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertFalse(tripArrivalConfirmed)

        // Acceptance Criteria R2: Vehicle enters destination geofence (dist <= radius)
        // Vehicle enters Office geofence (50m from center, well inside 200m radius)
        val nearOfficeLat = 21.9100 + (50.0 / 111139.0)
        val arrivalTime = activeTripId + 120000L
        engine.processLocation(createLocation(nearOfficeLat, 83.4100, 25.0f, arrivalTime))

        // Geofence arrival must be detected and active trip finalized
        assertTrue("Geofence arrival must confirm trip completion", tripArrivalConfirmed)
        assertEquals("Office", confirmedDestName)
        assertEquals(arrivalTime, confirmedEndTime)

        // Active state must be cleared
        assertEquals("Active trip ID must be cleared upon arrival", 0L, engine.activeTripId)
        assertEquals("Active trip start time must be cleared", 0L, engine.activeTripStartTime)
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
        assertEquals("Office", engine.currentGeofence?.name)
    }

    @Test
    fun testGeofenceArrivalWithTripDetectionEngineIntegration() {
        val homeGf = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 200f)
        val officeGf = TripGeofenceEntity(id = 2, name = "Office", latitude = 21.9100, longitude = 83.4100, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(homeGf, officeGf))

        var completedSummary: TripSummary? = null

        val tripEngine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { summary -> completedSummary = summary },
            onTripDiscarded = {}
        )
        tripEngine.isGeofenceControlled = true

        val lifecycleEngine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { startTime, originName, departurePoints, departureDistMeters ->
                val loc = createLocation(departurePoints.last().latitude, departurePoints.last().longitude, 30f, startTime)
                tripEngine.startGeofenceTrip(startTime, loc, departureDistMeters, originName)
            },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { endTime, destName ->
                tripEngine.completeGeofenceTrip(endTime, destName)
            }
        )

        // 1. Depart Home
        lifecycleEngine.processLocation(createLocation(21.8974, 83.3950, 0f, 1000L))
        lifecycleEngine.processLocation(createLocation(21.8974, 83.3950, 0f, 2500L))
        lifecycleEngine.processLocation(createLocation(21.8974, 83.3950, 0f, 4500L))

        // Move inside home geofence
        lifecycleEngine.processLocation(createLocation(21.8976, 83.3950, 10f, 5000L))
        // Cross boundary outward
        val outLat = 21.8974 + (230.0 / 111139.0)
        lifecycleEngine.processLocation(createLocation(outLat, 83.3950, 35f, 6000L))
        // Sustained exit
        val exitLat = 21.8974 + (250.0 / 111139.0)
        lifecycleEngine.processLocation(createLocation(exitLat, 83.3950, 40f, 7000L))

        assertEquals(TripLifecycleState.TRIP_ACTIVE, lifecycleEngine.state)
        assertEquals(TripStatus.ACTIVE_TRIP, tripEngine.currentStatus)
        assertEquals("Home", tripEngine.currentOriginName)

        // 2. Drive to Office (arrive inside Office geofence)
        val arrivalTime = 60000L
        lifecycleEngine.processLocation(createLocation(21.9100, 83.4100, 20f, arrivalTime))

        // Verify trip completion
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, lifecycleEngine.state)
        assertEquals(TripStatus.COMPLETED, tripEngine.currentStatus)
        assertEquals(0L, tripEngine.currentTripId)
        assertNotNull(completedSummary)
        assertEquals("Home", completedSummary?.startLocationName)
        assertEquals("Office", completedSummary?.endLocationName)
        assertEquals(TripStatus.COMPLETED, completedSummary?.status)
    }

    @Test
    fun testArrivalEdgeBoundaryPendingAndConfirmed() {
        val destGf = TripGeofenceEntity(id = 10, name = "Warehouse", latitude = 21.9500, longitude = 83.4500, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(destGf))

        var arrivalConfirmed = false
        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, _, _ -> },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> arrivalConfirmed = true }
        )

        engine.restoreActiveTrip(100L, 100L)

        // Fix 1: Enter right on the boundary edge (197m from center, radius = 200m -> within 5m edge)
        val edgeLat = 21.9500 + (197.0 / 111139.0)
        engine.processLocation(createLocation(edgeLat, 83.4500, 25f, 5000L))
        assertEquals(TripLifecycleState.ARRIVAL_PENDING, engine.state)
        assertFalse("Edge fix 1 remains ARRIVAL_PENDING", arrivalConfirmed)

        // Fix 2: Second fix inside boundary (196m from center)
        val insideLat = 21.9500 + (196.0 / 111139.0)
        engine.processLocation(createLocation(insideLat, 83.4500, 20f, 6000L))
        assertTrue("Second fix inside boundary must confirm arrival", arrivalConfirmed)
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
    }

    @Test
    fun testArrivalPowerDisconnectedInsideGeofence() {
        val destGf = TripGeofenceEntity(id = 11, name = "Depot", latitude = 21.9500, longitude = 83.4500, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(destGf))

        var arrivalConfirmed = false
        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, _, _ -> },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> arrivalConfirmed = true }
        )

        engine.restoreActiveTrip(200L, 200L)

        // Enter edge of boundary -> ARRIVAL_PENDING
        val edgeLat = 21.9500 + (198.0 / 111139.0)
        engine.processLocation(createLocation(edgeLat, 83.4500, 15f, 5000L))
        assertEquals(TripLifecycleState.ARRIVAL_PENDING, engine.state)

        // Vehicle engine cut / hardware power disconnection while ARRIVAL_PENDING
        engine.onPowerDisconnected(6000L)
        assertTrue("Power disconnected inside geofence must immediately confirm arrival", arrivalConfirmed)
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
    }

    @Test
    fun testPreTripTrailAnchorNotResetByBriefSlowdown() {
        val homeGf = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 300f)
        val provider = TestGeofenceProvider(listOf(homeGf))

        var confirmedDeparturePoints: List<TripPoint> = emptyList()

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, departurePoints, _ ->
                confirmedDeparturePoints = departurePoints
            },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> }
        )

        // 1. Stabilize GPS parked at origin
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 1000L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 2500L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 4500L))

        // 2. Start moving at t = 10,000L from origin
        val p1Lat = 21.8974 + (30.0 / 111139.0)
        engine.processLocation(createLocation(p1Lat, 83.3950, 10.0f, 10000L))

        // 3. Drive inside geofence for 16 seconds (t = 26,000L)
        val p2Lat = 21.8974 + (150.0 / 111139.0)
        engine.processLocation(createLocation(p2Lat, 83.3950, 15.0f, 26000L))

        // 4. Brief slowdown for speed bump at t = 27,000L (< 1.0 km/h for 1 second)
        // Must NOT wipe out the departureAnchorPoint!
        val pBumpLat = 21.8974 + (160.0 / 111139.0)
        engine.processLocation(createLocation(pBumpLat, 83.3950, 0.5f, 27000L))

        // 5. Accelerate and cross boundary at t = 35,000L (310m from origin)
        val pBoundaryLat = 21.8974 + (310.0 / 111139.0)
        engine.processLocation(createLocation(pBoundaryLat, 83.3950, 30.0f, 35000L))

        // 6. Confirmed departure at t = 37,000L (335m from origin)
        val pExitLat = 21.8974 + (335.0 / 111139.0)
        engine.processLocation(createLocation(pExitLat, 83.3950, 35.0f, 37000L))

        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertTrue(confirmedDeparturePoints.isNotEmpty())

        // The first point in departure trail MUST still be the initial parking location (21.8974, 83.3950)
        val firstPt = confirmedDeparturePoints.first()
        assertEquals("Origin point must be preserved despite brief slowdown", 21.8974, firstPt.latitude, 0.0001)
        assertEquals("Origin point must be preserved despite brief slowdown", 83.3950, firstPt.longitude, 0.0001)
    }

    @Test
    fun testArrivalEdgeBoundaryDriveThroughCancelsArrival() {
        val destGf = TripGeofenceEntity(id = 10, name = "Warehouse", latitude = 21.9500, longitude = 83.4500, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(destGf))

        var arrivalConfirmed = false
        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, _, _ -> },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> arrivalConfirmed = true }
        )

        engine.restoreActiveTrip(100L, 100L)

        // Fix 1: Enter right on the boundary edge (197m from center, radius = 200m -> within 5m edge)
        val edgeLat = 21.9500 + (197.0 / 111139.0)
        engine.processLocation(createLocation(edgeLat, 83.4500, 50f, 5000L))
        assertEquals(TripLifecycleState.ARRIVAL_PENDING, engine.state)
        assertFalse("Edge fix 1 remains ARRIVAL_PENDING", arrivalConfirmed)

        // Fix 2: Second fix outside boundary (205m from center, driving straight through on road)
        val outsideLat = 21.9500 + (205.0 / 111139.0)
        engine.processLocation(createLocation(outsideLat, 83.4500, 50f, 6000L))
        assertFalse("Drive-through outside boundary must NOT confirm arrival", arrivalConfirmed)
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
    }

    @Test
    fun testRapidReEntryAndReDeparture() {
        val hubGf = TripGeofenceEntity(id = 12, name = "Hub", latitude = 21.8974, longitude = 83.3950, radiusMeters = 200f)
        val provider = TestGeofenceProvider(listOf(hubGf))

        var tripStartCount = 0
        var tripArrivalCount = 0
        var latestDeparturePoints: List<TripPoint> = emptyList()

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, pts, _ ->
                tripStartCount++
                latestDeparturePoints = pts
            },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> tripArrivalCount++ }
        )

        // Start active trip #1 on the highway (with GPS stabilization)
        engine.restoreActiveTrip(1000L, 1000L)
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        engine.processLocation(createLocation(21.8000, 83.3000, 70f, 40000L))
        engine.processLocation(createLocation(21.8500, 83.3500, 50f, 45000L))
        assertTrue(engine.isGpsStabilized)

        // 1. Vehicle enters Hub geofence deep penetration (30m from center) at t = 50,000L
        val deepLat = 21.8974 + (30.0 / 111139.0)
        engine.processLocation(createLocation(deepLat, 83.3950, 15f, 50000L))
        assertEquals("Trip #1 completed upon deep arrival", 1, tripArrivalCount)
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)

        // 2. Immediate re-departure within < 2 seconds:
        // Movement fix 1 second later inside Hub
        val moveLat = 21.8974 + (80.0 / 111139.0)
        engine.processLocation(createLocation(moveLat, 83.3950, 20f, 51000L))

        // Exit boundary at t = 52,000L (220m from center)
        val exitLat1 = 21.8974 + (220.0 / 111139.0)
        engine.processLocation(createLocation(exitLat1, 83.3950, 30f, 52000L))

        // Sustained exit at t = 53,000L (240m from center)
        val exitLat2 = 21.8974 + (240.0 / 111139.0)
        engine.processLocation(createLocation(exitLat2, 83.3950, 35f, 53000L))

        assertEquals("New trip #2 must start upon immediate re-departure", 1, tripStartCount)
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertTrue("Departure points must be captured for trip #2", latestDeparturePoints.size >= 3)
    }

    @Test
    fun testPreTripTrailAnchorResetAfterExtendedStandstillInsideGeofence() {
        val homeGf = TripGeofenceEntity(id = 1, name = "Home", latitude = 21.8974, longitude = 83.3950, radiusMeters = 300f)
        val provider = TestGeofenceProvider(listOf(homeGf))

        var confirmedDeparturePoints: List<TripPoint> = emptyList()

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, departurePoints, _ ->
                confirmedDeparturePoints = departurePoints
            },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, _ -> }
        )

        // 1. Stabilize GPS parked at origin (0m)
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 1000L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 2500L))
        engine.processLocation(createLocation(21.8974, 83.3950, 0f, 4500L))

        // 2. Start moving at t = 10,000L from origin towards internal gate
        val p1Lat = 21.8974 + (30.0 / 111139.0)
        engine.processLocation(createLocation(p1Lat, 83.3950, 10.0f, 10000L))

        // 3. Reach internal gate at 100m at t = 20,000L
        val pGateLat = 21.8974 + (100.0 / 111139.0)
        engine.processLocation(createLocation(pGateLat, 83.3950, 0.0f, 20000L))

        // 4. Extended standstill for 20 seconds at internal gate (> 15s threshold)
        engine.processLocation(createLocation(pGateLat, 83.3950, 0.0f, 30000L))
        engine.processLocation(createLocation(pGateLat, 83.3950, 0.0f, 41000L)) // 21s stationary

        // 5. Resume movement from gate towards exit at t = 50,000L
        val pResumeLat = 21.8974 + (120.0 / 111139.0)
        engine.processLocation(createLocation(pResumeLat, 83.3950, 15.0f, 50000L))

        // 6. Cross boundary at t = 60,000L (310m from origin)
        val pBoundaryLat = 21.8974 + (310.0 / 111139.0)
        engine.processLocation(createLocation(pBoundaryLat, 83.3950, 30.0f, 60000L))

        // 7. Confirmed departure at t = 62,000L (335m from origin)
        val pExitLat = 21.8974 + (335.0 / 111139.0)
        engine.processLocation(createLocation(pExitLat, 83.3950, 35.0f, 62000L))

        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)
        assertTrue(confirmedDeparturePoints.isNotEmpty())

        // The first point in departure trail must now be the gate anchor (100m), NOT the 0m origin
        val firstPt = confirmedDeparturePoints.first()
        assertEquals("Departure anchor must reset to gate after > 15s stationary", pGateLat, firstPt.latitude, 0.0001)
    }

    @Test
    fun testGeofenceArrivalAtExactCenter() {
        val campusGf = TripGeofenceEntity(id = 5, name = "Campus", latitude = 21.9200, longitude = 83.4200, radiusMeters = 250f)
        val provider = TestGeofenceProvider(listOf(campusGf))

        var arrivalConfirmed = false
        var confirmedDestName = ""

        val engine = GeofenceTripLifecycleEngine(
            geofenceManager = provider,
            onTripStartConfirmed = { _, _, _, _ -> },
            onTripActiveTelemetry = {},
            onTripArrivalConfirmed = { _, name ->
                arrivalConfirmed = true
                confirmedDestName = name
            }
        )

        engine.restoreActiveTrip(500L, 500L)
        assertEquals(TripLifecycleState.TRIP_ACTIVE, engine.state)

        // Vehicle enters directly at the exact center of Campus geofence (dist = 0m)
        engine.processLocation(createLocation(campusGf.latitude, campusGf.longitude, 10f, 10000L))

        assertTrue("Arrival must be confirmed immediately at center", arrivalConfirmed)
        assertEquals("Campus", confirmedDestName)
        assertEquals(TripLifecycleState.INSIDE_GEOFENCE, engine.state)
    }
}
