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
    val shadowElevationDp: Float,
    /** Soft ambient colour pools painted behind screens (see `Modifier.cockpitBackground`). */
    val ambientGlows: List<AmbientGlow> = emptyList(),
    /** `Modifier.liquidGlass` body: translucent vertical sheen (white → clear → deep slate). */
    val panelFill: Brush = sheen,
    /** `Modifier.liquidGlass` 1.25dp refractive rim: glossy white-cyan top-left → faint bottom-right. */
    val rim: Brush = specularBorder,
    /** Inner 1dp highlight line along the top edge (centre colour of a horizontal fade). */
    val topHighlight: Color = Color(0x59FFFFFF),
    /** Emerald pool painted behind the Fuel card (`Modifier.glassAmbientOrb`). */
    val fuelGlow: Color = Color.Transparent
)

/** One radial colour pool; [centerX]/[centerY]/[radius] are fractions of the screen size. */
data class AmbientGlow(val color: Color, val centerX: Float, val centerY: Float, val radius: Float)

// ==================== LIQUID GLASS PALETTE ====================

val Glass_Base = Color(0xFF060B19) // deep midnight navy
val Glass_BaseIndigoTint = Color(0xFF0B1230)
val Glass_BaseTealTint = Color(0xFF05101C)
// Neutral fills are genuinely translucent so the ambient orbs read through them.
val Glass_Surface = Color(0x9E0F172A) // #0F172A @ 62%
val Glass_SurfaceElevated = Color(0xB3172036) // lifted panel @ 70%
val Glass_SurfaceLow = Color(0x8C0F172A) // #0F172A @ 55%
// Opaque on purpose (≈18% white over Glass_Base): screens call border.copy(alpha = x),
// which REPLACES alpha — a translucent token would turn 0.6 into a glaring 60% white rim.
val Glass_Border = Color(0xFF333B4B)
val Glass_Cyan = Color(0xFF38BDF8)
val Glass_Indigo = Color(0xFF818CF8)
val Glass_Emerald = Color(0xFF34D399)
val Glass_Amber = Color(0xFFFBBF24)
val Glass_Rose = Color(0xFFFB7185)
val Glass_TextPrimary = Color(0xFFF5F8FF)
val Glass_TextSecondary = Color(0xFFB4C0D4)
val Glass_TextTertiary = Color(0xFF7484A0)

// Ambient light sources (spec: cyan 22% top-left, indigo 20% bottom-right, emerald behind Fuel).
val Glass_OrbCyan = Color(0xFF0EA5E9).copy(alpha = 0.22f)
val Glass_OrbIndigo = Color(0xFF6366F1).copy(alpha = 0.20f)
val Glass_OrbEmerald = Color(0xFF10B981).copy(alpha = 0.20f)

/** Translucent vertical glass body shared by `liquidGlass` and the theme card brushes. */
private val Glass_PanelFill = Brush.verticalGradient(
    0.0f to Color.White.copy(alpha = 0.16f),
    0.45f to Color.White.copy(alpha = 0.05f),
    1.0f to Color(0xFF0F172A).copy(alpha = 0.55f)
)

/** Specular rim: glossy white / ice-cyan top-left → faint white bottom-right. */
private val Glass_Rim = Brush.linearGradient(
    0.0f to Color(0x99FFFFFF),
    0.22f to Color(0x807DD3FC),
    0.6f to Color(0x30FFFFFF),
    1.0f to Color(0x18FFFFFF)
)

val LiquidGlassStyle = GlassStyle(
    sheen = Brush.verticalGradient(listOf(Color(0x1FFFFFFF), Color(0x08FFFFFF), Color(0x00FFFFFF))),
    specularBorder = Glass_Rim,
    shadowElevationDp = 0f, // translucent fills would show an outline shadow through them
    ambientGlows = listOf(
        AmbientGlow(Glass_OrbCyan, centerX = 0.10f, centerY = 0.08f, radius = 0.85f),   // top-left
        AmbientGlow(Glass_OrbIndigo, centerX = 0.92f, centerY = 0.95f, radius = 0.85f) // bottom-right
    ),
    panelFill = Glass_PanelFill,
    rim = Glass_Rim,
    topHighlight = Color(0x66FFFFFF),
    fuelGlow = Glass_OrbEmerald
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
    cardTopLightGradient = Glass_PanelFill,
    // Midnight navy with a faint indigo lift at the top; the orbs supply the colour.
    backgroundBrush = Brush.verticalGradient(listOf(Glass_BaseIndigoTint, Glass_Base, Glass_BaseTealTint)),
    cardBorderGlow = Glass_Indigo,
    blueGlow = Glass_Indigo,
    greenEco = Glass_Emerald,
    amberWarning = Glass_Amber,
    redAlert = Glass_Rose,
    dockBackground = Glass_SurfaceLow,
    dockBorder = Glass_Border,
    dockBrush = Glass_PanelFill,
    dockActionBrush = Glass_PanelFill,
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
