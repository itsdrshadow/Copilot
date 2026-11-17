package com.antigravity.copilot

import com.antigravity.copilot.core.designsystem.CockpitThemeMode
import com.antigravity.copilot.core.designsystem.colors.ApexCockpitColors
import com.antigravity.copilot.core.designsystem.colors.Glass_Emerald
import com.antigravity.copilot.core.designsystem.colors.LiquidGlassCockpitColors
import com.antigravity.copilot.core.designsystem.colors.cockpitColorsFor
import com.antigravity.copilot.core.designsystem.typography.AutomotiveTypographyTokens
import com.antigravity.copilot.core.designsystem.typography.LiquidGlassTypography
import com.antigravity.copilot.core.settings.AppSettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassThemeTest {

    @Test
    fun liquidGlassIdRoundTrips() {
        assertEquals(CockpitThemeMode.LIQUID_GLASS, CockpitThemeMode.fromId("liquid_glass"))
        assertEquals(CockpitThemeMode.LIQUID_GLASS, CockpitThemeMode.fromId(" LIQUID_GLASS "))
        assertEquals("liquid_glass", CockpitThemeMode.LIQUID_GLASS.id)
        // Retired "glass" ids must still fold into Apex.
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("glass"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("gradient_glass"))
    }

    @Test
    fun settingsStateResolvesLiquidGlass() {
        val state = AppSettingsManager.SettingsState(cockpitTheme = "liquid_glass")
        assertEquals(CockpitThemeMode.LIQUID_GLASS, state.cockpitThemeMode)
    }

    @Test
    fun paletteSelectionAndGlassStyle() {
        assertEquals(ApexCockpitColors, cockpitColorsFor(CockpitThemeMode.APEX))
        assertEquals(LiquidGlassCockpitColors, cockpitColorsFor(CockpitThemeMode.LIQUID_GLASS))
        assertNull(ApexCockpitColors.glass)
        assertNotNull(LiquidGlassCockpitColors.glass)
        assertEquals(Glass_Emerald, LiquidGlassCockpitColors.greenEco)
        // Glass surfaces are translucent within the 55–75% alpha band so ambient orbs read through.
        val a = LiquidGlassCockpitColors.card.alpha
        assertTrue("card alpha $a", a in 0.55f..0.75f)
    }

    @Test
    fun liquidGlassTypographyKeepsMetricsAndTabularDigits() {
        assertEquals(AutomotiveTypographyTokens.DisplayLarge.fontSize, LiquidGlassTypography.displayLarge.fontSize)
        assertEquals("tnum", LiquidGlassTypography.displayLarge.fontFeatureSettings)
        assertEquals("tnum", LiquidGlassTypography.labelSmall.fontFeatureSettings)
    }
}
