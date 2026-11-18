package com.antigravity.copilot.core.designsystem.colors

import androidx.compose.ui.graphics.Color

/**
 * User-selectable accent colours (Settings › General › Appearance). The accent drives buttons,
 * selected states, nav highlight, small labels and icons.
 * (Dark theme only; one shade per option).
 */
enum class AccentOption(
    val id: String,
    val displayName: String,
    val night: Color
) {
    CYAN("cyan", "Blue", RaahiNight.Cyan),
    SKY("sky", "Sky", Color(0xFF38BDF8)),
    INDIGO("indigo", "Indigo", Color(0xFF9DA8EE)),
    VIOLET("violet", "Violet", Color(0xFFB7A0EC)),
    PINK("pink", "Pink", Color(0xFFE897B9)),
    RED("red", "Red", Color(0xFFE88585)),
    ORANGE("orange", "Orange", Color(0xFFE8A468)),
    AMBER("amber", "Amber", Color(0xFFE3BC62)),
    LIME("lime", "Lime", Color(0xFFA5CF6E)),
    GREEN("green", "Green", Color(0xFF5CC4A0)),
    TEAL("teal", "Teal", Color(0xFF54C0B2)),
    WHITE("white", "Silver", Color(0xFFD3DAE5));

    companion object {
        fun fromId(id: String?): AccentOption =
            entries.firstOrNull { it.id == id?.lowercase()?.trim() } ?: CYAN
    }
}
