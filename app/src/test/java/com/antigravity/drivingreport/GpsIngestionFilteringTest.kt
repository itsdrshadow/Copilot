package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.map.GpsLocationFilter
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GpsIngestionFilteringTest {

    private lateinit var filter: GpsLocationFilter

    @Before
    fun setup() {
        filter = GpsLocationFilter()
    }

    @Test
    fun testStationaryJitterRejection() {
        // Initial anchor: Stoplight at (21.900000, 83.400000)
        val initial = filter.filter(21.900000, 83.400000, 90f, 0f, 5f, 1000L)
        assertEquals(21.900000, initial.latitude, 1e-6)
        assertEquals(83.400000, initial.longitude, 1e-6)
        assertTrue(initial.isStationary)

        // GPS wandering jitter (1.2m displacement, 0 km/h) -> must be frozen
        val jitter1 = filter.filter(21.900010, 83.400005, 120f, 0.4f, 8f, 2000L)
        assertEquals("Stationary jitter < 2.5m must freeze latitude", 21.900000, jitter1.latitude, 1e-6)
        assertEquals("Stationary jitter < 2.5m must freeze longitude", 83.400000, jitter1.longitude, 1e-6)
        assertTrue(jitter1.isStationary)

        // Jitter in other direction (1.8m displacement) -> must still freeze
        val jitter2 = filter.filter(21.899990, 83.399995, 270f, 0.8f, 10f, 3000L)
        assertEquals("Stationary jitter < 2.5m must freeze latitude", 21.900000, jitter2.latitude, 1e-6)
        assertEquals("Stationary jitter < 2.5m must freeze longitude", 83.400000, jitter2.longitude, 1e-6)
    }

    @Test
    fun testIntentionalMotionUnfreezesAnchor() {
        filter.filter(21.900000, 83.400000, 90f, 0f, 5f, 1000L)

        // Vehicle moves 15m at 25 km/h
        val moving = filter.filter(21.900135, 83.400000, 90f, 25f, 4f, 2000L)
        assertFalse(moving.isStationary)
        assertTrue("Vehicle movement must update latitude", moving.latitude > 21.900000)
        assertEquals(25f, moving.speedKmh, 0.1f)
    }

    @Test
    fun testMultipathSpikeRejection() {
        filter.filter(21.900000, 83.400000, 90f, 40f, 5f, 1000L)

        // Multipath jump: 500 meters in 1 second (= 1800 km/h) with reported high accuracy (5m)
        val spike = filter.filter(21.904500, 83.400000, 90f, 40f, 5f, 2000L)
        assertEquals("Multipath spike (>240 km/h) must be discarded unconditionally", 21.900000, spike.latitude, 1e-6)
    }

    @Test
    fun testSignalOutageRecoverySnapsDirectly() {
        // Vehicle enters tunnel at (21.900000, 83.400000)
        filter.filter(21.900000, 83.400000, 90f, 60f, 5f, 10000L)

        // Tunnel exit 30s later at (21.905000, 83.400000) with 8m accuracy
        val exit = filter.filter(21.905000, 83.400000, 90f, 60f, 8f, 40000L)
        assertEquals("Outage recovery must snap directly without low-pass lag", 21.905000, exit.latitude, 1e-4)
    }
}
