package com.antigravity.copilot.core.trip

import java.util.Locale

/**
 * Trip place-name rules (audit D3): a lookup that fails stores the coordinates, never an invented
 * place; placeholders and coordinate names are retried later.
 */
object TripNaming {
    const val COORD_PREFIX = "≈"
    private val PLACEHOLDERS = setOf("", "Trip Origin", "Trip Destination", "Start Location")

    fun coordinateName(lat: Double, lon: Double): String =
        String.format(Locale.US, "%s%.4f, %.4f", COORD_PREFIX, lat, lon)

    fun isCoordinateName(name: String?): Boolean = name != null && name.startsWith(COORD_PREFIX)

    fun isPlaceholder(name: String?): Boolean = name == null || name.trim() in PLACEHOLDERS || isCoordinateName(name)

    /** A resolver result worth storing (fuel-station names are not trip endpoints). */
    fun acceptResolved(name: String?): String? =
        name?.trim()?.takeIf { it.isNotBlank() && !it.contains("Station") && !isPlaceholder(it) }
}
