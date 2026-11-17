package com.antigravity.copilot.feature.fuel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Wires [FuelHistoryViewModel] to the stateless [FuelHistoryScreen]. */
@Composable
fun FuelHistoryRoute(
    onBackToCockpit: () -> Unit,
    fuelState: com.antigravity.copilot.core.model.FuelState? = null,
    onAddFuel: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FuelHistoryViewModel = hiltViewModel()
) {
    val events by viewModel.fuelEvents.collectAsStateWithLifecycle()
    FuelHistoryScreen(
        fuelEvents = events,
        onBackToCockpit = onBackToCockpit,
        modifier = modifier,
        onEditEvent = viewModel::updateEvent,
        onDeleteEvent = viewModel::deleteEvent,
        fuelState = fuelState,
        onAddFuel = onAddFuel
    )
}
