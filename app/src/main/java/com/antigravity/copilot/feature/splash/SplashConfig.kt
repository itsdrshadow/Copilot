package com.antigravity.copilot.feature.splash

data class SplashConfig(
    val totalDurationMs: Long = 8200L,
    // Stage 1: Brand Logo (visible initially, fades out into Stage 2)
    val logoVisibleDurationMs: Long = 2800L,
    val logoFadeOutDurationMs: Long = 700L,
    // Stage 2: Light trails & Car silhouette
    val trailsStartMs: Long = 2900L,
    val trailsDurationMs: Long = 1800L,
    val silhouetteStartMs: Long = 3300L,
    val silhouetteDurationMs: Long = 2200L,
    // Stage 3: Welcome typography & subtitle
    val welcomeStartMs: Long = 4800L,
    val welcomeDurationMs: Long = 1600L,
    val subtitleStartMs: Long = 5800L,
    val subtitleDurationMs: Long = 1300L,
    // Pulse effect
    val glowPulseStartMs: Long = 4200L,
    val glowPulsePeriodMs: Long = 1400L,
    // Stage 4: Exit transition into dashboard
    val exitTransitionStartMs: Long = 7400L,
    val exitTransitionDurationMs: Long = 800L,
    val allowTapToSkip: Boolean = true
) {
    init {
        require(totalDurationMs > 0) { "totalDurationMs must be positive" }
        require(exitTransitionStartMs < totalDurationMs) { "exitTransitionStartMs must be before totalDurationMs" }
    }
}
