package com.antigravity.copilot.core.designsystem.dimensions

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Standardized Automotive Spatial Grid, Radii, and Touch Target Tokens.
 * Engineered for 10.1-inch landscape infotainment display (1280x800).
 */
object AutomotiveDimensions {

    object Spacing {
        val extraSmall: Dp = 8.dp
        val small: Dp = 12.dp
        val medium: Dp = 16.dp
        val large: Dp = 24.dp
        val extraLarge: Dp = 32.dp
        val sectionSpacing: Dp = 24.dp
        val cardInternalPadding: Dp = 16.dp
        val cardInternalPaddingLarge: Dp = 20.dp
        val itemSpacing: Dp = 10.dp
    }

    object TouchTargets {
        val primaryButton: Dp = 72.dp
        val primaryButtonLarge: Dp = 88.dp
        val minInteractiveArea: Dp = 64.dp
        val secondaryButton: Dp = 56.dp
        val pillButtonHeight: Dp = 48.dp
        val iconTouchArea: Dp = 48.dp
        val iconLarge: Dp = 28.dp
        val iconMedium: Dp = 22.dp
        val iconSmall: Dp = 18.dp
    }

    object Radii {
        val card: Dp = 20.dp
        val container: Dp = 16.dp
        val button: Dp = 14.dp
        val pill: Dp = 24.dp
        val tag: Dp = 8.dp
    }

    object AppDrawer {
        val iconSize: Dp = 48.dp
        val labelSize: TextUnit = 12.sp
        val gridVerticalSpacing: Dp = 4.dp
        val gridHorizontalSpacing: Dp = 6.dp
    }

    object Dock {
        val height: Dp = 64.dp
        val pillPaddingHorizontal: Dp = 16.dp
        val pillPaddingVertical: Dp = 12.dp
        val iconSize: Dp = 28.dp
        val textSize: TextUnit = 17.sp
    }
}
