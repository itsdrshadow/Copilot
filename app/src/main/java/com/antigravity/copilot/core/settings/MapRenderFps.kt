package com.antigravity.copilot.core.settings

/**
 * Map rendering frame cap (Settings › General › Map Rendering). Applied live to the MapLibre
 * MapView through `setMaximumFps`; no restart needed.
 *
 * The head unit panel refreshes at 60 Hz, so 60 is the useful ceiling.
 */
enum class MapRenderFps(
    val id: String,
    val displayName: String,
    val subtitle: String,
    /** Cap while nobody is touching the map (driving / follow mode). */
    val idleFps: Int,
    /** Cap while a finger is on the map and until the gesture's camera motion settles. */
    val interactiveFps: Int
) {
    SMOOTH_60(
        id = "smooth_60",
        displayName = "Smoothest",
        subtitle = "Silkiest map; the head unit runs warmer",
        idleFps = 60,
        interactiveFps = 60
    ),
    BALANCED_30(
        id = "balanced_30",
        displayName = "Battery saver",
        subtitle = "Keeps the head unit cool; map moves a little less smoothly",
        idleFps = 30,
        interactiveFps = 30
    ),
    SMART_AUTO(
        id = "smart_auto",
        displayName = "Auto (recommended)",
        subtitle = "Saves power while driving, goes smooth when you touch the map",
        idleFps = 30,
        interactiveFps = 60
    );

    companion object {
        val DEFAULT = SMOOTH_60

        fun fromId(id: String?): MapRenderFps = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
