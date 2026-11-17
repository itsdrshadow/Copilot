package com.antigravity.copilot.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.colors.*
import com.antigravity.copilot.core.designsystem.typography.*

// Re-export color types and objects for backward compatibility
typealias CockpitColors = com.antigravity.copilot.core.designsystem.colors.CockpitColors
typealias CockpitThemeMode = com.antigravity.copilot.core.designsystem.colors.CockpitThemeMode
typealias ThemeAppearanceMode = com.antigravity.copilot.core.designsystem.colors.ThemeAppearanceMode

val Apex_Background = com.antigravity.copilot.core.designsystem.colors.Apex_Background
val Apex_BackgroundSecondary = com.antigravity.copilot.core.designsystem.colors.Apex_BackgroundSecondary
val Apex_CardSurface = com.antigravity.copilot.core.designsystem.colors.Apex_CardSurface
val Apex_CardSurfaceElevated = com.antigravity.copilot.core.designsystem.colors.Apex_CardSurfaceElevated
val Apex_CardBorder = com.antigravity.copilot.core.designsystem.colors.Apex_CardBorder
val Apex_CardBorderGlow = com.antigravity.copilot.core.designsystem.colors.Apex_CardBorderGlow
val Apex_PrimaryAccent = com.antigravity.copilot.core.designsystem.colors.Apex_PrimaryAccent
val Apex_BlueGlow = com.antigravity.copilot.core.designsystem.colors.Apex_BlueGlow
val Apex_Positive = com.antigravity.copilot.core.designsystem.colors.Apex_Positive
val Apex_Warning = com.antigravity.copilot.core.designsystem.colors.Apex_Warning
val Apex_Danger = com.antigravity.copilot.core.designsystem.colors.Apex_Danger
val Apex_TextPrimary = com.antigravity.copilot.core.designsystem.colors.Apex_TextPrimary
val Apex_TextSecondary = com.antigravity.copilot.core.designsystem.colors.Apex_TextSecondary
val Apex_TextTertiary = com.antigravity.copilot.core.designsystem.colors.Apex_TextTertiary


val Cockpit_DarkBg = com.antigravity.copilot.core.designsystem.colors.Cockpit_DarkBg
val Cockpit_DarkBgSecondary = com.antigravity.copilot.core.designsystem.colors.Cockpit_DarkBgSecondary
val Cockpit_CardSurface = com.antigravity.copilot.core.designsystem.colors.Cockpit_CardSurface
val Cockpit_CardSurfaceElevated = com.antigravity.copilot.core.designsystem.colors.Cockpit_CardSurfaceElevated
val Cockpit_CardBorder = com.antigravity.copilot.core.designsystem.colors.Cockpit_CardBorder
val Cockpit_CardBorderGlow = com.antigravity.copilot.core.designsystem.colors.Cockpit_CardBorderGlow
val Cockpit_Cyan = com.antigravity.copilot.core.designsystem.colors.Cockpit_Cyan
val Cockpit_BlueAccent = com.antigravity.copilot.core.designsystem.colors.Cockpit_BlueAccent
val Cockpit_BlueGlow = com.antigravity.copilot.core.designsystem.colors.Cockpit_BlueGlow
val Cockpit_PurpleGlow = com.antigravity.copilot.core.designsystem.colors.Cockpit_PurpleGlow
val Cockpit_GreenEco = com.antigravity.copilot.core.designsystem.colors.Cockpit_GreenEco
val Cockpit_AmberWarning = com.antigravity.copilot.core.designsystem.colors.Cockpit_AmberWarning
val Cockpit_RedAlert = com.antigravity.copilot.core.designsystem.colors.Cockpit_RedAlert
val Cockpit_TextPrimary = com.antigravity.copilot.core.designsystem.colors.Cockpit_TextPrimary
val Cockpit_TextSecondary = com.antigravity.copilot.core.designsystem.colors.Cockpit_TextSecondary
val Cockpit_TextTertiary = com.antigravity.copilot.core.designsystem.colors.Cockpit_TextTertiary
val Coolwalk_TealPlay = com.antigravity.copilot.core.designsystem.colors.Coolwalk_TealPlay
val ApexCockpitColors = com.antigravity.copilot.core.designsystem.colors.ApexCockpitColors
val PremiumCockpitColors = ApexCockpitColors
val ClassicCockpitColors = ApexCockpitColors
val LiquidGlassCockpitColors = com.antigravity.copilot.core.designsystem.colors.LiquidGlassCockpitColors
val LocalCockpitColors = com.antigravity.copilot.core.designsystem.colors.LocalCockpitColors
val LocalCockpitThemeMode = com.antigravity.copilot.core.designsystem.colors.LocalCockpitThemeMode

// Re-export typography for backward compatibility
val CockpitTypography = com.antigravity.copilot.core.designsystem.typography.CockpitTypography
val AutomotiveTypographyTokens = com.antigravity.copilot.core.designsystem.typography.AutomotiveTypographyTokens

object CockpitTheme {
    val colors: CockpitColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitColors.current

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitTypography.current

    val mode: CockpitThemeMode
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitThemeMode.current

    val tokens: AutomotiveDesignTokens
        @Composable
        @ReadOnlyComposable
        get() = LocalAutomotiveTokens.current

    val isPremium: Boolean
        @Composable
        @ReadOnlyComposable
        get() = true

    /** True while Liquid Glass is active — lets screens opt into glass-only touches. */
    val isGlass: Boolean
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitColors.current.glass != null
}

// ==================== DYNAMIC ANIMATED THEME PROVIDER ====================

