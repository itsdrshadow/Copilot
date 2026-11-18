package com.antigravity.copilot

import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.logic.LatLon
import com.antigravity.copilot.core.logic.TripPointRetention
import com.antigravity.copilot.core.persistence.FullBackupManager
import com.antigravity.copilot.core.persistence.LegacyPrefTyping
import com.antigravity.copilot.core.recovery.ResetConfirmation
import com.antigravity.copilot.core.trip.TripNaming
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Batch 3 audit fixes: schema version (M2), point retention (M3), legacy backup fuel rows,
 * typed prefs restore (N4), reset confirmation (N5), trip naming fallback (D3).
 */
class Batch3PersistenceTest {

    @Test
    fun m2_backupSchemaVersion_followsDatabaseVersion() {
        assertEquals(AppDatabase.DB_VERSION, AppDatabase.DATABASE_SCHEMA_VERSION)
        assertEquals(AppDatabase.DB_VERSION, FullBackupManager.DATABASE_SCHEMA_VERSION)
        assertEquals(17, AppDatabase.DB_VERSION)
    }

    @Test
    fun m3_retention_keepsShapeAndStops_dropsStraightLineNoise() {
        // 1 Hz straight road (200 points, ~10 m apart) with a 5 min stop in the middle.
        val pts = ArrayList<LatLon>()
        val ts = ArrayList<Long>()
        var t = 0L
        for (i in 0 until 200) {
            pts += LatLon(21.9 + i * 10.0 / 111_195.0, 83.4)
            ts += t
            t += if (i == 100) 300_000L else 1000L
        }
        val keep = TripPointRetention.keepIndices(pts, ts)
        assertTrue("first / last kept", keep.first() == 0 && keep.last() == 199)
        assertTrue("stop borders kept", 100 in keep && 101 in keep)
        assertTrue("straight-line points dropped: ${keep.size}", keep.size <= 6)
        assertEquals(listOf(0, 1), TripPointRetention.keepIndices(pts.take(2), ts.take(2)))
    }

    @Test
    fun legacyBackupFuelRows_getNotRecordedDefaults() {
        val legacy = """[{"id":1,"eventType":"REFUEL","liters":10.0},{"id":2,"eventType":"REFUEL","liters":5.0,"fullTank":1,"gaugePoint":7.5}]"""
        val arr = JsonParser.parseString(FullBackupManager.withLegacyFuelDefaults(legacy)).asJsonArray
        assertEquals(-1, arr[0].asJsonObject.get("fullTank").asInt)
        assertEquals(-1.0, arr[0].asJsonObject.get("gaugePoint").asDouble, 0.0)
        assertEquals(1, arr[1].asJsonObject.get("fullTank").asInt)
        assertEquals(7.5, arr[1].asJsonObject.get("gaugePoint").asDouble, 0.0)
    }

    @Test
    fun n4_legacyPrefs_useExistingType_andNeverTruncateLongs() {
        // Existing Long key without a "_time" style suffix: the old heuristic wrote an Int.
        assertEquals(1_700_000_000_000L, LegacyPrefTyping.coerce("anchor_time_ms", "1700000000000", false, true, 5L))
        assertEquals(48.0f, LegacyPrefTyping.coerce("design_capacity", "48", false, true, 40.0f))
        assertEquals(3, LegacyPrefTyping.coerce("theme", "3", false, true, 1))
        assertNull("does not fit the existing Int", LegacyPrefTyping.coerce("count", "3000000000", false, true, 1))
        // Unknown key: lossless guess.
        assertEquals(3_000_000_000L, LegacyPrefTyping.coerce("big_number", "3000000000", false, true, null))
        assertEquals(12, LegacyPrefTyping.coerce("small", "12", false, true, null))
        assertEquals(12.5f, LegacyPrefTyping.coerce("ratio", "12.5", false, true, null))
        assertEquals(true, LegacyPrefTyping.coerce("flag", "true", true, false, null))
        assertEquals("x", LegacyPrefTyping.coerce("name", "x", false, false, null))
    }

    @Test
    fun n5_reset_needsTypedWordAndThreeSecondHold() {
        assertTrue(ResetConfirmation.isTypedWordValid(" RESET "))
        assertFalse(ResetConfirmation.isTypedWordValid("@Shadow@511"))
        assertFalse(ResetConfirmation.isTypedWordValid("reset"))
        assertFalse(ResetConfirmation.isTypedWordValid(null))
        assertTrue(ResetConfirmation.isHoldLongEnough(1000, 4000))
        assertFalse(ResetConfirmation.isHoldLongEnough(1000, 3999))
    }

    @Test
    fun d3_failedLookup_storesCoordinates_neverInventedPlace() {
        val name = TripNaming.coordinateName(21.90123, 83.40456)
        assertEquals("≈21.9012, 83.4046", name)
        assertTrue(TripNaming.isPlaceholder(name))
        assertTrue(TripNaming.isPlaceholder("Trip Origin"))
        assertFalse(TripNaming.isPlaceholder("Pusaur"))
        assertNull(TripNaming.acceptResolved(""))
        assertNull(TripNaming.acceptResolved("HP Fuel Station"))
        assertEquals("Raigarh", TripNaming.acceptResolved(" Raigarh "))
    }
}
