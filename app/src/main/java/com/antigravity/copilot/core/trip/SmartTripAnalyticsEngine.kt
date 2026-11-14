package com.antigravity.copilot.core.trip

import com.antigravity.copilot.core.database.FuelLedgerEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.database.TripPointEntity
import com.antigravity.copilot.core.database.VehicleStateTransitionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class SmartTripReport(
    val tripId: Long,
    val overview: TripOverviewData,
    val drivingStats: DrivingStatsData,
    val fuelReport: FuelReportData,
    val aiInsights: List<AiInsightItem>,
    val timelineEvents: List<TripTimelineItem>,
    val points: List<TripPointEntity>
)

data class TripOverviewData(
    val distanceKm: Double,
    val distanceMeters: Double,
    val durationFormatted: String,
    val timeRangeFormatted: String,
    val startLocation: String,
    val endLocation: String,
    val avgSpeedKmh: Double,
    val maxSpeedKmh: Double,
    val drivingDurationMs: Long,
    val stationaryDurationMs: Long,
    val stopCount: Int,
    val departureTimeFormatted: String,
    val arrivalTimeFormatted: String
)

data class DrivingStatsData(
    val movingPercentage: Int,
    val stoppedPercentage: Int,
    val stops: List<TripStopInfo>,
    val intervalDistances: List<IntervalDistanceItem>,
    val speedBands: List<SpeedBandData>,
    val elevationGainMeters: Double?,
    val elevationLossMeters: Double?,
    val isElevationAvailable: Boolean,
    val gpsQuality: GpsQualityData
)

data class TripStopInfo(
    val timestamp: Long,
    val durationMs: Long,
    val latitude: Double,
    val longitude: Double,
    val timeFormatted: String
)

data class IntervalDistanceItem(
    val intervalLabel: String,
    val distanceKm: Double,
    val avgSpeedKmh: Double
)

data class SpeedBandData(
    val label: String,
    val percentage: Int,
    val speedRangeKmh: String,
    val durationMs: Long
)

data class GpsQualityData(
    val totalFixes: Int,
    val highAccuracyPercentage: Int,
    val avgAccuracyMeters: Float,
    val hasMissingSegments: Boolean,
    val qualityRating: String
)

data class FuelReportData(
    val refuelEvents: List<RefuelEventItem>,
    val hasRefuel: Boolean,
    val estimatedFuelConsumedLiters: Double,
    val calculatedMileageKmpl: Double,
    val estimatedTripCost: Double,
    val isSensorMeasured: Boolean,
    val measurementMethodLabel: String,
    val startFuelLiters: Double?,
    val endFuelLiters: Double?
)

data class RefuelEventItem(
    val timestamp: Long,
    val timeFormatted: String,
    val liters: Double,
    val totalCost: Double,
    val pricePerLiter: Double,
    val locationName: String,
    val fuelType: String
)

data class AiInsightItem(
    val title: String,
    val description: String,
    val category: InsightCategory,
    val badgeLabel: String,
    val isPositive: Boolean
)

enum class InsightCategory {
    EFFICIENCY,
    SPEED,
    STATIONARY,
    HISTORICAL,
    TERRAIN,
    RECOMMENDATION
}

sealed class TripTimelineItem(
    val timestamp: Long,
    val title: String,
    val subtitle: String,
    val type: TimelineType
) {
    class TripStart(time: Long, origin: String) :
        TripTimelineItem(time, "Trip Commenced", origin.ifBlank { "Starting point" }, TimelineType.START)

    class MotionStarted(time: Long, speedKmh: Float) :
        TripTimelineItem(time, "Vehicle Motion Initiated", "Reached %.1f km/h".format(Locale.US, speedKmh), TimelineType.MOTION)

    class VehicleStop(time: Long, durationMs: Long, location: String) :
        TripTimelineItem(
            time,
            "Stationary Stop (%s)".format(formatDuration(durationMs)),
            location.ifBlank { "Vehicle parked / idling" },
            TimelineType.STOP
        )

    class RefuelRecorded(time: Long, liters: Double, cost: Double, station: String) :
        TripTimelineItem(
            time,
            "Refuelled %.1f L (â‚¹%.0f)".format(Locale.US, liters, cost),
            station.ifBlank { "Fuel Station" },
            TimelineType.REFUEL
        )

    class PeakSpeedRecorded(time: Long, speedKmh: Float) :
        TripTimelineItem(
            time,
            "Peak Velocity Reached",
            "%.1f km/h".format(Locale.US, speedKmh),
            TimelineType.PEAK_SPEED
        )

    class TripCompleted(time: Long, destination: String, totalKm: Double) :
        TripTimelineItem(
            time,
            "Trip Concluded",
            "%.2f km at %s".format(Locale.US, totalKm, destination.ifBlank { "Final destination" }),
            TimelineType.COMPLETE
        )
}

