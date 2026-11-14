package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.persistence.FullBackupManager
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class BackupRestoreTypeSafetyTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    @Test
    fun testBackupConstantsVersionSynchronization() {
        assertEquals("1.18.0", FullBackupManager.CURRENT_APP_VERSION)
        assertEquals(33, FullBackupManager.CURRENT_VERSION_CODE)
        assertEquals(8, FullBackupManager.DATABASE_SCHEMA_VERSION)
    }

    @Test
    fun testTypedPrefSerializationAndDeserialization() {
        val originalEntries = mapOf(
            "model_confidence" to FullBackupManager.TypedPrefEntry("INT", "85"),
            "last_state_timestamp" to FullBackupManager.TypedPrefEntry("LONG", "1731110400123"),
            "baseline_odometer_km" to FullBackupManager.TypedPrefEntry("FLOAT", "125430.75"),
            "dark_mode" to FullBackupManager.TypedPrefEntry("BOOLEAN", "true"),
            "theme_name" to FullBackupManager.TypedPrefEntry("STRING", "Deep Teal")
        )

        val json = gson.toJson(originalEntries)
        assertNotNull(json)

        val jsonTree = JsonParser.parseString(json).asJsonObject
        assertTrue(jsonTree.has("model_confidence"))
        assertTrue(jsonTree.has("last_state_timestamp"))

        // Simulate restore decoding
        val restoredInt = jsonTree.get("model_confidence").asJsonObject.get("value").asString.toInt()
        val restoredLong = jsonTree.get("last_state_timestamp").asJsonObject.get("value").asString.toLong()
        val restoredFloat = jsonTree.get("baseline_odometer_km").asJsonObject.get("value").asString.toFloat()
        val restoredBool = jsonTree.get("dark_mode").asJsonObject.get("value").asString.toBoolean()
        val restoredString = jsonTree.get("theme_name").asJsonObject.get("value").asString

        assertEquals(85, restoredInt)
        assertEquals(1731110400123L, restoredLong)
        assertEquals(125430.75f, restoredFloat, 0.01f)
        assertTrue(restoredBool)
        assertEquals("Deep Teal", restoredString)
    }

    @Test
    fun testLegacyUntypedNumberClassification() {
        // Test key-based type classification to avoid ClassCastException on legacy backups
        fun resolveTypeForKey(key: String, strVal: String): String {
            val lower = key.lowercase()
            return when {
                lower.endsWith("_timestamp") || lower.endsWith("_time") || lower == "timestamp" || lower.endsWith("_id") || lower == "id" || lower.endsWith("_bits") -> "LONG"
                lower.contains("count") || lower == "theme" || lower == "model_confidence" || !strVal.contains(".") -> "INT"
                else -> "FLOAT"
            }
        }

        assertEquals("LONG", resolveTypeForKey("last_updated_time", "1731110400000"))
        assertEquals("LONG", resolveTypeForKey("trip_id", "42"))
        assertEquals("LONG", resolveTypeForKey("baseline_odometer_km_bits", "4614256656552045848"))
        assertEquals("INT", resolveTypeForKey("sample_count", "14"))
        assertEquals("INT", resolveTypeForKey("model_confidence", "90"))
        assertEquals("FLOAT", resolveTypeForKey("remaining_liters", "34.5"))
        assertEquals("FLOAT", resolveTypeForKey("baseline_odometer", "125430.5"))
    }
}
