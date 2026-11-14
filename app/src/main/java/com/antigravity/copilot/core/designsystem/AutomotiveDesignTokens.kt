package com.antigravity.copilot.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized Automotive Design System Tokens.
 *
 * Engineered specifically for a 10.1-inch landscape (1280x800) automotive infotainment cockpit.
 * Provides a strict 8dp-based spatial grid, driver-safe touch target dimensions,
 * high-contrast glanceable automotive typography, and unified container geometry.
 */
object AutomotiveDesignTokens {

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

    object Typography {
        val speedValue: TextUnit = 82.sp
        val speedUnit: TextUnit = 14.5.sp
        val clockValue: TextUnit = 62.sp
        val heroMetric: TextUnit = 44.sp
        val largeMetric: TextUnit = 34.sp
        val screenTitle: TextUnit = 22.sp
        val cardTitle: TextUnit = 16.sp
        val sectionHeader: TextUnit = 18.sp
        val buttonLabel: TextUnit = 14.sp
        val secondaryLabel: TextUnit = 13.sp
        val bodyText: TextUnit = 14.sp
        val caption: TextUnit = 11.5.sp
        val badgeText: TextUnit = 11.sp
    }

    object AppDrawer {
        val iconSize: Dp = 48.dp
        val labelSize: TextUnit = 12.sp
        val gridVerticalSpacing: Dp = 4.dp
        val gridHorizontalSpacing: Dp = 6.dp
    }

    object Dock {
        val height: Dp = 48.dp
        val pillPaddingHorizontal: Dp = 14.dp
        val pillPaddingVertical: Dp = 10.dp
        val iconSize: Dp = 20.dp
        val textSize: TextUnit = 13.5.sp
    }
}

val LocalAutomotiveTokens = staticCompositionLocalOf { AutomotiveDesignTokens }
