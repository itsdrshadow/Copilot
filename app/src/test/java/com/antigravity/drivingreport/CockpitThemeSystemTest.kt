package com.antigravity.drivingreport

import com.antigravity.drivingreport.core.designsystem.*
import com.antigravity.drivingreport.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test

class CockpitThemeSystemTest {

    @Test
    fun testThemeEnumFromIdResolution() {
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("apex"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("APEX"))
        assertEquals("Apex Studio", CockpitThemeMode.APEX.displayName)
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("premium"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("PREMIUM"))
        // Legacy "classic", "calm_teal", or unknown/blank values automatically and safely resolve to finalized APEX
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("classic"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("calm_teal"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("unknown"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId(""))
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
