package com.antigravity.copilot

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.feature.launcher.LauncherManager
import org.junit.Assert.*
import org.junit.Test

class AutomotiveDesignTokensTest {

    @Test
    fun testSpatialGridTokens() {
        assertEquals(8.dp, AutomotiveDesignTokens.Spacing.extraSmall)
        assertEquals(12.dp, AutomotiveDesignTokens.Spacing.small)
        assertEquals(16.dp, AutomotiveDesignTokens.Spacing.medium)
        assertEquals(24.dp, AutomotiveDesignTokens.Spacing.large)
        assertEquals(32.dp, AutomotiveDesignTokens.Spacing.extraLarge)
        assertEquals(24.dp, AutomotiveDesignTokens.Spacing.sectionSpacing)
        assertEquals(16.dp, AutomotiveDesignTokens.Spacing.cardInternalPadding)
        assertEquals(20.dp, AutomotiveDesignTokens.Spacing.cardInternalPaddingLarge)
        assertEquals(10.dp, AutomotiveDesignTokens.Spacing.itemSpacing)
    }

    @Test
    fun testDriverSafeTouchTargets() {
        // Apple HIG and Automotive HIG compliance: primary touch targets >= 64dp
        assertTrue(AutomotiveDesignTokens.TouchTargets.primaryButton >= 64.dp)
        assertEquals(72.dp, AutomotiveDesignTokens.TouchTargets.primaryButton)
        assertEquals(88.dp, AutomotiveDesignTokens.TouchTargets.primaryButtonLarge)
        assertEquals(64.dp, AutomotiveDesignTokens.TouchTargets.minInteractiveArea)
        assertEquals(56.dp, AutomotiveDesignTokens.TouchTargets.secondaryButton)
        assertEquals(48.dp, AutomotiveDesignTokens.TouchTargets.pillButtonHeight)
        assertEquals(48.dp, AutomotiveDesignTokens.TouchTargets.iconTouchArea)
    }

    @Test
    fun testGlanceableTypographyHierarchy() {
        // High-contrast, glanceable automotive typography
        assertEquals(82.sp, AutomotiveDesignTokens.Typography.speedValue)
        assertEquals(18.sp, AutomotiveDesignTokens.Typography.speedUnit)
        assertEquals(62.sp, AutomotiveDesignTokens.Typography.clockValue)
        assertEquals(44.sp, AutomotiveDesignTokens.Typography.heroMetric)
        assertEquals(34.sp, AutomotiveDesignTokens.Typography.largeMetric)
        assertEquals(22.sp, AutomotiveDesignTokens.Typography.screenTitle)
        assertEquals(16.sp, AutomotiveDesignTokens.Typography.cardTitle)
        assertEquals(18.sp, AutomotiveDesignTokens.Typography.sectionHeader)
        assertEquals(17.sp, AutomotiveDesignTokens.Typography.buttonLabel)
        assertEquals(16.sp, AutomotiveDesignTokens.Typography.secondaryLabel)
        assertEquals(17.sp, AutomotiveDesignTokens.Typography.bodyText)
        assertEquals(16.sp, AutomotiveDesignTokens.Typography.caption)
        assertEquals(16.sp, AutomotiveDesignTokens.Typography.badgeText)
    }

    @Test
    fun testConsistentRadiiTokens() {
        assertEquals(20.dp, AutomotiveDesignTokens.Radii.card)
        assertEquals(16.dp, AutomotiveDesignTokens.Radii.container)
        assertEquals(14.dp, AutomotiveDesignTokens.Radii.button)
        assertEquals(24.dp, AutomotiveDesignTokens.Radii.pill)
        assertEquals(8.dp, AutomotiveDesignTokens.Radii.tag)
    }

    @Test
    fun testAppDrawerGridTokens() {
        assertEquals(6, LauncherManager.GRID_COLUMNS)
        assertEquals(5, LauncherManager.GRID_ROWS)
        assertEquals(30, LauncherManager.APPS_PER_PAGE)
        assertEquals(48.dp, AutomotiveDesignTokens.AppDrawer.iconSize)
        assertEquals(17.sp, AutomotiveDesignTokens.AppDrawer.labelSize)
        assertEquals(4.dp, AutomotiveDesignTokens.AppDrawer.gridVerticalSpacing)
        assertEquals(6.dp, AutomotiveDesignTokens.AppDrawer.gridHorizontalSpacing)
    }

    @Test
    fun testDockGeometryTokens() {
        assertEquals(64.dp, AutomotiveDesignTokens.Dock.height)
        assertEquals(16.dp, AutomotiveDesignTokens.Dock.pillPaddingHorizontal)
        assertEquals(12.dp, AutomotiveDesignTokens.Dock.pillPaddingVertical)
        assertEquals(28.dp, AutomotiveDesignTokens.Dock.iconSize)
        assertEquals(17.sp, AutomotiveDesignTokens.Dock.textSize)
    }

    @Test
    fun testStandardizedNumericTypographyTokens() {
        assertEquals(com.antigravity.copilot.core.designsystem.typography.GoogleSansFamily, AutomotiveDesignTokens.NumericTypography.fontFamily)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, AutomotiveDesignTokens.NumericTypography.fontWeightHero)
        assertEquals("tnum", AutomotiveDesignTokens.NumericTypography.fontFeatureSettings)
        assertEquals((-0.5).sp, AutomotiveDesignTokens.NumericTypography.letterSpacing)

        val heroStyle = AutomotiveDesignTokens.NumericTypography.hero()
        assertEquals(AutomotiveDesignTokens.Typography.heroMetric, heroStyle.fontSize)
        assertEquals(androidx.compose.ui.text.font.FontWeight.Black, heroStyle.fontWeight)
        assertEquals("tnum", heroStyle.fontFeatureSettings)
    }

    @Test
    fun testAppSettingsManagerCleanStateWithoutScaling() {
        val state = AppSettingsManager.SettingsState()
        // Ensure default settings exist cleanly without scaling fields
        assertEquals("My Car", state.carName)
        assertEquals("Petrol", state.fuelType)
        assertTrue(state.screenAlwaysOn)
        assertEquals(48.0, state.tankCapacityLiters, 0.01)
    }
}
