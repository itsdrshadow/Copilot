package com.antigravity.copilot.core.designsystem

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.antigravity.copilot.core.designsystem.colors.LocalCockpitColors as ColorsLocal
import com.antigravity.copilot.core.designsystem.colors.LocalCockpitThemeMode as ThemeModeLocal
import com.antigravity.copilot.core.designsystem.colors.LocalMapPalette as MapPaletteLocal
import com.antigravity.copilot.core.designsystem.colors.MapPalette
import com.antigravity.copilot.core.designsystem.colors.cockpitColorsFor
import com.antigravity.copilot.core.designsystem.colors.mapPaletteFor
import com.antigravity.copilot.core.designsystem.typography.LocalCockpitTypography

// Re-export colour types for call sites that import them from this package.
typealias CockpitColors = com.antigravity.copilot.core.designsystem.colors.CockpitColors
typealias CockpitThemeMode = com.antigravity.copilot.core.designsystem.colors.CockpitThemeMode
typealias ThemeAppearanceMode = com.antigravity.copilot.core.designsystem.colors.ThemeAppearanceMode

val ApexCockpitColors = com.antigravity.copilot.core.designsystem.colors.RaahiNightColors
val PremiumCockpitColors = ApexCockpitColors
val ClassicCockpitColors = ApexCockpitColors
val LocalCockpitColors = ColorsLocal
val LocalCockpitThemeMode = ThemeModeLocal

val CockpitTypography = com.antigravity.copilot.core.designsystem.typography.CockpitTypography
val AutomotiveTypographyTokens = com.antigravity.copilot.core.designsystem.typography.AutomotiveTypographyTokens

object CockpitTheme {
    val colors: CockpitColors
        @Composable
        @ReadOnlyComposable
        get() = ColorsLocal.current

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitTypography.current

    val mode: CockpitThemeMode
        @Composable
        @ReadOnlyComposable
        get() = ThemeModeLocal.current

    val tokens: AutomotiveDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAutomotiveTokens.current

    /** Basemap colours for the active appearance. */
    val map: MapPalette
        @Composable
        @ReadOnlyComposable
        get() = MapPaletteLocal.current

    val isDark: Boolean
        @Composable
        @ReadOnlyComposable
        get() = ColorsLocal.current.isDark

    val isPremium: Boolean
        @Composable
        @ReadOnlyComposable
        get() = true
}

@Composable
private fun animated(target: Color, label: String): Color =
    animateColorAsState(targetValue = target, animationSpec = tween(durationMillis = 300), label = label).value

/**
 * Raahi theme provider. [appearanceMode] is the Settings choice (Auto / Night / Day); Auto is
 * resolved by [rememberIsDay]. Colours cross-fade on a switch; the activity is never recreated, so
 * the MapView survives.
 */
@Composable
fun CopilotTheme(
    themeMode: CockpitThemeMode = CockpitThemeMode.RAAHI,
    appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.AUTO,
    @Suppress("UNUSED_PARAMETER") isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDay = rememberIsDay(appearanceMode)
    val base = cockpitColorsFor(isDay)

    val bg = animated(base.bg, "bg")
    val rail = animated(base.rail, "rail")
    val card = animated(base.card, "card")
    val cardRaised = animated(base.cardElevated, "cardRaised")
    val border = animated(base.border, "border")
    val textPrimary = animated(base.textPrimary, "textPrimary")
    val textSecondary = animated(base.textSecondary, "textSecondary")
    val textDisabled = animated(base.textDisabled, "textDisabled")
    val accent = animated(base.accent, "accent")
    val warning = animated(base.warning, "warning")
    val critical = animated(base.critical, "critical")
    val positive = animated(base.positive, "positive")

    val solidCard = Brush.verticalGradient(listOf(card, card))
    val solidRaised = Brush.verticalGradient(listOf(cardRaised, cardRaised))

    val colors = base.copy(
        bg = bg,
        card = card,
        cardElevated = cardRaised,
        border = border,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        textDisabled = textDisabled,
        accent = accent,
        accentSoft = accent.copy(alpha = base.accentSoft.alpha),
        warning = warning,
        critical = critical,
        positive = positive,
        rail = rail,
        cardTopLightGradient = solidCard,
        backgroundBrush = Brush.verticalGradient(listOf(bg, bg)),
        background = bg,
        backgroundSecondary = cardRaised,
        cardSurface = card,
        cardSurfaceElevated = cardRaised,
        cardBorder = border,
        cardBorderGlow = accent,
        primaryAccent = accent,
        blueGlow = accent,
        greenEco = positive,
        amberWarning = warning,
        redAlert = critical,
        textTertiary = textDisabled,
        cardGradientOdo = solidCard,
        cardGradientFuel = solidCard,
        cardGradientTrip = solidCard,
        cardGradientSpeedo = solidCard,
        activeCardBrush = solidCard,
        dockBackground = card,
        dockBorder = border,
        dockBrush = solidCard,
        dockActionBrush = solidRaised
    )

    val materialColors = if (isDay) {
        lightColorScheme(
            primary = accent, onPrimary = base.onAccent,
            primaryContainer = cardRaised, onPrimaryContainer = textPrimary,
            secondary = accent, onSecondary = base.onAccent,
            background = bg, onBackground = textPrimary,
            surface = card, onSurface = textPrimary,
            surfaceVariant = cardRaised, onSurfaceVariant = textSecondary,
            surfaceContainerHigh = card, surfaceContainer = card,
            outline = border, outlineVariant = border,
            error = critical, onError = Color.White
        )
    } else {
        darkColorScheme(
            primary = accent, onPrimary = base.onAccent,
            primaryContainer = cardRaised, onPrimaryContainer = textPrimary,
            secondary = accent, onSecondary = base.onAccent,
            background = bg, onBackground = textPrimary,
            surface = card, onSurface = textPrimary,
            surfaceVariant = cardRaised, onSurfaceVariant = textSecondary,
            surfaceContainerHigh = card, surfaceContainer = card,
            outline = border, outlineVariant = border,
            error = critical, onError = base.onAccent
        )
    }

    // System bars are hidden (immersive); tint them for the swipe-to-reveal transient state.
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(isDay) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val barColor = base.bg.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = barColor
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isDay
                isAppearanceLightNavigationBars = isDay
            }
        }
    }

    CompositionLocalProvider(
        ColorsLocal provides colors,
        ThemeModeLocal provides themeMode,
        MapPaletteLocal provides mapPaletteFor(isDay),
        LocalAutomotiveTokens provides AutomotiveDesignTokens,
        LocalCockpitTypography provides CockpitTypography
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = CockpitTypography,
            content = content
        )
    }
}
