package com.antigravity.copilot.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

/**
 * The cockpit UI is designed for one display: the 10.1" head unit at 1280 x 800 dp. Instead of
 * separate phone / tablet layouts, every screen gets that same UI scaled uniformly: the density
 * is chosen so the window always measures as at least 1280 dp wide and 800 dp tall (scale-to-fit
 * on the tighter axis). dp and sp scale together, so cards, icons and text keep the exact
 * proportions of the 1280 x 800 design on smaller, medium and larger displays.
 *
 * On wider aspect ratios (e.g. a 20:9 phone) the height locks to 800 dp and the extra width goes
 * to the weighted panels; on squarer ones (4:3 tablets) the width locks to 1280 dp.
 * The user's system font scale is kept.
 */
@Composable
fun ScaleToReferenceDisplay(content: @Composable () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val base = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scaled = remember(base, widthPx, heightPx) {
            val pxPerDp = minOf(widthPx / ReferenceWidthDp, heightPx / ReferenceHeightDp)
            if (pxPerDp.isFinite() && pxPerDp > 0f) Density(pxPerDp, base.fontScale) else base
        }
        CompositionLocalProvider(LocalDensity provides scaled, content = content)
    }
}

private const val ReferenceWidthDp = 1280f
private const val ReferenceHeightDp = 800f
