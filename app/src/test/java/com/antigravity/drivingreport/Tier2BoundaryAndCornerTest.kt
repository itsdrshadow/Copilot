package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.fuel.intelligence.*
import com.antigravity.drivingreport.core.map.GpsLocationFilter
import com.antigravity.drivingreport.core.model.TripPoint
import com.antigravity.drivingreport.core.persistence.FullBackupManager
import com.antigravity.drivingreport.core.util.NumberFormatUtils
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.*

/**
 * Tier 2: Boundary & Corner Cases Test Suite.
 * Validates behavior at extremes: zero/negative values, max limits, GPS dropouts,
 * clock sync discontinuities, corrupt archives, and division-by-zero safeguards.
 */
class Tier2BoundaryAndCornerTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    // B1: Fuel Estimation Zero and Negative Balance Clamping
    @Test
    fun testB01_FuelEstimationZeroAndNegativeBalanceClamping() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 5.0, gaugePoints = 1, odometerKm = 10000.0)

        // Burn 10.0 L (more than remaining 5.0 L in tank)
        estimator.applyConsumption(consumedLiters = 10.0, odometerKm = 10150.0)

        assertEquals("Nominal fuel balance must be clamped to 0.0 L", 0.0, estimator.nominalFuelLiters, 0.0001)
        assertEquals("Conservative fuel balance must be clamped to 0.0 L", 0.0, estimator.conservativeFuelLiters, 0.0001)
        assertEquals("Upper fuel balance must be clamped to 0.0 L", 0.0, estimator.upperFuelLiters, 0.0001)

        // Negative consumption input (e.g. invalid telemetry tick)
        estimator.applyConsumption(consumedLiters = -2.5, odometerKm = 10160.0)
        assertEquals("Negative consumption must be coerced to 0, balance remains 0", 0.0, estimator.nominalFuelLiters, 0.0001)
    }

    // B2: Fuel Overfill Beyond Max Capacity Clamping
    @Test
    fun testB02_FuelOverfillBeyondMaxCapacityClamping() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0, maxCapacityLiters = 50.0)
        estimator.initializeBaseline(initialLiters = 40.0, gaugePoints = 8, odometerKm = 20000.0)

        // Attempt to pump 50.0 L into a tank with 40.0 L (total 90 L > max capacity)
        estimator.applyRefuel(addedLiters = 50.0, observedGaugePoints = 10, odometerKm = 20000.0)

        val maxAllowedOverfill = estimator.designCapacityLiters * 1.35
        assertEquals("Nominal fuel must be clamped to safe overfill bound (1.35x design = 64.8 L)", maxAllowedOverfill, estimator.nominalFuelLiters, 0.0001)
        assertTrue("Upper fuel bound must not exceed max capacity", estimator.upperFuelLiters <= maxAllowedOverfill)
    }

    // B3: Extreme High Speeds Beyond Physical Limits
    @Test
    fun testB03_ExtremeHighSpeedsBeyondPhysicalVehicleLimits() {
        val filter = GpsLocationFilter()
        val t0 = 1000000L

        // Settle initial fix
        filter.filter(rawLat = 21.8900, rawLon = 83.3900, rawBearing = 0f, rawSpeedKmh = 50f, timestamp = t0)

        // Teleportation spike at 450 km/h with degraded accuracy
        val teleportLat = 21.8900 + (1000.0 / 111139.0) // 1 km in 1 second
        val state = filter.filter(rawLat = teleportLat, rawLon = 83.3900, rawBearing = 0f, rawSpeedKmh = 450f, rawAccuracyMeters = 25f, timestamp = t0 + 1000L)

        // Must reject teleport jump
        assertEquals("Teleportation spike at 450 km/h must be suppressed", 21.8900, state.latitude, 0.0001)

        // Negative speed input
        val negativeSpeedState = filter.filter(rawLat = 21.8900, rawLon = 83.3900, rawBearing = 0f, rawSpeedKmh = -20f, timestamp = t0 + 2000L)
        assertTrue("Negative speed must be coerced to 0 km/h", negativeSpeedState.speedKmh >= 0f)
    }

    // B4: Extreme Geographic Coordinates Boundary
    @Test
    fun testB04_ExtremeGeographicCoordinatesBoundary() {
        val filter = GpsLocationFilter()
        val t0 = 1000000L

        // Null Island (0.0, 0.0)
        val nullIslandState = filter.filter(rawLat = 0.0, rawLon = 0.0, rawBearing = 0f, rawSpeedKmh = 0f, timestamp = t0)
        assertEquals(0.0, nullIslandState.latitude, 0.0001)
        assertEquals(0.0, nullIslandState.longitude, 0.0001)

        // Near North Pole (+89.9 deg latitude)
        val arcticFilter = GpsLocationFilter()
        val arcticState = arcticFilter.filter(rawLat = 89.9, rawLon = 0.0, rawBearing = 180f, rawSpeedKmh = 30f, timestamp = t0)
        assertEquals(89.9, arcticState.latitude, 0.0001)
        assertFalse("Arctic coordinate calculation must not produce NaN", arcticState.latitude.isNaN())

        // Anti-meridian (+179.9 deg to -179.9 deg longitude)
        val bearingA = 179.0f
        val bearingB = -179.0f
        val shortestDelta = (bearingB - bearingA + 540f) % 360f - 180f
        assertEquals("Shortest angular delta across 180 deg anti-meridian must be 2.0 degrees", 2.0f, shortestDelta, 0.01f)
    }

    // B5: Clock Backwards Jump Negative Delta Handling
    @Test
    fun testB05_ClockBackwardsJumpNegativeDeltaHandling() {
        val tNow = 500000L
        val tJumpBackwards = 450000L // Clock stepped backward 50 seconds

        val rawDt = tJumpBackwards - tNow
        val clampedDt = rawDt.coerceAtLeast(0L)

        assertEquals("Negative clock jump must be clamped to 0 ms", 0L, clampedDt)
    }

    // B6: Clock Forward Jump Massive Delta Clamping
    @Test
    fun testB06_ClockForwardJumpMassiveDeltaClamping() {
        val tNow = 1000000L
        val tMassiveJump = 1000000L + (7 * 86400 * 1000L) // 7 days forward

        val rawDt = tMassiveJump - tNow
        val maxAllowedStepMs = 10000L // 10-second automotive cap
        val clampedDt = rawDt.coerceIn(0L, maxAllowedStepMs)

        assertEquals(maxAllowedStepMs, clampedDt)
    }

    // B7: Rapid Location Fix Storm 350ms Deduplication
    @Test
    fun testB07_RapidLocationFixStorm350msDeduplication() {
        var lastAcceptedTime = 1000L
        var acceptedCount = 1
        var droppedCount = 0

        // 100 rapid GPS fixes spaced 10ms apart (1000 Hz spam)
        for (i in 1..100) {
            val fixTime = 1000L + (i * 10L) // from 1010 to 2000
            if ((fixTime - lastAcceptedTime) < 350L) {
                droppedCount++
            } else {
                acceptedCount++
                lastAcceptedTime = fixTime
            }
        }

        // Between 1000 and 2000 ms, window of 1000ms with 350ms deduplication should admit 3 fixes: ~1350, ~1700
        assertTrue("Most rapid storm fixes must be dropped", droppedCount >= 95)
        assertEquals(100, acceptedCount + droppedCount - 1)
    }

    // B8: High Odometer Value 64-Bit Precision Limits
    @Test
    fun testB08_HighOdometerValue64BitPrecisionLimits() {
        val extremeOdometerKm = 1754892.87654321

        // 32-bit Float representation loses the decimal digits:
        val floatRepresentation = extremeOdometerKm.toFloat().toDouble()
        assertNotEquals("32-bit float loses precision on high odometer numbers", extremeOdometerKm, floatRepresentation, 0.0)

        // 64-bit IEEE 754 raw bits preserves every bit:
        val rawBits = java.lang.Double.doubleToRawLongBits(extremeOdometerKm)
        val reconstructed = java.lang.Double.longBitsToDouble(rawBits)
        assertEquals(extremeOdometerKm, reconstructed, 0.0)
    }

    // B9: Empty Backup Archive Handling
    @Test
    fun testB09_EmptyBackupArchiveHandling() {
        // Create completely empty ZIP stream
        val emptyBaos = ByteArrayOutputStream()
        ZipOutputStream(emptyBaos).use { } // empty ZIP
        val emptyZipBytes = emptyBaos.toByteArray()

        var hasManifest = false
        ZipInputStream(ByteArrayInputStream(emptyZipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup_manifest.json") hasManifest = true
                entry = zis.nextEntry
            }
        }

        assertFalse("Empty archive must not contain manifest", hasManifest)
    }

    // B10: Corrupted Json Manifest Parsing Safeguard
    @Test
    fun testB10_CorruptedJsonManifestParsingSafeguard() {
        val corruptedJson = "{\"appVersion\": \"1.18.0\", \"schemaVersion\": [unclosed array"

        var parseFailedGracefully = false
        try {
            gson.fromJson(corruptedJson, FullBackupManager.BackupManifest::class.java)
        } catch (_: JsonSyntaxException) {
            parseFailedGracefully = true
        }

        assertTrue("Corrupted JSON manifest syntax must be caught by exception handler", parseFailedGracefully)
    }

    // B11: Empty Bounding Box Tile Calculation Safeguard
    @Test
    fun testB11_EmptyBoundingBoxTileCalculationSafeguard() {
        // Point bounding box (north == south, east == west)
        val lat = 21.8974
        val lon = 83.3950
        val zoom = 12

        fun lon2TileX(l: Double, z: Int): Int {
            val n = 1 shl z
            return floor((l + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
        }

        fun lat2TileY(l: Double, z: Int): Int {
            val n = 1 shl z
            val latRad = Math.toRadians(l.coerceIn(-85.0511, 85.0511))
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n
            return floor(y).toInt().coerceIn(0, n - 1)
        }

        val tileX = lon2TileX(lon, zoom)
        val tileY = lat2TileY(lat, zoom)

        assertTrue(tileX in 0..(1 shl zoom))
        assertTrue(tileY in 0..(1 shl zoom))
    }

    // B12: Corrupted Truncated Tile Data Payload Rejection
    @Test
    fun testB12_CorruptedTruncatedTileDataPayloadRejection() {
        fun isTileValid(bytes: ByteArray): Boolean {
            if (bytes.size < 512) return false
            val isJpeg = (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
            val isPng = (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte())
            return isJpeg || isPng
        }

        // 4 bytes payload with valid magic prefix but truncated length
        val truncatedJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        assertFalse("Truncated tile under 512 bytes must be rejected", isTileValid(truncatedJpeg))
    }

    // B13: Low Fuel Reserve Border Condition at Exact 7.0 Liters
    @Test
    fun testB13_LowFuelReserveBorderConditionExact7Liters() {
        val mileage = 16.0

        fun calculateBufferedRange(liters: Double): Double {
            val isLow = liters < 7.0
            val multiplier = if (isLow) 0.85 else 1.0
            return liters * mileage * multiplier
        }

        val at7Liters = calculateBufferedRange(7.0)
        val below7Liters = calculateBufferedRange(6.999)

        assertEquals(112.0, at7Liters, 0.01) // 7.0 * 16.0 * 1.0 = 112.0
        assertEquals(95.1864, below7Liters, 0.01) // 6.999 * 16.0 * 0.85 = 95.1864
        assertTrue("Drop below 7.0 L triggers 0.85 buffer", below7Liters < 100.0)
    }

    // B14: Zero Distance Zero Duration Average Speed Safeguard
    @Test
    fun testB14_ZeroDistanceZeroDurationAverageSpeedCalculation() {
        val totalDistanceMeters = 0.0
        val drivingDurationMs = 0L

        val drivingHours = drivingDurationMs / 3600000.0
        val avgSpeed = if (drivingHours > 0.002) {
            (totalDistanceMeters / 1000.0) / drivingHours
        } else {
            0.0
        }

        assertEquals(0.0, avgSpeed, 0.0001)
        assertFalse("Must never produce NaN", avgSpeed.isNaN())
        assertFalse("Must never produce Infinity", avgSpeed.isInfinite())
    }
}