@Composable
fun CopilotTheme(
    themeMode: CockpitThemeMode = CockpitThemeMode.APEX,
    @Suppress("UNUSED_PARAMETER") appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.NIGHT,
    @Suppress("UNUSED_PARAMETER") isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetBaseColors = cockpitColorsFor(themeMode)
    val isGlass = themeMode.isGlass
    val activeTypography = if (isGlass) LiquidGlassTypography else CockpitTypography

    val animSpec = tween<Color>(durationMillis = 300)

    val animatedBg = animateColorAsState(targetValue = targetBaseColors.bg, animationSpec = animSpec, label = "bg").value
    val animatedCard = animateColorAsState(targetValue = targetBaseColors.card, animationSpec = animSpec, label = "card").value
    val animatedCardElevated = animateColorAsState(targetValue = targetBaseColors.cardElevated, animationSpec = animSpec, label = "cardElevated").value
    val animatedBorder = animateColorAsState(targetValue = targetBaseColors.border, animationSpec = animSpec, label = "border").value
    val animatedTextPrimary = animateColorAsState(targetValue = targetBaseColors.textPrimary, animationSpec = animSpec, label = "textPrimary").value
    val animatedTextSecondary = animateColorAsState(targetValue = targetBaseColors.textSecondary, animationSpec = animSpec, label = "textSecondary").value
    val animatedTextDisabled = animateColorAsState(targetValue = targetBaseColors.textDisabled, animationSpec = animSpec, label = "textDisabled").value
    val animatedAccent = animateColorAsState(targetValue = targetBaseColors.accent, animationSpec = animSpec, label = "accent").value
    val animatedAccentSoft = animateColorAsState(targetValue = targetBaseColors.accentSoft, animationSpec = animSpec, label = "accentSoft").value
    val animatedOnAccent = animateColorAsState(targetValue = targetBaseColors.onAccent, animationSpec = animSpec, label = "onAccent").value
    val animatedWarning = animateColorAsState(targetValue = targetBaseColors.warning, animationSpec = animSpec, label = "warning").value
    val animatedCritical = animateColorAsState(targetValue = targetBaseColors.critical, animationSpec = animSpec, label = "critical").value

    // Apex keeps its historical animated brushes; Liquid Glass uses its pre-built glass brushes.
    val animatedCardGradient = if (isGlass) targetBaseColors.cardTopLightGradient
        else Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    val activeBackgroundBrush = if (isGlass) targetBaseColors.backgroundBrush
        else Brush.verticalGradient(listOf(animatedBg, animatedBg))
    val activeDockBrush = if (isGlass) targetBaseColors.dockBrush
        else Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    val activeDockActionBrush = if (isGlass) targetBaseColors.dockActionBrush
        else Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))

    val activeColors = CockpitColors(
        bg = animatedBg,
        card = animatedCard,
        cardElevated = animatedCardElevated,
        border = animatedBorder,
        textPrimary = animatedTextPrimary,
        textSecondary = animatedTextSecondary,
        textDisabled = animatedTextDisabled,
        accent = animatedAccent,
        accentSoft = animatedAccentSoft,
        onAccent = animatedOnAccent,
        warning = animatedWarning,
        critical = animatedCritical,
        cardTopLightGradient = animatedCardGradient,
        backgroundBrush = activeBackgroundBrush,
        background = animatedBg,
        backgroundSecondary = animatedCardElevated,
        cardSurface = animatedCard,
        cardSurfaceElevated = animatedCardElevated,
        cardBorder = animatedBorder,
        cardBorderGlow = if (isGlass) targetBaseColors.cardBorderGlow else animatedAccent,
        primaryAccent = animatedAccent,
        blueGlow = if (isGlass) targetBaseColors.blueGlow else animatedAccent,
        greenEco = if (isGlass) targetBaseColors.greenEco else animatedAccent,
        amberWarning = animatedWarning,
        redAlert = animatedCritical,
        textTertiary = animatedTextDisabled,
        cardGradientOdo = animatedCardGradient,
        cardGradientFuel = animatedCardGradient,
        cardGradientTrip = animatedCardGradient,
        cardGradientSpeedo = animatedCardGradient,
        activeCardBrush = animatedCardGradient,
        dockBackground = if (isGlass) targetBaseColors.dockBackground else animatedCardElevated,
        dockBorder = animatedBorder,
        dockBrush = activeDockBrush,
        dockActionBrush = activeDockActionBrush,
        isPremium = true,
        glass = targetBaseColors.glass
    )

    val materialColors = darkColorScheme(
        primary = animatedAccent,
        onPrimary = animatedOnAccent,
        primaryContainer = animatedCardElevated,
        onPrimaryContainer = animatedAccent,
        secondary = animatedAccent,
        onSecondary = animatedOnAccent,
        background = animatedBg,
        onBackground = animatedTextPrimary,
        surface = animatedCard,
        onSurface = animatedTextPrimary,
        surfaceVariant = animatedCardElevated,
        onSurfaceVariant = animatedTextSecondary,
        outline = animatedBorder,
        error = animatedCritical,
        onError = animatedOnAccent
    )

    // System bars are hidden (immersive), but tint them for the swipe-to-reveal transient state.
    // Runs once per theme change; never touches the MapView or forces activity recreation.
    val view = LocalView.current
    if (!view.isInEditMode) {
        LaunchedEffect(themeMode) {
            val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
            val barColor = targetBaseColors.bg.toArgb()
            @Suppress("DEPRECATION")
            window.statusBarColor = barColor
            @Suppress("DEPRECATION")
            window.navigationBarColor = barColor
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    CompositionLocalProvider(
        LocalCockpitColors provides activeColors,
        LocalCockpitThemeMode provides themeMode,
        LocalAutomotiveTokens provides AutomotiveDesignTokens,
        LocalCockpitTypography provides activeTypography
    ) {
        MaterialTheme(
            colorScheme = materialColors,
            typography = activeTypography,
            content = content
        )
    }
}
