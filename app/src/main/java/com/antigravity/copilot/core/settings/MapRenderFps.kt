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
        displayName = "Smooth (60 FPS)",
        subtitle = "Maximum smoothness, highest GPU load and heat",
        idleFps = 60,
        interactiveFps = 60
    ),
    BALANCED_30(
        id = "balanced_30",
        displayName = "Balanced (30 FPS)",
        subtitle = "Battery & heat saver, lowest GPU load",
        idleFps = 30,
        interactiveFps = 30
    ),
    SMART_AUTO(
        id = "smart_auto",
        displayName = "Smart Auto (30 / 60 FPS)",
        subtitle = "30 FPS while driving, 60 FPS the moment you drag or zoom the map",
        idleFps = 30,
        interactiveFps = 60
    );

    companion object {
        val DEFAULT = SMOOTH_60

        fun fromId(id: String?): MapRenderFps = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
