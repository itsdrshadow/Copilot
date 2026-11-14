package com.antigravity.drivingreport.core.trip

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormatUtils {

    /**
     * Formats duration into an adaptive human-readable string:
     * - < 1 hr: "45m" (or "00:45" if compact live timer)
     * - 1h to 24h: "3h 25m"
     * - >= 24h: "1d 4h 25m", "5d 12h 10m", "10d 3h 45m"
     */
    fun formatAdaptiveDuration(durationMs: Long, isCompactTimer: Boolean = false): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L

        return when {
            days > 0 -> {
                if (isCompactTimer) {
                    "${days}d " + String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    "${days}d ${hours}h ${minutes}m"
                }
            }
            hours > 0 -> {
                if (isCompactTimer) {
                    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
                } else {
                    "${hours}h ${minutes}m"
                }
            }
            else -> {
                if (isCompactTimer) {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                } else {
                    val m = minutes.coerceAtLeast(1)
                    "${m}m"
                }
            }
        }
    }

    /**
     * Formats duration into a live automotive trip timer:
     * - Under 24h: "HH:mm:ss" (e.g. "00:04:12", "01:23:45")
     * - Multi-day (>= 24h): "1d 04h 25m", "2d 12h 10m"
     */
    fun formatLiveTripTimer(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        val seconds = totalSec % 60L

        return if (days > 0) {
            String.format(Locale.US, "%dd %02dh %02dm", days, hours, minutes)
        } else {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        }
    }

    /**
     * Formats duration into a zero-padded automotive long-trip format:
     * Days : Hours : Minutes
     * e.g. "00d : 02h : 35m", "01d : 04h : 25m", "12d : 08h : 10m"
     */
    fun formatTripDaysHoursMinutes(durationMs: Long): String {
        val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
        val days = totalSec / 86400L
        val hours = (totalSec % 86400L) / 3600L
        val minutes = (totalSec % 3600L) / 60L
        return String.format(Locale.US, "%02dd : %02dh : %02dm", days, hours, minutes)
    }

    /**
     * Formats start and end dates into a readable span.
     * If same day: "09 Nov 2026, 10:15 AM - 11:45 AM"
     * If different days: "09 Nov 2026, 10:15 AM -> 12 Nov 2026, 04:30 PM"
     */
    fun formatDateRange(startTime: Long, endTime: Long): String {
        if (startTime <= 0L) return ""
        val actualEnd = if (endTime > 0L) endTime else System.currentTimeMillis()

        val dayFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)

        val startDay = dayFormat.format(Date(startTime))
        val endDay = dayFormat.format(Date(actualEnd))

        return if (startDay == endDay) {
            "$startDay, ${timeFormat.format(Date(startTime))} - ${timeFormat.format(Date(actualEnd))}"
        } else {
            "$startDay, ${timeFormat.format(Date(startTime))} \u2192 $endDay, ${timeFormat.format(Date(actualEnd))}"
        }
    }
}
