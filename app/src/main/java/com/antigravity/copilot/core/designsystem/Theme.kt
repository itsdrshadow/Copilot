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

// ==================== THEME ENUMS & CONTRACTS ====================

enum class CockpitThemeMode(val id: String, val displayName: String, val subtitle: String) {
    GRADIENT_GLASS(
        id = "gradient_glass",
        displayName = "Gradient Glass",
        subtitle = "Frosted glassmorphism with splash cyan & deep sapphire reflections"
    ),
    APEX(
        id = "apex",
        displayName = "Apex Studio",
        subtitle = "Contemporary dark navy studio cockpit with luminous cyan telemetry"
    );

    companion object {
        // Backwards compatibility alias
        val PREMIUM = APEX

        fun fromId(id: String): CockpitThemeMode {
            return when (id.lowercase().trim()) {
                "gradient_glass", "glass", "splash_glass", "gradient" -> GRADIENT_GLASS
                "apex" -> APEX
                "premium" -> PREMIUM
                "classic", "calm_teal" -> APEX
                else -> GRADIENT_GLASS
            }
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
    val backgroundBrush: Brush = Brush.verticalGradient(listOf(bg, bg)),
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
    val dockBrush: Brush = cardTopLightGradient,
    val dockActionBrush: Brush = cardTopLightGradient,
    val isPremium: Boolean = true
)

// ==================== GRADIENT GLASS COCKPIT PALETTE ====================
// Derived from the Welcome Splash screen automotive aesthetic:
// Deep midnight sapphire gradient backdrop, luminous electric cyan telemetry,
// and frosted glass cards with top specular sheen and glowing translucent borders.

val Glass_Background = Color(0xFF02040A)
val Glass_BackgroundSecondary = Color(0xFF071026)
val Glass_CardSurface = Color(0xD908142A)         // 85% frosted midnight sapphire glass
val Glass_CardSurfaceElevated = Color(0xE60E2142) // 90% elevated frosted glass
val Glass_CardBorder = Color(0x4D00F0FF)          // Translucent neon cyan glass border
val Glass_CardBorderGlow = Color(0xFF00F0FF)      // Splash electric cyan glow
val Glass_PrimaryAccent = Color(0xFF00F0FF)       // Splash electric cyan
val Glass_AccentSoft = Color(0x2800F0FF)          // Soft cyan glass glow pill
val Glass_BlueGlow = Color(0xFF00D2FF)            // Mid cyan glow
val Glass_Positive = Color(0xFF00E5FF)            // Luminous cyan-teal
val Glass_Warning = Color(0xFFFBBF24)             // Luminous amber
val Glass_Danger = Color(0xFFF43F5E)              // Luminous crimson
val Glass_TextPrimary = Color(0xFFF4FAFF)         // Splash typography primary ice white
val Glass_TextSecondary = Color(0xFF7FAEE8)       // Splash subtitle aerospace blue
val Glass_TextTertiary = Color(0xFF4C6B99)        // Subdued muted blue-gray

val GradientGlassBackgroundBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF02040A),
        Color(0xFF071026),
        Color(0xFF040A18),
        Color(0xFF010307)
    )
)

val GradientGlassCardBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0x3300F0FF), // Top edge specular cyan reflection on glass
        Color(0xDD08142A), // Frosted dark sapphire body
        Color(0xF2030918)  // Deep midnight foundation
    )
)

val GradientGlassDockBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0x3500F0FF), // Specular cyan reflection on dock top
        Color(0xCC07132B), // Frosted sapphire glass body
        Color(0xF2020510)  // Deep midnight base
    )
)

val GradientGlassDockActionBrush = Brush.verticalGradient(
    colors = listOf(
        Color(0x2800F0FF), // Soft glass sheen
        Color(0xD90A1A36), // Frosted sapphire pill body
        Color(0xEE040A18)  // Midnight foundation
    )
)

