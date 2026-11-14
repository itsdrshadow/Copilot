package com.antigravity.copilot

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.settings.AppSettingsManager
import com.antigravity.copilot.feature.launcher.LauncherManager
import org.junit.Assert.*
import org.junit.Test

class UiScalingSystemTest {

    @Test
    fun testUiScaleModeEnumsAndScaleFactors() {
        assertEquals("small", UiScaleMode.SMALL.id)
        assertEquals(0.85f, UiScaleMode.SMALL.scaleFactor, 0.001f)

        assertEquals("default", UiScaleMode.DEFAULT.id)
        assertEquals(1.00f, UiScaleMode.DEFAULT.scaleFactor, 0.001f)

        assertEquals("large", UiScaleMode.LARGE.id)
        assertEquals(1.15f, UiScaleMode.LARGE.scaleFactor, 0.001f)

        assertEquals("extra_large", UiScaleMode.EXTRA_LARGE.id)
        assertEquals(1.30f, UiScaleMode.EXTRA_LARGE.scaleFactor, 0.001f)
    }

    @Test
    fun testUiScaleModeFromIdResolution() {
        assertEquals(UiScaleMode.SMALL, UiScaleMode.fromId("small"))
        assertEquals(UiScaleMode.SMALL, UiScaleMode.fromId("SMALL"))
        assertEquals(UiScaleMode.DEFAULT, UiScaleMode.fromId("default"))
        assertEquals(UiScaleMode.DEFAULT, UiScaleMode.fromId("DEFAULT"))
        assertEquals(UiScaleMode.LARGE, UiScaleMode.fromId("large"))
        assertEquals(UiScaleMode.LARGE, UiScaleMode.fromId("LARGE"))
        assertEquals(UiScaleMode.EXTRA_LARGE, UiScaleMode.fromId("extra_large"))
        assertEquals(UiScaleMode.EXTRA_LARGE, UiScaleMode.fromId("EXTRA_LARGE"))

        // Invalid or null fallback safely to DEFAULT
        assertEquals(UiScaleMode.DEFAULT, UiScaleMode.fromId("invalid_scale_id"))
        assertEquals(UiScaleMode.DEFAULT, UiScaleMode.fromId(""))
        assertEquals(UiScaleMode.DEFAULT, UiScaleMode.fromId(null))
    }

    @Test
    fun testCockpitScaleTokensForDefaultMode() {
        val tokens = CockpitScaleTokens.forMode(UiScaleMode.DEFAULT)
        assertEquals(UiScaleMode.DEFAULT, tokens.mode)
        assertEquals(1.00f, tokens.scaleFactor, 0.001f)
        assertEquals(1.00f, tokens.fontScale, 0.001f)
        assertEquals(14.dp, tokens.cardPadding)
        assertEquals(8.dp, tokens.itemSpacing)
        assertEquals(46.dp, tokens.appDrawerIconSize)
        assertEquals(11.5.sp, tokens.appDrawerLabelSize)
        assertEquals(19.dp, tokens.dockIconSize)
        assertEquals(13.5.sp, tokens.dockTextSize)
        assertEquals(82.sp, tokens.speedNumberSize)
        assertEquals(34.sp, tokens.fuelValueSize)
    }

    @Test
    fun testCockpitScaleTokensForSmallMode() {
        val tokens = CockpitScaleTokens.forMode(UiScaleMode.SMALL)
        assertEquals(UiScaleMode.SMALL, tokens.mode)
        assertEquals(0.85f, tokens.scaleFactor, 0.001f)
        assertEquals(0.85f, tokens.fontScale, 0.001f)
        assertEquals(11.dp, tokens.cardPadding)
        assertEquals(6.dp, tokens.itemSpacing)
        assertEquals(40.dp, tokens.appDrawerIconSize)
        assertEquals(10.sp, tokens.appDrawerLabelSize)
        assertEquals(16.dp, tokens.dockIconSize)
        assertEquals(11.5.sp, tokens.dockTextSize)
        assertEquals(70.sp, tokens.speedNumberSize)
        assertEquals(29.sp, tokens.fuelValueSize)
    }

    @Test
    fun testCockpitScaleTokensForLargeMode() {
        val tokens = CockpitScaleTokens.forMode(UiScaleMode.LARGE)
        assertEquals(UiScaleMode.LARGE, tokens.mode)
        assertEquals(1.15f, tokens.scaleFactor, 0.001f)
        assertEquals(1.15f, tokens.fontScale, 0.001f)
        assertEquals(13.dp, tokens.cardPadding)
        assertEquals(7.dp, tokens.itemSpacing)
        assertEquals(50.dp, tokens.appDrawerIconSize)
        assertEquals(12.5.sp, tokens.appDrawerLabelSize)
        assertEquals(20.dp, tokens.dockIconSize)
        assertEquals(14.5.sp, tokens.dockTextSize)
        assertEquals(80.sp, tokens.speedNumberSize)
        assertEquals(36.sp, tokens.fuelValueSize)
    }

