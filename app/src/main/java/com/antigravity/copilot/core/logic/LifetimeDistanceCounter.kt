package com.antigravity.copilot.core.logic

/**
 * Pure lifetime distance counter (audit G1): the sum of every accepted odometer segment and
 * bridge, independent of the UI and of the trip list (short / discarded / deleted trips never
 * move it backwards). Distance that arrives before the one-time seed is buffered and added on top
 * of the seed. Persistence is throttled to every [persistEveryKm] or [persistEveryMs].
 */
class LifetimeDistanceCounter(
    totalKm: Double = 0.0,
    seeded: Boolean = false,
    private val persistEveryKm: Double = 0.1,
    private val persistEveryMs: Long = 30_000L
) {
    var totalKm: Double = totalKm.coerceAtLeast(0.0)
        private set
    var isSeeded: Boolean = seeded
        private set
    private var pendingBeforeSeedKm = 0.0
    private var lastPersistKm = this.totalKm
    private var lastPersistMs = Long.MIN_VALUE

    /** Adds [meters]; returns true when the caller should persist now. */
    fun addMeters(meters: Double, nowMs: Long): Boolean {
        if (!meters.isFinite() || meters <= 0.0) return false
        if (!isSeeded) {
            pendingBeforeSeedKm += meters / 1000.0
            return false
        }
        totalKm += meters / 1000.0
        return shouldPersist(nowMs)
    }

    /** One-time seed from the legacy source (sum of qualifying trips + active trip). */
    fun seed(seedKm: Double) {
        if (isSeeded) return
        totalKm = (if (seedKm.isFinite()) seedKm.coerceAtLeast(0.0) else 0.0) + pendingBeforeSeedKm
        pendingBeforeSeedKm = 0.0
        isSeeded = true
    }

    /** Backup restore / factory reset: replaces the total outright. */
    fun reset(totalKm: Double) {
        this.totalKm = if (totalKm.isFinite()) totalKm.coerceAtLeast(0.0) else 0.0
        pendingBeforeSeedKm = 0.0
        isSeeded = true
    }

    fun markPersisted(nowMs: Long) {
        lastPersistKm = totalKm
        lastPersistMs = nowMs
    }

    private fun shouldPersist(nowMs: Long): Boolean =
        lastPersistMs == Long.MIN_VALUE || totalKm - lastPersistKm >= persistEveryKm || nowMs - lastPersistMs >= persistEveryMs
}

/**
 * Real odometer entry validation (audit G4): a reading more than 1 km below the current odometer
 * is a typo (rejected); a jump of more than [CONFIRM_JUMP_KM] needs an explicit confirmation.
 */
object OdometerReadingValidator {
    const val MAX_BACKWARD_KM = 1.0
    const val CONFIRM_JUMP_KM = 2000.0

    enum class Result { OK, TOO_LOW, NEEDS_CONFIRMATION, INVALID }

    fun validate(readingKm: Double, currentKm: Double, isFirstReading: Boolean): Result {
        if (!readingKm.isFinite() || readingKm < 0.0 || readingKm > 9_999_999.0) return Result.INVALID
        if (isFirstReading || currentKm <= 0.0) return Result.OK
        if (readingKm < currentKm - MAX_BACKWARD_KM) return Result.TOO_LOW
        if (readingKm - currentKm > CONFIRM_JUMP_KM) return Result.NEEDS_CONFIRMATION
        return Result.OK
    }
}
