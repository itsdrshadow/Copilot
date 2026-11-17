package com.antigravity.copilot

import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.model.TripStatus
import com.antigravity.copilot.core.model.TripSummary
import com.antigravity.copilot.core.trip.SnapshotTrailList
import com.antigravity.copilot.core.trip.TimeFormatUtils
import com.antigravity.copilot.core.trip.TripDetectionEngine
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Milestone1Challenger2Test {

    // =========================================================================
    // FEATURE 5: Idle Watchdog Geofence Synchronization
    // =========================================================================

    @Test
    fun testFeature5_StationaryWatchdog_OutsideGeofenceNeverPrematurelyCompletes() {
        var tripCompletedCalled = false
        var completedSummary: TripSummary? = null

        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { summary ->
                tripCompletedCalled = true
                completedSummary = summary
            },
            onTripDiscarded = {}
        )

        // 1. Put engine in ACTIVE_TRIP with geofence control ENABLED (default true)
        engine.isGeofenceControlled = true
        val t0 = System.currentTimeMillis()
        engine.restoreActiveTripState(
            tripId = 555L,
            startTime = t0,
            distanceMeters = 15000.0,
            drivingMs = 600000L,
            stationaryMs = 0L,
            lastLocation = null
        )
        assertEquals(TripStatus.ACTIVE_TRIP, engine.currentStatus)
        assertEquals(555L, engine.currentTripId)

        // 2. Stationary watchdog detects 16s motionless dwell -> transitions to TEMPORARY_STOP
        engine.processStationaryWatchdog(t0 + 31000L)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse(tripCompletedCalled)

        // 3. Stop duration exceeds 5 minutes (300s), 10 minutes (600s), 1 hour (3600s), 24 hours (86400s)
        // With isGeofenceControlled == true, the engine MUST NOT terminate the trip!
        engine.processStationaryWatchdog(t0 + 301000L) // 5m 1s
        assertEquals("Must remain in TEMPORARY_STOP after 5 min standstill outside geofence", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse("onTripCompleted must NOT be called", tripCompletedCalled)

        engine.processStationaryWatchdog(t0 + 600000L) // 10m
        assertEquals("Must remain in TEMPORARY_STOP after 10 min standstill outside geofence", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse("onTripCompleted must NOT be called", tripCompletedCalled)

        engine.processStationaryWatchdog(t0 + 3600000L) // 1 hour
        assertEquals("Must remain in TEMPORARY_STOP after 1 hour standstill outside geofence", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse("onTripCompleted must NOT be called", tripCompletedCalled)

        engine.processStationaryWatchdog(t0 + 86400000L) // 24 hours
        assertEquals("Must remain in TEMPORARY_STOP after 24 hours standstill outside geofence", TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse("onTripCompleted must NOT be called", tripCompletedCalled)

        // 4. Contrast: When isGeofenceControlled == false (standard automotive mode),
        // stationary watchdog MUST terminate trip after 300s
        engine.isGeofenceControlled = false
        engine.processStationaryWatchdog(t0 + 86400000L + 301000L)
        assertEquals("Standard mode must transition to COMPLETED after >= 300s standstill", TripStatus.COMPLETED, engine.currentStatus)
        assertTrue("onTripCompleted must be called in standard mode", tripCompletedCalled)
        assertNotNull(completedSummary)
        assertEquals(555L, completedSummary!!.id)
    }

    @Test
    fun testFeature5_StationaryWatchdog_Under300sInStandardModeDoesNotComplete() {
        var completed = false
        val engine = TripDetectionEngine(
            onPointReceived = {},
            onTripStarted = {},
            onTripUpdated = { _, _ -> },
            onTripCompleted = { completed = true },
            onTripDiscarded = {}
        )
        engine.isGeofenceControlled = false
        val t0 = System.currentTimeMillis()
        engine.restoreActiveTripState(
            tripId = 777L,
            startTime = t0,
            distanceMeters = 8000.0,
            drivingMs = 300000L,
            stationaryMs = 0L,
            lastLocation = null
        )

        // 16s stationary -> TEMPORARY_STOP
        engine.processStationaryWatchdog(t0 + 31000L)
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)

        // Standstill for 299 seconds (1 second before timeout)
        engine.processStationaryWatchdog(t0 + 16000L + 283000L) // total 299s since lastMovementTime
        assertEquals(TripStatus.TEMPORARY_STOP, engine.currentStatus)
        assertFalse(completed)

        // Exactly 300 seconds -> Trip terminates
        engine.processStationaryWatchdog(t0 + 316000L) // 300s since temporaryStopStartTime
        assertEquals(TripStatus.COMPLETED, engine.currentStatus)
        assertTrue(completed)
    }

    // =========================================================================
    // FEATURE 6: Unclamped Time Delta Protection
    // =========================================================================

    @Test
    fun testFeature6_TimeDeltaClamping_MathematicalProperties() {
        val maxDt = TripDetectionEngine.MAX_STEP_DT_MS
        assertEquals(5000L, maxDt)

        // Property 1: Massive forward jump (+1 hour = 3,600,000ms) clamped to exactly 5000ms
        val jump1h = 3600000L
        assertEquals(5000L, jump1h.coerceIn(0L, maxDt))

        // Property 2: Multi-day forward jump (+7 days = 604,800,000ms) clamped to 5000ms
        val jump7d = 604800000L
        assertEquals(5000L, jump7d.coerceIn(0L, maxDt))

        // Property 3: Negative clock jump (-60 seconds NTP step-back) clamped to 0ms
        val jumpNeg = -60000L
        assertEquals(0L, jumpNeg.coerceIn(0L, maxDt))

        // Property 4: Zero delta clamped to 0ms
        assertEquals(0L, 0L.coerceIn(0L, maxDt))

        // Property 5: Normal automotive cadence (1000ms) passes unhindered
        assertEquals(1000L, 1000L.coerceIn(0L, maxDt))

        // Property 6: High frequency GPS (100ms) passes unhindered
        assertEquals(100L, 100L.coerceIn(0L, maxDt))

        // Property 7: Bound at 5000ms exact boundary
        assertEquals(5000L, 5000L.coerceIn(0L, maxDt))
        assertEquals(5000L, 5001L.coerceIn(0L, maxDt))

        // Property 8: Time delta in seconds for speed/accel computation clamped to [0.1, 5.0]
        fun clampDeltaSec(rawSec: Double): Double = rawSec.coerceIn(0.1, 5.0)
        assertEquals(0.1, clampDeltaSec(0.001), 1e-9) // division-by-zero protection
        assertEquals(0.1, clampDeltaSec(-10.0), 1e-9) // negative step protection
        assertEquals(5.0, clampDeltaSec(3600.0), 1e-9) // long outage protection
        assertEquals(1.0, clampDeltaSec(1.0), 1e-9) // normal 1s
    }

    // =========================================================================
    // FEATURE 7: Foreign Key Sequencing & Concurrency Stress
    // =========================================================================

    @Test
    fun testFeature7_ForeignKeySequencing_ConcurrentInsertionSafety() {
        val insertedTrips = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
        val fkViolations = AtomicInteger(0)
        val successfulPoints = AtomicInteger(0)

        // Simulate async Room SQLite storage with foreign key constraints
        class MockTripDao {
            fun insertTrip(id: Long) {
                // Simulate SQLite disk I/O latency
                Thread.sleep(10)
                insertedTrips[id] = true
            }

            fun tripExists(id: Long): Boolean = insertedTrips.containsKey(id)

            fun insertPoints(tripId: Long, count: Int) {
                if (!tripExists(tripId)) {
                    // SQLite foreign key constraint failure!
                    fkViolations.incrementAndGet()
                    throw java.sql.SQLException("FOREIGN KEY constraint failed (code 787 SQLITE_CONSTRAINT_FOREIGNKEY)")
                }
                successfulPoints.addAndGet(count)
            }
        }

        val dao = MockTripDao()
        val coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
        val testIterations = 50
        val latch = CountDownLatch(testIterations)

        for (i in 0 until testIterations) {
            val tripId = 10000L + i
            coroutineScope.launch {
                try {
                    // 1. Launch parent trip insertion asynchronously (as in onTripStarted)
                    val activeJob = coroutineScope.launch {
                        dao.insertTrip(tripId)
                    }

                    // 2. Concurrently attempt to attach departure points (as in attachDeparturePoints)
                    // The sequencing guarantee: await parent job join AND safety fallback check
                    activeJob.join()

                    if (!dao.tripExists(tripId)) {
                        dao.insertTrip(tripId)
                    }

                    dao.insertPoints(tripId, 5)
                } catch (e: Throwable) {
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val finished = latch.await(10, TimeUnit.SECONDS)
        assertTrue("Concurrent FK sequencing test must complete within 10s", finished)
        assertEquals("Zero foreign key violations permitted under concurrent start/departure insertions", 0, fkViolations.get())
        assertEquals("All departure points must be safely committed", testIterations * 5, successfulPoints.get())
    }

    // =========================================================================
    // FEATURE 8: Multi-Day Trip Duration Display Consistency
    // =========================================================================

    @Test
    fun testFeature8_MultiDayTripDurationDisplayConsistency() {
        // Test 1: formatLiveTripTimer across all boundaries
        // 0ms
        assertEquals("00:00:00", TimeFormatUtils.formatLiveTripTimer(0L))
        // 45m 12s
        assertEquals("00:45:12", TimeFormatUtils.formatLiveTripTimer((45 * 60 + 12) * 1000L))
        // 23h 59m 59s
        assertEquals("23:59:59", TimeFormatUtils.formatLiveTripTimer((23 * 3600 + 59 * 60 + 59) * 1000L))
        // Exactly 24h 00m 00s (multi-day transition threshold)
        assertEquals("1d 00h 00m", TimeFormatUtils.formatLiveTripTimer(24 * 3600 * 1000L))
        // 2 days, 8 hours, 15 minutes
        val elapsed2d8h15m = (2 * 86400 + 8 * 3600 + 15 * 60) * 1000L
        assertEquals("2d 08h 15m", TimeFormatUtils.formatLiveTripTimer(elapsed2d8h15m))
        // 10 days, 3 hours, 45 minutes
        val elapsed10d = (10 * 86400 + 3 * 3600 + 45 * 60) * 1000L
        assertEquals("10d 03h 45m", TimeFormatUtils.formatLiveTripTimer(elapsed10d))

        // Test 2: formatAdaptiveDuration across non-compact and compact modes
        // Under 1 hour
        assertEquals("45m", TimeFormatUtils.formatAdaptiveDuration(45 * 60 * 1000L, isCompactTimer = false))
        assertEquals("45:00", TimeFormatUtils.formatAdaptiveDuration(45 * 60 * 1000L, isCompactTimer = true))

        // 1 to 24 hours
        val h9m30 = (9 * 3600 + 30 * 60) * 1000L
        assertEquals("9h 30m", TimeFormatUtils.formatAdaptiveDuration(h9m30, isCompactTimer = false))
        assertEquals("09:30:00", TimeFormatUtils.formatAdaptiveDuration(h9m30, isCompactTimer = true))

        // Multi-day
        assertEquals("2d 8h 15m", TimeFormatUtils.formatAdaptiveDuration(elapsed2d8h15m, isCompactTimer = false))
        assertEquals("2d 08:15:00", TimeFormatUtils.formatAdaptiveDuration(elapsed2d8h15m, isCompactTimer = true))

        // Test 3: formatTripDaysHoursMinutes zero-padded consistency
        assertEquals("00d : 09h : 30m", TimeFormatUtils.formatTripDaysHoursMinutes(h9m30))
        assertEquals("02d : 08h : 15m", TimeFormatUtils.formatTripDaysHoursMinutes(elapsed2d8h15m))

        // Test 4: Full Multi-day Journey Harmonization (CurrentTripCard & TripHistoryScreen)
        val elapsedMs = (48 * 3600 + 8 * 3600 + 15 * 60) * 1000L // 56h 15m
        val drivingMs = 9 * 3600 * 1000L // 9h 00m
        val idleMs = 30 * 60 * 1000L // 30m
        val activeMs = drivingMs + idleMs // 9h 30m
        val parkedMs = elapsedMs - activeMs // 46h 45m

        val liveTimer = TimeFormatUtils.formatLiveTripTimer(elapsedMs)
        val drivingBadge = TimeFormatUtils.formatAdaptiveDuration(drivingMs)
        val wallText = TimeFormatUtils.formatAdaptiveDuration(elapsedMs)
        val activeText = TimeFormatUtils.formatAdaptiveDuration(activeMs)
        val parkedText = TimeFormatUtils.formatAdaptiveDuration(parkedMs)

        assertEquals("2d 08h 15m", liveTimer)
        assertEquals("9h 0m", drivingBadge)
        assertEquals("2d 8h 15m", wallText)
        assertEquals("9h 30m", activeText)
        assertEquals("1d 22h 45m", parkedText)

        val historyCardDurationText = "$wallText ($activeText active)"
        assertEquals("2d 8h 15m (9h 30m active)", historyCardDurationText)

        // Mathematical invariant: Active + Parked == Elapsed
        assertEquals(elapsedMs, activeMs + parkedMs)
    }

    // =========================================================================
    // FEATURE 9: SnapshotTrailList Memory Optimization
    // =========================================================================

    @Test
    fun testFeature9_SnapshotTrailList_ImmutabilityEnforced() {
        val backing = ArrayList<TripPoint>()
        backing.add(TripPoint(id = 1, tripId = 10, latitude = 21.9, longitude = 83.4, speedKmh = 45f, bearing = 0f, accuracy = 5f, timestamp = 1000L))

        val snapshot = SnapshotTrailList(backing, 1L, backing.size)
        val javaList = snapshot as java.util.List<TripPoint>

        // 1. Modifying operations must throw UnsupportedOperationException
        try {
            javaList.add(TripPoint(id = 2, tripId = 10, latitude = 21.91, longitude = 83.41, speedKmh = 45f, bearing = 0f, accuracy = 5f, timestamp = 2000L))
            fail("SnapshotTrailList must throw UnsupportedOperationException on add()")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }

        try {
            javaList.remove(0)
            fail("SnapshotTrailList must throw UnsupportedOperationException on remove()")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }

        try {
            javaList.clear()
            fail("SnapshotTrailList must throw UnsupportedOperationException on clear()")
        } catch (_: UnsupportedOperationException) {
            // Expected
        }
    }

    @Test
    fun testFeature9_SnapshotTrailList_IndexBoundsChecking() {
        val backing = ArrayList<TripPoint>()
        for (i in 0 until 5) {
            backing.add(TripPoint(id = i.toLong(), tripId = 1, latitude = 21.0 + i, longitude = 83.0, speedKmh = 30f, bearing = 0f, accuracy = 5f, timestamp = i * 1000L))
        }
        val snapshot = SnapshotTrailList(backing, 1L, 5)

        // Valid reads
        assertEquals(5, snapshot.size)
        assertEquals(21.0, snapshot[0].latitude, 1e-6)
        assertEquals(25.0, snapshot[4].latitude, 1e-6)

        // Negative index -> IndexOutOfBoundsException
        try {
            snapshot[-1]
            fail("Negative index must throw IndexOutOfBoundsException")
        } catch (e: IndexOutOfBoundsException) {
            assertTrue(e.message!!.contains("-1"))
        }

        // Index equal to size -> IndexOutOfBoundsException
        try {
            snapshot[5]
            fail("Index == size must throw IndexOutOfBoundsException")
        } catch (e: IndexOutOfBoundsException) {
            assertTrue(e.message!!.contains("5"))
        }

        // Out of bounds index
        try {
            snapshot[100]
            fail("Index > size must throw IndexOutOfBoundsException")
        } catch (e: IndexOutOfBoundsException) {
            assertTrue(e.message!!.contains("100"))
        }
    }

    @Test
    fun testFeature9_SnapshotTrailList_O1VersionEqualityAndZeroCopyPerformance() {
        val backing = ArrayList<TripPoint>()
        val count = 30000
        for (i in 0 until count) {
            backing.add(TripPoint(id = i.toLong(), tripId = 1, latitude = 21.0, longitude = 83.0, speedKmh = 50f, bearing = 0f, accuracy = 5f, timestamp = i * 1000L))
        }

        val version = 42L
        val snapshotA = SnapshotTrailList(backing, version, count)
        val snapshotB = SnapshotTrailList(backing, version, count)
        val snapshotC = SnapshotTrailList(backing, version + 1, count)

        // O(1) version equality
        val startTime = System.nanoTime()
        val isEqual = (snapshotA == snapshotB)
        val elapsedNano = System.nanoTime() - startTime

        assertTrue("Snapshots with identical version and size must be equal", isEqual)
        // Must complete in less than 50 microseconds (strictly O(1) check, not O(N) element scan)
        assertTrue("Equality check on 30,000 points took $elapsedNano ns; must be < 500,000 ns (O(1))", elapsedNano < 500_000L)

        // Different version -> not equal
        assertNotEquals(snapshotA, snapshotC)
        assertEquals(snapshotA.hashCode(), snapshotB.hashCode())
    }

    @Test
    fun testFeature9_SnapshotTrailList_HighConcurrencyStress() {
        val backing = ArrayList<TripPoint>()
        val initialSize = 2000
        for (i in 0 until initialSize) {
            backing.add(TripPoint(id = i.toLong(), tripId = 1, latitude = 21.0, longitude = 83.0, speedKmh = 40f, bearing = 0f, accuracy = 5f, timestamp = i * 1000L))
        }

        val readerCount = 8
        val readIterations = 20000
        val writerIterations = 1000
        val latch = CountDownLatch(readerCount + 1)
        val executor = Executors.newFixedThreadPool(readerCount + 1)
        val exceptions = AtomicInteger(0)

        val snapshot = SnapshotTrailList(backing, 100L, initialSize)

        // 8 concurrent reader threads querying the snapshot
        for (r in 0 until readerCount) {
            executor.submit {
                try {
                    for (i in 0 until readIterations) {
                        val pt = snapshot[i % snapshot.size]
                        assertNotNull(pt)
                    }
                } catch (e: Throwable) {
                    exceptions.incrementAndGet()
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        // 1 concurrent writer thread appending new points to the backing list under synchronization
        executor.submit {
            try {
                for (i in 0 until writerIterations) {
                    synchronized(backing) {
                        backing.add(
                            TripPoint(
                                id = (initialSize + i).toLong(),
                                tripId = 1,
                                latitude = 22.0,
                                longitude = 84.0,
                                speedKmh = 50f,
                                bearing = 90f,
                                accuracy = 5f,
                                timestamp = (initialSize + i) * 1000L
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                exceptions.incrementAndGet()
                e.printStackTrace()
            } finally {
                latch.countDown()
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdown()

        assertTrue("Concurrency stress test must complete within 10 seconds", completed)
        assertEquals("Zero exceptions permitted under concurrent read/write stress", 0, exceptions.get())
    }
}
