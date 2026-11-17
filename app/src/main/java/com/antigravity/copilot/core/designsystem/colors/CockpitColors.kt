package com.antigravity.copilot.core.designsystem.colors

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ==================== THEME ENUMS & CONTRACTS ====================

/** The single app theme family. Legacy ids from retired themes all resolve to Raahi. */
enum class CockpitThemeMode(val id: String, val displayName: String, val subtitle: String) {
    RAAHI(
        id = "raahi",
        displayName = "Raahi",
        subtitle = "Flat solid cards, cyan for live data, colour only where it means something"
    );

    companion object {
        val APEX = RAAHI
        val PREMIUM = RAAHI

        @Suppress("UNUSED_PARAMETER")
        fun fromId(id: String): CockpitThemeMode = RAAHI
    }
}

/** Day / night choice. AUTO follows ambient light (or the clock when there is no light sensor). */
enum class ThemeAppearanceMode(val id: String, val displayName: String) {
    AUTO(id = "auto", displayName = "Auto"),
    NIGHT(id = "night", displayName = "Raahi Night"),
    DAY(id = "day", displayName = "Raahi Day");

    companion object {
        fun fromId(id: String): ThemeAppearanceMode = when (id.lowercase().trim()) {
            "night", "dark" -> NIGHT
            "day", "light" -> DAY
            else -> AUTO
        }
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
    val trail: String = "#38D6FF",
    val overlap2: String = "#FFA94D",
    val overlap3: String = "#FF5C5C",
    val overlap1Blue: String = "#5BA8FF",
    val puckRing: String = "#EDF3FB",
    val geofence: String = "#38D6FF",
    val mileageLow: String = "#FF5C5C",
    val mileageMid: String = "#FFB224",
    val mileageHigh: String = "#34D399"
)

// ==================== RAAHI NIGHT ====================

object RaahiNight {
    val AppBg = Color(0xFF080D16)
    val Rail = Color(0xFF0B111C)
    val Card = Color(0xFF0F1623)
    val CardRaised = Color(0xFF172033)
    val Border = Color(0xFF26334B)
    val TextPrimary = Color(0xFFEDF3FB)
    val TextSecondary = Color(0xFF9AABC4)
    val TextDisabled = Color(0xFF7D8DA6)
    val Cyan = Color(0xFF38D6FF)
    val Amber = Color(0xFFFFB224)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFFF5C5C)
    val OverlapBlue = Color(0xFF5BA8FF)
    val OverlapOrange = Color(0xFFFFA94D)
    val OnAccent = Color(0xFF04131A)

    val Map = MapPalette(
        land = "#0B1220",
        park = "#0D1A1F",
        water = "#0E3556",
        roadMinor = "#3B4F70",
        roadMajor = "#5A7399",
        roadHighway = "#5A7399",
        roadCasing = "#080D16",
        building = "#101A2C",
        label = "#9AABC4",
        labelMajor = "#EDF3FB",
        labelHalo = "#080D16",
        waterLabel = "#7FB3E0"
    )
}

// ==================== RAAHI DAY ====================

object RaahiDay {
    val AppBg = Color(0xFFF4F7FB)
    val Rail = Color(0xFFEBF0F6)
    val Card = Color(0xFFFFFFFF)
    val CardRaised = Color(0xFFE8EEF6)
    val Border = Color(0xFFD5DEEB)
    val TextPrimary = Color(0xFF0B1220)
    val TextSecondary = Color(0xFF4A5B75)
    val TextDisabled = Color(0xFF56667E)
    val Cyan = Color(0xFF0A9CC4)
    val Amber = Color(0xFFC27A00)
    val Green = Color(0xFF0F9F6E)
    val Red = Color(0xFFD93A3A)
    val OverlapBlue = Color(0xFF2F7FE0)
    val OverlapOrange = Color(0xFFE07B12)
    val OnAccent = Color(0xFF04131A)
    val CyanText = Color(0xFF066F8C)
    val AmberText = Color(0xFF8A5700)
    val GreenText = Color(0xFF0A7652)
    val RedText = Color(0xFFB42C2C)

    val Map = MapPalette(
        land = "#EEF2F7",
        park = "#DCEFE3",
        water = "#A9CBEA",
        roadMinor = "#FFFFFF",
        roadMajor = "#C5D0DF",
        roadHighway = "#AFBDD0",
        roadCasing = "#D0D9E5",
        building = "#DCE3EC",
        label = "#4A5B75",
        labelMajor = "#0B1220",
        labelHalo = "#FFFFFF",
        waterLabel = "#2C5F8E",
        trail = "#0A9CC4",
        overlap2 = "#E07B12",
        overlap3 = "#D93A3A",
        overlap1Blue = "#2F7FE0",
        puckRing = "#FFFFFF",
        geofence = "#0A9CC4",
        mileageLow = "#D93A3A",
        mileageMid = "#C27A00",
        mileageHigh = "#0F9F6E"
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

val RaahiDayColors = CockpitColors(
    bg = RaahiDay.AppBg,
    card = RaahiDay.Card,
    cardElevated = RaahiDay.CardRaised,
    border = RaahiDay.Border,
    textPrimary = RaahiDay.TextPrimary,
    textSecondary = RaahiDay.TextSecondary,
    textDisabled = RaahiDay.TextDisabled,
    accent = RaahiDay.Cyan,
    accentSoft = RaahiDay.Cyan.copy(alpha = 0.14f),
    onAccent = RaahiDay.OnAccent,
    warning = RaahiDay.Amber,
    critical = RaahiDay.Red,
    cardTopLightGradient = solid(RaahiDay.Card),
    rail = RaahiDay.Rail,
    positive = RaahiDay.Green,
    overlapBlue = RaahiDay.OverlapBlue,
    overlapOrange = RaahiDay.OverlapOrange,
    scrim = Color(0x66000000),
    isDark = false,
    accentText = RaahiDay.CyanText,
    positiveText = RaahiDay.GreenText,
    warningText = RaahiDay.AmberText,
    criticalText = RaahiDay.RedText
)

fun cockpitColorsFor(isDay: Boolean): CockpitColors = if (isDay) RaahiDayColors else RaahiNightColors

fun mapPaletteFor(isDay: Boolean): MapPalette = if (isDay) RaahiDay.Map else RaahiNight.Map

// Legacy names, now aliases of the Raahi Night palette (Night is the default appearance).
val ApexCockpitColors = RaahiNightColors
val PremiumCockpitColors = RaahiNightColors
val ClassicCockpitColors = RaahiNightColors

// Composition Locals
val LocalCockpitColors = staticCompositionLocalOf { RaahiNightColors }
val LocalCockpitThemeMode = staticCompositionLocalOf { CockpitThemeMode.RAAHI }
val LocalMapPalette = staticCompositionLocalOf { RaahiNight.Map }
