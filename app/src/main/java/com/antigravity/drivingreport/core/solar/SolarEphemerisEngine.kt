package com.antigravity.drivingreport.core.solar

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.*

object SolarEphemerisEngine {

    data class SolarTimes(
        val sunriseEpochMs: Long,
        val sunsetEpochMs: Long,
        val isDaytime: Boolean,
        val solarElevationDeg: Double
    )

    /**
     * Exact 100% Offline Astronomical Solar Ephemeris Calculation.
     * Computes sunrise, sunset, and solar elevation angle without internet or external APIs.
     * Based on the standard NOAA Solar Calculator algorithm.
     */
    fun calculateSolarTimes(
        latitude: Double,
        longitude: Double,
        timestampMs: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): SolarTimes {
        // Fallback for invalid un-anchored (0,0) coordinates
        if (latitude == 0.0 && longitude == 0.0) {
            val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMs }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val isDay = hour in 6..18
            return SolarTimes(
                sunriseEpochMs = timestampMs - (hour - 6) * 3600000L,
                sunsetEpochMs = timestampMs + (18 - hour) * 3600000L,
                isDaytime = isDay,
                solarElevationDeg = if (isDay) 45.0 else -30.0
            )
        }

        val cal = Calendar.getInstance(timeZone).apply { timeInMillis = timestampMs }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val timeZoneOffsetHours = timeZone.getOffset(timestampMs) / 3600000.0

        // Julian Day calculation
        val a = (14 - month) / 12
        val y = year + 4800 - a
        val m = month + 12 * a - 3
        var julianDay = day + (153 * m + 2) / 5 + 365 * y + y / 4 - y / 100 + y / 400 - 32045.0
        val julianCentury = (julianDay + (hour + minute / 60.0 + second / 3600.0 - timeZoneOffsetHours) / 24.0 - 2451545.0) / 36525.0

        // NOAA solar position calculations
        val geomMeanLongSun = (280.46646 + julianCentury * (36000.76983 + julianCentury * 0.0003032)) % 360.0
        val geomMeanAnomSun = 357.52911 + julianCentury * (35999.05029 - 0.0001537 * julianCentury)
        val eccentEarthOrbit = 0.016708634 - julianCentury * (0.000042037 + 0.0000001267 * julianCentury)

        val sunEqOfCtr = sin(Math.toRadians(geomMeanAnomSun)) * (1.914602 - julianCentury * (0.004817 + 0.000014 * julianCentury)) +
                sin(Math.toRadians(2 * geomMeanAnomSun)) * (0.019993 - 0.000101 * julianCentury) +
                sin(Math.toRadians(3 * geomMeanAnomSun)) * 0.000289

        val sunTrueLong = geomMeanLongSun + sunEqOfCtr
        val sunAppLong = sunTrueLong - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * julianCentury))
        val meanObliqEcliptic = 23 + (26 + ((21.448 - julianCentury * (46.815 + julianCentury * (0.00059 - julianCentury * 0.001813)))) / 60) / 60
        val obliqCorr = meanObliqEcliptic + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * julianCentury))

        val sunDeclinRad = asin(sin(Math.toRadians(obliqCorr)) * sin(Math.toRadians(sunAppLong)))
        val sunDeclinDeg = Math.toDegrees(sunDeclinRad)

        val varY = tan(Math.toRadians(obliqCorr) / 2).pow(2)
        val eqOfTimeMinutes = 4 * Math.toDegrees(
            varY * sin(2 * Math.toRadians(geomMeanLongSun)) -
                    2 * eccentEarthOrbit * sin(Math.toRadians(geomMeanAnomSun)) +
                    4 * eccentEarthOrbit * varY * sin(Math.toRadians(geomMeanAnomSun)) * cos(2 * Math.toRadians(geomMeanLongSun)) -
                    0.5 * varY.pow(2) * sin(4 * Math.toRadians(geomMeanLongSun)) -
                    1.25 * eccentEarthOrbit.pow(2) * sin(2 * Math.toRadians(geomMeanAnomSun))
        )

        // Hour angle of sunrise / sunset at 90.833 degrees zenith
        val latRad = Math.toRadians(latitude)
        val cosZenith = cos(Math.toRadians(90.833))
        val cosHourAngle = (cosZenith - sin(latRad) * sin(sunDeclinRad)) / (cos(latRad) * cos(sunDeclinRad))

        val haSunriseDeg = if (cosHourAngle > 1.0) {
            0.0 // Polar night (Sun does not rise)
        } else if (cosHourAngle < -1.0) {
            180.0 // Midnight sun (Sun does not set)
        } else {
            Math.toDegrees(acos(cosHourAngle))
        }

        val solarNoonMinutes = (720 - 4 * longitude - eqOfTimeMinutes + timeZoneOffsetHours * 60) % 1440
        val sunriseMinutes = solarNoonMinutes - haSunriseDeg * 4
        val sunsetMinutes = solarNoonMinutes + haSunriseDeg * 4

        // Midnight base of today in epoch ms
        val midnightCal = Calendar.getInstance(timeZone).apply {
            timeInMillis = timestampMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val midnightEpochMs = midnightCal.timeInMillis

        val sunriseEpochMs = midnightEpochMs + (sunriseMinutes * 60000L).toLong()
        val sunsetEpochMs = midnightEpochMs + (sunsetMinutes * 60000L).toLong()

        // Current solar elevation calculation
        val currentMinutesFromMidnight = hour * 60 + minute + second / 60.0
        val trueSolarTimeMin = (currentMinutesFromMidnight + (4 * longitude) + eqOfTimeMinutes - (60 * timeZoneOffsetHours)) % 1440
        val hourAngleDeg = if (trueSolarTimeMin / 4 < 0) (trueSolarTimeMin / 4) + 180 else (trueSolarTimeMin / 4) - 180
        val hourAngleRad = Math.toRadians(hourAngleDeg)

        val sinElevation = sin(latRad) * sin(sunDeclinRad) + cos(latRad) * cos(sunDeclinRad) * cos(hourAngleRad)
        val elevationDeg = Math.toDegrees(asin(sinElevation.coerceIn(-1.0, 1.0)))

        val isDaytime = elevationDeg > -0.833

        return SolarTimes(
            sunriseEpochMs = sunriseEpochMs,
            sunsetEpochMs = sunsetEpochMs,
            isDaytime = isDaytime,
            solarElevationDeg = elevationDeg
        )
    }

    fun isDaytime(latitude: Double, longitude: Double, timestampMs: Long = System.currentTimeMillis()): Boolean {
        return calculateSolarTimes(latitude, longitude, timestampMs).isDaytime
    }
}
