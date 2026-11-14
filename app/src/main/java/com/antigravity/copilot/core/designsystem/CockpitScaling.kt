package com.antigravity.copilot.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI & Text Scaling Modes for the Automotive Launcher Cockpit.
 * Supports 4 optimized levels: Small (0.85×), Default (1.00×), Large (1.15×), Extra Large (1.30×).
 */
enum class UiScaleMode(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val scaleFactor: Float
) {
    SMALL(
        id = "small",
        displayName = "Small",
        subtitle = "Compact UI & text (0.85×)",
        scaleFactor = 0.85f
    ),
    DEFAULT(
        id = "default",
        displayName = "Default",
        subtitle = "Standard balanced cockpit (1.00×)",
        scaleFactor = 1.00f
    ),
    LARGE(
        id = "large",
        displayName = "Large",
        subtitle = "Enlarged UI & typography (1.15×)",
        scaleFactor = 1.15f
    ),
    EXTRA_LARGE(
        id = "extra_large",
        displayName = "Extra Large",
        subtitle = "Maximum glanceable scaling (1.30×)",
        scaleFactor = 1.30f
    );

    companion object {
        fun fromId(id: String?): UiScaleMode {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: DEFAULT
        }
    }
}

/**
 * Centralized Scaling Tokens:
 * Derived systematically from the active [UiScaleMode].
 * Guarantees responsive typography, icon sizing, spacing, card metrics, and dock sizing
 * without layout clipping, text overlapping, or horizontal/vertical overflow.
 */
