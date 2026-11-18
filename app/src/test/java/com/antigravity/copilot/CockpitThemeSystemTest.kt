package com.antigravity.copilot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.designsystem.colors.RaahiDayColors
import com.antigravity.copilot.core.designsystem.colors.RaahiNightColors
import com.antigravity.copilot.core.designsystem.colors.cockpitColorsFor
import com.antigravity.copilot.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/** Raahi theme contract: palette values, legacy-id fallback, day/night policy, type scale. */
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
    fun appearanceModeFromIdAndDefaultIsAuto() {
        assertEquals(ThemeAppearanceMode.NIGHT, ThemeAppearanceMode.fromId("night"))
        assertEquals(ThemeAppearanceMode.DAY, ThemeAppearanceMode.fromId("day"))
        assertEquals(ThemeAppearanceMode.AUTO, ThemeAppearanceMode.fromId("auto"))
        assertEquals(ThemeAppearanceMode.AUTO, ThemeAppearanceMode.fromId("garbage"))
        assertEquals(ThemeAppearanceMode.AUTO, AppSettingsManager.SettingsState().appearanceMode)
    }

    @Test
    fun raahiNightPaletteMatchesSpec() {
        val c = RaahiNightColors
        assertEquals(Color(0xFF080D16), c.bg)
        assertEquals(Color(0xFF0B111C), c.rail)
        assertEquals(Color(0xFF0F1623), c.card)
        assertEquals(Color(0xFF172033), c.cardElevated)
        assertEquals(Color(0xFF26334B), c.border)
        assertEquals(Color(0xFFEDF3FB), c.textPrimary)
        assertEquals(Color(0xFF9AABC4), c.textSecondary)
        assertEquals(Color(0xFF38D6FF), c.accent)
        assertEquals(Color(0xFFFFB224), c.warning)
        assertEquals(Color(0xFF34D399), c.positive)
        assertEquals(Color(0xFFFF5C5C), c.critical)
        assertEquals(Color(0xFF5BA8FF), c.overlapBlue)
        assertEquals(Color(0xFFFFA94D), c.overlapOrange)
        assertTrue(c.isDark)
    }

    @Test
    fun raahiDayPaletteMatchesSpec() {
        val c = RaahiDayColors
        assertEquals(Color(0xFFF4F7FB), c.bg)
        assertEquals(Color(0xFFFFFFFF), c.card)
        assertEquals(Color(0xFFD5DEEB), c.border)
        assertEquals(Color(0xFF0B1220), c.textPrimary)
        assertEquals(Color(0xFF4A5B75), c.textSecondary)
        assertEquals(Color(0xFF0A9CC4), c.accent)
        assertEquals(Color(0xFFC27A00), c.warning)
        assertEquals(Color(0xFF0F9F6E), c.positive)
        assertEquals(Color(0xFFD93A3A), c.critical)
        assertFalse(c.isDark)
        assertSame(RaahiDayColors, cockpitColorsFor(isDay = true))
        assertSame(RaahiNightColors, cockpitColorsFor(isDay = false))
    }

    @Test
    fun textContrastIsAtLeast4_5InBothThemes() {
        for (c in listOf(RaahiNightColors, RaahiDayColors)) {
            for (surface in listOf(c.bg, c.card, c.cardElevated)) {
                assertTrue(contrast(c.textPrimary, surface) >= 4.5)
                assertTrue(contrast(c.textSecondary, surface) >= 4.5)
                assertTrue(contrast(c.textDisabled, surface) >= 4.5)
            }
            // Label on a filled cyan button.
            assertTrue(contrast(c.onAccent, c.accent) >= 4.5)
        }
        // Status colours used as text (accentText etc.) are readable on every surface.
        for (c in listOf(RaahiNightColors, RaahiDayColors)) {
            for (surface in listOf(c.bg, c.card, c.cardElevated)) {
                listOf(c.accentText, c.warningText, c.positiveText, c.criticalText)
                    .forEach { assertTrue(contrast(it, surface) >= 4.5) }
            }
        }
    }

    @Test
    fun dayNightClockAndLuxHysteresis() {
        fun at(h: Int, m: Int) = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        assertTrue(DayNightPolicy.isDayByClock(at(12, 0)))
        assertTrue(DayNightPolicy.isDayByClock(at(6, 30)))
        assertFalse(DayNightPolicy.isDayByClock(at(6, 29)))
        assertFalse(DayNightPolicy.isDayByClock(at(18, 30)))
        assertFalse(DayNightPolicy.isDayByClock(at(23, 0)))

        assertTrue(DayNightPolicy.nextFromLux(current = false, lux = 5000f))
        assertFalse(DayNightPolicy.nextFromLux(current = true, lux = 10f))
        // Inside the band the current appearance is kept.
        assertTrue(DayNightPolicy.nextFromLux(current = true, lux = 150f))
        assertFalse(DayNightPolicy.nextFromLux(current = false, lux = 150f))
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
            .forEach { assertTrue(it.fontSize.value >= t.MinLabelSize.value) }
        // 16 sp minimum for any text on the 148 dpi head unit; nothing renders in Regular.
        assertEquals(16f, t.MinLabelSize.value, 0.01f)
        assertEquals(FontWeight.Medium, t.BodyMedium.fontWeight)
        assertEquals(FontWeight.SemiBold, t.LabelLarge.fontWeight)
    }
}
