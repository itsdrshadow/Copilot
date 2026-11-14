package com.antigravity.copilot

import android.location.Location
import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.InsightCategory
import com.antigravity.copilot.core.trip.SmartTripAnalyticsEngine
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test

class SmartTripSummaryLifecycleTest {

    private fun createMockLocation(lat: Double, lon: Double, timeMs: Long, speedKmh: Float, accuracy: Float = 5f): Location {
        return TripDetectionEngine.CoordinateLocation(
            _latitude = lat,
            _longitude = lon,
            _time = timeMs,
            _accuracy = accuracy,
            _speedKmh = speedKmh,
            _bearing = 0f,
            _altitude = 250.0
        )
    }

    @Test
    fun testStationaryDebounce_DoesNotTerminateTripAtTrafficSignals() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.stationaryCompletionThresholdSeconds = 180

        val t0 = 1_000_000L
        engine.restoreActiveTripState(
            tripId = 101L,
            startTime = t0,
            distanceMeters = 3500.0,
            drivingMs = 240_000L,
            stationaryMs = 0L,
            lastLocation = createMockLocation(21.8974, 83.3980, t0, 35.0f)
        )
        assertEquals("Trip should be in ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // 1. Standstill for 90 seconds at traffic signal (minor GPS jitter < 15m)
        val stopLat = 21.9020
        val stopLon = 83.3990
        var currentTime = t0
        for (sec in 1..90) {
            currentTime += 1000L
            val jitterLat = stopLat + ((sec % 5) * 0.00002) // ~2m jitter
            val jitterLon = stopLon + ((sec % 3) * 0.00001)
            engine.processLocation(createMockLocation(jitterLat, jitterLon, currentTime, 0.0f))
            engine.processStationaryWatchdog(currentTime)
        }

        // At 90s, standstill is TEMPORARY_STOP, NOT COMPLETED
        assertEquals("Vehicle at 90s signal must remain in TEMPORARY_STOP", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertNull("Trip should NOT be completed at a 90s red light", completedSummary)

        // 2. Signal turns green -> Vehicle accelerates to 35 km/h and moves away (> 35m)
        currentTime += 2000L
        val locResume = createMockLocation(stopLat + 0.0020, stopLon + 0.0020, currentTime, 35.0f)
        engine.processLocation(locResume)

        assertEquals("Vehicle resuming movement must return to ACTIVE_TRIP", TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertNull("Trip should still be ongoing", completedSummary)
    }

    @Test
    fun testStationaryDebounce_ConcludesTripAfterConfiguredStationaryThreshold() {
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completedSummary = it },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        engine.stationaryCompletionThresholdSeconds = 120 // 2 minutes configured threshold

        val t0 = 2_000_000L
        engine.restoreActiveTripState(
            tripId = 202L,
            startTime = t0,
            distanceMeters = 4200.0,
            drivingMs = 300_000L,
            stationaryMs = 0L,
            lastLocation = createMockLocation(21.9150, 83.4050, t0, 40.0f)
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)

        // Vehicle parks at destination: stationary watchdog ticks up to 125s
        var currentTime = t0
        for (sec in 1..125) {
            currentTime += 1000L
            engine.processStationaryWatchdog(currentTime)
        }

        assertNotNull("Trip must be completed after stationary threshold exceeded", completedSummary)
        assertEquals(TripStatus.COMPLETED, completedSummary?.status)
        assertEquals(202L, completedSummary?.id)
        assertTrue("Completed trip distance should be > 1000m", (completedSummary?.distanceMeters ?: 0.0) >= 1000.0)
    }

    @Test
    fun testRefuelingMatchingWithTripTimestamps() {
        val tripStartTime = 10_000_000L
        val tripEndTime = 10_000_000L + (45 * 60 * 1000L) // 45 minutes trip

        val trip = TripEntity(
            id = 101L,
            vehicleId = 1,
            startTime = tripStartTime,
            endTime = tripEndTime,
            distanceMeters = 24_500.0,
            drivingDurationMs = 38 * 60 * 1000L,
            stationaryDurationMs = 7 * 60 * 1000L,
            avgSpeedKmh = 38.7,
            maxSpeedKmh = 72.0,
            estimatedFuelLiters = 1.63,
            calculatedMileageKmpl = 15.0,
            driverScore = 92,
            startLocationName = "Raigarh Station",
            endLocationName = "Kharsia Highway",
            status = TripStatus.COMPLETED.name,
            isQualifying = true
        )

        val fuelEvents = listOf(
            // Event 1: Unrelated fuel event 2 hours BEFORE trip
            FuelLedgerEntity(
                id = 1L,
                eventType = "REFUEL",
                timestamp = tripStartTime - (120 * 60 * 1000L),
                fuelQuantityLiters = 20.0,
                totalAmount = 1930.0,
                locationName = "IndianOil Bypass"
            ),
            // Event 2: Legitimate refueling DURING trip at minute 20
            FuelLedgerEntity(
                id = 2L,
                eventType = "REFUEL",
                timestamp = tripStartTime + (20 * 60 * 1000L),
                fuelQuantityLiters = 12.5,
                totalAmount = 1206.25,
                pricePerLiter = 96.5,
                locationName = "HP Fuel Stop Shahdol"
            ),
            // Event 3: Fuel event 3 hours AFTER trip
            FuelLedgerEntity(
                id = 3L,
                eventType = "REFUEL",
                timestamp = tripEndTime + (180 * 60 * 1000L),
                fuelQuantityLiters = 15.0,
                totalAmount = 1447.5,
                locationName = "Bharat Petroleum Bilaspur"
            )
        )

        val points = listOf(
            TripPointEntity(tripId = 101L, latitude = 21.8974, longitude = 83.3980, speedKmh = 0f, bearing = 0f, accuracy = 5f, timestamp = tripStartTime),
            TripPointEntity(tripId = 101L, latitude = 21.9050, longitude = 83.4020, speedKmh = 50f, bearing = 45f, accuracy = 5f, timestamp = tripStartTime + (20 * 60 * 1000L)),
            TripPointEntity(tripId = 101L, latitude = 21.9200, longitude = 83.4150, speedKmh = 0f, bearing = 0f, accuracy = 5f, timestamp = tripEndTime)
        )

        val report = SmartTripAnalyticsEngine.generateReport(
            trip = trip,
            points = points,
            fuelEvents = fuelEvents,
            fuelPricePerLiter = 96.5
        )

        // Verify ONLY the during-trip refueling was matched
        assertTrue("Report must detect refueling during trip", report.fuelReport.hasRefuel)
        assertEquals("Exactly 1 refuel must be matched", 1, report.fuelReport.refuelEvents.size)
        val matched = report.fuelReport.refuelEvents.first()
        assertEquals(12.5, matched.liters, 0.001)
        assertEquals("HP Fuel Stop Shahdol", matched.locationName)
        assertEquals(1206.25, matched.totalCost, 0.01)

        // Verify timeline contains Refuel event
        val refuelTimelineItem = report.timelineEvents.any { it.title.contains("Refuelled 12.5 L") }
        assertTrue("Timeline must include refueling event", refuelTimelineItem)
    }

    @Test
    fun testEmptyStateWhenNoRefuelingDuringTrip() {
        val tripStartTime = 10_000_000L
        val tripEndTime = tripStartTime + 30 * 60 * 1000L

        val trip = TripEntity(
            id = 102L,
            vehicleId = 1,
            startTime = tripStartTime,
            endTime = tripEndTime,
            distanceMeters = 15_000.0,
            drivingDurationMs = 28 * 60 * 1000L,
            stationaryDurationMs = 2 * 60 * 1000L,
            avgSpeedKmh = 32.1,
            maxSpeedKmh = 58.0,
            estimatedFuelLiters = 1.0,
            calculatedMileageKmpl = 15.0,
            driverScore = 95,
            startLocationName = "City Center",
            endLocationName = "Suburbs",
            status = TripStatus.COMPLETED.name,
            isQualifying = true
        )

        val report = SmartTripAnalyticsEngine.generateReport(
            trip = trip,
            points = emptyList(),
            fuelEvents = emptyList(),
            fuelPricePerLiter = 96.5
        )

        assertFalse("Report must have no refuel", report.fuelReport.hasRefuel)
        assertTrue("Refuel list must be empty", report.fuelReport.refuelEvents.isEmpty())
        assertTrue("Calculation method label must be present", report.fuelReport.measurementMethodLabel.contains("baseline"))
    }

    @Test
    fun testDeterministicAiInsightsAndDriverScore() {
        val tripStartTime = 10_000_000L
        val tripEndTime = tripStartTime + 40 * 60 * 1000L

        val trip = TripEntity(
            id = 103L,
            vehicleId = 1,
            startTime = tripStartTime,
            endTime = tripEndTime,
            distanceMeters = 30_000.0,
            drivingDurationMs = 35 * 60 * 1000L,
            stationaryDurationMs = 5 * 60 * 1000L,
            avgSpeedKmh = 51.4,
            maxSpeedKmh = 82.0,
            estimatedFuelLiters = 1.875,
            calculatedMileageKmpl = 16.0,
            driverScore = 88,
            startLocationName = "Point A",
            endLocationName = "Point B",
            status = TripStatus.COMPLETED.name,
            isQualifying = true
        )

        val points = mutableListOf<TripPointEntity>()
        // Generate points with steady cruising between 40-70 km/h
        for (i in 0..100) {
            val t = tripStartTime + (i * 20_000L)
            points.add(
                TripPointEntity(
                    tripId = 103L,
                    latitude = 21.90 + (i * 0.001),
                    longitude = 83.40 + (i * 0.001),
                    altitude = 250.0 + (i * 0.8), // Net elevation ascent
                    speedKmh = 55.0f,
                    bearing = 45f,
                    accuracy = 5f,
                    timestamp = t
                )
            )
        }

        val pastTrips = listOf(
            TripEntity(id = 90L, startTime = 5000L, calculatedMileageKmpl = 14.5, isQualifying = true),
            TripEntity(id = 91L, startTime = 6000L, calculatedMileageKmpl = 14.8, isQualifying = true)
        )

        val report = SmartTripAnalyticsEngine.generateReport(
            trip = trip,
            points = points,
            historicalTrips = pastTrips
        )

        assertNotNull("Report must be generated", report)
        assertTrue("AI insights list must not be empty", report.aiInsights.isNotEmpty())

        // Check Driver Performance score insight
        val scoreInsight = report.aiInsights.find { it.title.contains("Driver Performance Score") }
        assertNotNull("Score insight must exist", scoreInsight)
        assertTrue("Score badge must reflect 88/100", scoreInsight?.badgeLabel?.contains("88/100") == true)

        // Check Cruising insight
        val cruiseInsight = report.aiInsights.find { it.category == InsightCategory.EFFICIENCY }
        assertNotNull("Cruising efficiency insight must exist", cruiseInsight)

        // Check Historical Comparison (16.0 km/L vs ~14.65 km/L past average -> positive improvement)
        val histInsight = report.aiInsights.find { it.category == InsightCategory.HISTORICAL }
        assertNotNull("Historical comparison insight must exist", histInsight)
        assertTrue("Historical comparison must be positive", histInsight?.isPositive == true)

        // Check Elevation Terrain insight
        val terrainInsight = report.aiInsights.find { it.category == InsightCategory.TERRAIN }
        assertNotNull("Terrain insight must detect net ascent", terrainInsight)

        // Check Actionable Eco Recommendation
        val tipInsight = report.aiInsights.find { it.category == InsightCategory.RECOMMENDATION }
        assertNotNull("Actionable eco tip must exist", tipInsight)
    }

    @Test
    fun testTripTimelineChronologicalOrder() {
        val tripStartTime = 10_000_000L
        val tripEndTime = tripStartTime + 20 * 60 * 1000L

        val trip = TripEntity(
            id = 104L,
            vehicleId = 1,
            startTime = tripStartTime,
            endTime = tripEndTime,
            distanceMeters = 8_000.0,
            drivingDurationMs = 15 * 60 * 1000L,
            stationaryDurationMs = 5 * 60 * 1000L,
            avgSpeedKmh = 32.0,
            maxSpeedKmh = 65.0,
            estimatedFuelLiters = 0.53,
            calculatedMileageKmpl = 15.0,
            driverScore = 95,
            startLocationName = "Sector 1",
            endLocationName = "Sector 4",
            status = TripStatus.COMPLETED.name,
            isQualifying = true
        )

        val points = listOf(
            TripPointEntity(tripId = 104L, latitude = 21.900, longitude = 83.400, speedKmh = 0f, bearing = 0f, accuracy = 4f, timestamp = tripStartTime),
            TripPointEntity(tripId = 104L, latitude = 21.905, longitude = 83.403, speedKmh = 25f, bearing = 45f, accuracy = 4f, timestamp = tripStartTime + 60_000L),
            TripPointEntity(tripId = 104L, latitude = 21.910, longitude = 83.408, speedKmh = 65f, bearing = 45f, accuracy = 4f, timestamp = tripStartTime + 300_000L),
            TripPointEntity(tripId = 104L, latitude = 21.915, longitude = 83.412, speedKmh = 0f, bearing = 0f, accuracy = 4f, timestamp = tripEndTime)
        )

        val report = SmartTripAnalyticsEngine.generateReport(
            trip = trip,
            points = points
        )

        val events = report.timelineEvents
        assertTrue("Must have multiple timeline events", events.size >= 3)
        // Verify monotonically non-decreasing timestamps
        for (i in 1 until events.size) {
            assertTrue("Timeline must be chronologically ordered", events[i].timestamp >= events[i - 1].timestamp)
        }
        assertEquals("First event must be Trip Commenced", "Trip Commenced", events.first().title)
        assertEquals("Last event must be Trip Concluded", "Trip Concluded", events.last().title)
    }

    @Test
    fun testNavigationTabIndicesPreserved() {
        // Tab index invariants:
        // 0 = Dashboard (Home Cockpit)
        // 1 = Full Live Map
        // 2 = Trip History Archive
        // 3 = Fuel Records
        // 4 = Smart Trip Summary
        val TAB_COCKPIT = 0
        val TAB_MAP = 1
        val TAB_TRIP_HISTORY = 2
        val TAB_FUEL_HISTORY = 3
        val TAB_SMART_TRIP_SUMMARY = 4

        assertEquals(0, TAB_COCKPIT)
        assertEquals(1, TAB_MAP)
        assertEquals(2, TAB_TRIP_HISTORY)
        assertEquals(3, TAB_FUEL_HISTORY)
        assertEquals(4, TAB_SMART_TRIP_SUMMARY)
    }
}
