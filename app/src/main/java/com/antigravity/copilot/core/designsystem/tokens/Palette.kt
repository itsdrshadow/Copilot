package com.antigravity.copilot.core.designsystem.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.antigravity.copilot.core.designsystem.colors.LocalCockpitColors

/**
 * Legacy primitive names, now **mapped onto the active Raahi theme**. Older screens that still say
 * `Palette.Sky400` get the Raahi cyan of the current appearance (Day or Night), so there is one
 * source of colour truth (`CockpitColors`) and no hard-coded swatches left.
 *
 * Mapping by role: sky / cyan → accent; light slate → primary text; mid slate → secondary text;
 * dark slate / graphite / ink borders → border; ink surfaces → raised card; green → positive;
 * amber / orange → warning (fuel); red / rose → critical; purple → overlap blue.
 *
 * New code should use `CockpitTheme.colors` directly.
 */
object Palette {
    private val c @Composable @ReadOnlyComposable get() = LocalCockpitColors.current

    // Sky / blue accents
    val Sky300: Color @Composable @ReadOnlyComposable get() = c.accent
    val Sky400: Color @Composable @ReadOnlyComposable get() = c.accent
    val Sky600: Color @Composable @ReadOnlyComposable get() = c.accent
    val Sky700: Color @Composable @ReadOnlyComposable get() = c.accent
    val Blue700: Color @Composable @ReadOnlyComposable get() = c.accent
    val LightBlueA400: Color @Composable @ReadOnlyComposable get() = c.accent

    // Cyans
    val Cyan: Color @Composable @ReadOnlyComposable get() = c.accent
    val CyanNeon: Color @Composable @ReadOnlyComposable get() = c.accent
    val CyanElectric: Color @Composable @ReadOnlyComposable get() = c.accent
    val CyanBright: Color @Composable @ReadOnlyComposable get() = c.accent

    // Slate neutrals (text / dividers)
    val Slate300: Color @Composable @ReadOnlyComposable get() = c.textPrimary
    val Slate400: Color @Composable @ReadOnlyComposable get() = c.textSecondary
    val Slate500: Color @Composable @ReadOnlyComposable get() = c.textSecondary
    val Slate600: Color @Composable @ReadOnlyComposable get() = c.textDisabled
    val Slate700: Color @Composable @ReadOnlyComposable get() = c.border
    val Slate800: Color @Composable @ReadOnlyComposable get() = c.border
    val Slate900: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Steel400: Color @Composable @ReadOnlyComposable get() = c.textSecondary
    val Graphite700: Color @Composable @ReadOnlyComposable get() = c.border

    // Ink surfaces → raised card; ink "lines" → border
    val Ink950: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink925: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink900: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink875: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink850: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink825: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink800: Color @Composable @ReadOnlyComposable get() = c.cardElevated
    val Ink750: Color @Composable @ReadOnlyComposable get() = c.border
    val Ink725: Color @Composable @ReadOnlyComposable get() = c.border
    val Ink700: Color @Composable @ReadOnlyComposable get() = c.border
    val Ink675: Color @Composable @ReadOnlyComposable get() = c.border
    val Ink600: Color @Composable @ReadOnlyComposable get() = c.border

    // Status
    val Emerald500: Color @Composable @ReadOnlyComposable get() = c.positive
    val GreenA400: Color @Composable @ReadOnlyComposable get() = c.positive
    val Amber300: Color @Composable @ReadOnlyComposable get() = c.warning
    val Amber500: Color @Composable @ReadOnlyComposable get() = c.warning
    val OrangeA400: Color @Composable @ReadOnlyComposable get() = c.overlapOrange
    val Red500: Color @Composable @ReadOnlyComposable get() = c.critical
    val Rose500: Color @Composable @ReadOnlyComposable get() = c.critical
    val Purple400: Color @Composable @ReadOnlyComposable get() = c.overlapBlue
    val Purple500: Color @Composable @ReadOnlyComposable get() = c.overlapBlue
}

/**
 * Non-composable view of the same role mapping, for colours computed outside composition (data
 * models, draw lambdas). [StaticPalette] follows the active appearance via [ThemeColorsSnapshot];
 * [BrandPalette] is always Raahi Night (the splash / brand animation is dark by design).
 */
class PaletteMap(private val colors: () -> com.antigravity.copilot.core.designsystem.colors.CockpitColors) {
    private val c get() = colors()
    val Sky300 get() = c.accent
    val Sky400 get() = c.accent
    val Sky600 get() = c.accent
    val Sky700 get() = c.accent
    val Blue700 get() = c.accent
    val LightBlueA400 get() = c.accent
    val Cyan get() = c.accent
    val CyanNeon get() = c.accent
    val CyanElectric get() = c.accent
    val CyanBright get() = c.accent
    val Slate300 get() = c.textPrimary
    val Slate400 get() = c.textSecondary
    val Slate500 get() = c.textSecondary
    val Slate600 get() = c.textDisabled
    val Slate700 get() = c.border
    val Slate800 get() = c.border
    val Slate900 get() = c.cardElevated
    val Steel400 get() = c.textSecondary
    val Graphite700 get() = c.border
    val Ink950 get() = c.cardElevated
    val Ink925 get() = c.cardElevated
    val Ink900 get() = c.cardElevated
    val Ink875 get() = c.cardElevated
    val Ink850 get() = c.cardElevated
    val Ink825 get() = c.cardElevated
    val Ink800 get() = c.cardElevated
    val Ink750 get() = c.border
    val Ink725 get() = c.border
    val Ink700 get() = c.border
    val Ink675 get() = c.border
    val Ink600 get() = c.border
    val Emerald500 get() = c.positive
    val GreenA400 get() = c.positive
    val Amber300 get() = c.warning
    val Amber500 get() = c.warning
    val OrangeA400 get() = c.overlapOrange
    val Red500 get() = c.critical
    val Rose500 get() = c.critical
    val Purple400 get() = c.overlapBlue
    val Purple500 get() = c.overlapBlue
    val Background get() = c.bg
    // Semantic names (same as CockpitColors) for non-composable call sites
    val textPrimary get() = c.textPrimary
    val textSecondary get() = c.textSecondary
    val card get() = c.card
    val cardElevated get() = c.cardElevated
    val border get() = c.border
    val accent get() = c.accent
    val accentSoft get() = c.accentSoft
    val onAccent get() = c.onAccent
    val critical get() = c.critical
    val positive get() = c.positive
    val warning get() = c.warning
    val overlapBlue get() = c.overlapBlue
    val scrim get() = c.scrim
}

/** Latest resolved (non-animated) theme colours, published by `CopilotTheme`. */
object ThemeColorsSnapshot {
    @Volatile
    var colors: com.antigravity.copilot.core.designsystem.colors.CockpitColors =
        com.antigravity.copilot.core.designsystem.colors.RaahiNightColors
}

val StaticPalette = PaletteMap { ThemeColorsSnapshot.colors }
val BrandPalette = PaletteMap { com.antigravity.copilot.core.designsystem.colors.RaahiNightColors }
