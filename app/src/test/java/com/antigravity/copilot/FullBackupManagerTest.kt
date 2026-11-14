package com.antigravity.copilot

import com.antigravity.copilot.core.persistence.FullBackupManager
import com.google.gson.GsonBuilder
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FullBackupManagerTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun testManifestSerializationAndIntegrity() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "1.14.0",
            versionCode = 23,
            schemaVersion = 5,
            timestamp = 1731110400000L,
            formattedDate = "2026-11-09 01:30:00",
            tripCount = 12,
            tripPointCount = 450,
            fuelEventCount = 5,
            fuelLedgerCount = 8,
            vehicleTransitionCount = 20,
            hasOfflineMaps = true,
            note = "Automotive Test Backup"
        )

        val json = gson.toJson(manifest)
        assertNotNull(json)
        assertTrue(json.contains("\"versionCode\": 23"))
        assertTrue(json.contains("\"appVersion\": \"1.14.0\""))
        assertTrue(json.contains("\"tripCount\": 12"))

        val parsed = gson.fromJson(json, FullBackupManager.BackupManifest::class.java)
        assertEquals(manifest.appVersion, parsed.appVersion)
        assertEquals(manifest.versionCode, parsed.versionCode)
        assertEquals(manifest.schemaVersion, parsed.schemaVersion)
        assertEquals(manifest.tripCount, parsed.tripCount)
        assertEquals(manifest.fuelEventCount, parsed.fuelEventCount)
        assertTrue(parsed.hasOfflineMaps)
    }

    @Test
    fun testZipArchiveCompressionAndExtraction() {
        val manifest = FullBackupManager.BackupManifest(
            appVersion = "1.14.0",
            versionCode = 23,
            tripCount = 3,
            fuelEventCount = 2
        )
        val manifestJson = gson.toJson(manifest)
        val sampleTripsJson = "[{\"id\":1,\"distanceMeters\":5200.0},{\"id\":2,\"distanceMeters\":12300.0}]"

        // Create in-memory ZIP archive
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Entry 1: manifest
            zos.putNextEntry(ZipEntry("backup_manifest.json"))
            zos.write(manifestJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Entry 2: trips
            zos.putNextEntry(ZipEntry("trips.json"))
            zos.write(sampleTripsJson.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        assertTrue(zipBytes.isNotEmpty())

        // Read and verify in-memory ZIP archive
        var extractedManifest: FullBackupManager.BackupManifest? = null
        var extractedTrips: String? = null

        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "backup_manifest.json") {
                    val reader = InputStreamReader(zis, Charsets.UTF_8)
                    extractedManifest = gson.fromJson(reader, FullBackupManager.BackupManifest::class.java)
                } else if (entry.name == "trips.json") {
                    extractedTrips = zis.bufferedReader(Charsets.UTF_8).readText()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        assertNotNull(extractedManifest)
        assertEquals("1.14.0", extractedManifest?.appVersion)
        assertEquals(3, extractedManifest?.tripCount)
        assertNotNull(extractedTrips)
        assertTrue(extractedTrips!!.contains("5200.0"))
    }
}
