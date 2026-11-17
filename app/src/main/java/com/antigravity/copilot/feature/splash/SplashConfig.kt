package com.antigravity.copilot.feature.splash

/** Splash timeline. Kept short (~5.4 s) so the dashboard is reachable quickly; tap skips it. */
data class SplashConfig(
    val totalDurationMs: Long = 5400L,
    // Stage 1: Brand Logo (visible initially, fades out into Stage 2)
    val logoVisibleDurationMs: Long = 1700L,
    val logoFadeOutDurationMs: Long = 500L,
    // Stage 2: Light trails & Car silhouette
    val trailsStartMs: Long = 1800L,
    val trailsDurationMs: Long = 1300L,
    val silhouetteStartMs: Long = 2000L,
    val silhouetteDurationMs: Long = 1600L,
    // Stage 3: Welcome typography & subtitle
    val welcomeStartMs: Long = 2900L,
    val welcomeDurationMs: Long = 1100L,
    val subtitleStartMs: Long = 3500L,
    val subtitleDurationMs: Long = 900L,
    // Pulse effect
    val glowPulseStartMs: Long = 2700L,
    val glowPulsePeriodMs: Long = 1400L,
    // Stage 4: Exit transition into dashboard
    val exitTransitionStartMs: Long = 4800L,
    val exitTransitionDurationMs: Long = 600L,
    val allowTapToSkip: Boolean = true
) {
    init {
        require(totalDurationMs > 0) { "totalDurationMs must be positive" }
        require(exitTransitionStartMs < totalDurationMs) { "exitTransitionStartMs must be before totalDurationMs" }
    }
}