enum class TimelineType {
    START,
    MOTION,
    STOP,
    REFUEL,
    PEAK_SPEED,
    COMPLETE
}

private fun formatDuration(ms: Long): String {
    val sec = ms / 1000
    val min = sec / 60
    val hr = min / 60
    return when {
        hr > 0 -> "%dh %dm".format(hr, min % 60)
        min > 0 -> "%dm %ds".format(min, sec % 60)
        else -> "%ds".format(sec)
    }
}

object SmartTripAnalyticsEngine {

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.US)
    private val dateTimeFormat = SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.US)

    fun generateReport(
        trip: TripEntity,
        points: List<TripPointEntity>,
        fuelEvents: List<FuelLedgerEntity> = emptyList(),
        transitions: List<VehicleStateTransitionEntity> = emptyList(),
        historicalTrips: List<TripEntity> = emptyList(),
        fuelPricePerLiter: Double = 96.5
    ): SmartTripReport {
        val distanceKm = trip.distanceMeters / 1000.0
        val startTime = trip.startTime
        val endTime = if (trip.endTime > startTime) trip.endTime else (startTime + trip.drivingDurationMs + trip.stationaryDurationMs)
        val totalWallClockMs = max(endTime - startTime, trip.drivingDurationMs + trip.stationaryDurationMs).coerceAtLeast(1000L)

        // 1. Overview
        val startLoc = trip.startLocationName.ifBlank { "Trip Origin" }
        val endLoc = trip.endLocationName.ifBlank { "Trip Destination" }
        val startFormatted = timeFormat.format(Date(startTime))
        val endFormatted = timeFormat.format(Date(endTime))

        val stops = detectStops(points)

        val overview = TripOverviewData(
            distanceKm = distanceKm,
            distanceMeters = trip.distanceMeters,
            durationFormatted = formatDuration(totalWallClockMs),
            timeRangeFormatted = "$startFormatted - $endFormatted",
            startLocation = startLoc,
            endLocation = endLoc,
            avgSpeedKmh = trip.avgSpeedKmh,
            maxSpeedKmh = trip.maxSpeedKmh,
            drivingDurationMs = trip.drivingDurationMs,
            stationaryDurationMs = trip.stationaryDurationMs,
            stopCount = stops.size,
            departureTimeFormatted = startFormatted,
            arrivalTimeFormatted = endFormatted
        )

        // 2. Driving Statistics
        val movingPct = ((trip.drivingDurationMs.toDouble() / totalWallClockMs) * 100).roundToInt().coerceIn(0, 100)
        val stoppedPct = (100 - movingPct).coerceIn(0, 100)
        val intervals = computeIntervalDistances(points, startTime)
        val speedBands = computeSpeedBands(points)
        val (elevGain, elevLoss, isElevAvailable) = computeElevation(points)
        val gpsQuality = computeGpsQuality(points)

        val drivingStats = DrivingStatsData(
            movingPercentage = movingPct,
            stoppedPercentage = stoppedPct,
            stops = stops,
            intervalDistances = intervals,
            speedBands = speedBands,
            elevationGainMeters = elevGain,
            elevationLossMeters = elevLoss,
            isElevationAvailable = isElevAvailable,
            gpsQuality = gpsQuality
        )

        // 3. Fuel Report
        // Match refuelling records that occurred during this trip (with +/- 3 minutes tolerance)
        val matchedRefuels = fuelEvents.filter { fe ->
            val isRefuelType = fe.eventType == "REFUEL" || fe.eventType == "INITIAL_FUEL_BASELINE" || fe.eventType == "INFERRED_REFUEL"
            val inTimeRange = fe.timestamp in (startTime - 180_000L)..(endTime + 180_000L)
            isRefuelType && inTimeRange && fe.fuelQuantityLiters > 0.0
        }.map { fe ->
            RefuelEventItem(
                timestamp = fe.timestamp,
                timeFormatted = timeFormat.format(Date(fe.timestamp)),
                liters = fe.fuelQuantityLiters,
                totalCost = if (fe.totalAmount > 0) fe.totalAmount else fe.fuelQuantityLiters * fuelPricePerLiter,
                pricePerLiter = if (fe.pricePerLiter > 0) fe.pricePerLiter else fuelPricePerLiter,
                locationName = fe.locationName.ifBlank { "Fuel Station" },
                fuelType = "Petrol"
            )
        }

        val effectiveMileage = if (trip.calculatedMileageKmpl > 0) trip.calculatedMileageKmpl else 15.0
        val fuelConsumed = if (trip.estimatedFuelLiters > 0) trip.estimatedFuelLiters else (distanceKm / effectiveMileage)
        val tripCost = fuelConsumed * fuelPricePerLiter

        val fuelReport = FuelReportData(
            refuelEvents = matchedRefuels,
            hasRefuel = matchedRefuels.isNotEmpty(),
            estimatedFuelConsumedLiters = fuelConsumed,
            calculatedMileageKmpl = effectiveMileage,
            estimatedTripCost = tripCost,
            isSensorMeasured = false,
            measurementMethodLabel = "Calculated via Vehicle Telemetry Model (%.1f km/L baseline)".format(Locale.US, effectiveMileage),
            startFuelLiters = null,
            endFuelLiters = null
        )

        // 4. Deterministic AI Insights
        val aiInsights = generateDeterministicInsights(
            trip = trip,
            points = points,
            overview = overview,
            drivingStats = drivingStats,
            fuelReport = fuelReport,
            historicalTrips = historicalTrips
        )

        // 5. Timeline Events
        val timelineEvents = generateTimeline(
            trip = trip,
            points = points,
            stops = stops,
            refuels = matchedRefuels,
            transitions = transitions,
            startTime = startTime,
            endTime = endTime
        )

        return SmartTripReport(
            tripId = trip.id,
            overview = overview,
            drivingStats = drivingStats,
            fuelReport = fuelReport,
            aiInsights = aiInsights,
            timelineEvents = timelineEvents,
            points = points
        )
    }

    private fun detectStops(points: List<TripPointEntity>): List<TripStopInfo> {
        val stops = mutableListOf<TripStopInfo>()
        if (points.size < 2) return stops

        var stopStartPt: TripPointEntity? = null
        for (i in 1 until points.size) {
            val pt = points[i]
            if (pt.speedKmh < 2.5f) {
                if (stopStartPt == null) stopStartPt = pt
            } else {
                if (stopStartPt != null) {
                    val stopDur = pt.timestamp - stopStartPt.timestamp
                    if (stopDur >= 25_000L) { // Stop duration >= 25s
                        stops.add(
                            TripStopInfo(
                                timestamp = stopStartPt.timestamp,
                                durationMs = stopDur,
                                latitude = stopStartPt.latitude,
                                longitude = stopStartPt.longitude,
                                timeFormatted = timeFormat.format(Date(stopStartPt.timestamp))
                            )
                        )
                    }
                    stopStartPt = null
                }
            }
        }
        return stops
    }

    private fun computeIntervalDistances(points: List<TripPointEntity>, startTime: Long): List<IntervalDistanceItem> {
        val items = mutableListOf<IntervalDistanceItem>()
        if (points.size < 2) return items

        val intervalMs = 15 * 60 * 1000L // 15-minute segments
        var bucketIndex = 0
        var bucketStart = startTime
        var bucketDistMeters = 0.0
        var speedSum = 0.0
        var speedCount = 0

        for (i in 1 until points.size) {
            val prev = points[i - 1]
            val curr = points[i]
            val d = TripDetectionEngine.computeGeodesicDistance(prev.latitude, prev.longitude, curr.latitude, curr.longitude)
            bucketDistMeters += d
            if (curr.speedKmh > 2.0f) {
                speedSum += curr.speedKmh
                speedCount++
            }

            if (curr.timestamp - bucketStart >= intervalMs || i == points.size - 1) {
                val label = "Min ${bucketIndex * 15} - ${(bucketIndex + 1) * 15}"
                val avgSpd = if (speedCount > 0) speedSum / speedCount else 0.0
                items.add(
                    IntervalDistanceItem(
                        intervalLabel = label,
                        distanceKm = bucketDistMeters / 1000.0,
                        avgSpeedKmh = avgSpd
                    )
                )
                bucketIndex++
                bucketStart = curr.timestamp
                bucketDistMeters = 0.0
                speedSum = 0.0
                speedCount = 0
            }
        }
        return items
    }

    private fun computeSpeedBands(points: List<TripPointEntity>): List<SpeedBandData> {
        if (points.isEmpty()) {
            return listOf(
                SpeedBandData("Optimal Cruising (40-70)", 60, "40-70 km/h", 0L),
                SpeedBandData("City Speed (0-40)", 30, "0-40 km/h", 0L),
                SpeedBandData("Highway (>70)", 10, ">70 km/h", 0L)
            )
        }

        var cityCount = 0
        var cruiseCount = 0
        var highwayCount = 0
        val total = points.size.toDouble()

        for (p in points) {
            when {
                p.speedKmh < 35.0f -> cityCount++
                p.speedKmh <= 75.0f -> cruiseCount++
                else -> highwayCount++
            }
        }

        return listOf(
            SpeedBandData(
                label = "Optimal Cruising",
                percentage = ((cruiseCount / total) * 100).roundToInt(),
                speedRangeKmh = "35-75 km/h",
                durationMs = 0L
            ),
            SpeedBandData(
                label = "City & Stop-and-Go",
                percentage = ((cityCount / total) * 100).roundToInt(),
                speedRangeKmh = "< 35 km/h",
                durationMs = 0L
            ),
            SpeedBandData(
                label = "Highway & Express",
                percentage = ((highwayCount / total) * 100).roundToInt(),
                speedRangeKmh = "> 75 km/h",
                durationMs = 0L
            )
        )
    }

    private fun computeElevation(points: List<TripPointEntity>): Triple<Double?, Double?, Boolean> {
        val validAltPoints = points.filter { it.altitude > 0.0 && it.accuracy <= 25.0f }
        if (validAltPoints.size < 5) {
            return Triple(null, null, false)
        }

        var gain = 0.0
        var loss = 0.0
        var prevAlt = validAltPoints.first().altitude

        for (i in 1 until validAltPoints.size) {
            val currAlt = validAltPoints[i].altitude
            val diff = currAlt - prevAlt
            // 3.0 meter noise deadband to avoid barometric/GPS jitter false altitude
            if (diff > 3.0) {
                gain += diff
                prevAlt = currAlt
            } else if (diff < -3.0) {
                loss += abs(diff)
                prevAlt = currAlt
            }
        }

        return if (gain > 0.0 || loss > 0.0) {
            Triple(gain, loss, true)
        } else {
            Triple(null, null, false)
        }
    }

    private fun computeGpsQuality(points: List<TripPointEntity>): GpsQualityData {
        if (points.isEmpty()) {
            return GpsQualityData(0, 100, 5.0f, false, "EXCELLENT")
        }

        val accurateCount = points.count { it.accuracy <= 20.0f }
        val highAccPct = ((accurateCount.toDouble() / points.size) * 100).roundToInt().coerceIn(0, 100)
        val avgAcc = (points.map { it.accuracy }.average()).toFloat()

        var hasMissing = false
        for (i in 1 until points.size) {
            val dt = points[i].timestamp - points[i - 1].timestamp
            if (dt > 45_000L) { // Gap > 45 seconds indicates potential signal loss or tunnel
                hasMissing = true
                break
            }
        }

        val rating = when {
            highAccPct >= 85 && avgAcc <= 12f -> "EXCELLENT"
            highAccPct >= 65 -> "GOOD"
            else -> "FAIR"
        }

        return GpsQualityData(
            totalFixes = points.size,
            highAccuracyPercentage = highAccPct,
            avgAccuracyMeters = avgAcc,
            hasMissingSegments = hasMissing,
            qualityRating = rating
        )
    }

    private fun generateDeterministicInsights(
        trip: TripEntity,
        @Suppress("UNUSED_PARAMETER") points: List<TripPointEntity>,
        overview: TripOverviewData,
        drivingStats: DrivingStatsData,
        fuelReport: FuelReportData,
        historicalTrips: List<TripEntity>
    ): List<AiInsightItem> {
        val insights = mutableListOf<AiInsightItem>()

        // 1. Driver Performance & Smoothness Score
        val score = trip.driverScore.coerceIn(50, 100)
        val scoreGrade = when {
            score >= 90 -> "EXCELLENT (Score $score/100)"
            score >= 75 -> "GOOD (Score $score/100)"
            score >= 60 -> "MODERATE (Score $score/100)"
            else -> "ATTENTION NEEDED (Score $score/100)"
        }
        insights.add(
            AiInsightItem(
                title = "Driver Performance Score",
                description = "Automotive telemetry rating: $scoreGrade. Transparent assessment based on smooth acceleration, controlled braking, and speed discipline.",
                category = InsightCategory.EFFICIENCY,
                badgeLabel = "$score/100",
                isPositive = score >= 75
            )
        )

        // 2. Cruising Efficiency
        val cruiseBand = drivingStats.speedBands.find { it.label == "Optimal Cruising" }
        val cruisePct = cruiseBand?.percentage ?: 50
        if (cruisePct >= 40) {
            insights.add(
                AiInsightItem(
                    title = "Optimal Cruising Window",
                    description = "$cruisePct% of driving time was spent in the vehicle's thermal efficiency sweet spot (35-75 km/h), maximizing fuel mileage.",
                    category = InsightCategory.EFFICIENCY,
                    badgeLabel = "$cruisePct% Optimal",
                    isPositive = true
                )
            )
        } else {
            insights.add(
                AiInsightItem(
                    title = "Speed Consistency Observation",
                    description = "Only $cruisePct% spent in steady cruising. Frequent speed fluctuations or city stop-and-go reduced overall momentum.",
                    category = InsightCategory.SPEED,
                    badgeLabel = "Fluctuating Pace",
                    isPositive = false
                )
            )
        }

        // 3. Stationary Idling Impact
        val idleMs = trip.stationaryDurationMs
        val idleMin = idleMs / 60000L
        if (idleMin >= 3) {
            // Approx 0.8 L/hr idling burn for standard petrol engine
            val idleFuelLiters = (idleMin / 60.0) * 0.8
            insights.add(
                AiInsightItem(
                    title = "Stationary Idling Fuel Impact",
                    description = "Vehicle spent $idleMin minutes stationary across ${overview.stopCount} stops, consuming approximately %.2f L of fuel while stopped.".format(Locale.US, idleFuelLiters),
                    category = InsightCategory.STATIONARY,
                    badgeLabel = "${idleMin}m Stopped",
                    isPositive = false
                )
            )
        } else {
            insights.add(
                AiInsightItem(
                    title = "Minimal Idling Overhead",
                    description = "Excellent stop management: vehicle spent under ${max(1, idleMin)} minute idling throughout the journey.",
                    category = InsightCategory.STATIONARY,
                    badgeLabel = "Low Idling",
                    isPositive = true
                )
            )
        }

        // 4. Comparison with Previous Trips
        if (historicalTrips.isNotEmpty()) {
            val pastQualifying = historicalTrips.filter { it.id != trip.id && it.calculatedMileageKmpl > 0 }
            if (pastQualifying.isNotEmpty()) {
                val avgHistoricalMileage = pastQualifying.map { it.calculatedMileageKmpl }.average()
                val currentMileage = fuelReport.calculatedMileageKmpl
                val deltaPct = ((currentMileage - avgHistoricalMileage) / avgHistoricalMileage) * 100.0

                if (deltaPct >= 3.0) {
                    insights.add(
                        AiInsightItem(
                            title = "Efficiency vs Historical Average",
                            description = "Achieved %.1f km/L, which is +%.1f%% better than your recorded average of %.1f km/L over past trips.".format(Locale.US, currentMileage, deltaPct, avgHistoricalMileage),
                            category = InsightCategory.HISTORICAL,
                            badgeLabel = "+%.1f%%".format(Locale.US, deltaPct),
                            isPositive = true
                        )
                    )
                } else if (deltaPct <= -5.0) {
                    insights.add(
                        AiInsightItem(
                            title = "Efficiency vs Historical Average",
                            description = "Mileage of %.1f km/L was %.1f%% lower than historical average (%.1f km/L), likely due to traffic density or short trips.".format(Locale.US, currentMileage, abs(deltaPct), avgHistoricalMileage),
                            category = InsightCategory.HISTORICAL,
                            badgeLabel = "%.1f%%".format(Locale.US, deltaPct),
                            isPositive = false
                        )
                    )
                }
            }
        }

        // 5. Elevation / Terrain Impact
        if (drivingStats.isElevationAvailable) {
            val gain = drivingStats.elevationGainMeters ?: 0.0
            val loss = drivingStats.elevationLossMeters ?: 0.0
            if (gain >= 40.0 || loss >= 40.0) {
                insights.add(
                    AiInsightItem(
                        title = "Terrain & Elevation Profile",
                        description = "Observed +%.0fm ascent / -%.0fm descent. Terrain gradients moderately influenced engine load.".format(Locale.US, gain, loss),
                        category = InsightCategory.TERRAIN,
                        badgeLabel = "+%.0fm Elevation".format(Locale.US, gain),
                        isPositive = true
                    )
                )
            }
        }

        // 6. Actionable Eco-Driving Recommendation
        val tip = when {
            trip.maxSpeedKmh > 95.0 ->
                "Capping maximum highway velocity between 75-85 km/h can reduce aerodynamic drag and save up to 12% fuel on longer corridors."
            idleMin >= 5 ->
                "Switching off ignition during prolonged railway crossings or long signals (> 60s) avoids wasted fuel consumption."
            else ->
                "Maintaining gentle throttle inputs and anticipatory coasting before intersections preserves kinetic energy."
        }
        insights.add(
            AiInsightItem(
                title = "Actionable Eco-Driving Recommendation",
                description = tip,
                category = InsightCategory.RECOMMENDATION,
                badgeLabel = "Eco Tip",
                isPositive = true
            )
        )

        return insights
    }

    private fun generateTimeline(
        trip: TripEntity,
        points: List<TripPointEntity>,
        stops: List<TripStopInfo>,
        refuels: List<RefuelEventItem>,
        transitions: List<VehicleStateTransitionEntity>,
        startTime: Long,
        endTime: Long
    ): List<TripTimelineItem> {
        val events = mutableListOf<TripTimelineItem>()

        // 1. Trip Start
        events.add(TripTimelineItem.TripStart(startTime, trip.startLocationName))

        // 2. Motion Started
        val motionPt = points.firstOrNull { it.speedKmh >= 5.0f }
        if (motionPt != null && motionPt.timestamp > startTime) {
            events.add(TripTimelineItem.MotionStarted(motionPt.timestamp, motionPt.speedKmh))
        }

        // 3. Stops
        for (stop in stops) {
            events.add(TripTimelineItem.VehicleStop(stop.timestamp, stop.durationMs, "Stopped near %.4f, %.4f".format(Locale.US, stop.latitude, stop.longitude)))
        }

        // 4. Engine Power Transitions
        for (tr in transitions) {
            if (tr.toState == "ENGINE_OFF") {
                events.add(TripTimelineItem.VehicleStop(tr.timestamp, 0L, "Ignition Severed (${tr.triggerReason})"))
            }
        }

        // 5. Refueling events
        for (refuel in refuels) {
            events.add(TripTimelineItem.RefuelRecorded(refuel.timestamp, refuel.liters, refuel.totalCost, refuel.locationName))
        }

        // 6. Peak Speed
        val peakPt = points.maxByOrNull { it.speedKmh }
        if (peakPt != null && peakPt.speedKmh >= 25.0f) {
            events.add(TripTimelineItem.PeakSpeedRecorded(peakPt.timestamp, peakPt.speedKmh))
        }

        // 7. Trip Completed
        val finalTime = if (points.isNotEmpty()) points.last().timestamp else endTime
        events.add(TripTimelineItem.TripCompleted(finalTime, trip.endLocationName, trip.distanceMeters / 1000.0))

        return events.sortedBy { it.timestamp }
    }
}
