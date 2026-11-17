package com.antigravity.copilot.core.designsystem.tokens

import androidx.compose.ui.graphics.Color

/**
 * Primitive color palette shared by every feature screen.
 *
 * Screens must reference these tokens (or the semantic `CockpitTheme.colors`) instead of
 * inline `Color(0x…)` literals so a palette tweak is a one-line change.
 */
object Palette {
    // Sky / blue accents
    val Sky300 = Color(0xFF7DD3FC)
    val Sky400 = Color(0xFF38BDF8)
    val Sky600 = Color(0xFF0284C7)
    val Sky700 = Color(0xFF0369A1)
    val Blue700 = Color(0xFF0A58CA)
    val LightBlueA400 = Color(0xFF00B0FF)

    // Neon cyans (gauges, glows)
    val Cyan = Color(0xFF00E5FF)
    val CyanNeon = Color(0xFF00F0FF)
    val CyanElectric = Color(0xFF00F5FF)
    val CyanBright = Color(0xFF00D2FF)

    // Slate neutrals (text / dividers)
    val Slate300 = Color(0xFFCBD5E1)
    val Slate400 = Color(0xFF94A3B8)
    val Slate500 = Color(0xFF64748B)
    val Slate600 = Color(0xFF475569)
    val Slate700 = Color(0xFF334155)
    val Slate800 = Color(0xFF1E293B)
    val Slate900 = Color(0xFF0F172A)
    val Steel400 = Color(0xFF87929A)
    val Graphite700 = Color(0xFF262A31)

    // Ink navy surfaces (darkest → lightest)
    val Ink950 = Color(0xFF0B1220)
    val Ink925 = Color(0xFF0C1322)
    val Ink900 = Color(0xFF0F1522)
    val Ink875 = Color(0xFF0F1A2D)
    val Ink850 = Color(0xFF141A24)
    val Ink825 = Color(0xFF141F32)
    val Ink800 = Color(0xFF1A2332)
    val Ink750 = Color(0xFF1E2838)
    val Ink725 = Color(0xFF1E2E44)
    val Ink700 = Color(0xFF1E2D4A)
    val Ink675 = Color(0xFF1E2F4A)
    val Ink600 = Color(0xFF1E3A5F)

    // Status
    val Emerald500 = Color(0xFF10B981)
    val GreenA400 = Color(0xFF00E676)
    val Amber300 = Color(0xFFFFD54F)
    val Amber500 = Color(0xFFF59E0B)
    val OrangeA400 = Color(0xFFFF9100)
    val Red500 = Color(0xFFEF4444)
    val Rose500 = Color(0xFFF43F5E)
    val Purple400 = Color(0xFFC084FC)
    val Purple500 = Color(0xFFA855F7)
}
