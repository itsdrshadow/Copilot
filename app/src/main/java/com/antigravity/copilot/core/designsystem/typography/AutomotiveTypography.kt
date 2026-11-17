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
 * Raahi typography. Both families are bundled in res/font (the app runs offline; no downloadable
 * font provider).
 *
 * - **Barlow Semi Condensed** 500/600 for numbers (speed, litres, km, clocks) with tabular digits.
 * - **Inter** 400/500 for labels and body text.
 *
 * Heavier weight requests (Bold, ExtraBold, Black) map to the heaviest bundled file of each family,
 * so older call sites that ask for `FontWeight.Black` render the real 600 / 500 cut instead of a
 * synthetic bold.
 */
val BarlowSemiCondensedFamily = FontFamily(
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Light),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Normal),
    Font(R.font.barlow_semi_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.Bold),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.ExtraBold),
    Font(R.font.barlow_semi_condensed_semibold, FontWeight.Black)
)

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_medium, FontWeight.SemiBold),
    Font(R.font.inter_medium, FontWeight.Bold),
    Font(R.font.inter_medium, FontWeight.ExtraBold),
    Font(R.font.inter_medium, FontWeight.Black)
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
    val MinLabelSize = 12.sp

    private fun numeric(size: Float, line: Float, weight: FontWeight = FontWeight.SemiBold, tracking: Float = 0f) = TextStyle(
        fontFamily = FontFamilyNumeric,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    private fun label(size: Float, line: Float, weight: FontWeight = FontWeight.Normal, tracking: Float = 0f) = TextStyle(
        fontFamily = FontFamilyLabel,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        letterSpacing = tracking.sp,
        fontFeatureSettings = FontFeatureTabular,
        platformStyle = DefaultPlatformStyle
    )

    // ---- Numbers (Barlow Semi Condensed) ----
    /** Hero speed readout. */
    val SpeedHero = numeric(112f, 112f, tracking = -1.5f)
    val DisplayLarge = numeric(72f, 76f, tracking = -1f)
    val DisplayMedium = numeric(48f, 52f, tracking = -0.5f)
    val DisplaySmall = numeric(34f, 38f)
    val HeadlineLarge = numeric(28f, 32f)
    val HeadlineMedium = numeric(22f, 26f)
    val HeadlineSmall = numeric(18f, 22f, weight = FontWeight.Medium)

    // ---- Labels (Inter) ----
    val TitleLarge = label(19f, 26f, FontWeight.Medium)
    val TitleMedium = label(16f, 22f, FontWeight.Medium)
    val TitleSmall = label(14f, 20f, FontWeight.Medium)
    val BodyLarge = label(16f, 23f)
    val BodyMedium = label(14f, 20f)
    val BodySmall = label(13f, 18f)
    val LabelLarge = label(15f, 20f, FontWeight.Medium, 0.1f)
    val LabelMedium = label(13f, 18f, FontWeight.Medium, 0.2f)
    val LabelSmall = label(12f, 16f, FontWeight.Medium, 0.3f)

    // Dedicated numeric variants
    val NumericClock = numeric(56f, 60f, tracking = -0.5f)
    val NumericFuelLiters = numeric(40f, 44f)
    val NumericTankPct = numeric(28f, 32f)
    val NumericOdometer = numeric(28f, 32f)
    val NumericSpeedUnit = label(14f, 18f, FontWeight.Medium, 0.4f)
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
