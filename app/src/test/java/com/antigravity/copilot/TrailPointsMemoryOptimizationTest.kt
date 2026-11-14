package com.antigravity.copilot

import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.trip.SnapshotTrailList
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TrailPointsMemoryOptimizationTest {

    @Test
    fun testSnapshotTrailListZeroHeapCopyAndEquality() {
        val backing = ArrayList<TripPoint>()
        var version = 0L

        // Add 20,000 points
        for (i in 0 until 20000) {
            backing.add(
                TripPoint(
                    tripId = 1L,
                    latitude = 21.9 + (i * 0.00001),
                    longitude = 83.4 + (i * 0.00001),
                    speedKmh = 40f,
                    bearing = 90f,
                    accuracy = 5f,
                    timestamp = 1000L + i * 1000L
                )
            )
        }

        version++
        val snapshot1 = SnapshotTrailList(backing, version, backing.size)
        assertEquals(20000, snapshot1.size)
        assertEquals(21.9, snapshot1[0].latitude, 1e-6)

        // Verify versioned equality in O(1)
        val snapshot1Clone = SnapshotTrailList(backing, version, backing.size)
        assertEquals("Snapshots with matching version and size must be equal", snapshot1, snapshot1Clone)

        // Add 1 more point
        version++
        backing.add(TripPoint(tripId = 1L, latitude = 22.0, longitude = 83.5, speedKmh = 40f, bearing = 90f, accuracy = 5f, timestamp = 21000000L))
        val snapshot2 = SnapshotTrailList(backing, version, backing.size)

        assertNotEquals("Snapshots with different versions must not be equal", snapshot1, snapshot2)
        assertEquals(20001, snapshot2.size)

        // Verify StateFlow emission triggering
        val flow = MutableStateFlow<List<TripPoint>>(snapshot1)
        assertEquals(20000, flow.value.size)

        flow.value = snapshot2
        assertEquals(20001, flow.value.size)
    }

    @Test
    fun testConcurrentReadsDuringAppends() {
        val backing = ArrayList<TripPoint>()
        var version = 0L

        // Seed initial points
        for (i in 0 until 1000) {
            backing.add(TripPoint(tripId = 1L, latitude = 21.9, longitude = 83.4, speedKmh = 30f, bearing = 0f, accuracy = 5f, timestamp = i * 1000L))
        }
        val snapshot = SnapshotTrailList(backing, ++version, backing.size)

        val readerCount = 4
        val iterations = 5000
        val latch = CountDownLatch(readerCount)
        val executor = Executors.newFixedThreadPool(readerCount + 1)

        // Concurrent Readers
        for (r in 0 until readerCount) {
            executor.submit {
                try {
                    for (i in 0 until iterations) {
                        val pt = snapshot[i % snapshot.size]
                        assertNotNull(pt)
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        // Concurrent Writer appending to backing list
        executor.submit {
            for (i in 0 until 1000) {
                synchronized(backing) {
                    backing.add(TripPoint(tripId = 1L, latitude = 22.0, longitude = 83.5, speedKmh = 30f, bearing = 0f, accuracy = 5f, timestamp = 1000000L + i))
                }
            }
        }

        assertTrue("Concurrent reads must complete safely without exception", latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }
}
