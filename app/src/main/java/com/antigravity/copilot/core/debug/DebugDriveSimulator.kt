package com.antigravity.copilot.core.debug

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.fuel.station.FuelPoiCache
import com.antigravity.copilot.core.fuel.station.FuelStationRepository
import com.antigravity.copilot.core.fuel.station.RefuelMode
import com.antigravity.copilot.core.model.TripPoint
import com.antigravity.copilot.core.road.RoadAnomalyManager
import com.antigravity.copilot.feature.fuel.GAUGE_NOT_OBSERVED
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DEBUG BUILDS ONLY. Drives a virtual car by road from the current real position to a
 * destination and back, feeding 1 Hz GPS fixes into the tracking service's normal pipeline
 * (filter → trip engine → map → fuel ledger → pothole manager). Real GPS is ignored meanwhile.
 *
 * Clocks stay coherent: one fix per real second with real timestamps. "Faster" means the car
 * simply drives faster (cruise speed), so trip, dwell and prompt timers all behave as on a road.
 *
 * Along the way it exercises: pump stops (fake POIs → stop prompt → ₹ refuel through the same
 * repository calls as the Add Fuel dialog), the "Reserve light ON" marker, and pothole /
 * speed-breaker hits on the way out, so the return leg passes them (ahead alert, self-healing).
 *
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_SIM_DRIVE -p com.antigravity.copilot
 *   [--ef destLat 21.8974 --ef destLon 83.3950 --ef cruiseKmh 100 --ei refuels 4 --ef rupees 100 --ef rate 101]
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_SIM_DRIVE -p com.antigravity.copilot --ez stop true
 */
