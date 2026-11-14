package com.antigravity.copilot

import org.junit.Assert.*
import org.junit.Test

class OdometerPersistenceTest {

    @Test
    fun test64BitDoublePrecisionRawBitsRoundTrip() {
        val testReadings = doubleArrayOf(
            0.0,
            12.5,
            142589.654321,
            999999.99,
            1500234.125
        )

        for (reading in testReadings) {
            val rawBits = java.lang.Double.doubleToRawLongBits(reading)
            val reconstructed = java.lang.Double.longBitsToDouble(rawBits)
            assertEquals("Bit-exact round-trip preservation", reading, reconstructed, 0.0)
        }
    }

    @Test
    fun testHighOdometerPrecisionAbove100kKm() {
        // Above 100,000 km, 32-bit Float mantissa (24 bits) cannot represent 0.01 precision:
        // 142589.65f becomes 142589.66f (lost 0.01)
        val exactOdometer = 142589.65
        val floatTruncated = exactOdometer.toFloat().toDouble()
        val bitPreserved = java.lang.Double.longBitsToDouble(java.lang.Double.doubleToRawLongBits(exactOdometer))

        assertNotEquals("Float truncation causes precision loss", exactOdometer, floatTruncated, 0.0)
        assertEquals("Bit storage retains exact 64-bit precision", exactOdometer, bitPreserved, 0.0)
    }

    @Test
    fun testDeltaAndLiveOdometerCalculation() {
        val baselineOdo = 125000.0
        val baselineLifetime = 2500.0
        val currentLifetime = 2534.8

        val delta = currentLifetime - baselineLifetime
        val liveOdo = baselineOdo + delta

        assertEquals(34.8, delta, 0.001)
        assertEquals(125034.8, liveOdo, 0.001)
    }
}
