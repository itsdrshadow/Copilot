package com.antigravity.copilot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.designsystem.colors.RaahiNightColors
import com.antigravity.copilot.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test

/** Raahi theme contract: palette values, legacy-id fallback, type scale. */
class CockpitThemeSystemTest {

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return (maxOf(la, lb) / minOf(la, lb)).toDouble()
    }

    @Test
    fun legacyThemeIdsAllResolveToRaahi() {
        listOf("gradient_glass", "glass", "apex", "APEX", "premium", "classic", "calm_teal", "liquid_glass", "unknown", "")
            .forEach { assertEquals(CockpitThemeMode.RAAHI, CockpitThemeMode.fromId(it)) }
        assertEquals(CockpitThemeMode.RAAHI, CockpitThemeMode.APEX)
        assertEquals("Raahi", CockpitThemeMode.RAAHI.displayName)
    }

    @Test
    fun raahiNightPaletteMatchesSpec() {
        val c = RaahiNightColors
        assertEquals(Color(0xFF0A0A0B), c.bg)
        assertEquals(Color(0xFF0E0F11), c.rail)
        assertEquals(Color(0xFF17181A), c.card)
        assertEquals(Color(0xFF1C1D20), c.cardElevated)
        assertEquals(Color(0xFF26282C), c.border)
        assertEquals(Color(0xFFFFFFFF), c.textPrimary)
        assertEquals(Color(0xFF8E9299), c.textSecondary)
        assertEquals(Color(0xFF3B82F6), c.accent)
        assertEquals(Color(0xFFF59E0B), c.warning)
        assertEquals(Color(0xFF22C55E), c.positive)
        assertEquals(Color(0xFFF87171), c.critical)
        assertEquals(Color(0xFF3B82F6), c.overlapBlue)
        assertEquals(Color(0xFFF59E0B), c.overlapOrange)
        assertTrue(c.isDark)
    }

    @Test
    fun textContrastIsAtLeast4_5InBothThemes() {
        for (c in listOf(RaahiNightColors)) {
            for (surface in listOf(c.bg, c.card, c.cardElevated)) {
                assertTrue(contrast(c.textPrimary, surface) >= 4.5)
                assertTrue(contrast(c.textSecondary, surface) >= 4.5)
                assertTrue(contrast(c.textDisabled, surface) >= 4.5)
            }
            // Label on a filled cyan button.
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5)
        }
        // Status colours used as text (accentText etc.) are readable on every surface.
        for (c in listOf(RaahiNightColors)) {
            for (surface in listOf(c.bg, c.card, c.cardElevated)) {
                listOf(c.accentText, c.warningText, c.positiveText, c.criticalText)
                    .forEach { assertTrue(contrast(it, surface) >= 4.5) }
            }
        }
    }

    @Test
    fun vehicleSettingsUnaffectedByThemeId() {
        val s = AppSettingsManager.SettingsState(
            carName = "Skoda Octavia RS", fuelType = "Petrol", tankCapacityLiters = 55.0,
            expectedMileageKmpl = 16.5, cockpitTheme = "premium"
        )
        assertEquals(CockpitThemeMode.RAAHI, s.cockpitThemeMode)
        assertEquals("Skoda Octavia RS", s.carName)
        assertEquals(55.0, s.tankCapacityLiters, 0.001)
        assertEquals(16.5, s.expectedMileageKmpl, 0.001)
    }

    @Test
    fun typeScaleUsesTabularDigitsAndMinimumLabelSize() {
        val t = AutomotiveTypographyTokens
        listOf(t.SpeedHero, t.DisplayLarge, t.DisplayMedium, t.DisplaySmall, t.HeadlineLarge, t.HeadlineMedium, t.HeadlineSmall)
            .forEach { assertEquals("tnum", it.fontFeatureSettings) }
        assertEquals(112f, t.SpeedHero.fontSize.value, 0.01f)
        listOf(t.TitleLarge, t.TitleMedium, t.TitleSmall, t.BodyLarge, t.BodyMedium, t.BodySmall, t.LabelLarge, t.LabelMedium, t.LabelSmall)
            .forEach { assertTrue(it.fontSize.value >= 12f) }
        assertEquals(FontWeight.Normal, t.BodyMedium.fontWeight)
        assertEquals(FontWeight.Medium, t.LabelLarge.fontWeight)
    }
}
