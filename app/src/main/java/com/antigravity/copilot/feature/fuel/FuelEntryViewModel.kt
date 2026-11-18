package com.antigravity.copilot.feature.fuel

import android.content.Context
import androidx.lifecycle.ViewModel
import com.antigravity.copilot.core.fuel.FuelRepository
import com.antigravity.copilot.core.fuel.StationPriceManager
import com.antigravity.copilot.core.location.LocationNameResolver
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Station name plus the price last paid there, if remembered. */
data class StationLookup(val name: String, val rememberedPricePerLiter: Double?)

@HiltViewModel
class FuelEntryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fuelRepository: FuelRepository
) : ViewModel() {

    /** Mid-gauge bar (0..10) the learned calibration curve predicts for [liters]. */
    fun barForLiters(liters: Double): Int =
        fuelRepository.gaugeCalibration.getBarForLiters(liters).coerceIn(0, 10)

    suspend fun lookupStation(lat: Double, lon: Double): StationLookup {
        // Offline first: a saved station (with its last rate), then a map fuel POI, within 40 m.
        val stations = com.antigravity.copilot.core.fuel.station.FuelStationRepository.getInstance(context)
        stations.findStationNear(lat, lon)?.let { s ->
            return StationLookup(s.name, s.lastFuelRate.takeIf { it > 0.0 }
                ?: StationPriceManager.getRememberedPrice(context, lat, lon, s.name)?.takeIf { it > 0.0 })
        }
        val poiName = com.antigravity.copilot.core.fuel.station.FuelPoiCache
            .nearest(lat, lon, com.antigravity.copilot.core.fuel.station.FuelStationRepository.STATION_MATCH_RADIUS_M)
            ?.name?.takeIf { it.isNotBlank() }
        val name = poiName ?: LocationNameResolver.resolveStationOrAddress(context, lat, lon)
        val price = StationPriceManager.getRememberedPrice(context, lat, lon, name)?.takeIf { it > 0.0 }
        return StationLookup(name, price)
    }
}
