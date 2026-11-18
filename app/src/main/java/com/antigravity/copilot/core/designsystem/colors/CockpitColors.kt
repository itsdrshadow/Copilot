package com.antigravity.copilot.core.designsystem.colors

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ==================== THEME ENUMS & CONTRACTS ====================

/** The single app theme family (dark only). Legacy ids from retired themes all resolve to Raahi. */
enum class CockpitThemeMode(val id: String, val displayName: String, val subtitle: String) {
    RAAHI(
        id = "raahi",
        displayName = "Raahi",
        subtitle = "Flat solid cards, blue for live data, colour only where it means something"
    );

    companion object {
        val APEX = RAAHI
        val PREMIUM = RAAHI

        @Suppress("UNUSED_PARAMETER")
        fun fromId(id: String): CockpitThemeMode = RAAHI
    }
}

/**
 * Semantic colour contract. Screens read these through `CockpitTheme.colors`; nothing else in the
 * app should spell out a colour literal.
 *
 * Meaning of the status colours:
 * - [accent] (cyan): live / primary / selected.
 * - [positive] (green): good / full / cruising.
 * - [warning] (amber): fuel and caution.
 * - [critical] (red): danger / low / braking.
 */
data class CockpitColors(
    val bg: Color,
    val card: Color,
    val cardElevated: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val warning: Color,
    val critical: Color,
    val cardTopLightGradient: Brush,
    val rail: Color = card,
    val positive: Color = accent,
    val overlapBlue: Color = accent,
    val overlapOrange: Color = warning,
    val scrim: Color = Color(0x99000000),
    val isDark: Boolean = true,
    /**
     * Status colours for *text* on cards. Same as the fill colours in Night; darker in Day,
     * where the spec fill colours are below 4.5:1 against white.
     */
    val accentText: Color = accent,
    val positiveText: Color = positive,
    val warningText: Color = warning,
    val criticalText: Color = critical,
    // ---- Legacy aliases (kept so older call sites keep compiling; all point at Raahi tokens) ----
    val backgroundBrush: Brush = Brush.verticalGradient(listOf(bg, bg)),
    val background: Color = bg,
    val backgroundSecondary: Color = cardElevated,
    val cardSurface: Color = card,
    val cardSurfaceElevated: Color = cardElevated,
    val cardBorder: Color = border,
    val cardBorderGlow: Color = accent,
    val primaryAccent: Color = accent,
    val blueGlow: Color = accent,
    val greenEco: Color = positive,
    val amberWarning: Color = warning,
    val redAlert: Color = critical,
    val textTertiary: Color = textDisabled,
    val cardGradientOdo: Brush = cardTopLightGradient,
    val cardGradientFuel: Brush = cardTopLightGradient,
    val cardGradientTrip: Brush = cardTopLightGradient,
    val cardGradientSpeedo: Brush = cardTopLightGradient,
    val activeCardBrush: Brush = cardTopLightGradient,
    val dockBackground: Color = card,
    val dockBorder: Color = border,
    val dockBrush: Brush = cardTopLightGradient,
    val dockActionBrush: Brush = cardTopLightGradient,
    val isPremium: Boolean = true
)

/** Basemap colours as `#RRGGBB` strings, consumed by the MapLibre style builder. */
data class MapPalette(
    val land: String,
    val park: String,
    val water: String,
    val roadMinor: String,
    val roadMajor: String,
    val roadHighway: String,
    val roadCasing: String,
    val building: String,
    val label: String,
    val labelMajor: String,
    val labelHalo: String,
    val waterLabel: String,
    // Overlays drawn on the map (live + trip maps)
    val trail: String = "#3B82F6",
    val overlap2: String = "#F59E0B",
    val overlap3: String = "#F87171",
    val overlap1Blue: String = "#3B82F6",
    val puckRing: String = "#FFFFFF",
    val geofence: String = "#3B82F6",
    val mileageLow: String = "#F87171",
    val mileageMid: String = "#F59E0B",
    val mileageHigh: String = "#22C55E"
)

// ==================== RAAHI (DARK) ====================

object RaahiNight {
    // Bento dark: OLED black base, matte charcoal cards, hairline borders.
    val AppBg = Color(0xFF0A0A0B)
    val Rail = Color(0xFF0E0F11)
    val Card = Color(0xFF17181A)
    val CardRaised = Color(0xFF1C1D20)
    val Border = Color(0xFF26282C)
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xFF8E9299)
    val TextDisabled = Color(0xFF868B93)
    /** Primary highlight (name kept for call sites): Electric Blue. */
    val Cyan = Color(0xFF3B82F6)
    val Amber = Color(0xFFF59E0B)
    val Green = Color(0xFF22C55E)
    val Red = Color(0xFFF87171)
    val OverlapBlue = Color(0xFF3B82F6)
    val OverlapOrange = Color(0xFFF59E0B)
    val OnAccent = Color(0xFF0A0A0B)

    val Map = MapPalette(
        land = "#0E0F11",
        park = "#11171A",
        water = "#10233A",
        roadMinor = "#2A2D33",
        roadMajor = "#3D414A",
        roadHighway = "#4A4F5A",
        roadCasing = "#0A0A0B",
        building = "#17181A",
        label = "#8E9299",
        labelMajor = "#FFFFFF",
        labelHalo = "#0A0A0B",
        waterLabel = "#6E93C4"
    )
}

private fun solid(c: Color) = Brush.verticalGradient(listOf(c, c))

val RaahiNightColors = CockpitColors(
    bg = RaahiNight.AppBg,
    card = RaahiNight.Card,
    cardElevated = RaahiNight.CardRaised,
    border = RaahiNight.Border,
    textPrimary = RaahiNight.TextPrimary,
    textSecondary = RaahiNight.TextSecondary,
    textDisabled = RaahiNight.TextDisabled,
    accent = RaahiNight.Cyan,
    accentSoft = RaahiNight.Cyan.copy(alpha = 0.16f),
    onAccent = RaahiNight.OnAccent,
    warning = RaahiNight.Amber,
    critical = RaahiNight.Red,
    cardTopLightGradient = solid(RaahiNight.Card),
    rail = RaahiNight.Rail,
    positive = RaahiNight.Green,
    overlapBlue = RaahiNight.OverlapBlue,
    overlapOrange = RaahiNight.OverlapOrange,
    scrim = Color(0xB3000000),
    isDark = true
)

fun mapPaletteFor(accent: AccentOption = AccentOption.CYAN): MapPalette {
    // Trail and geofence rings follow the chosen accent.
    val hex = String.format("#%06X", accent.night.toArgb() and 0xFFFFFF)
    return RaahiNight.Map.copy(trail = hex, geofence = hex)
}

// Legacy names, now aliases of the Raahi Night palette (Night is the default appearance).
val ApexCockpitColors = RaahiNightColors
val PremiumCockpitColors = RaahiNightColors
val ClassicCockpitColors = RaahiNightColors

// Composition Locals
val LocalCockpitColors = staticCompositionLocalOf { RaahiNightColors }
val LocalCockpitThemeMode = staticCompositionLocalOf { CockpitThemeMode.RAAHI }
val LocalMapPalette = staticCompositionLocalOf { RaahiNight.Map }
