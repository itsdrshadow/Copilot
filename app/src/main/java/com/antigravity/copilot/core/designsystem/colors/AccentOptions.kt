package com.antigravity.copilot.core.designsystem.colors

import androidx.compose.ui.graphics.Color

/**
 * User-selectable accent colours (Settings › General › Appearance). The accent drives buttons,
 * selected states, nav highlight, small labels and icons. Each option has a bright Night shade,
 * a deeper Day shade, and a darker Day text shade so small labels stay readable on white cards.
 */
enum class AccentOption(
    val id: String,
    val displayName: String,
    val night: Color,
    val day: Color,
    val dayText: Color
) {
    CYAN("cyan", "Cyan", RaahiNight.Cyan, RaahiDay.Cyan, RaahiDay.CyanText),
    SKY("sky", "Sky", Color(0xFF5BA8FF), Color(0xFF2F7FE0), Color(0xFF1B5DB0)),
    INDIGO("indigo", "Indigo", Color(0xFF8C9EFF), Color(0xFF4F5BD5), Color(0xFF3640A8)),
    VIOLET("violet", "Violet", Color(0xFFB794F6), Color(0xFF7C4DDB), Color(0xFF5A32B0)),
    PINK("pink", "Pink", Color(0xFFFF7EB6), Color(0xFFD6336C), Color(0xFFA81F52)),
    RED("red", "Red", Color(0xFFFF6B6B), Color(0xFFD93A3A), Color(0xFFA82424)),
    ORANGE("orange", "Orange", Color(0xFFFF9F43), Color(0xFFE07B12), Color(0xFF9E5500)),
    AMBER("amber", "Amber", Color(0xFFFFC53D), Color(0xFFC79100), Color(0xFF8A6200)),
    LIME("lime", "Lime", Color(0xFFB4E94B), Color(0xFF6FA30F), Color(0xFF4C7208)),
    GREEN("green", "Green", Color(0xFF34D399), Color(0xFF0F9F6E), Color(0xFF0A7652)),
    TEAL("teal", "Teal", Color(0xFF2DD4BF), Color(0xFF0D9488), Color(0xFF0A6B63)),
    WHITE("white", "Silver", Color(0xFFE2E8F0), Color(0xFF475569), Color(0xFF334155));

    companion object {
        fun fromId(id: String?): AccentOption =
            entries.firstOrNull { it.id == id?.lowercase()?.trim() } ?: CYAN
    }
}
