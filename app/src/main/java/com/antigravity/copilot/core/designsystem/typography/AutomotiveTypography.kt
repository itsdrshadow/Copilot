package com.antigravity.copilot.core.designsystem.typography

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.R

/**
 * Raahi typography, tuned for a 10.1" landscape head unit read from ~2.5 ft. Both families are
 * bundled in res/font (the app runs offline; no downloadable font provider).
 *
 * - **Barlow Semi Condensed** 600/700 for numbers (speed, litres, km, clocks) with tabular digits;
 *   primary readouts are Bold 700.
 * - **Inter** 500/600 for labels and body text: nothing on the cockpit renders in Regular or Light,
 *   which looked too thin at arm's length.
 *
 * Light/Normal requests are promoted to the Medium cut, and ExtraBold/Black map to the real Bold
 * file (never a synthetic bold).
 */
val BarlowSemiCondensedFamily = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Light),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.ExtraBold),
    Font(R.font.barlow_semi_condensed_bold, FontWeight.Black)
)

val InterFamily = FontFamily(
    Font(R.font.inter_medium, FontWeight.Light),
    Font(R.font.inter_medium, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_bold, FontWeight.ExtraBold),
    Font(R.font.inter_bold, FontWeight.Black)
)

/** Legacy name: numeric call sites used the old bundled family; they now get Barlow. */
val GoogleSansFamily = BarlowSemiCondensedFamily

object AutomotiveTypographyTokens {
    val FontFamilyNumeric = BarlowSemiCondensedFamily
    val FontFamilyLabel = InterFamily
    /** Legacy alias. */
    val FontFamilySans = InterFamily
    const val FontFeatureTabular = "tnum"

    val DefaultPlatformStyle = PlatformTextStyle(includeFontPadding = false)

    /** Minimum label size anywhere in the app. */
    val MinLabelSize = 14.sp

    private fun numeric(size: Float, line: Float, weight: FontWeight = FontWeight.Bold, tracking: Float = 0f) = TextStyle(
        fontFamily = FontFamilyNumeric,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    private fun label(size: Float, line: Float, weight: FontWeight = FontWeight.Medium, tracking: Float = 0f) = TextStyle(
        fontFamily = FontFamilyLabel,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    // ---- Numbers (Barlow Semi Condensed, Bold 700) ----
    /** Hero speed readout. */
    val SpeedHero = numeric(112f, 112f, tracking = -1.5f)
    val DisplayLarge = numeric(76f, 80f, tracking = -1f)
    val DisplayMedium = numeric(50f, 54f, tracking = -0.5f)
    val DisplaySmall = numeric(36f, 40f)
    val HeadlineLarge = numeric(30f, 34f)
    val HeadlineMedium = numeric(24f, 28f)
    val HeadlineSmall = numeric(20f, 24f, weight = FontWeight.SemiBold)

    // ---- Labels (Inter, Medium 500 / SemiBold 600; 14 sp minimum) ----
    val TitleLarge = label(20f, 26f, FontWeight.SemiBold)
    val TitleMedium = label(17f, 22f, FontWeight.SemiBold)
    val TitleSmall = label(15f, 20f, FontWeight.SemiBold)
    val BodyLarge = label(17f, 24f)
    val BodyMedium = label(15f, 21f)
    val BodySmall = label(14f, 19f)
    val LabelLarge = label(16f, 21f, FontWeight.SemiBold, 0.1f)
    val LabelMedium = label(14f, 19f, FontWeight.SemiBold, 0.15f)
    val LabelSmall = label(14f, 18f, FontWeight.SemiBold, 0.2f)

    // Dedicated numeric variants
    val NumericClock = numeric(56f, 60f, tracking = -0.5f)
    val NumericFuelLiters = numeric(40f, 44f)
    val NumericTankPct = numeric(28f, 32f)
    val NumericOdometer = numeric(28f, 32f)
    val NumericSpeedUnit = label(14f, 18f, FontWeight.SemiBold, 0.4f)
}

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
