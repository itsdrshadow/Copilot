package com.antigravity.drivingreport.core.designsystem

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
    PREMIUM(
        id = "premium",
        displayName = "Premium Studio",
        subtitle = "Contemporary dark navy studio cockpit with luminous cyan telemetry"
    ),
    CALM_TEAL(
        id = "calm_teal",
        displayName = "Calm Teal",
        subtitle = "Refined automotive palette with calming teal accent and balanced contrast"
    );

    companion object {
        fun fromId(id: String): CockpitThemeMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: PREMIUM
        }
    }
}

enum class ThemeAppearanceMode(val id: String, val displayName: String) {
    AUTO(id = "auto", displayName = "Auto (Solar / Time)"),
    DAY(id = "day", displayName = "Day"),
    NIGHT(id = "night", displayName = "Night");

    companion object {
        fun fromId(id: String): ThemeAppearanceMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
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

// ==================== CALM TEAL TOKEN SPECIFICATIONS ====================

// Night Tokens (Default)
val CalmTeal_Night_Bg = Color(0xFF0E1319)
val CalmTeal_Night_Card = Color(0xFF161C24)
val CalmTeal_Night_CardElevated = Color(0xFF1B222C)
val CalmTeal_Night_Border = Color(0xFF232B36)
val CalmTeal_Night_TextPrimary = Color(0xFFE6EAF0)
val CalmTeal_Night_TextSecondary = Color(0xFF9AA6B5)
val CalmTeal_Night_TextDisabled = Color(0xFF55606E)
val CalmTeal_Night_Accent = Color(0xFF4FD1C5)
val CalmTeal_Night_AccentSoft = Color(0x294FD1C5) // ~16% opacity (0x29 / 0xFF ~= 0.16)
val CalmTeal_Night_OnAccent = Color(0xFF06201E)
val CalmTeal_Night_Warning = Color(0xFFF2B35E)
val CalmTeal_Night_Critical = Color(0xFFFF6B6B)

// Card blended with 5% text-primary at the top, fading to flat card by 40% height
val CalmTeal_Night_CardGradient = Brush.verticalGradient(
    0.0f to Color(0xFF202630), // blended 5% #E6EAF0 into #161C24
    0.4f to Color(0xFF161C24),
    1.0f to Color(0xFF161C24)
)

val CalmTealNightColors = CockpitColors(
    bg = CalmTeal_Night_Bg,
    card = CalmTeal_Night_Card,
    cardElevated = CalmTeal_Night_CardElevated,
    border = CalmTeal_Night_Border,
    textPrimary = CalmTeal_Night_TextPrimary,
    textSecondary = CalmTeal_Night_TextSecondary,
    textDisabled = CalmTeal_Night_TextDisabled,
    accent = CalmTeal_Night_Accent,
    accentSoft = CalmTeal_Night_AccentSoft,
    onAccent = CalmTeal_Night_OnAccent,
    warning = CalmTeal_Night_Warning,
    critical = CalmTeal_Night_Critical,
    cardTopLightGradient = CalmTeal_Night_CardGradient
)

// Day Tokens
val CalmTeal_Day_Bg = Color(0xFFEEF1F5)
val CalmTeal_Day_Card = Color(0xFFFFFFFF)
val CalmTeal_Day_CardElevated = Color(0xFFF3F5F8)
val CalmTeal_Day_Border = Color(0xFFD8DEE6)
val CalmTeal_Day_TextPrimary = Color(0xFF1B2028)
val CalmTeal_Day_TextSecondary = Color(0xFF566272)
val CalmTeal_Day_TextDisabled = Color(0xFFA0AAB8)
val CalmTeal_Day_Accent = Color(0xFF0E8F8A)
val CalmTeal_Day_AccentSoft = Color(0x290E8F8A) // ~16% opacity
val CalmTeal_Day_OnAccent = Color(0xFFFFFFFF)
val CalmTeal_Day_Warning = Color(0xFFB96F0A)
val CalmTeal_Day_Critical = Color(0xFFD63B3B)

val CalmTeal_Day_CardGradient = Brush.verticalGradient(
    0.0f to Color(0xFFF5F6F8),
    0.4f to Color(0xFFFFFFFF),
    1.0f to Color(0xFFFFFFFF)
)

val CalmTealDayColors = CockpitColors(
    bg = CalmTeal_Day_Bg,
    card = CalmTeal_Day_Card,
    cardElevated = CalmTeal_Day_CardElevated,
    border = CalmTeal_Day_Border,
    textPrimary = CalmTeal_Day_TextPrimary,
    textSecondary = CalmTeal_Day_TextSecondary,
    textDisabled = CalmTeal_Day_TextDisabled,
    accent = CalmTeal_Day_Accent,
    accentSoft = CalmTeal_Day_AccentSoft,
    onAccent = CalmTeal_Day_OnAccent,
    warning = CalmTeal_Day_Warning,
    critical = CalmTeal_Day_Critical,
    cardTopLightGradient = CalmTeal_Day_CardGradient
)

// ==================== PREMIUM COCKPIT PALETTE ====================

val Premium_Background = Color(0xFF080E1B)
val Premium_BackgroundSecondary = Color(0xFF0C1424)
val Premium_CardSurface = Color(0xFF111D30)
val Premium_CardSurfaceElevated = Color(0xFF16243A)
val Premium_CardBorder = Color(0xFF243750)
val Premium_CardBorderGlow = Color(0xFF35BDF5)
val Premium_PrimaryAccent = Color(0xFF35BDF5)
val Premium_BlueGlow = Color(0xFF229ED9)
val Premium_Positive = Color(0xFF22C993)
val Premium_Warning = Color(0xFFF5B942)
val Premium_Danger = Color(0xFFF05B67)
val Premium_TextPrimary = Color(0xFFF3F6FC)
val Premium_TextSecondary = Color(0xFF91A1B8)
val Premium_TextTertiary = Color(0xFF5D718C)

val PremiumCockpitColors = CockpitColors(
    bg = Premium_Background,
    card = Premium_CardSurface,
    cardElevated = Premium_CardSurfaceElevated,
    border = Premium_CardBorder,
    textPrimary = Premium_TextPrimary,
    textSecondary = Premium_TextSecondary,
    textDisabled = Premium_TextTertiary,
    accent = Premium_PrimaryAccent,
    accentSoft = Color(0x2935BDF5),
    onAccent = Color(0xFF080E1B),
    warning = Premium_Warning,
    critical = Premium_Danger,
    cardTopLightGradient = Brush.verticalGradient(
        listOf(Premium_CardSurfaceElevated, Premium_CardSurface)
    ),
    greenEco = Premium_Positive,
    amberWarning = Premium_Warning,
    redAlert = Premium_Danger
)

// Classic Cockpit Palette (Backwards compatibility)
val ClassicCockpitColors = PremiumCockpitColors

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
val LocalCockpitColors = staticCompositionLocalOf { CalmTealNightColors }
val LocalCockpitThemeMode = staticCompositionLocalOf { CockpitThemeMode.CALM_TEAL }

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
        get() = mode == CockpitThemeMode.PREMIUM
}

// ==================== DYNAMIC ANIMATED THEME PROVIDER ====================

@Composable
fun DrivingReportTheme(
    themeMode: CockpitThemeMode = CockpitThemeMode.CALM_TEAL,
    appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.AUTO,
    isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) {
    val isDay = when (appearanceMode) {
        ThemeAppearanceMode.AUTO -> isDaytimeCalculated
        ThemeAppearanceMode.DAY -> true
        ThemeAppearanceMode.NIGHT -> false
    }

    val targetBaseColors = when (themeMode) {
        CockpitThemeMode.PREMIUM -> PremiumCockpitColors
        CockpitThemeMode.CALM_TEAL -> if (isDay) CalmTealDayColors else CalmTealNightColors
    }

    // 2 to 3 second gradual color fade transition between Day and Night (2400ms)
    val animSpec = tween<Color>(durationMillis = 2400)

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

    val animatedCardGradient = when (themeMode) {
        CockpitThemeMode.PREMIUM -> Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
        CockpitThemeMode.CALM_TEAL -> {
            if (isDay) {
                Brush.verticalGradient(
                    0.0f to animatedCardElevated,
                    0.4f to animatedCard,
                    1.0f to animatedCard
                )
            } else {
                Brush.verticalGradient(
                    0.0f to animatedCardElevated,
                    0.4f to animatedCard,
                    1.0f to animatedCard
                )
            }
        }
    }

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
        isPremium = (themeMode == CockpitThemeMode.PREMIUM)
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
