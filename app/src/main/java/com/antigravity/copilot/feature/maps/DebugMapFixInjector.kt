package com.antigravity.copilot.feature.maps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.antigravity.copilot.BuildConfig
import com.antigravity.copilot.core.map.CarMapController
import com.antigravity.copilot.core.map.intel.LonLat
import com.antigravity.copilot.core.map.intel.RoadGeometry

/**
 * DEBUG BUILDS ONLY: inject fake GPS fixes into the live map (puck, camera, road intelligence)
 * to test the location pill / speed-limit match / sharp-turn alert on a parked head unit.
 *
 * ```
 * # by coordinates
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_MAP_FIX --ef lat 21.9 --ef lon 83.0 --ef speed 60
 * # by map-view pixel (pan the map first so the camera stops following)
 * adb shell am broadcast -a com.antigravity.copilot.DEBUG_MAP_FIX --ef x 420 --ef y 300 --ef speed 60
 * ```
 * `bearing` is optional: without it the bearing from the previous injected fix is used.
 * Does not touch trip recording or SpeedLimitEngine telemetry (real GPS keeps flowing there).
 */
@Composable
internal fun DebugMapFixInjector(mapController: CarMapController) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current.applicationContext
    DisposableEffect(mapController) {
        var last: LonLat? = null
        var lastBearing = 0.0
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val pos = when {
                    intent.hasExtra("lat") && intent.hasExtra("lon") ->
                        LonLat(intent.getFloatExtra("lon", 0f).toDouble(), intent.getFloatExtra("lat", 0f).toDouble())
                    intent.hasExtra("x") && intent.hasExtra("y") ->
                        mapController.debugScreenToLonLat(intent.getFloatExtra("x", 0f), intent.getFloatExtra("y", 0f))
                    else -> null
                } ?: return
                val bearing = when {
                    intent.hasExtra("bearing") -> intent.getFloatExtra("bearing", 0f).toDouble()
                    last != null && RoadGeometry.distanceM(last!!, pos) > 1.0 -> RoadGeometry.bearingDeg(last!!, pos)
                    else -> lastBearing
                }
                last = pos
                lastBearing = bearing
                val speed = intent.getFloatExtra("speed", 50f)
                Log.d("DebugMapFix", "fix lat=${pos.lat} lon=${pos.lon} bearing=$bearing speed=$speed")
                mapController.updateVehiclePosition(pos.lat, pos.lon, bearing.toFloat(), speed)
            }
        }
        context.registerReceiver(receiver, IntentFilter(ACTION))
        onDispose { context.unregisterReceiver(receiver) }
    }
}

private const val ACTION = "com.antigravity.copilot.DEBUG_MAP_FIX"
