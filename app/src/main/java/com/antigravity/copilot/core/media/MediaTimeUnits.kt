package com.antigravity.copilot.core.media

/**
 * Seconds-vs-milliseconds resolution for head-unit media broadcasts (audit Q1). Some units send
 * seconds, Android sends milliseconds. A duration of 1..7200 is a seconds value (a 7 s song in
 * ms is not plausible); a position is NOT judged on its own (7,200 ms = 7.2 s into a song is
 * normal), it follows the unit of the duration in the same broadcast, defaulting to ms.
 */
object MediaTimeUnits {
    const val MAX_SECONDS_DURATION = 7200L

    fun isSecondsDuration(raw: Long, key: String?): Boolean =
        !(key?.contains("ms", ignoreCase = true) ?: false) && raw in 1..MAX_SECONDS_DURATION

    fun durationToMillis(raw: Long, key: String?): Long =
        if (raw <= 0L) 0L else if (isSecondsDuration(raw, key)) raw * 1000L else raw

    fun positionToMillis(raw: Long, key: String?, durationInSeconds: Boolean?): Long {
        if (raw <= 0L) return 0L
        if (key?.contains("ms", ignoreCase = true) == true) return raw
        return if (durationInSeconds == true) raw * 1000L else raw
    }
}
