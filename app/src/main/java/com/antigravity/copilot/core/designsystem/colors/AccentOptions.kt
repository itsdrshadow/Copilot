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
    SKY("sky", "Sky", Color(0xFF74AEEB), Color(0xFF3B7FCC), Color(0xFF1F5CA6)),
    INDIGO("indigo", "Indigo", Color(0xFF9DA8EE), Color(0xFF5662C4), Color(0xFF3A44A0)),
    VIOLET("violet", "Violet", Color(0xFFB7A0EC), Color(0xFF7A58C8), Color(0xFF5B3BA6)),
    PINK("pink", "Pink", Color(0xFFE897B9), Color(0xFFC24A78), Color(0xFF9E2F5C)),
    RED("red", "Red", Color(0xFFE88585), Color(0xFFC44A4A), Color(0xFF9C3030)),
    ORANGE("orange", "Orange", Color(0xFFE8A468), Color(0xFFC97A2E), Color(0xFF925512)),
    AMBER("amber", "Amber", Color(0xFFE3BC62), Color(0xFFAE8416), Color(0xFF7E5E0A)),
    LIME("lime", "Lime", Color(0xFFA5CF6E), Color(0xFF6A9A2A), Color(0xFF4A7016)),
    GREEN("green", "Green", Color(0xFF5CC4A0), Color(0xFF1F9468), Color(0xFF0F7050)),
    TEAL("teal", "Teal", Color(0xFF54C0B2), Color(0xFF16867C), Color(0xFF0C645C)),
    WHITE("white", "Silver", Color(0xFFD3DAE5), Color(0xFF4B5768), Color(0xFF334155));

    companion object {
        fun fromId(id: String?): AccentOption =
            entries.firstOrNull { it.id == id?.lowercase()?.trim() } ?: CYAN
    }
}
