package com.antigravity.copilot.core.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.antigravity.copilot.core.database.AppDatabase
import com.antigravity.copilot.core.database.FuelEventEntity
import com.antigravity.copilot.core.database.TripEntity
import com.antigravity.copilot.core.model.TripStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import kotlin.random.Random

/**
 * DEBUG BUILDS ONLY: fill Trips and Fuel history with fake rows for UI testing.
 *
 * ```
 * # seed Jan 2025 .. Dec 2030 (defaults), 3-6 trips and 3-5 fill-ups per month
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_SEED_DEMO
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_SEED_DEMO --ei fromYear 2026 --ei toYear 2030
 * # remove every seeded row
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_SEED_DEMO --ez clear true
 * ```
 * Writes only `trips` and the legacy `fuel_events` table the Fuel history screen reads.
 * The fuel ledger is left alone so the live fuel estimate / mileage learning is not affected.
 */
object DebugDemoDataSeeder {
    private const val TAG = "DebugDemoSeed"
    private const val ACTION = "com.antigravity.copilot.DEBUG_SEED_DEMO"
    private const val PREFS = "debug_demo_seed"
    private const val KEY_TRIP_IDS = "trip_ids"
    const val FUEL_MARKER = "[demo]"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val places = listOf(
        "Home", "Office", "City Market", "Railway Station", "Bus Stand", "Hospital",
        "Shopping Mall", "School", "Temple", "Highway Dhaba", "Airport", "College",
        "Vegetable Mandi", "Bank", "Friend's Place", "Gym"
    )
    private val pumps = listOf(
        "Indian Oil · Station Road", "HP · NH-49", "Bharat Petroleum · Main Chowk",
        "Indian Oil · Bypass", "HP · Market Road", "Jio-bp · Ring Road"
    )

    fun register(context: Context) {
        val app = context.applicationContext
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val clear = intent.getBooleanExtra("clear", false)
                val fromYear = intent.getIntExtra("fromYear", 2025)
                val toYear = intent.getIntExtra("toYear", 2030)
                scope.launch {
                    try {
                        if (clear) clear(app) else seed(app, fromYear, toYear)
                    } catch (e: Exception) {
                        Log.e(TAG, "Demo seed failed: ${e.message}", e)
                    }
                }
            }
        }, IntentFilter(ACTION))
    }

    private suspend fun clear(context: Context) {
        val db = AppDatabase.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString(KEY_TRIP_IDS, "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        ids.forEach { db.tripDao().deleteTrip(it) }
        val fuel = db.fuelDao().getAllFuelEvents().filter { it.notes.startsWith(FUEL_MARKER) }
        fuel.forEach { db.fuelDao().deleteFuelEvent(it.id) }
        prefs.edit().remove(KEY_TRIP_IDS).apply()
        Log.i(TAG, "Cleared ${ids.size} demo trips and ${fuel.size} demo fuel rows")
    }

    private suspend fun seed(context: Context, fromYear: Int, toYear: Int) {
        clear(context) // re-seeding replaces the previous demo set
        val db = AppDatabase.getInstance(context)
        val rnd = Random(fromYear * 31 + toYear)
        var odo = 12_000.0 + rnd.nextDouble(0.0, 3_000.0)
        var fuelLeft = 30.0
        var price = 102.0
        val tank = 48.0
        val tripIds = mutableListOf<Long>()
        var fuelCount = 0

        for (year in fromYear..toYear) {
            for (month in 0..11) {
                val daysInMonth = Calendar.getInstance().run {
                    clear(); set(year, month, 1); getActualMaximum(Calendar.DAY_OF_MONTH)
                }
                price = (price + rnd.nextDouble(-0.6, 0.9)).coerceIn(95.0, 125.0)

                // Event list for the month: trips and fill-ups interleaved by time.
                val tripTimes = List(rnd.nextInt(3, 7)) { randomTime(rnd, year, month, daysInMonth) }
                val fuelTimes = List(rnd.nextInt(3, 6)) { randomTime(rnd, year, month, daysInMonth) }
                val events = (tripTimes.map { it to true } + fuelTimes.map { it to false }).sortedBy { it.first }

                for ((time, isTrip) in events) {
                    if (isTrip) {
                        val distKm = if (rnd.nextInt(10) == 0) rnd.nextDouble(80.0, 220.0) else rnd.nextDouble(4.0, 45.0)
                        val avg = rnd.nextDouble(22.0, 58.0)
                        val driveMs = (distKm / avg * 3_600_000).toLong()
                        val stopMs = (driveMs * rnd.nextDouble(0.05, 0.3)).toLong()
                        val mileage = rnd.nextDouble(13.0, 19.0)
                        val litres = distKm / mileage
                        val from = places.random(rnd)
                        val to = places.filter { it != from }.random(rnd)
                        val brakes = rnd.nextInt(0, 6)
                        val accels = rnd.nextInt(0, 6)
                        val id = db.tripDao().insertTrip(
                            TripEntity(
                                startTime = time,
                                endTime = time + driveMs + stopMs,
                                distanceMeters = distKm * 1000,
                                drivingDurationMs = driveMs,
                                stationaryDurationMs = stopMs,
                                avgSpeedKmh = avg,
                                maxSpeedKmh = avg + rnd.nextDouble(15.0, 45.0),
                                estimatedFuelLiters = litres,
                                calculatedMileageKmpl = mileage,
                                driverScore = (100 - brakes * 4 - accels * 3 - rnd.nextInt(0, 8)).coerceIn(40, 100),
                                startLocationName = from,
                                endLocationName = to,
                                status = TripStatus.COMPLETED.name,
                                isQualifying = true,
                                brakeCount = brakes,
                                accelCount = accels,
                                bestMileageKmpl = mileage + rnd.nextDouble(0.5, 2.5)
                            )
                        )
                        tripIds += id
                        odo += distKm
                        fuelLeft = (fuelLeft - litres).coerceAtLeast(3.0)
                    } else {
                        val full = rnd.nextInt(4) == 0
                        val litres = if (full) {
                            tank - fuelLeft
                        } else {
                            // Round-rupee fills like ₹500 / ₹1000 / ₹2000
                            val rupees = listOf(500, 1000, 1500, 2000).random(rnd)
                            (rupees / price).coerceAtMost(tank - fuelLeft)
                        }.coerceAtLeast(2.0)
                        fuelLeft = (fuelLeft + litres).coerceAtMost(tank)
                        val cost = litres * price
                        db.fuelDao().insertFuelEvent(
                            FuelEventEntity(
                                vehicleId = 1,
                                timestamp = time,
                                eventType = "REFUEL",
                                liters = litres,
                                midLevelObserved = (fuelLeft / tank * 10).toInt().coerceIn(0, 10),
                                pricePerLiter = price,
                                totalCost = cost,
                                calculatedBalanceLiters = fuelLeft,
                                notes = "$FUEL_MARKER Refueled ${"%.1f".format(litres)} L",
                                odometerKm = odo,
                                calculatedMileageKmpl = rnd.nextDouble(13.5, 18.5),
                                locationName = pumps.random(rnd)
                            )
                        )
                        fuelCount++
                    }
                }
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TRIP_IDS, tripIds.joinToString(",")).apply()
        Log.i(TAG, "Seeded ${tripIds.size} trips and $fuelCount fuel rows for $fromYear..$toYear")
    }

    private fun randomTime(rnd: Random, year: Int, month: Int, days: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(year, month, rnd.nextInt(1, days + 1), rnd.nextInt(6, 22), rnd.nextInt(0, 60), rnd.nextInt(0, 60))
        }.timeInMillis
}
