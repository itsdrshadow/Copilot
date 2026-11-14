package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.designsystem.*
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test

class CockpitThemeSystemTest {

    @Test
    fun testThemeEnumFromIdResolution() {
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("premium"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("PREMIUM"))
        // Legacy "classic" or unknown/blank values automatically and safely resolve to finalized PREMIUM
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("classic"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("CLASSIC"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("unknown"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId(""))
    }

    @Test
    fun testPremiumPaletteTokensIntegrity() {
        assertTrue(PremiumCockpitColors.isPremium)
        assertEquals(Premium_Background, PremiumCockpitColors.background)
        assertEquals(Premium_CardSurface, PremiumCockpitColors.cardSurface)
        assertEquals(Premium_CardSurfaceElevated, PremiumCockpitColors.cardSurfaceElevated)
        assertEquals(Premium_CardBorder, PremiumCockpitColors.cardBorder)
        assertEquals(Premium_PrimaryAccent, PremiumCockpitColors.primaryAccent)
        assertEquals(Premium_Positive, PremiumCockpitColors.greenEco)
        assertEquals(Premium_Warning, PremiumCockpitColors.amberWarning)
        assertEquals(Premium_Danger, PremiumCockpitColors.redAlert)
        assertEquals(Premium_TextPrimary, PremiumCockpitColors.textPrimary)
        assertEquals(Premium_TextSecondary, PremiumCockpitColors.textSecondary)
        assertEquals(Premium_TextTertiary, PremiumCockpitColors.textTertiary)
    }

    @Test
    fun testDefaultSettingsStateIsPremium() {
        val state = AppSettingsManager.SettingsState()
        assertEquals("premium", state.cockpitTheme)
        assertEquals(CockpitThemeMode.PREMIUM, state.cockpitThemeMode)
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
        // Resolves safely to finalized PREMIUM without crashing
        assertEquals(CockpitThemeMode.PREMIUM, corruptedState.cockpitThemeMode)
    }
}
