package com.antigravity.copilot.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

val Premium_Background = Apex_Background
val Premium_BackgroundSecondary = Apex_BackgroundSecondary
val Premium_CardSurface = Apex_CardSurface
val Premium_CardSurfaceElevated = Apex_CardSurfaceElevated
val Premium_CardBorder = Apex_CardBorder
val Premium_CardBorderGlow = Apex_CardBorderGlow
val Premium_PrimaryAccent = Apex_PrimaryAccent
val Premium_BlueGlow = Apex_BlueGlow
val Premium_Positive = Apex_Positive
val Premium_Warning = Apex_Warning
val Premium_Danger = Apex_Danger
val Premium_TextPrimary = Apex_TextPrimary
val Premium_TextSecondary = Apex_TextSecondary
val Premium_TextTertiary = Apex_TextTertiary

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
}

// ==================== DYNAMIC ANIMATED THEME PROVIDER ====================

@Composable
fun CopilotTheme(
    themeMode: CockpitThemeMode = CockpitThemeMode.APEX,
    @Suppress("UNUSED_PARAMETER") appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.NIGHT,
    @Suppress("UNUSED_PARAMETER") isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetBaseColors = ApexCockpitColors

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

    val animatedCardGradient = Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    val activeBackgroundBrush = Brush.verticalGradient(listOf(animatedBg, animatedBg))
    val activeDockBrush = Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    val activeDockActionBrush = Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))

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
        cardBorderGlow = animatedAccent,
        primaryAccent = animatedAccent,
        blueGlow = animatedAccent,
        greenEco = animatedAccent,
        amberWarning = animatedWarning,
        redAlert = animatedCritical,
        textTertiary = animatedTextDisabled,
        cardGradientOdo = animatedCardGradient,
        cardGradientFuel = animatedCardGradient,
        cardGradientTrip = animatedCardGradient,
        cardGradientSpeedo = animatedCardGradient,
        activeCardBrush = animatedCardGradient,
        dockBackground = animatedCardElevated,
        dockBorder = animatedBorder,
        dockBrush = activeDockBrush,
        dockActionBrush = activeDockActionBrush,
        isPremium = true
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

    CompositionLocalProvider(
        LocalCockpitColors provides activeColors,
        LocalCockpitThemeMode provides themeMode,
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

@Composable
fun DrivingReportTheme(
    themeMode: CockpitThemeMode = CockpitThemeMode.APEX,
    appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.NIGHT,
    isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) = CopilotTheme(themeMode, appearanceMode, isDaytimeCalculated, content)
