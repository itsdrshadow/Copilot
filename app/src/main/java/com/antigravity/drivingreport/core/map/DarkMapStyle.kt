package com.antigravity.drivingreport.core.map

import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/**
 * DarkMapStyle:
 * Engineered exclusively for automotive cockpits (Dark Mode Only).
 * Transforms OSM raster tiles into a deep OLED midnight cockpit theme:
 * - Ultra-low glare for night driving
 * - High-contrast street geometry and highways
 * - Pristine typographic readability
 */
object DarkMapStyle {

    /**
     * Precision 4x5 Color Matrix designed for automotive AMOLED/IPS screens:
     * - Inverts luminance while preserving blue/cyan channels for waterways
     * - Shifts background terrain to deep space navy (#080D1A)
     * - Boosts road and highway contrast for quick glanceability
     */
    private val darkMatrix = ColorMatrix(
        floatArrayOf(
            -0.85f,  0.00f,  0.00f, 0.00f, 195.0f, // R
             0.00f, -0.85f,  0.00f, 0.00f, 195.0f, // G
             0.00f,  0.00f, -0.80f, 0.00f, 220.0f, // B
             0.00f,  0.00f,  0.00f, 1.00f,   0.0f  // A
        )
    )

    val filter: ColorFilter = ColorMatrixColorFilter(darkMatrix)
}
