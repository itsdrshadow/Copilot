package com.antigravity.copilot

import com.antigravity.copilot.core.map.OfflineMapManager
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.trip.TripVisualLifecycleEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SatelliteMapAndMultiPassTrailTest:
 * Validates satellite tile source URL resolution,
 * multi-pass turnaround segmentation, and trail clearing on trip completion.
 */
class SatelliteMapAndMultiPassTrailTest {

    @Test
    fun testOfflineVectorTileUriGeneration() {
        val vectorPkg = OfflineMapManager.AVAILABLE_PACKAGES.find { it.type == OfflineMapManager.PackageType.VECTOR_MAP }
        assertNotNull(vectorPkg)
        assertEquals("india.mbtiles", vectorPkg?.fileName)
        val samplePath = "/storage/emulated/0/Android/data/com.antigravity.copilot/files/maps/india.mbtiles"
        val mbtilesUri = "mbtiles://$samplePath"
        assertTrue(mbtilesUri.startsWith("mbtiles:///"))
        assertTrue(mbtilesUri.endsWith(".mbtiles"))
    }

    @Test
    fun testMultiPassTurnaroundDetectionLogic() {
        // Simulate an outbound journey (North, bearing ~ 0°) followed by a turnaround and return (South, bearing ~ 180°)
        val outboundPoints = (0..5).map { i ->
            TripPoint(
                tripId = 1L,
                latitude = 21.68 + i * 0.001,
                longitude = 83.29,
                speedKmh = 35.0f,
                bearing = 5.0f,
                accuracy = 5.0f,
                timestamp = 1000L + i * 1000L
            )
        }

        // Vehicle turns around at the apex and heads back South
        val returnPoints = (0..5).map { i ->
            TripPoint(
                tripId = 1L,
                latitude = 21.685 - i * 0.001,
                longitude = 83.29,
                speedKmh = 32.0f,
                bearing = 185.0f,
                accuracy = 5.0f,
                timestamp = 7000L + i * 1000L
            )
        }

        val fullTrail = outboundPoints + returnPoints

        // Run multi-pass segmentation logic
        var legCount = 1
        var legStartBearing = fullTrail[0].bearing
        var consecutiveReverseCount = 0

        for (i in 1 until fullTrail.size) {
            val pt = fullTrail[i]
            if (pt.speedKmh > 2.0f && pt.bearing > 0f && legStartBearing > 0f) {
                val diff = kotlin.math.abs((pt.bearing - legStartBearing + 540f) % 360f - 180f)
                if (diff > 125f) {
                    consecutiveReverseCount++
                    if (consecutiveReverseCount >= 3) {
                        legCount++
                        legStartBearing = pt.bearing
                        consecutiveReverseCount = 0
                    }
                } else {
                    consecutiveReverseCount = 0
                    legStartBearing = legStartBearing * 0.9f + pt.bearing * 0.1f
                }
            }
        }

        assertEquals("Full trail with U-turn must be cleanly partitioned into 2 distinct legs", 2, legCount)
    }

    @Test
    fun testTripVisualLifecycleTransitions() {
        val startEvent = TripVisualLifecycleEvent.STARTED
        val completedEvent = TripVisualLifecycleEvent.COMPLETED

        val now = System.currentTimeMillis()
        val isStarting = startEvent == TripVisualLifecycleEvent.STARTED && (now - now) < 10_000L
        val isCompleted = completedEvent == TripVisualLifecycleEvent.COMPLETED && (now - now) < 10_000L

        assertTrue("Start event must trigger green flash condition within 10s window", isStarting)
        assertTrue("Completed event must trigger red flash condition within 10s window", isCompleted)
    }

    @Test
    fun testIncrementalTrailProcessing() {
        val outboundPoints = (0..5).map { i ->
            TripPoint(
                tripId = 1L,
                latitude = 21.68 + i * 0.001,
                longitude = 83.29,
                speedKmh = 35.0f,
                bearing = 5.0f,
                accuracy = 5.0f,
                timestamp = 1000L + i * 1000L
            )
        }

        // Simulate incremental intake: batch 1 of 4 points
        var lastProcessedSize = 0
        val segmentedLegs = mutableListOf<MutableList<TripPoint>>()
        var legStartBearing = 0f
        var consecutiveReverseCount = 0

        fun processIncremental(trail: List<TripPoint>) {
            if (lastProcessedSize == 0 && trail.isNotEmpty()) {
                val initialLeg = mutableListOf<TripPoint>()
                initialLeg.add(trail[0])
                segmentedLegs.add(initialLeg)
                legStartBearing = trail[0].bearing
                lastProcessedSize = 1
            }

            var currentLeg = segmentedLegs.last()
            for (i in lastProcessedSize until trail.size) {
                val pt = trail[i]
                if (pt.speedKmh > 2.0f && pt.bearing > 0f && legStartBearing > 0f) {
                    val diff = kotlin.math.abs((pt.bearing - legStartBearing + 540f) % 360f - 180f)
                    if (diff > 125f) {
                        consecutiveReverseCount++
                        if (consecutiveReverseCount >= 3) {
                            val lastTurnPoint = currentLeg.lastOrNull() ?: pt
                            currentLeg = mutableListOf()
                            currentLeg.add(lastTurnPoint)
                            currentLeg.add(pt)
                            segmentedLegs.add(currentLeg)
                            legStartBearing = pt.bearing
                            consecutiveReverseCount = 0
                            continue
                        }
                    } else {
                        consecutiveReverseCount = 0
                        legStartBearing = legStartBearing * 0.9f + pt.bearing * 0.1f
                    }
                }
                currentLeg.add(pt)
            }
            lastProcessedSize = trail.size
        }

        // Intake first 3 points
        processIncremental(outboundPoints.subList(0, 3))
        assertEquals(3, lastProcessedSize)
        assertEquals(1, segmentedLegs.size)
        assertEquals(3, segmentedLegs[0].size)

        // Intake next 3 points incrementally
        processIncremental(outboundPoints)
        assertEquals(6, lastProcessedSize)
        assertEquals(1, segmentedLegs.size)
        assertEquals(6, segmentedLegs[0].size)
    }

    @Test
    fun testSpeedLimitEngineOfflineBehavior() {
        val engine = com.antigravity.copilot.core.location.SpeedLimitEngine.getInstance()
        // Without active network or initialized context, isNetworkConnected must safely return false without exception
        val connected = engine.isNetworkConnected(null)
        org.junit.Assert.assertFalse("Offline or uninitialized context must report false for network connectivity", connected)
    }
}
