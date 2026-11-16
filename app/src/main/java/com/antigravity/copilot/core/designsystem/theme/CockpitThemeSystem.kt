package com.antigravity.copilot.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import com.antigravity.copilot.core.designsystem.colors.CockpitColors
import com.antigravity.copilot.core.designsystem.typography.LocalCockpitTypography

/**
 * Clean Architecture theme contract facade and backward compatibility alias.
 */
val CockpitTheme = com.antigravity.copilot.core.designsystem.CockpitTheme

object CockpitThemeSystem {
    val colors: CockpitColors
        @Composable
        @ReadOnlyComposable
        get() = CockpitTheme.colors

    val typography: Typography
        @Composable
        @ReadOnlyComposable
        get() = LocalCockpitTypography.current
}
