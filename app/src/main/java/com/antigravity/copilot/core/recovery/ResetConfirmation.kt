package com.antigravity.copilot.core.recovery

/**
 * Factory-reset confirmation (audit N5): no password is compiled into the APK. The driver types
 * [WORD] and then holds the erase button for [HOLD_MS].
 */
object ResetConfirmation {
    const val WORD = "RESET"
    const val HOLD_MS = 3000L

    fun isTypedWordValid(input: String?): Boolean = input?.trim() == WORD

    fun isHoldLongEnough(pressedMs: Long, releasedMs: Long): Boolean = releasedMs - pressedMs >= HOLD_MS
}
