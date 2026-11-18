package com.antigravity.copilot.core.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Centralized numerical precision and presentation formatter.
 *
 * Display rules (one place for the whole app):
 * - speed: whole km/h; distance, litres, km/L: 1 decimal; ₹ and odometer: whole, grouped (₹20,095).
 * - Call sites pass extra decimals only where a reading genuinely needs it (price per litre).
 *
 * Rules:
 * 1. Global Precision Rule: All displayed numerical values must use a maximum of 2 decimal places.
 * 2. Never display unnecessarily long floating-point values (e.g., 42.857142857 -> "42.86", 65.000000 -> "65").
 * 3. Strip unnecessary trailing zeros (e.g., 8.50 -> "8.5", 65.0 -> "65").
 * 4. Safe handling for null, NaN, Infinity, and invalid numbers.
 * 5. Presentation-only formatting: Does NOT round internal database or calculation state.
 */
object NumberFormatUtils {

    private val symbols = DecimalFormatSymbols(Locale.US)

    /**
     * Formats any number to a maximum of [maxDecimals] decimal places with trailing zeros stripped.
     *
     * Examples:
     * - 42.857142857 -> "42.86"
     * - 65.000000 -> "65"
     * - 8.5 -> "8.5"
     * - 0.123456 -> "0.12"
     * - 0.0 -> "0"
     * - null / NaN / Infinity -> [fallback] ("0")
     */
    fun formatPrecision(
        value: Number?,
        maxDecimals: Int = 2,
        useGrouping: Boolean = false,
        fallback: String = "0"
    ): String {
        if (value == null) return fallback
        val d = value.toDouble()
        if (d.isNaN() || d.isInfinite()) return fallback

        return try {
            val bd = BigDecimal.valueOf(d).setScale(maxDecimals.coerceAtLeast(0), RoundingMode.HALF_UP)
            if (bd.compareTo(BigDecimal.ZERO) == 0) {
                return "0"
            }

            if (useGrouping) {
                val pattern = if (maxDecimals > 0) "#,##0." + "#".repeat(maxDecimals) else "#,##0"
                val df = DecimalFormat(pattern, symbols).apply {
                    roundingMode = RoundingMode.HALF_UP
                }
                val formatted = df.format(bd)
                if (formatted == "-0") "0" else formatted
            } else {
                val stripped = bd.stripTrailingZeros()
                val plain = stripped.toPlainString()
                if (plain == "-0") "0" else plain
            }
        } catch (_: Exception) {
            fallback
        }
    }

    /**
     * Formats signed numbers with an explicit '+' for positive values.
     * e.g. +1.4, -2.1, 0
     */
    fun formatSignedPrecision(
        value: Number?,
        maxDecimals: Int = 2,
        fallback: String = "0"
    ): String {
        if (value == null) return fallback
        val d = value.toDouble()
        if (d.isNaN() || d.isInfinite()) return fallback
        return when {
            d > 0.0001 -> "+${formatPrecision(d, maxDecimals)}"
            d < -0.0001 -> formatPrecision(d, maxDecimals)
            else -> "0"
        }
    }

    /**
     * Formats vehicle speed in km/h (max 2 decimals, trailing zeros stripped).
     */
    fun formatSpeed(speedKmh: Number?, maxDecimals: Int = 0): String {
        return formatPrecision(speedKmh, maxDecimals = maxDecimals, useGrouping = false, fallback = "0")
    }

    fun formatSpeedWithUnit(speedKmh: Number?, maxDecimals: Int = 0): String {
        return "${formatSpeed(speedKmh, maxDecimals)} km/h"
    }

    /**
     * Formats distance in km (max 2 decimals, trailing zeros stripped).
     */
    fun formatDistance(distanceKm: Number?, maxDecimals: Int = 1, useGrouping: Boolean = true): String {
        return formatPrecision(distanceKm, maxDecimals = maxDecimals, useGrouping = useGrouping, fallback = "0")
    }

    fun formatDistanceWithUnit(distanceKm: Number?, maxDecimals: Int = 1, useGrouping: Boolean = true): String {
        return "${formatDistance(distanceKm, maxDecimals, useGrouping)} km"
    }

    /**
     * Formats fuel efficiency in km/L (max 2 decimals, trailing zeros stripped).
     */
    fun formatMileage(mileageKmpl: Number?, maxDecimals: Int = 1): String {
        return formatPrecision(mileageKmpl, maxDecimals = maxDecimals, useGrouping = false, fallback = "0")
    }

    fun formatMileageWithUnit(mileageKmpl: Number?, maxDecimals: Int = 1): String {
        return "${formatMileage(mileageKmpl, maxDecimals)} km/L"
    }

    /**
     * Formats fuel volume in Litres (max 2 decimals, trailing zeros stripped).
     */
    fun formatFuelLiters(liters: Number?, maxDecimals: Int = 1): String {
        return formatPrecision(liters, maxDecimals = maxDecimals, useGrouping = false, fallback = "0")
    }

    fun formatFuelLitersWithUnit(liters: Number?, maxDecimals: Int = 1): String {
        return "${formatFuelLiters(liters, maxDecimals)} L"
    }

    /**
     * Formats currency amounts (₹) without or with symbol (max 2 decimals, trailing zeros stripped).
     */
    fun formatCurrency(amount: Number?, maxDecimals: Int = 0, useGrouping: Boolean = true): String {
        return formatPrecision(amount, maxDecimals = maxDecimals, useGrouping = useGrouping, fallback = "0")
    }

    fun formatCurrencyWithSymbol(amount: Number?, maxDecimals: Int = 0, useGrouping: Boolean = true): String {
        return "₹${formatCurrency(amount, maxDecimals, useGrouping)}"
    }

    /**
     * Formats odometer readings in km (max 2 decimals, trailing zeros stripped).
     */
    fun formatOdometer(odometerKm: Number?, maxDecimals: Int = 0, useGrouping: Boolean = true): String {
        return formatPrecision(odometerKm, maxDecimals = maxDecimals, useGrouping = useGrouping, fallback = "0")
    }
}
