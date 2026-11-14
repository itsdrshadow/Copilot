package com.antigravity.drivingreport.feature.splash

data class SplashConfig(
    val totalDurationMs: Long = 3600L,
    val trailsStartMs: Long = 150L,
    val trailsDurationMs: Long = 1100L,
    val silhouetteStartMs: Long = 550L,
    val silhouetteDurationMs: Long = 1450L,
    val welcomeStartMs: Long = 1350L,
    val welcomeDurationMs: Long = 950L,
    val subtitleStartMs: Long = 2100L,
    val subtitleDurationMs: Long = 850L,
    val glowPulseStartMs: Long = 1800L,
    val glowPulsePeriodMs: Long = 1200L,
    val exitTransitionStartMs: Long = 3100L,
    val exitTransitionDurationMs: Long = 500L,
    val allowTapToSkip: Boolean = true
) {
    init {
        require(totalDurationMs > 0) { "totalDurationMs must be positive" }
        require(exitTransitionStartMs < totalDurationMs) { "exitTransitionStartMs must be before totalDurationMs" }
    }
}
