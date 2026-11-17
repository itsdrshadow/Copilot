package com.antigravity.copilot

import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.settings.AppSettingsManager
import org.junit.Assert.*
import org.junit.Test

class CockpitThemeSystemTest {

    @Test
    fun testThemeEnumFromIdResolution() {
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("gradient_glass"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("glass"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("apex"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("APEX"))
        assertEquals("Apex Studio", CockpitThemeMode.APEX.displayName)
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("premium"))
        assertEquals(CockpitThemeMode.PREMIUM, CockpitThemeMode.fromId("PREMIUM"))
        // Legacy "classic", "calm_teal"
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("classic"))
        assertEquals(CockpitThemeMode.APEX, CockpitThemeMode.fromId("calm_teal"))
        // Safe fallback for unknown/blank values resolves to APEX
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
        assertEquals(Apex_Background, Apex_Background)
    }

    @Test
    fun testDefaultSettingsStateIsApex() {
        val state = AppSettingsManager.SettingsState()
        assertEquals("apex", state.cockpitTheme)
        assertEquals(CockpitThemeMode.APEX, state.cockpitThemeMode)
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
        // Resolves safely to default APEX without crashing
        assertEquals(CockpitThemeMode.APEX, corruptedState.cockpitThemeMode)
    }

    @Test
    fun testCentralizedTypographySystemTokens() {
        // 14 Semantic Text Styles Specification Verification
        assertEquals(82f, AutomotiveTypographyTokens.DisplayLarge.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, AutomotiveTypographyTokens.DisplayLarge.fontWeight)
        assertEquals("tnum", AutomotiveTypographyTokens.DisplayLarge.fontFeatureSettings)

        assertEquals(48f, AutomotiveTypographyTokens.DisplayMedium.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, AutomotiveTypographyTokens.DisplayMedium.fontWeight)
        assertEquals("tnum", AutomotiveTypographyTokens.DisplayMedium.fontFeatureSettings)

        assertEquals(34f, AutomotiveTypographyTokens.DisplaySmall.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, AutomotiveTypographyTokens.DisplaySmall.fontWeight)
        assertEquals("tnum", AutomotiveTypographyTokens.DisplaySmall.fontFeatureSettings)

        assertEquals(24f, AutomotiveTypographyTokens.HeadlineLarge.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, AutomotiveTypographyTokens.HeadlineLarge.fontWeight)

        assertEquals(20f, AutomotiveTypographyTokens.HeadlineMedium.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.HeadlineMedium.fontWeight)

        assertEquals(17f, AutomotiveTypographyTokens.HeadlineSmall.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.HeadlineSmall.fontWeight)

        assertEquals(19f, AutomotiveTypographyTokens.TitleLarge.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.TitleLarge.fontWeight)

        assertEquals(16.5f, AutomotiveTypographyTokens.TitleMedium.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, AutomotiveTypographyTokens.TitleMedium.fontWeight)

        assertEquals(15f, AutomotiveTypographyTokens.TitleSmall.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.SemiBold, AutomotiveTypographyTokens.TitleSmall.fontWeight)

        assertEquals(16f, AutomotiveTypographyTokens.BodyLarge.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, AutomotiveTypographyTokens.BodyLarge.fontWeight)

        assertEquals(14.5f, AutomotiveTypographyTokens.BodyMedium.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, AutomotiveTypographyTokens.BodyMedium.fontWeight)

        assertEquals(13f, AutomotiveTypographyTokens.BodySmall.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, AutomotiveTypographyTokens.BodySmall.fontWeight)

        assertEquals(15f, AutomotiveTypographyTokens.LabelLarge.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.LabelLarge.fontWeight)

        assertEquals(14.5f, AutomotiveTypographyTokens.LabelMedium.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.LabelMedium.fontWeight)

        assertEquals(13f, AutomotiveTypographyTokens.LabelSmall.fontSize.value, 0.01f)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Bold, AutomotiveTypographyTokens.LabelSmall.fontWeight)
    }

    @Test
    fun testNumericTypographyAndTabularNumerals() {
        assertEquals(82f, AutomotiveTypographyTokens.DisplayLarge.fontSize.value, 0.01f)
        assertEquals("tnum", AutomotiveTypographyTokens.DisplayLarge.fontFeatureSettings)

        assertEquals(62f, AutomotiveTypographyTokens.NumericClock.fontSize.value, 0.01f)
        assertEquals("tnum", AutomotiveTypographyTokens.NumericClock.fontFeatureSettings)

        assertEquals(38f, AutomotiveTypographyTokens.NumericFuelLiters.fontSize.value, 0.01f)
        assertEquals("tnum", AutomotiveTypographyTokens.NumericFuelLiters.fontFeatureSettings)

        assertEquals(32f, AutomotiveTypographyTokens.NumericOdometer.fontSize.value, 0.01f)
        assertEquals("tnum", AutomotiveTypographyTokens.NumericOdometer.fontFeatureSettings)
    }

    @Test
    fun testCockpitNavigationArchitectureContracts() {
        val tabs = com.antigravity.copilot.core.navigation.CockpitTab.values()
        assertEquals(4, tabs.size)
        assertTrue(tabs.contains(com.antigravity.copilot.core.navigation.CockpitTab.COCKPIT))
        assertTrue(tabs.contains(com.antigravity.copilot.core.navigation.CockpitTab.FULL_MAP))
        assertTrue(tabs.contains(com.antigravity.copilot.core.navigation.CockpitTab.TRIP_HISTORY))
        assertTrue(tabs.contains(com.antigravity.copilot.core.navigation.CockpitTab.FUEL_HISTORY))
    }
}