    @Test
    fun testCockpitScaleTokensForExtraLargeResponsiveOverflowPrevention() {
        val tokens = CockpitScaleTokens.forMode(UiScaleMode.EXTRA_LARGE)
        assertEquals(UiScaleMode.EXTRA_LARGE, tokens.mode)
        assertEquals(1.30f, tokens.scaleFactor, 0.001f)
        assertEquals(1.30f, tokens.fontScale, 0.001f)
        // Responsive restraint: card padding is clamped to prevent internal overflow
        assertEquals(11.dp, tokens.cardPadding)
        assertEquals(6.dp, tokens.itemSpacing)
        assertEquals(54.dp, tokens.appDrawerIconSize)
        assertEquals(13.sp, tokens.appDrawerLabelSize)
        assertEquals(21.dp, tokens.dockIconSize)
        assertEquals(15.sp, tokens.dockTextSize)
        // Dial number capped to fit inside gauge arc
        assertTrue(tokens.speedNumberSize.value <= 82f)
        // Spacing factor is kept compact to protect layout bounds
        assertEquals(0.85f, tokens.spacingScale, 0.001f)
    }

    @Test
    fun testScaleCalculationHelpers() {
        val small = CockpitScaleTokens.forMode(UiScaleMode.SMALL)
        assertEquals(8.5.dp, small.scale(10.dp))
        assertEquals(17.sp, small.scaleFont(20.sp))

        val defaultTokens = CockpitScaleTokens.forMode(UiScaleMode.DEFAULT)
        assertEquals(10.dp, defaultTokens.scale(10.dp))
        assertEquals(20.sp, defaultTokens.scaleFont(20.sp))

        val large = CockpitScaleTokens.forMode(UiScaleMode.LARGE)
        assertEquals(11.5.dp, large.scale(10.dp))
        assertEquals(23.sp, large.scaleFont(20.sp))

        val extraLarge = CockpitScaleTokens.forMode(UiScaleMode.EXTRA_LARGE)
        assertEquals(13.0.dp, extraLarge.scale(10.dp))
        assertEquals(26.sp, extraLarge.scaleFont(20.sp))
    }

    @Test
    fun testTypographyScaledFunctionIdentityOnOne() {
        val original = CockpitTypography
        val scaledOne = original.scaled(1.0f)
        assertSame(original, scaledOne)
    }

    @Test
    fun testTypographyScaledFunctionScalingFactors() {
        val original = CockpitTypography
        val scaledLarge = original.scaled(1.15f)

        assertEquals(64f * 1.15f, scaledLarge.displayLarge.fontSize.value, 0.01f)
        assertEquals(36f * 1.15f, scaledLarge.displayMedium.fontSize.value, 0.01f)
        assertEquals(22f * 1.15f, scaledLarge.headlineMedium.fontSize.value, 0.01f)
        assertEquals(18f * 1.15f, scaledLarge.titleLarge.fontSize.value, 0.01f)
        assertEquals(15f * 1.15f, scaledLarge.titleMedium.fontSize.value, 0.01f)
        assertEquals(14f * 1.15f, scaledLarge.bodyLarge.fontSize.value, 0.01f)
        assertEquals(12f * 1.15f, scaledLarge.bodyMedium.fontSize.value, 0.01f)
        assertEquals(10f * 1.15f, scaledLarge.labelSmall.fontSize.value, 0.01f)
    }

    @Test
    fun testAppSettingsManagerDefaultScaleState() {
        val state = AppSettingsManager.SettingsState()
        assertEquals("default", state.uiScale)
        assertEquals(UiScaleMode.DEFAULT, state.uiScaleMode)
        assertEquals(1.00f, state.uiScaleFactor, 0.001f)
    }

    @Test
    fun testAppSettingsManagerCustomScaleState() {
        val customState = AppSettingsManager.SettingsState(
            uiScale = "large",
            carName = "Skoda Superb",
            fuelType = "Diesel"
        )
        assertEquals("large", customState.uiScale)
        assertEquals(UiScaleMode.LARGE, customState.uiScaleMode)
        assertEquals(1.15f, customState.uiScaleFactor, 0.001f)
    }

    @Test
    fun testAppSettingsManagerScaleCorruptedFallback() {
        val corruptedState = AppSettingsManager.SettingsState(uiScale = "unknown_legacy_val")
        assertEquals(UiScaleMode.DEFAULT, corruptedState.uiScaleMode)
        assertEquals(1.00f, corruptedState.uiScaleFactor, 0.001f)
    }

    @Test
    fun testAppDrawerGridPreservedAcrossScaling() {
        // App drawer structure is guaranteed to maintain exactly 6 columns and 5 rows
        assertEquals(6, LauncherManager.GRID_COLUMNS)
        assertEquals(5, LauncherManager.GRID_ROWS)
        assertEquals(30, LauncherManager.APPS_PER_PAGE)

        // For all four scaling modes, grid spacing is positive and within responsive limits
        UiScaleMode.values().forEach { mode ->
            val tokens = CockpitScaleTokens.forMode(mode)
            assertTrue(tokens.appDrawerGridHorizontalSpacing.value in 3f..8f)
            assertTrue(tokens.appDrawerGridVerticalSpacing.value in 2f..6f)
            assertTrue(tokens.appDrawerIconSize.value in 36f..60f)
        }
    }
}
