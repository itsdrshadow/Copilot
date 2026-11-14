package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.trip.TimeFormatUtils
import org.junit.Assert.*
import org.junit.Test

class MultiDayDurationAlignmentTest {

    @Test
    fun testMultiDayDurationReconciliation() {
        val elapsedMs = (48 * 3600 + 8 * 3600 + 15 * 60) * 1000L // 56h 15m (2d 08h 15m)

        val drivingMs = (9 * 3600) * 1000L // 9h 00m
        val idleMs = (30 * 60) * 1000L // 30m
        val activeMs = drivingMs + idleMs // 9h 30m
        val parkedMs = elapsedMs - activeMs // 46h 45m

        // 1. CurrentTripCard formats
        val liveTimerText = TimeFormatUtils.formatLiveTripTimer(elapsedMs)
        assertEquals("2d 08h 15m", liveTimerText)

        val drivingText = TimeFormatUtils.formatAdaptiveDuration(drivingMs)
        assertEquals("9h 0m", drivingText)

        // 2. TripHistoryScreen harmonized durationText
        val wallText = TimeFormatUtils.formatAdaptiveDuration(elapsedMs)
        val activeText = TimeFormatUtils.formatAdaptiveDuration(activeMs)
        val harmonizedCardBadge = "$wallText ($activeText active)"
        assertEquals("2d 8h 15m (9h 30m active)", harmonizedCardBadge)

        // 3. Engine-Off Parked duration
        val parkedText = TimeFormatUtils.formatAdaptiveDuration(parkedMs)
        assertEquals("1d 22h 45m", parkedText)
    }

    @Test
    fun testSingleDayTripFormatting() {
        val singleDayMs = (45 * 60) * 1000L // 45m
        val text = TimeFormatUtils.formatAdaptiveDuration(singleDayMs)
        assertEquals("45m", text)
    }
}
