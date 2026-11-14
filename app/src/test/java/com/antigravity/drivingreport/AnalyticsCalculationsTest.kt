package com.antigravity.drivingreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsCalculationsTest {

    @Test
    fun testFuelAndMileageCalculationsDivisionByZeroSafe() {
        val totalDistanceKm = 0.0
        val totalLiters = 0.0
        val totalCost = 0.0

        val mileage = if (totalDistanceKm > 0 && totalLiters > 0) {
            totalDistanceKm / totalLiters
        } else 0.0
        assertEquals(0.0, mileage, 0.0001)

        val pricePerLiter = if (totalLiters > 0 && totalCost > 0) {
            totalCost / totalLiters
        } else 0.0
        assertEquals(0.0, pricePerLiter, 0.0001)
    }

    @Test
    fun testValidMileageCalculation() {
        val totalDistanceKm = 450.0
        val totalLiters = 30.0
        val totalCost = 3000.0

        val mileage = totalDistanceKm / totalLiters
        assertEquals(15.0, mileage, 0.01)

        val pricePerLiter = totalCost / totalLiters
        assertEquals(100.0, pricePerLiter, 0.01)
    }

    @Test
    fun testSpeedometerSpeedLimitRatios() {
        val speedLimit = 60
        val safeSpeed = 50f
        val nearLimitSpeed = 62f
        val exceedingSpeed = 75f

        val ratioSafe = safeSpeed / speedLimit.toFloat()
        assertTrue(ratioSafe <= 0.90f)

        val ratioNear = nearLimitSpeed / speedLimit.toFloat()
        assertTrue(ratioNear in 0.90f..1.10f)

        val ratioExceeding = exceedingSpeed / speedLimit.toFloat()
        assertTrue(ratioExceeding > 1.10f)
    }
}
