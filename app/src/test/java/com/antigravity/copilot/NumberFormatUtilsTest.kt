package com.antigravity.copilot

import com.antigravity.copilot.core.util.NumberFormatUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class NumberFormatUtilsTest {

    @Test
    fun testGlobalPrecisionRuleExamples() {
        // As specified in user prompt:
        // 42.857142857 → 42.86
        // 65.000000 → 65
        // 8.5 → 8.5
        // 0.123456 → 0.12
        assertEquals("42.86", NumberFormatUtils.formatPrecision(42.857142857))
        assertEquals("65", NumberFormatUtils.formatPrecision(65.000000))
        assertEquals("8.5", NumberFormatUtils.formatPrecision(8.5))
        assertEquals("0.12", NumberFormatUtils.formatPrecision(0.123456))
    }

    @Test
    fun testTrailingZerosStripped() {
        assertEquals("10.5", NumberFormatUtils.formatPrecision(10.50))
        assertEquals("10", NumberFormatUtils.formatPrecision(10.00))
        assertEquals("0", NumberFormatUtils.formatPrecision(0.0))
        assertEquals("0", NumberFormatUtils.formatPrecision(0.0000))
    }

    @Test
    fun testSafeHandlingOfNullAndInvalidValues() {
        assertEquals("0", NumberFormatUtils.formatPrecision(null))
        assertEquals("0", NumberFormatUtils.formatPrecision(Double.NaN))
        assertEquals("0", NumberFormatUtils.formatPrecision(Double.POSITIVE_INFINITY))
        assertEquals("0", NumberFormatUtils.formatPrecision(Double.NEGATIVE_INFINITY))
        assertEquals("--", NumberFormatUtils.formatPrecision(null, fallback = "--"))
        assertEquals("--", NumberFormatUtils.formatPrecision(Double.NaN, fallback = "--"))
    }

    @Test
    fun testCustomDecimals() {
        assertEquals("42.9", NumberFormatUtils.formatPrecision(42.857, maxDecimals = 1))
        assertEquals("43", NumberFormatUtils.formatPrecision(42.857, maxDecimals = 0))
        assertEquals("42.857", NumberFormatUtils.formatPrecision(42.8571, maxDecimals = 3))
    }

    @Test
    fun testGroupingOption() {
        assertEquals("12,500.5", NumberFormatUtils.formatPrecision(12500.50, maxDecimals = 2, useGrouping = true))
        assertEquals("12,500", NumberFormatUtils.formatPrecision(12500.0, maxDecimals = 2, useGrouping = true))
        assertEquals("1,234,567.89", NumberFormatUtils.formatPrecision(1234567.891, maxDecimals = 2, useGrouping = true))
    }

    @Test
    fun testSignedPrecision() {
        assertEquals("+1.5", NumberFormatUtils.formatSignedPrecision(1.5))
        assertEquals("-1.5", NumberFormatUtils.formatSignedPrecision(-1.5))
        assertEquals("0", NumberFormatUtils.formatSignedPrecision(0.0))
    }

    @Test
    fun testSemanticHelpers() {
        assertEquals("42.86 km/h", NumberFormatUtils.formatSpeedWithUnit(42.857142857))
        assertEquals("15.2 km/L", NumberFormatUtils.formatMileageWithUnit(15.200))
        assertEquals("35.5 L", NumberFormatUtils.formatFuelLitersWithUnit(35.50))
        assertEquals("₹96.5", NumberFormatUtils.formatCurrencyWithSymbol(96.50))
        assertEquals("₹1,250", NumberFormatUtils.formatCurrencyWithSymbol(1250.0, useGrouping = true))
        assertEquals("120.45 km", NumberFormatUtils.formatDistanceWithUnit(120.452))
    }
}
