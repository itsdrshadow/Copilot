package com.antigravity.copilot.core.designsystem.colors

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ==================== THEME ENUMS & CONTRACTS ====================

enum class CockpitThemeMode(val id: String, val displayName: String, val subtitle: String) {
    APEX(
        id = "apex",
        displayName = "Apex Studio",
        subtitle = "Contemporary dark navy studio cockpit with luminous cyan telemetry"
    );

    companion object {
        val PREMIUM = APEX

        fun fromId(id: String): CockpitThemeMode {
            return when (id.lowercase().trim()) {
                // Legacy ids from retired themes (incl. Liquid Glass) fold into Apex.
                "liquid_glass", "liquidglass",
                "apex", "premium", "classic", "calm_teal", "gradient_glass", "glass", "splash_glass", "gradient" -> APEX
                else -> APEX
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
 * Strict 12-token semantic design contract.
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
    val backgroundBrush: Brush = Brush.verticalGradient(listOf(bg, bg)),
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

fun cockpitColorsFor(mode: CockpitThemeMode): CockpitColors = when (mode) {
    CockpitThemeMode.APEX -> ApexCockpitColors
}

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
