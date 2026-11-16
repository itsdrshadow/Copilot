package com.antigravity.copilot.core.designsystem.typography

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Standardized Automotive Infotainment Typography System.
 *
 * Engineered strictly for a 10.1-inch landscape (1280x800) automotive cockpit.
 * Provides 14 semantic text styles derived from the production Home Screen,
 * ensuring high contrast, immediate glanceability from driving distance,
 * zero layout thrashing, and uniform tabular numeral alignment ("tnum").
 */
object AutomotiveTypographyTokens {
    val FontFamilySans = FontFamily.SansSerif
    const val FontFeatureTabular = "tnum"

    // Default zero-padding platform style for precision vertical automotive alignment
    val DefaultPlatformStyle = PlatformTextStyle(includeFontPadding = false)

    // ==================== 14 SEMANTIC AUTOMOTIVE TEXT STYLES ====================

    /**
     * Display Large: Speedometer and primary numerical telemetry values.
     * Derived from Home Screen speedometer readout (82sp, Black, Tabular figures).
     */
    val DisplayLarge = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Black,
        fontSize = 82.sp,
        lineHeight = 88.sp,
        letterSpacing = (-1.0).sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Display Medium: Digital clock, odometer and fuel readings.
     * High-glanceability numeric display for primary instrument clusters (48sp..62sp).
     */
    val DisplayMedium = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Black,
        fontSize = 48.sp,
        lineHeight = 54.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Display Small: Trip statistics and secondary numerical values.
     * Used for rotating trip cards, telemetry readouts, and secondary metrics (34sp).
     */
    val DisplaySmall = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Headline Large: Main screen headings.
     * Used for top-level screen titles (Trip History, Refuel Logs, Settings, Apps) (24sp).
     */
    val HeadlineLarge = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Black,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Headline Medium: Section headings.
     * Used for screen sub-sections, dialog group headers, and monthly accordions (20sp).
     */
    val HeadlineMedium = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Headline Small: Card headings.
     * Used for telemetry card titles (SPEEDOMETER, FUEL TANK, CURRENT TRIP, DIGITAL CLOCK) (17sp).
     */
    val HeadlineSmall = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.5.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Title Large: Important card titles.
     * Used for trip item location routes, prominent dialog titles, and hero titles (19sp).
     */
    val TitleLarge = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Title Medium: Component titles.
     * Used for setting row items, dialog item labels, and media track titles (16.5sp).
     */
    val TitleMedium = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.5.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.15.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Title Small: Secondary titles.
     * Used for sub-headers, secondary item titles, and chip labels (15sp).
     */
    val TitleSmall = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Body Large: Important descriptive information.
     * Used for modal descriptions, important status alerts, and confirmation dialog bodies (16sp).
     */
    val BodyLarge = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.25.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Body Medium: Standard descriptive text.
     * Used for card subtitles, list item descriptions, and general content (14.5sp).
     */
    val BodyMedium = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Body Small: Secondary information.
     * Used for timestamps, auxiliary telemetry metadata, and secondary notes (13sp).
     */
    val BodySmall = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Label Large: Primary buttons.
     * Used for tactile cockpit buttons (+ ADD FUEL, SAVE REFUEL, CONFIRM, RESET) (15sp).
     */
    val LabelLarge = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.4.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Label Medium: Navigation and tabs.
     * Used for Cockpit dock buttons, detail tab selectors, and segment indicators (14.5sp).
     */
    val LabelMedium = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 14.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.3.sp,
        platformStyle = DefaultPlatformStyle
    )

    /**
     * Label Small: Units and supporting labels.
     * Used for measurement units (KM/H, KM, KM/L, L, ₹), badges, and telemetry pills (13sp).
     */
    val LabelSmall = TextStyle(
        fontFamily = FontFamilySans,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        platformStyle = DefaultPlatformStyle
    )

    // Dedicated numeric variants using the speedometer-grade tabular font
    val NumericClock = DisplayMedium.copy(fontSize = 62.sp, lineHeight = 66.sp, letterSpacing = (-1.0).sp)
    val NumericFuelLiters = DisplayMedium.copy(fontSize = 38.sp, lineHeight = 42.sp)
    val NumericTankPct = DisplayMedium.copy(fontSize = 36.sp, lineHeight = 40.sp)
    val NumericOdometer = DisplaySmall.copy(fontSize = 32.sp, lineHeight = 36.sp)
    val NumericSpeedUnit = LabelSmall.copy(fontSize = 14.5.sp, fontWeight = FontWeight.Black, letterSpacing = 1.0.sp)
}

/**
 * Complete Material 3 Typography integration adhering to the Automotive Infotainment Design System.
 */
val CockpitTypography = Typography(
    displayLarge = AutomotiveTypographyTokens.DisplayLarge,
    displayMedium = AutomotiveTypographyTokens.DisplayMedium,
    displaySmall = AutomotiveTypographyTokens.DisplaySmall,
    headlineLarge = AutomotiveTypographyTokens.HeadlineLarge,
    headlineMedium = AutomotiveTypographyTokens.HeadlineMedium,
    headlineSmall = AutomotiveTypographyTokens.HeadlineSmall,
    titleLarge = AutomotiveTypographyTokens.TitleLarge,
    titleMedium = AutomotiveTypographyTokens.TitleMedium,
    titleSmall = AutomotiveTypographyTokens.TitleSmall,
    bodyLarge = AutomotiveTypographyTokens.BodyLarge,
    bodyMedium = AutomotiveTypographyTokens.BodyMedium,
    bodySmall = AutomotiveTypographyTokens.BodySmall,
    labelLarge = AutomotiveTypographyTokens.LabelLarge,
    labelMedium = AutomotiveTypographyTokens.LabelMedium,
    labelSmall = AutomotiveTypographyTokens.LabelSmall
)

val LocalCockpitTypography = staticCompositionLocalOf { CockpitTypography }
