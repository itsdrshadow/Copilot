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
        val boost = remember(scaled) { popupBoostFor(scaled.density) }
        CompositionLocalProvider(LocalDensity provides scaled, LocalPopupBoost provides boost, content = content)
    }
}

/**
 * Readability boost for compact popups (dialogs, map bubbles, alert cards). The 1280 x 800 rule
 * keeps the cockpit layout identical everywhere, but on a small dense screen (a 6" phone) one dp
 * becomes about half its head-unit size and 12-14 sp popup text ends up ~1 mm tall. Popups are
 * free-floating, so they can grow without changing the layout: the boost restores at least
 * [POPUP_MIN_PHYSICAL] of the device's native dp size, capped at [POPUP_MAX_BOOST].
 * Head units and tablets (reference scale >= native) get 1.0, i.e. unchanged.
 */
val LocalPopupBoost = androidx.compose.runtime.staticCompositionLocalOf { 1f }

private const val POPUP_MIN_PHYSICAL = 0.72f
private const val POPUP_MAX_BOOST = 1.45f

fun popupBoostFor(scaledDensity: Float): Float {
    val native = android.content.res.Resources.getSystem().displayMetrics.density
    if (scaledDensity <= 0f || native <= 0f) return 1f
    return (native * POPUP_MIN_PHYSICAL / scaledDensity).coerceIn(1f, POPUP_MAX_BOOST)
}

/** Draws [content] at the popup readability scale ([LocalPopupBoost]); no-op when it is 1. */
@Composable
fun PopupScaled(enabled: Boolean = true, content: @Composable () -> Unit) {
    val boost = if (enabled) LocalPopupBoost.current else 1f
    if (boost <= 1.01f) {
        content()
        return
    }
    val d = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(d.density * boost, d.fontScale), content = content)
}

private const val ReferenceWidthDp = 1280f
private const val ReferenceHeightDp = 800f

/**
 * The same 1280 x 800 scale applied to the Activity's resources (call from attachBaseContext).
 * Compose dialogs, dropdowns and alert dialogs open their own windows, which read the density
 * from resources rather than from [ScaleToReferenceDisplay]; overriding `densityDpi` here makes
 * every window of the activity (and the MapView's label sizes) use the reference scale too.
 * The app is landscape-only, so the long display side is the width.
 */
fun referenceDensityConfig(base: android.content.Context): android.content.res.Configuration? {
    // System metrics only: in attachBaseContext the activity's own resources / display services
    // must not be touched yet (both throw).
    val dm = android.content.res.Resources.getSystem().displayMetrics
    val w = maxOf(dm.widthPixels, dm.heightPixels)
    val h = minOf(dm.widthPixels, dm.heightPixels)
    if (w <= 0 || h <= 0) return null
    val pxPerDp = minOf(w / ReferenceWidthDp, h / ReferenceHeightDp)
    val dpi = (pxPerDp * android.util.DisplayMetrics.DENSITY_DEFAULT).toInt().coerceIn(60, 960)
    val cfg = android.content.res.Configuration(base.resources.configuration)
    val ratio = cfg.densityDpi.toFloat() / dpi
    // Screen size in dp must follow the new density, or windows (dialogs) are sized from the old one.
    cfg.screenWidthDp = (cfg.screenWidthDp * ratio).toInt()
    cfg.screenHeightDp = (cfg.screenHeightDp * ratio).toInt()
    cfg.smallestScreenWidthDp = (cfg.smallestScreenWidthDp * ratio).toInt()
    cfg.densityDpi = dpi
    return cfg
}
