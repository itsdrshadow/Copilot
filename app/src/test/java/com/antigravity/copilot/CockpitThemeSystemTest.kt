package com.antigravity.copilot

import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test

class CockpitThemeSystemTest {

    @Test
    fun testThemeEnumFromIdResolution() {
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, CockpitThemeMode.fromId("gradient_glass"))
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, CockpitThemeMode.fromId("glass"))
        assertEquals("Gradient Glass", CockpitThemeMode.GRADIENT_GLASS.displayName)
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("apex"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("APEX"))
        assertEquals("Apex Studio", CockpitThemeMode.APEX.displayName)
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("premium"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("PREMIUM"))
        // Legacy "classic", "calm_teal"
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("classic"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("calm_teal"))
        // Safe fallback for unknown/blank values resolves to GRADIENT_GLASS
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, CockpitThemeMode.fromId("unknown"))
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, CockpitThemeMode.fromId(""))
    }

    @Test
    fun testGradientGlassPaletteTokensIntegrity() {
        assertTrue(GradientGlassCockpitColors.isPremium)
        assertEquals(Glass_Background, GradientGlassCockpitColors.background)
        assertEquals(Glass_CardSurface, GradientGlassCockpitColors.cardSurface)
        assertEquals(Glass_CardSurfaceElevated, GradientGlassCockpitColors.cardSurfaceElevated)
        assertEquals(Glass_CardBorder, GradientGlassCockpitColors.cardBorder)
        assertEquals(Glass_PrimaryAccent, GradientGlassCockpitColors.primaryAccent)
        assertEquals(Glass_Positive, GradientGlassCockpitColors.greenEco)
        assertEquals(Glass_Warning, GradientGlassCockpitColors.amberWarning)
        assertEquals(Glass_Danger, GradientGlassCockpitColors.redAlert)
        assertEquals(Glass_TextPrimary, GradientGlassCockpitColors.textPrimary)
        assertEquals(Glass_TextSecondary, GradientGlassCockpitColors.textSecondary)
        assertEquals(Glass_TextTertiary, GradientGlassCockpitColors.textTertiary)
    }

    @Test
    fun testPremiumPaletteTokensIntegrity() {
        assertTrue(ApexCockpitColors.isPremium)
        assertEquals(Apex_Background, ApexCockpitColors.background)
        assertEquals(Apex_CardSurface, ApexCockpitColors.cardSurface)
        assertEquals(Apex_CardSurfaceElevated, ApexCockpitColors.cardSurfaceElevated)
        assertEquals(Apex_CardBorder, ApexCockpitColors.cardBorder)
        assertEquals(Apex_PrimaryAccent, ApexCockpitColors.primaryAccent)
        assertEquals(Apex_Positive, ApexCockpitColors.greenEco)
        assertEquals(Apex_Warning, ApexCockpitColors.amberWarning)
        assertEquals(Apex_Danger, ApexCockpitColors.redAlert)
        assertEquals(Apex_TextPrimary, ApexCockpitColors.textPrimary)
        assertEquals(Apex_TextSecondary, ApexCockpitColors.textSecondary)
        assertEquals(Apex_TextTertiary, ApexCockpitColors.textTertiary)

        // Verifies backward compatibility of Premium aliases
        assertEquals(ApexCockpitColors, PremiumCockpitColors)
        assertEquals(Apex_Background, Premium_Background)
    }

    @Test
    fun testDefaultSettingsStateIsGradientGlass() {
        val state = AppSettingsManager.SettingsState()
        assertEquals("gradient_glass", state.cockpitTheme)
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, state.cockpitThemeMode)
    }

    @Test
    fun testVehicleSettingsPreservedUnderFinalPremiumTheme() {
        // Verify customized vehicle configuration under final Premium theme
        val customState = AppSettingsManager.SettingsState(
            carName = "Skoda Octavia RS",
            fuelType = "Petrol",
            tankCapacityLiters = 55.0,
            expectedMileageKmpl = 16.5,
            cockpitTheme = "premium",
            screenAlwaysOn = true,
            autoTripDetection = true
        )
        assertEquals(CockpitThemeMode.PREMIUM, customState.cockpitThemeMode)
        assertEquals("Skoda Octavia RS", customState.carName)
        assertEquals("Petrol", customState.fuelType)
        assertEquals(55.0, customState.tankCapacityLiters, 0.001)
        assertEquals(16.5, customState.expectedMileageKmpl, 0.001)
        assertTrue(customState.screenAlwaysOn)
        assertTrue(customState.autoTripDetection)
    }

    @Test
    fun testThemeSafeFallbackForInvalidIds() {
        val corruptedState = AppSettingsManager.SettingsState(cockpitTheme = "corrupted_theme_99")
        // Resolves safely to default GRADIENT_GLASS without crashing
        assertEquals(CockpitThemeMode.GRADIENT_GLASS, corruptedState.cockpitThemeMode)
    }
}
