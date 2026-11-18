package com.antigravity.copilot.feature.splash

/**
 * Splash timeline (SP1/SP2): one logo stage, about 1.8 s, so the Drive screen comes up fast on the
 * head unit's slow CPU. The later stages start after the exit, so they never draw; tap skips.
 */
data class SplashConfig(
    val totalDurationMs: Long = 1800L,
    // Stage 1: Brand Logo (the only stage shown)
    val logoVisibleDurationMs: Long = 1300L,
    val logoFadeOutDurationMs: Long = 400L,
    // Stage 2: Light trails & Car silhouette (not shown: starts after the exit)
    val trailsStartMs: Long = 60_000L,
    val trailsDurationMs: Long = 1300L,
    val silhouetteStartMs: Long = 60_000L,
    val silhouetteDurationMs: Long = 1600L,
    // Stage 3: Welcome typography & subtitle (not shown)
    val welcomeStartMs: Long = 60_000L,
    val welcomeDurationMs: Long = 1100L,
    val subtitleStartMs: Long = 60_000L,
    val subtitleDurationMs: Long = 900L,
    // Pulse effect
    val glowPulseStartMs: Long = 300L,
    val glowPulsePeriodMs: Long = 1400L,
    // Exit transition into dashboard
    val exitTransitionStartMs: Long = 1400L,
    val exitTransitionDurationMs: Long = 400L,
    val allowTapToSkip: Boolean = true
) {
    init {
        require(totalDurationMs > 0) { "totalDurationMs must be positive" }
        require(exitTransitionStartMs < totalDurationMs) { "exitTransitionStartMs must be before totalDurationMs" }
    }
}
