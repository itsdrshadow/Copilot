package com.antigravity.copilot.core.designsystem

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Soft halo in the number's own colour so figures read as lit on the dark cockpit surface.
 * Skipped for dark ink (light theme) where a blur would only look like a smudge.
 */
fun numberGlow(color: Color): Shadow? =
    if (color.luminance() < 0.2f) null
    else Shadow(color = color.copy(alpha = 0.30f), offset = Offset.Zero, blurRadius = 24f)

/**
 * Composite Adaptive Metric Text (Value + Unit):
 * Renders a primary numerical value with an attached unit label.
 * Measures both together to ensure neither the number nor the unit wraps or clips,
 * scaling both in exact proportion to the available container dimensions.
 */
@Composable
fun AdaptiveMetricValueText(
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    preferredFontSize: TextUnit = 38.sp,
    minFontSize: TextUnit = 12.sp,
    unitRatio: Float = 0.38f,
    minUnitFontSize: TextUnit = 9.sp,
    style: TextStyle = LocalTextStyle.current,
    valueColor: Color = CockpitTheme.colors.textPrimary,
    unitColor: Color = CockpitTheme.colors.textSecondary,
    fontWeight: FontWeight = FontWeight.Black,
    fontFamily: FontFamily? = AutomotiveDesignTokens.NumericTypography.fontFamily,
    fontFeatureSettings: String? = AutomotiveDesignTokens.NumericTypography.fontFeatureSettings,
    letterSpacing: TextUnit = (-0.5).sp,
    alignment: Alignment = Alignment.Center,
    textAlign: TextAlign = TextAlign.Center
) {
    val textMeasurer = rememberTextMeasurer()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = alignment
    ) {
        val maxW = constraints.maxWidth
        val maxH = constraints.maxHeight

        val baseStyle = remember(style, fontWeight, fontFamily, letterSpacing, textAlign, fontFeatureSettings) {
            style.copy(
                fontWeight = fontWeight,
                fontFamily = fontFamily ?: AutomotiveDesignTokens.NumericTypography.fontFamily,
                letterSpacing = letterSpacing,
                textAlign = textAlign,
                fontFeatureSettings = fontFeatureSettings ?: AutomotiveDesignTokens.NumericTypography.fontFeatureSettings,
                platformStyle = PlatformTextStyle(includeFontPadding = false)
            )
        }

        fun buildText(valSizeSp: Float, unitSizeSp: Float): AnnotatedString {
            return buildAnnotatedString {
                withStyle(
                    SpanStyle(
                        fontSize = valSizeSp.sp,
                        color = valueColor,
                        shadow = numberGlow(valueColor),
                        fontWeight = fontWeight,
                        fontFamily = fontFamily ?: AutomotiveDesignTokens.NumericTypography.fontFamily,
                        letterSpacing = letterSpacing
                    )
                ) {
                    append(value)
                }
                if (unit.isNotBlank()) {
                    append(" ")
                    withStyle(
                        SpanStyle(
                            fontSize = unitSizeSp.sp,
                            color = unitColor,
                            fontWeight = FontWeight.Bold,
                            fontFamily = fontFamily ?: AutomotiveDesignTokens.NumericTypography.fontFamily
                        )
                    ) {
                        append(unit)
                    }
                }
            }
        }

        val optimalSizes = remember(value, unit, maxW, maxH, preferredFontSize, minFontSize, baseStyle) {
            if (maxW <= 0 || value.isEmpty()) {
                val u = (preferredFontSize.value * unitRatio).coerceAtLeast(minUnitFontSize.value)
                Pair(preferredFontSize.value, u)
            } else {
                val high = preferredFontSize.value
                val low = minFontSize.value

                fun fits(valSize: Float): Boolean {
                    val unitSize = (valSize * unitRatio).coerceAtLeast(minUnitFontSize.value)
                    val annotated = buildText(valSize, unitSize)
                    val result = textMeasurer.measure(
                        text = annotated,
                        style = baseStyle.copy(fontSize = valSize.sp),
                        constraints = Constraints(maxWidth = maxW)
                    )
                    val fitsWidth = result.size.width <= maxW
                    val fitsHeight = maxH >= 1_000_000 || result.size.height <= maxH
                    return fitsWidth && fitsHeight && result.lineCount == 1
                }

                if (fits(high)) {
                    val u = (high * unitRatio).coerceAtLeast(minUnitFontSize.value)
                    Pair(high, u)
                } else {
                    var l = low
                    var r = high
                    var best = low
                    repeat(6) {
                        val mid = (l + r) / 2f
                        if (fits(mid)) {
                            best = mid
                            l = mid
                        } else {
                            r = mid
                        }
                    }
                    val u = (best * unitRatio).coerceAtLeast(minUnitFontSize.value)
                    Pair(best, u)
                }
            }
        }

        val finalAnnotated = remember(value, unit, optimalSizes, valueColor, unitColor) {
            buildText(optimalSizes.first, optimalSizes.second)
        }

        Text(
            text = finalAnnotated,
            style = baseStyle.copy(
                fontSize = optimalSizes.first.sp,
                lineHeight = (optimalSizes.first * 1.05f).sp
            ),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = textAlign
        )
    }
}
