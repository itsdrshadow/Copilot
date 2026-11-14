package com.antigravity.copilot.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ==================== THEME ENUMS & CONTRACTS ====================

enum class CockpitThemeMode(val id: String, val displayName: String, val subtitle: String) {
    APEX(
        id = "apex",
        displayName = "Apex Studio",
        subtitle = "Contemporary dark navy studio cockpit with luminous cyan telemetry"
    );

    companion object {
        // Backwards compatibility alias
        val PREMIUM = APEX

        fun fromId(@Suppress("UNUSED_PARAMETER") id: String): CockpitThemeMode {
            return APEX
        }
    }
}

enum class ThemeAppearanceMode(val id: String, val displayName: String) {
    NIGHT(id = "night", displayName = "Night");

    companion object {
        fun fromId(@Suppress("UNUSED_PARAMETER") id: String): ThemeAppearanceMode {
            return NIGHT
        }
    }
}

/**
 * Strict 12-token semantic design contract as specified in Step 2 & 3.
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
    // Top-light subtle gradient: card blended with 5% textPrimary fading to flat card
    val cardTopLightGradient: Brush,
    // Backwards-compatible fields for existing components during transition
    val background: Color = bg,
    val backgroundSecondary: Color = cardElevated,
    val cardSurface: Color = card,
    val cardSurfaceElevated: Color = cardElevated,
    val cardBorder: Color = border,
    val cardBorderGlow: Color = accent,
    val primaryAccent: Color = accent,
    val blueGlow: Color = accent,
    val greenEco: Color = accent,
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
    val isPremium: Boolean = true
)

// ==================== APEX STUDIO COCKPIT PALETTE ====================

val Apex_Background = Color(0xFF080E1B)
val Apex_BackgroundSecondary = Color(0xFF0C1424)
val Apex_CardSurface = Color(0xFF111D30)
val Apex_CardSurfaceElevated = Color(0xFF16243A)
val Apex_CardBorder = Color(0xFF243750)
val Apex_CardBorderGlow = Color(0xFF35BDF5)
val Apex_PrimaryAccent = Color(0xFF35BDF5)
val Apex_BlueGlow = Color(0xFF229ED9)
val Apex_Positive = Color(0xFF22C993)
val Apex_Warning = Color(0xFFF5B942)
val Apex_Danger = Color(0xFFF05B67)
val Apex_TextPrimary = Color(0xFFF3F6FC)
val Apex_TextSecondary = Color(0xFF91A1B8)
val Apex_TextTertiary = Color(0xFF5D718C)

// Backward-compatible color aliases
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

val ApexCockpitColors = CockpitColors(
    bg = Apex_Background,
    card = Apex_CardSurface,
    cardElevated = Apex_CardSurfaceElevated,
    border = Apex_CardBorder,
    textPrimary = Apex_TextPrimary,
    textSecondary = Apex_TextSecondary,
    textDisabled = Apex_TextTertiary,
    accent = Apex_PrimaryAccent,
    accentSoft = Color(0x2935BDF5),
    onAccent = Color(0xFF080E1B),
    warning = Apex_Warning,
    critical = Apex_Danger,
    cardTopLightGradient = Brush.verticalGradient(
        listOf(Apex_CardSurfaceElevated, Apex_CardSurface)
    ),
    greenEco = Apex_Positive,
    amberWarning = Apex_Warning,
    redAlert = Apex_Danger
)

val PremiumCockpitColors = ApexCockpitColors
val ClassicCockpitColors = ApexCockpitColors

// Compatibility Aliases for components
val Cockpit_DarkBg = Color(0xFF060A14)
val Cockpit_DarkBgSecondary = Color(0xFF0A1020)
val Cockpit_CardSurface = Color(0xFF0E172C)
val Cockpit_CardSurfaceElevated = Color(0xFF131F3B)
val Cockpit_CardBorder = Color(0xFF1F325A)
val Cockpit_CardBorderGlow = Color(0xFF2B467E)
val Cockpit_Cyan = Color(0xFF00E5FF)
val Cockpit_BlueAccent = Color(0xFF2979FF)
val Cockpit_BlueGlow = Color(0xFF448AFF)
val Cockpit_PurpleGlow = Color(0xFF7C4DFF)
val Cockpit_GreenEco = Color(0xFF00E676)
val Cockpit_AmberWarning = Color(0xFFFFAB00)
val Cockpit_RedAlert = Color(0xFFFF3D00)
val Cockpit_TextPrimary = Color(0xFFFFFFFF)
val Cockpit_TextSecondary = Color(0xFF8FA7D1)
val Cockpit_TextTertiary = Color(0xFF5A7099)
val Coolwalk_TealPlay = Color(0xFF2DD4BF)

// Composition Locals
val LocalCockpitColors = staticCompositionLocalOf { ApexCockpitColors }
val LocalCockpitThemeMode = staticCompositionLocalOf { CockpitThemeMode.APEX }

object CockpitTheme {
    val colors: CockpitColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitColors.current

    val mode: CockpitThemeMode
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitThemeMode.current

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
        LocalCockpitThemeMode provides themeMode
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

val CockpitTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Black,
        fontSize = 64.sp,
        letterSpacing = (-1.0).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp
    )
)
