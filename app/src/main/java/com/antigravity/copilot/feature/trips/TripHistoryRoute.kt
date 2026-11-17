package com.antigravity.copilot.feature.trips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Wires [TripHistoryViewModel] to the stateless [TripHistoryScreen]. */
@Composable
fun TripHistoryRoute(
    onBackToCockpit: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripHistoryViewModel = hiltViewModel()
) {
    val trips by viewModel.trips.collectAsStateWithLifecycle()
    val fuelPrice by viewModel.fuelPricePerLiter.collectAsStateWithLifecycle()
    val viaNames by viewModel.viaNames.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalTripViaNames provides viaNames) {
    TripHistoryScreen(
        trips = trips,
        onLoadTripPoints = viewModel::loadPoints,
        onLoadTripTransitions = viewModel::loadTransitions,
        onLoadTripFuelEvents = viewModel::loadFuelEvents,
        onBackToCockpit = onBackToCockpit,
        onDeleteTrip = viewModel::deleteTrip,
        fuelPrice = fuelPrice,
        modifier = modifier
    )
    }
}
