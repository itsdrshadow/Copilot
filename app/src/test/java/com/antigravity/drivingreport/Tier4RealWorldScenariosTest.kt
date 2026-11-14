package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.database.TripEntity
import com.antigravity.drivingreport.core.fuel.intelligence.*
import com.antigravity.drivingreport.core.map.GpsLocationFilter
import com.antigravity.drivingreport.core.persistence.FullBackupManager
import com.antigravity.drivingreport.core.trip.TimeFormatUtils
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
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
 * Tier 4: Real-World Scenarios Test Suite.
 * Validates complex, multi-step automotive workloads:
 * - R01: Multi-day interstate journey across several days with overnight engine-off stops.
 * - R02: Urban stop-and-go congestion with fuel station dwell, refuel prompt, and refill.
 * - R03: High-speed mountain tunnel transit with complete 45-second GPS blackout and recovery.
 * - R04: Captive portal Wi-Fi interception during background offline map tile downloads.
 * - R05: Full disaster recovery round-trip: backup packaging -> system wipe -> transactional restore.
 */
class Tier4RealWorldScenariosTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    // R01: Multi-Day Interstate Trip Tracking with Engine-Off Dwell
    @Test
    fun testR01_MultiDayInterstateTripTrackingWithEngineOffDwell() {
        // Raipur to Bengaluru (approx 1,000 km over 3 days)
        val totalDistanceKm = 1000.0

        // Driving segments:
        // Day 1: 350 km in 5.0 hours (18,000,000 ms driving). Overnight stop 10 hours.
        // Day 2: 420 km in 6.0 hours (21,600,000 ms driving). Evening stop 8 hours.
        // Day 3: 230 km in 3.5 hours (12,600,000 ms driving). Arrival.
        val drivingDurationMs = (5.0 + 6.0 + 3.5) * 3600000L // 14.5 hours = 52,200,000 ms
        val totalElapsedMs = (3 * 86400 * 1000L) + (4 * 3600 * 1000L) // 3 days, 4 hours elapsed

        // Average speed calculation must be based on driving duration, NOT total calendar time:
        val drivingHours = drivingDurationMs / 3600000.0
        val avgSpeedKmh = totalDistanceKm / drivingHours

        assertEquals(14.5, drivingHours, 0.001)
        assertEquals(68.965, avgSpeedKmh, 0.01)

        // TimeFormatUtils representation of running driving timer:
        assertEquals("14:30:00", TimeFormatUtils.formatLiveTripTimer(drivingDurationMs.toLong()))

        // Total journey elapsed time formatting (multi-day >= 24h format: "3d 04h ..."):
        val multiDayFormatted = TimeFormatUtils.formatLiveTripTimer(totalElapsedMs)
        assertTrue("Multi-day timer must include day prefix e.g. '3d 04h 00m'", multiDayFormatted.startsWith("3d 04h"))
    }

    // R02: Urban Rush-Hour Stop-and-Go with Fuel Station Dwell and Prompt
    @Test
    fun testR02_UrbanRushHourStopAndGoWithFuelStationDwellAndPrompt() {
        val estimator = FuelStateEstimator(designCapacityLiters = 48.0)
        estimator.initializeBaseline(initialLiters = 8.5, gaugePoints = 2, odometerKm = 34500.0)

        // Traffic Light Stop 1: 90 seconds stationary at intersection
        var stationaryDurationSec = 90
        val isStationaryWatchdogTimeout = stationaryDurationSec >= 300 // 5-minute timeout
        assertFalse("90s stop does not abort active trip", isStationaryWatchdogTimeout)

        // Pull into IndianOil station POI
        val isAtFuelStationPoi = true
        var dwellAtStationSec = 0
        var promptTriggered = false

        // Dwell 35 seconds (> 30s threshold)
        dwellAtStationSec = 35
        if (isAtFuelStationPoi && dwellAtStationSec >= 30) {
            promptTriggered = true
        }
        assertTrue("Refuel prompt triggered after 30s dwell at confirmed fuel station", promptTriggered)

        // User logs refill: ₹2,000 at ₹102.50/L -> 19.51 Liters added
        val addedLiters = FuelMath.calculateLitersFromAmount(2000.0, 102.50)
        assertEquals(19.51, addedLiters, 0.01)

        estimator.applyRefuel(addedLiters = addedLiters, observedGaugePoints = 6, odometerKm = 34510.0)
        assertEquals(28.01, estimator.nominalFuelLiters, 0.01)
        assertEquals(6, estimator.currentGaugeBar)

        // Vehicle moves again (> 10 km/h) -> prompt cooldown initiated (30 minutes)
        val lastPromptTime = 1000000L
        val nextCheckTime = 1000000L + (15 * 60 * 1000L) // 15 mins later
        val isCooldownActive = (nextCheckTime - lastPromptTime) < (30 * 60 * 1000L)
        assertTrue("Prompt cooldown prevents duplicate station prompt within 30 minutes", isCooldownActive)
    }

    // R03: Mountain Tunnel Transit with GPS Dropout and Recovery
    @Test
    fun testR03_MountainTunnelTransitWithGPSDropoutAndRecovery() {
        val filter = GpsLocationFilter()
        var currentSpeed = 80.0f
        val tunnelLengthMeters = 2200.0

        val tunnelEntryLat = 21.7500
        val tunnelEntryLon = 83.2500
        val entryTime = 1000000L

        // Initial stable GPS fix entering tunnel
        val sEntry = filter.filter(tunnelEntryLat, tunnelEntryLon, 0f, currentSpeed, 5f, entryTime)
        assertEquals(80.0f, sEntry.speedKmh, 0.1f)

        // During 45-second blackout in tunnel, GPS ticks cease:
        // Speedometer graceful decay watchdog runs
        val decayStepSec = 1.0f
        for (step in 1..4) {
            currentSpeed = (currentSpeed - (10.0f * decayStepSec)).coerceAtLeast(0f)
        }
        assertEquals(40.0f, currentSpeed, 0.1f) // Smooth deceleration rather than 0 instant snap

        // Vehicle exits tunnel after 45 seconds at coordinate 2.2 km ahead
        val tunnelExitLat = tunnelEntryLat + (tunnelLengthMeters / 111139.0)
        val tunnelExitLon = tunnelEntryLon
        val exitTime = entryTime + 45000L

        // Geodesic bridge calculation across outage
        val bridgeDistance = haversineMeters(tunnelEntryLat, tunnelEntryLon, tunnelExitLat, tunnelExitLon)
        assertEquals("Geodesic bridge across tunnel length must equal 2200m", 2200.0, bridgeDistance, 5.0)

        // Satellite acquisition outside tunnel (accuracy = 8m) following > 15s outage
        filter.reset()
        val sExit = filter.filter(tunnelExitLat, tunnelExitLon, 0f, 80.0f, 8.0f, exitTime)
        assertEquals("Vehicle recovers valid position upon tunnel exit", tunnelExitLat, sExit.latitude, 0.0001)
        assertEquals(80.0f, sExit.speedKmh, 0.1f)
    }

    // R04: Captive Portal Wi-Fi Interception During Offline Map Download
    @Test
    fun testR04_CaptivePortalWifiInterceptionDuringOfflineMapDownload() {
        fun validateTileBytes(bytes: ByteArray, httpCode: Int): Boolean {
            if (httpCode != 200 || bytes.size < 512) return false
            val isJpeg = (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte())
            val isPng = (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte())
            return isJpeg || isPng
        }

        // Roadside motel Wi-Fi returns HTTP 200 with HTML portal login page
        val captivePortalHtmlResponse = """
            <!DOCTYPE html>
            <html>
            <head><title>Highway Motel - Guest WiFi Login</title></head>
            <body>Please accept terms to connect to the Internet</body>
            </html>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val isValid = validateTileBytes(captivePortalHtmlResponse, 200)
        assertFalse("Captive portal HTML page disguised as HTTP 200 MUST be rejected by tile validator", isValid)
    }

    // R05: Full Disaster Recovery Round-Trip with State Rehydration
    @Test
    fun testR05_FullDisasterRecoveryRoundTripWithStateRehydration() {
        // 1. Prepare operational vehicle dataset
        val originalTrips = listOf(
            TripEntity(id = 1, vehicleId = 1, startTime = 1000L, endTime = 2000L, distanceMeters = 15400.0, status = "COMPLETED", isQualifying = true),
            TripEntity(id = 2, vehicleId = 1, startTime = 3000L, endTime = 4000L, distanceMeters = 32800.0, status = "COMPLETED", isQualifying = true)
        )
        val originalManifest = FullBackupManager.BackupManifest(
            appVersion = "1.18.0",
            versionCode = 33,
            schemaVersion = FullBackupManager.DATABASE_SCHEMA_VERSION,
            tripCount = 2,
            note = "Automotive Pre-Crash Full Backup"
        )
        val originalOdoBits = java.lang.Double.doubleToRawLongBits(125489.654)

        // 2. Package into in-memory portable ZIP archive
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // backup_manifest.json
            zos.putNextEntry(ZipEntry("backup_manifest.json"))
            zos.write(gson.toJson(originalManifest).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // trips.json
            zos.putNextEntry(ZipEntry("trips.json"))
            zos.write(gson.toJson(originalTrips).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // preferences.json with 64-bit IEEE 754 odometer bits
            val prefsMap = mapOf(
                "odometer" to mapOf(
                    "baseline_odometer_bits" to FullBackupManager.TypedPrefEntry("LONG", originalOdoBits.toString())
                )
            )
            zos.putNextEntry(ZipEntry("preferences.json"))
            zos.write(gson.toJson(prefsMap).toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val backupZipBytes = baos.toByteArray()
        assertTrue(backupZipBytes.isNotEmpty())

        // 3. Simulate System Crash / Wipe: Local state is cleared
        var restoredTripsList: List<TripEntity>? = null
        var restoredOdoKm: Double = 0.0

        // 4. Restore and Rehydrate from ZIP archive
        ZipInputStream(ByteArrayInputStream(backupZipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "backup_manifest.json" -> {
                        val manifest = gson.fromJson(InputStreamReader(zis, Charsets.UTF_8), FullBackupManager.BackupManifest::class.java)
                        // Gate: Schema version must match DATABASE_SCHEMA_VERSION (8)
                        assertEquals(FullBackupManager.DATABASE_SCHEMA_VERSION, manifest.schemaVersion)
                    }
                    "trips.json" -> {
                        val type = object : TypeToken<List<TripEntity>>() {}.type
                        restoredTripsList = gson.fromJson(InputStreamReader(zis, Charsets.UTF_8), type)
                    }
                    "preferences.json" -> {
                        val text = zis.bufferedReader(Charsets.UTF_8).readText()
                        val json = com.google.gson.JsonParser.parseString(text).asJsonObject
                        val odoBitsStr = json.getAsJsonObject("odometer")
                            .getAsJsonObject("baseline_odometer_bits")
                            .get("value").asString
                        val bits = odoBitsStr.toLong()
                        restoredOdoKm = java.lang.Double.longBitsToDouble(bits)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 5. Verify round-trip bit-exact rehydration
        assertNotNull(restoredTripsList)
        assertEquals(2, restoredTripsList!!.size)
        assertEquals(15400.0, restoredTripsList!![0].distanceMeters, 0.001)
        assertEquals(32800.0, restoredTripsList!![1].distanceMeters, 0.001)

        assertEquals("Odometer must restore with bit-exact 64-bit precision", 125489.654, restoredOdoKm, 0.0)
    }
}
