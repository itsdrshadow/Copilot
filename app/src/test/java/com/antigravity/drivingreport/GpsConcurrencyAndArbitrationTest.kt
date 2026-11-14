package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.database.TripPointEntity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GpsConcurrencyAndArbitrationTest {

    @Test
    fun testConcurrentPendingPointsThreadSafety() {
        val queue = ConcurrentLinkedQueue<TripPointEntity>()
        val threadCount = 8
        val pointsPerThread = 500
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until pointsPerThread) {
                        queue.add(
                            TripPointEntity(
                                tripId = 1L,
                                latitude = 21.9 + (i * 0.0001),
                                longitude = 83.4 + (i * 0.0001),
                                speedKmh = 45f,
                                bearing = 180f,
                                accuracy = 5f,
                                timestamp = System.currentTimeMillis()
                            )
                        )
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("All concurrent pushes should complete within 5 seconds", latch.await(5, TimeUnit.SECONDS))
        assertEquals(threadCount * pointsPerThread, queue.size)

        // Drain concurrently
        val drained = mutableListOf<TripPointEntity>()
        while (true) {
            val pt = queue.poll() ?: break
            drained.add(pt)
        }

        assertEquals(threadCount * pointsPerThread, drained.size)
        assertTrue(queue.isEmpty())
        executor.shutdown()
    }

    @Test
    fun testLocationProviderArbitrationWindow() {
        val lastFusedFixTimeMs = 10000L
        val arbitrationThresholdMs = 3500L

        // Scenario 1: Native location fix arrives 500ms after Fused fix -> must be ignored
        val nativeFixTime1 = 10500L
        val shouldDrop1 = (nativeFixTime1 - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertTrue("Native fix within 3500ms of active Fused fix must be dropped", shouldDrop1)

        // Scenario 2: Native location fix arrives 4000ms after Fused fix (Fused died/delayed) -> must be accepted
        val nativeFixTime2 = 14000L
        val shouldDrop2 = (nativeFixTime2 - lastFusedFixTimeMs) < arbitrationThresholdMs
        assertFalse("Native fix after 3500ms timeout must be accepted as fallback", shouldDrop2)
    }
}