data class CockpitScaleTokens(
    val mode: UiScaleMode,
    val scaleFactor: Float,
    val fontScale: Float,
    val iconScale: Float,
    val spacingScale: Float,
    // Spacing & Padding
    val cardPadding: Dp,
    val itemSpacing: Dp,
    val contentPadding: Dp,
    // App Drawer Metrics
    val appDrawerIconSize: Dp,
    val appDrawerLabelSize: TextUnit,
    val appDrawerGridVerticalSpacing: Dp,
    val appDrawerGridHorizontalSpacing: Dp,
    // Dock & Header Navigation Metrics
    val dockPillPaddingHorizontal: Dp,
    val dockPillPaddingVertical: Dp,
    val dockIconSize: Dp,
    val dockTextSize: TextUnit,
    val dockHeight: Dp,
    // Telemetry Card Metrics
    val speedNumberSize: TextUnit,
    val speedUnitSize: TextUnit,
    val speedTrendTextSize: TextUnit,
    val fuelValueSize: TextUnit,
    val tripValueSize: TextUnit,
    val odoValueSize: TextUnit,
    // Dialog & Settings Metrics
    val dialogPadding: Dp,
    val dialogTitleSize: TextUnit,
    val settingsItemPaddingVertical: Dp
) {
    fun scale(dp: Dp): Dp = (dp.value * scaleFactor).dp
    fun scaleFont(sp: TextUnit): TextUnit = (sp.value * fontScale).sp

    companion object {
        fun forMode(mode: UiScaleMode): CockpitScaleTokens {
            return when (mode) {
                UiScaleMode.SMALL -> CockpitScaleTokens(
                    mode = mode,
                    scaleFactor = 0.85f,
                    fontScale = 0.85f,
                    iconScale = 0.85f,
                    spacingScale = 0.85f,
                    cardPadding = 11.dp,
                    itemSpacing = 6.dp,
                    contentPadding = 8.dp,
                    appDrawerIconSize = 40.dp,
                    appDrawerLabelSize = 10.sp,
                    appDrawerGridVerticalSpacing = 3.dp,
                    appDrawerGridHorizontalSpacing = 5.dp,
                    dockPillPaddingHorizontal = 12.dp,
                    dockPillPaddingVertical = 8.dp,
                    dockIconSize = 16.dp,
                    dockTextSize = 11.5.sp,
                    dockHeight = 42.dp,
                    speedNumberSize = 70.sp,
                    speedUnitSize = 12.sp,
                    speedTrendTextSize = 10.5.sp,
                    fuelValueSize = 29.sp,
                    tripValueSize = 36.sp,
                    odoValueSize = 36.sp,
                    dialogPadding = 16.dp,
                    dialogTitleSize = 17.sp,
                    settingsItemPaddingVertical = 8.dp
                )
                UiScaleMode.DEFAULT -> CockpitScaleTokens(
                    mode = mode,
                    scaleFactor = 1.00f,
                    fontScale = 1.00f,
                    iconScale = 1.00f,
                    spacingScale = 1.00f,
                    cardPadding = 14.dp,
                    itemSpacing = 8.dp,
                    contentPadding = 10.dp,
                    appDrawerIconSize = 46.dp,
                    appDrawerLabelSize = 11.5.sp,
                    appDrawerGridVerticalSpacing = 4.dp,
                    appDrawerGridHorizontalSpacing = 6.dp,
                    dockPillPaddingHorizontal = 15.dp,
                    dockPillPaddingVertical = 11.dp,
                    dockIconSize = 19.dp,
                    dockTextSize = 13.5.sp,
                    dockHeight = 48.dp,
                    speedNumberSize = 82.sp,
                    speedUnitSize = 14.sp,
                    speedTrendTextSize = 12.5.sp,
                    fuelValueSize = 34.sp,
                    tripValueSize = 44.sp,
                    odoValueSize = 44.sp,
                    dialogPadding = 20.dp,
                    dialogTitleSize = 20.sp,
                    settingsItemPaddingVertical = 10.dp
                )
                UiScaleMode.LARGE -> CockpitScaleTokens(
                    mode = mode,
                    scaleFactor = 1.15f,
                    fontScale = 1.15f,
                    iconScale = 1.12f,
                    spacingScale = 0.95f,
                    cardPadding = 13.dp,
                    itemSpacing = 7.dp,
                    contentPadding = 8.dp,
                    appDrawerIconSize = 50.dp,
                    appDrawerLabelSize = 12.5.sp,
                    appDrawerGridVerticalSpacing = 3.dp,
                    appDrawerGridHorizontalSpacing = 5.dp,
                    dockPillPaddingHorizontal = 13.dp,
                    dockPillPaddingVertical = 9.dp,
                    dockIconSize = 20.dp,
                    dockTextSize = 14.5.sp,
                    dockHeight = 50.dp,
                    speedNumberSize = 80.sp,
                    speedUnitSize = 15.sp,
                    speedTrendTextSize = 13.sp,
                    fuelValueSize = 36.sp,
                    tripValueSize = 46.sp,
                    odoValueSize = 46.sp,
                    dialogPadding = 18.dp,
                    dialogTitleSize = 22.sp,
                    settingsItemPaddingVertical = 11.dp
                )
                UiScaleMode.EXTRA_LARGE -> CockpitScaleTokens(
                    mode = mode,
                    scaleFactor = 1.30f,
                    fontScale = 1.30f,
                    iconScale = 1.20f,
                    spacingScale = 0.85f,
                    cardPadding = 11.dp,
                    itemSpacing = 6.dp,
                    contentPadding = 6.dp,
                    appDrawerIconSize = 54.dp,
                    appDrawerLabelSize = 13.sp,
                    appDrawerGridVerticalSpacing = 2.dp,
                    appDrawerGridHorizontalSpacing = 4.dp,
                    dockPillPaddingHorizontal = 11.dp,
                    dockPillPaddingVertical = 8.dp,
                    dockIconSize = 21.dp,
                    dockTextSize = 15.sp,
                    dockHeight = 52.dp,
                    speedNumberSize = 76.sp,
                    speedUnitSize = 15.sp,
                    speedTrendTextSize = 12.sp,
                    fuelValueSize = 36.sp,
                    tripValueSize = 46.sp,
                    odoValueSize = 46.sp,
                    dialogPadding = 16.dp,
                    dialogTitleSize = 23.sp,
                    settingsItemPaddingVertical = 11.dp
                )
            }
        }
    }
}

val LocalCockpitScale = staticCompositionLocalOf { CockpitScaleTokens.forMode(UiScaleMode.DEFAULT) }
val LocalUiScaleMode = staticCompositionLocalOf { UiScaleMode.DEFAULT }