class DebugDriveSimulator(
    private val context: Context,
    private val scope: CoroutineScope,
    private val fuelRepository: FuelRepository,
    private val roadAnomalyManager: RoadAnomalyManager,
    private val currentPoint: () -> TripPoint?,
    /** The service's own fix entry point; called on the main thread. */
    private val dispatch: (Location) -> Unit,
) {
    @Volatile var isRunning: Boolean = false
        private set
    private var job: Job? = null

    data class Config(
        val destLat: Double,
        val destLon: Double,
        val cruiseKmh: Double,
        val refuels: Int,
        val rupees: Double,
        val rate: Double,
    )

    private enum class EventType { PUMP, POTHOLE, BREAKER }

    private data class Event(val atM: Double, val type: EventType, val index: Int)

    fun stop() {
        job?.cancel()
        job = null
        isRunning = false
        Log.i(TAG, "Simulation stopped")
    }

    fun start(cfg: Config) {
        if (isRunning) { Log.w(TAG, "Already running"); return }
        val origin = currentPoint()
        if (origin == null) { Log.e(TAG, "No current GPS position yet; wait for a fix"); return }
        isRunning = true
        job = scope.launch {
            try {
                val route = withContext(Dispatchers.IO) {
                    fetchRoute(origin.latitude, origin.longitude, cfg.destLat, cfg.destLon)
                }
                drive(route, cfg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Simulation failed: ${e.message}", e)
            } finally {
                isRunning = false
            }
        }
    }

    private class Route(val lat: DoubleArray, val lon: DoubleArray, val cum: DoubleArray, val turnaroundM: Double) {
        val length get() = cum.last()
    }

    /** One OSRM request: origin → destination → origin (real road geometry). */
    private fun fetchRoute(oLat: Double, oLon: Double, dLat: Double, dLon: Double): Route {
        val coords = "$oLon,$oLat;$dLon,$dLat;$oLon,$oLat"
        val url = "https://router.project-osrm.org/route/v1/driving/$coords?overview=full&geometries=geojson&steps=false"
        val client = OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", "copilot-debug-sim").build())
            .execute().use { r -> check(r.isSuccessful) { "OSRM HTTP ${r.code}" }; r.body!!.string() }
        val routeJson = JSONObject(body).getJSONArray("routes").getJSONObject(0)
        val legOne = routeJson.getJSONArray("legs").getJSONObject(0).getDouble("distance")
        val pts = routeJson.getJSONObject("geometry").getJSONArray("coordinates")
        val n = pts.length()
        val lat = DoubleArray(n); val lon = DoubleArray(n); val cum = DoubleArray(n)
        for (i in 0 until n) {
            val p = pts.getJSONArray(i)
            lon[i] = p.getDouble(0); lat[i] = p.getDouble(1)
            if (i > 0) cum[i] = cum[i - 1] + distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i])
        }
        Log.i(TAG, "Route: %.1f km out, %.1f km total, %d points".format(legOne / 1000, cum.last() / 1000, n))
        return Route(lat, lon, cum, legOne)
    }

    private fun plan(route: Route, cfg: Config): List<Event> {
        val out = route.turnaroundM
        val back = route.length - out
        val events = mutableListOf<Event>()
        // Pumps: spread over the round trip; one at the destination (turnaround), none at the start/end.
        val pumpFractions = when (cfg.refuels) {
            1 -> listOf(out)
            2 -> listOf(out * 0.5, out)
            3 -> listOf(out * 0.4, out, out + back * 0.5)
            else -> listOf(out * 0.35, out * 0.75, out, out + back * 0.55)
        }
        pumpFractions.take(cfg.refuels.coerceAtLeast(0)).forEachIndexed { i, m -> events += Event(m, EventType.PUMP, i) }
        // Jolts on the way out, so the return leg drives back over them.
        events += Event(out * 0.20, EventType.BREAKER, 0)
        events += Event(out * 0.20 + 4.0, EventType.BREAKER, 1) // rear axle / 2nd hit → confidence 2
        events += Event(out * 0.50, EventType.POTHOLE, 0)
        events += Event(out * 0.85, EventType.POTHOLE, 1)
        return events.sortedBy { it.atM }
    }

    private suspend fun drive(route: Route, cfg: Config) {
        val events = plan(route, cfg).toMutableList()
        val pumps = events.filter { it.type == EventType.PUMP }
        val stationRepo = FuelStationRepository.getInstance(context)
        // Fake pumps the stop watchdog can recognise offline (like PMTiles fuel POIs).
        FuelPoiCache.addAll(pumps.map { e ->
            val (la, lo) = pointAt(route, e.atM)
            FuelPoiCache.Poi(pumpName(e.index), la, lo)
        })

        val cruise = cfg.cruiseKmh / 3.6
        var s = 0.0
        var v = 0.0
        var tick = 0
        Log.i(TAG, "Drive started: cruise ${cfg.cruiseKmh} km/h, ${pumps.size} pumps")
        while (scope.isActive && isRunning && s < route.length - 1.0) {
            val nextPump = events.firstOrNull { it.type == EventType.PUMP && it.atM >= s - 0.5 }
            // Target speed: cruise, slowed for bends ahead and braking (1.5 m/s²) into the next pump / the end.
            var target = min(cruise, turnLimit(route, s))
            val stopAt = nextPump?.atM ?: route.length
            target = min(target, sqrt(2 * 1.5 * (stopAt - s).coerceAtLeast(0.0)))
            if (tick < 3) target = 0.0 // engine on, still parked
            v = (v + (target - v).coerceIn(-3.0, 2.0)).coerceAtLeast(0.0)
            if (target > 0.5 && v < 1.0) v = 1.0 // creep forward instead of stalling short of a stop

            val step = min(v, stopAt - s).coerceAtLeast(0.0)
            s += step
            emitFix(route, s, v)

            // Jolts passed this second.
            events.filter { it.type != EventType.PUMP && it.atM <= s }.forEach { e ->
                val (la, lo) = pointAt(route, e.atM)
                val peak = if (e.type == EventType.POTHOLE) 7.8f else 4.6f
                roadAnomalyManager.debugInjectHit(la, lo, peak, bearingAt(route, e.atM).toFloat())
                Log.i(TAG, "Jolt ${e.type} injected at %.2f km (peak %.1f m/s²)".format(e.atM / 1000, peak))
                events.remove(e)
            }

            if (nextPump != null && stopAt - s < 0.5) {
                pumpStop(route, s, nextPump, cfg, stationRepo)
                events.remove(nextPump)
                v = 0.0
            }
            tick++
            delay(1000L)
        }
        // Parked back at the start: a few stationary fixes so the trip engine can settle / end the trip.
        repeat(20) { emitFix(route, route.length, 0.0); delay(1000L) }
        Log.i(TAG, "Drive finished: %.1f km".format(route.length / 1000))
        isRunning = false
    }

    /** Stopped at a pump long enough for the stop prompt (45 s dwell), then a ₹ refuel. */
    private suspend fun pumpStop(route: Route, s: Double, e: Event, cfg: Config, stationRepo: FuelStationRepository) {
        val (la, lo) = pointAt(route, s)
        Log.i(TAG, "Pump stop ${e.index + 1}: ${pumpName(e.index)} at %.2f km".format(s / 1000))
        // Refuels 1 and 3 happen with the reserve lamp on (tapped a few seconds before), the
        // others are plain top-ups: exercises reserve-to-reserve mileage (Rule B) and Rule C.
        val atReserve = e.index % 2 == 0
        if (atReserve) {
            stationRepo.toggleReserveLight(fuelRepository.getEffectiveOdometer())
            Log.i(TAG, "Reserve light ON tapped at odo %.1f".format(fuelRepository.getEffectiveOdometer()))
        }
        var promptSeen = false
        for (sec in 0 until 75) {
            emitFix(route, s, 0.0)
            if (!promptSeen && fuelRepository.refuelPromptEvent.value != null) {
                promptSeen = true
                Log.i(TAG, "Stop prompt shown after ${sec}s: ${fuelRepository.refuelPromptEvent.value?.locationName}")
            }
            if (promptSeen && sec >= 60) break // leave the prompt on screen a few seconds
            delay(1000L)
        }
        if (!promptSeen) Log.w(TAG, "Stop prompt did NOT appear at ${pumpName(e.index)}")
        fuelRepository.dismissRefuelPrompt()

        // Same two calls MainViewModel.recordRefuel makes when the Add Fuel dialog is saved.
        val liters = Math.round(cfg.rupees / cfg.rate * 100.0) / 100.0
        val mode = if (atReserve) RefuelMode.AT_RESERVE else RefuelMode.TOP_UP
        val name = pumpName(e.index)
        withContext(Dispatchers.IO) {
            stationRepo.recordRefuel(
                stationName = name, lat = la, lon = lo, liters = liters, rate = cfg.rate,
                mode = mode, odometerKm = fuelRepository.getEffectiveOdometer(), confirmedStation = true
            )
        }
        fuelRepository.recordRefuel(
            fullTank = false,
            litersAdded = liters,
            midBarObserved = GAUGE_NOT_OBSERVED,
            pricePerLiter = cfg.rate,
            totalCost = cfg.rupees,
            odometerKm = 0.0,
            locationName = name,
            latitude = la,
            longitude = lo
        )
        Log.i(TAG, "Refuel ${e.index + 1}: ₹${cfg.rupees} = $liters L ($mode) at $name")
        repeat(5) { emitFix(route, s, 0.0); delay(1000L) }
    }

    private suspend fun emitFix(route: Route, s: Double, v: Double) {
        val (la, lo) = pointAt(route, s)
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = la
            longitude = lo
            speed = v.toFloat()
            bearing = bearingAt(route, s).toFloat()
            accuracy = 4f
            altitude = 240.0
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        withContext(Dispatchers.Main) { dispatch(loc) }
    }

    // --- geometry ---------------------------------------------------------------------------

    private fun segmentAt(route: Route, s: Double): Int {
        var lo = 0; var hi = route.cum.size - 1
        while (hi - lo > 1) { val mid = (lo + hi) / 2; if (route.cum[mid] <= s) lo = mid else hi = mid }
        return lo
    }

    private fun pointAt(route: Route, s: Double): Pair<Double, Double> {
        val i = segmentAt(route, s.coerceIn(0.0, route.length))
        val j = (i + 1).coerceAtMost(route.cum.size - 1)
        val segLen = route.cum[j] - route.cum[i]
        val f = if (segLen > 0) ((s - route.cum[i]) / segLen).coerceIn(0.0, 1.0) else 0.0
        return (route.lat[i] + (route.lat[j] - route.lat[i]) * f) to (route.lon[i] + (route.lon[j] - route.lon[i]) * f)
    }

    private fun bearingAt(route: Route, s: Double): Double {
        val (a1, o1) = pointAt(route, s - 5.0)
        val (a2, o2) = pointAt(route, s + 5.0)
        return bearing(a1, o1, a2, o2)
    }

    /** Slow to ~35 km/h for a bend > 40° within the next 80 m, ~55 km/h for > 20°. */
    private fun turnLimit(route: Route, s: Double): Double {
        val h0 = bearingAt(route, s)
        var maxTurn = 0.0
        var d = 20.0
        while (d <= 80.0) {
            maxTurn = maxOf(maxTurn, angleDiff(h0, bearingAt(route, s + d)))
            d += 20.0
        }
        return when {
            maxTurn > 40 -> 35 / 3.6
            maxTurn > 20 -> 55 / 3.6
            else -> Double.MAX_VALUE
        }
    }

    private fun angleDiff(a: Double, b: Double): Double { val d = abs(a - b) % 360; return if (d > 180) 360 - d else d }

    private fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, r)
        return r[0].toDouble()
    }

    private fun pumpName(i: Int) = "[sim] Debug Pump ${i + 1}"

    companion object {
        const val TAG = "DebugDriveSim"
        const val ACTION = "com.antigravity.copilot.DEBUG_SIM_DRIVE"
        /** Raigarh, Chhattisgarh (city centre). */
        const val RAIGARH_LAT = 21.8974
        const val RAIGARH_LON = 83.3950
    }
}
