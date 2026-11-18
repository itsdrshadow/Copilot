package com.antigravity.copilot.fuel

import com.antigravity.copilot.feature.fuel.isTwoDecimalInput
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoDecimalInputTest {
    @Test fun acceptsUpToTwoDecimals() {
        listOf("", "0", "12", "12.", "12.3", "12.34", ".5").forEach { assertTrue(it, isTwoDecimalInput(it)) }
    }

    @Test fun rejectsThirdDecimalAndJunk() {
        listOf("12.345", "1.2.3", "12a", "-1", "1,2").forEach { assertFalse(it, isTwoDecimalInput(it)) }
    }
}