val GradientGlassCockpitColors = CockpitColors(
    bg = Glass_Background,
    card = Glass_CardSurface,
    cardElevated = Glass_CardSurfaceElevated,
    border = Glass_CardBorder,
    textPrimary = Glass_TextPrimary,
    textSecondary = Glass_TextSecondary,
    textDisabled = Glass_TextTertiary,
    accent = Glass_PrimaryAccent,
    accentSoft = Glass_AccentSoft,
    onAccent = Color(0xFF010612),
    warning = Glass_Warning,
    critical = Glass_Danger,
    cardTopLightGradient = GradientGlassCardBrush,
    backgroundBrush = GradientGlassBackgroundBrush,
    background = Glass_Background,
    backgroundSecondary = Glass_BackgroundSecondary,
    cardSurface = Glass_CardSurface,
    cardSurfaceElevated = Glass_CardSurfaceElevated,
    cardBorder = Glass_CardBorder,
    cardBorderGlow = Glass_CardBorderGlow,
    primaryAccent = Glass_PrimaryAccent,
    blueGlow = Glass_BlueGlow,
    greenEco = Glass_Positive,
    amberWarning = Glass_Warning,
    redAlert = Glass_Danger,
    textTertiary = Glass_TextTertiary,
    cardGradientOdo = GradientGlassCardBrush,
    cardGradientFuel = GradientGlassCardBrush,
    cardGradientTrip = GradientGlassCardBrush,
    cardGradientSpeedo = GradientGlassCardBrush,
    activeCardBrush = GradientGlassCardBrush,
    dockBackground = Glass_CardSurfaceElevated,
    dockBorder = Glass_CardBorder,
    dockBrush = GradientGlassDockBrush,
    dockActionBrush = GradientGlassDockActionBrush,
    isPremium = true
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
    dockBrush = Brush.verticalGradient(
        listOf(Apex_CardSurfaceElevated, Apex_CardSurface)
    ),
    dockActionBrush = Brush.verticalGradient(
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
val LocalCockpitColors = staticCompositionLocalOf { GradientGlassCockpitColors }
val LocalCockpitThemeMode = staticCompositionLocalOf { CockpitThemeMode.GRADIENT_GLASS }

object CockpitTheme {
    val colors: CockpitColors
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitColors.current

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
    themeMode: CockpitThemeMode = CockpitThemeMode.GRADIENT_GLASS,
    @Suppress("UNUSED_PARAMETER") appearanceMode: ThemeAppearanceMode = ThemeAppearanceMode.NIGHT,
    @Suppress("UNUSED_PARAMETER") isDaytimeCalculated: Boolean = false,
    content: @Composable () -> Unit
) {
    val targetBaseColors = when (themeMode) {
        CockpitThemeMode.GRADIENT_GLASS -> GradientGlassCockpitColors
        CockpitThemeMode.APEX -> ApexCockpitColors
    }

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

    val animatedCardGradient = when (themeMode) {
        CockpitThemeMode.GRADIENT_GLASS -> GradientGlassCardBrush
        CockpitThemeMode.APEX -> Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    }

    val activeBackgroundBrush = when (themeMode) {
        CockpitThemeMode.GRADIENT_GLASS -> GradientGlassBackgroundBrush
        CockpitThemeMode.APEX -> Brush.verticalGradient(listOf(animatedBg, animatedBg))
    }

    val activeDockBrush = when (themeMode) {
        CockpitThemeMode.GRADIENT_GLASS -> GradientGlassDockBrush
        CockpitThemeMode.APEX -> Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
    }

    val activeDockActionBrush = when (themeMode) {
        CockpitThemeMode.GRADIENT_GLASS -> GradientGlassDockActionBrush
        CockpitThemeMode.APEX -> Brush.verticalGradient(listOf(animatedCardElevated, animatedCard))
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
        LocalAutomotiveTokens provides AutomotiveDesignTokens
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
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        fontFeatureSettings = "tnum"
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
