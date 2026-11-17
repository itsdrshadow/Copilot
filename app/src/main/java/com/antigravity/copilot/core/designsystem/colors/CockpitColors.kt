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
    ),
    LIQUID_GLASS(
        id = "liquid_glass",
        displayName = "Liquid Glass",
        subtitle = "Translucent midnight glass with specular edges and Google Sans type"
    );

    val isGlass: Boolean get() = this == LIQUID_GLASS

    companion object {
        val PREMIUM = APEX

        fun fromId(id: String): CockpitThemeMode {
            return when (id.lowercase().trim()) {
                "liquid_glass", "liquidglass" -> LIQUID_GLASS
                // Legacy ids from retired themes (incl. the old "glass" variants) fold into Apex.
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
    val isPremium: Boolean = true,
    /** Non-null only for Liquid Glass; drives the specular/sheen treatment in `cockpitSurface`. */
    val glass: GlassStyle? = null
)

/**
 * Lightweight "glass" recipe: no RenderEffect/backdrop blur. Depth comes from a translucent
 * surface fill, a white sheen overlay and a 1dp specular border. All brushes are allocated
 * once here, so drawing them costs no per-frame allocations.
 */
data class GlassStyle(
    /** Overlay on a neutral surface: bright top-left fading to near-clear bottom-right. */
    val sheen: Brush,
    /** 1dp edge: specular highlight top-left → faint rim bottom-right. */
    val specularBorder: Brush,
    /** Cheap RenderNode outline shadow under neutral glass surfaces. */
    val shadowElevationDp: Float
)

// ==================== LIQUID GLASS PALETTE ====================

val Glass_Base = Color(0xFF0B1220)
val Glass_BaseIndigoTint = Color(0xFF111735)
val Glass_BaseTealTint = Color(0xFF0A1A22)
val Glass_Surface = Color(0xD1111A2E) // #111A2E @ 82%
val Glass_SurfaceElevated = Color(0xD91A2540) // lifted panel @ 85%
val Glass_SurfaceLow = Color(0xC70E1628) // #0E1628 @ 78%
val Glass_Border = Color(0x2EFFFFFF)
val Glass_Cyan = Color(0xFF38BDF8)
val Glass_Indigo = Color(0xFF818CF8)
val Glass_Emerald = Color(0xFF34D399)
val Glass_Amber = Color(0xFFFBBF24)
val Glass_Rose = Color(0xFFFB7185)
val Glass_TextPrimary = Color(0xFFF5F8FF)
val Glass_TextSecondary = Color(0xFFA9B6CC)
val Glass_TextTertiary = Color(0xFF6B7A93)

val LiquidGlassStyle = GlassStyle(
    sheen = Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x12FFFFFF), Color(0x0DFFFFFF))),
    specularBorder = Brush.linearGradient(listOf(Color(0x55FFFFFF), Color(0x24FFFFFF), Color(0x14FFFFFF))),
    shadowElevationDp = 3f
)

// Pre-composited sheen-over-surface fill for screens that paint a single brush.
private val Glass_CardFill = Brush.linearGradient(
    listOf(Color(0xD9353C4D), Color(0xD1252D41), Color(0xC71D2538))
)

val LiquidGlassCockpitColors = CockpitColors(
    bg = Glass_Base,
    card = Glass_Surface,
    cardElevated = Glass_SurfaceElevated,
    border = Glass_Border,
    textPrimary = Glass_TextPrimary,
    textSecondary = Glass_TextSecondary,
    textDisabled = Glass_TextTertiary,
    accent = Glass_Cyan,
    accentSoft = Color(0x2E38BDF8),
    onAccent = Glass_Base,
    warning = Glass_Amber,
    critical = Glass_Rose,
    cardTopLightGradient = Glass_CardFill,
    // Ambient indigo → slate → teal wash so translucent cards have something to sit on.
    backgroundBrush = Brush.linearGradient(listOf(Glass_BaseIndigoTint, Glass_Base, Glass_BaseTealTint)),
    cardBorderGlow = Glass_Indigo,
    blueGlow = Glass_Indigo,
    greenEco = Glass_Emerald,
    amberWarning = Glass_Amber,
    redAlert = Glass_Rose,
    dockBackground = Glass_SurfaceLow,
    dockBorder = Glass_Border,
    dockBrush = Glass_CardFill,
    dockActionBrush = Glass_CardFill,
    glass = LiquidGlassStyle
)

fun cockpitColorsFor(mode: CockpitThemeMode): CockpitColors = when (mode) {
    CockpitThemeMode.APEX -> ApexCockpitColors
    CockpitThemeMode.LIQUID_GLASS -> LiquidGlassCockpitColors
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
