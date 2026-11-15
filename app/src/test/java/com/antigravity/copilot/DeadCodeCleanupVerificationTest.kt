package com.antigravity.copilot

import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.model.FuelState
import com.antigravity.copilot.core.model.MileageLearningState
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadCodeCleanupVerificationTest {

    @Test
    fun testAppVersionBumpedToSemVerMinor() {
        assertEquals("1.32.0", BuildConfig.VERSION_NAME)
        assertEquals(56, BuildConfig.VERSION_CODE)
    }

    @Test
    fun testSmartTripMapViewEliminated() {
        var mapViewLoaded = false
        try {
            Class.forName("com.antigravity.copilot.feature.trips.SmartTripMapViewKt")
            mapViewLoaded = true
        } catch (_: ClassNotFoundException) {
            mapViewLoaded = false
        }
        assertFalse("SmartTripMapView should be removed from trips package", mapViewLoaded)
    }

    @Test
    fun testMapLibreIsFullyEliminated() {
        // Assert DrivingReportApp does not declare any MapLibre fields
        val fields = DrivingReportApp::class.java.declaredFields
        for (field in fields) {
            assertFalse(
                "DrivingReportApp must not contain MapLibre fields: ${field.name}",
                field.type.name.contains("maplibre", ignoreCase = true)
            )
        }

        // Verify MapLibre class is not loaded on classpath
        var mapLibreClassLoaded = false
        try {
            Class.forName("org.maplibre.android.MapLibre")
            mapLibreClassLoaded = true
        } catch (_: ClassNotFoundException) {
            mapLibreClassLoaded = false
        }
        assertFalse("MapLibre SDK should no longer be present in dependencies", mapLibreClassLoaded)
    }

    @Test
    fun testCoreDomainModelsPreservedAndIntact() {
        val summary = TripSummary(
            id = 101L,
            startTime = System.currentTimeMillis() - 3600000L,
            distanceMeters = 24500.0,
            drivingDurationMs = 1800000L,
            status = TripStatus.ACTIVE_TRIP
        )
        assertTrue(summary.isQualifyingTrip)
        assertEquals(24.5, summary.distanceKm, 0.001)
        assertEquals(30L, summary.drivingMinutes)

        val fuel = FuelState(
            tankCapacityLiters = 50.0,
            remainingLiters = 40.0,
            mileageLearningState = MileageLearningState.LEARNED
        )
        assertEquals(50.0, fuel.tankCapacityLiters, 0.001)
        assertEquals(40.0, fuel.remainingLiters, 0.001)

        val point = TripPoint(
            latitude = 28.6139,
            longitude = 77.2090,
            speedKmh = 45f
        )
        assertEquals(28.6139, point.latitude, 0.0001)
        assertEquals(77.2090, point.longitude, 0.0001)
    }

    @Test
    fun testFuelAndTripHistoryDataStructuresIntact() {
        val fuelEvents = listOf(
            FuelEventEntity(
                id = 1L,
                vehicleId = 1,
                timestamp = System.currentTimeMillis(),
                eventType = "REFUEL",
                liters = 35.0,
                pricePerLiter = 95.0,
                totalCost = 3325.0,
                calculatedBalanceLiters = 45.0,
                odometerKm = 12000.0
            )
        )
        assertEquals(1, fuelEvents.size)
        assertEquals(35.0, fuelEvents[0].liters, 0.001)

        val trips = listOf(
            TripEntity(
                id = 1L,
                vehicleId = 1,
                startTime = System.currentTimeMillis() - 100000L,
                endTime = System.currentTimeMillis(),
                distanceMeters = 5000.0,
                status = TripStatus.COMPLETED.name
            )
        )
        assertEquals(1, trips.size)
        assertEquals(5000.0, trips[0].distanceMeters, 0.001)
    }
}
