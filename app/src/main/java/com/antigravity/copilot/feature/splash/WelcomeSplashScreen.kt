package com.antigravity.copilot.feature.splash

import com.antigravity.copilot.core.designsystem.tokens.BrandPalette
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun WelcomeSplashScreen(
    viewModel: SplashViewModel,
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val config = viewModel.config
    val masterAnim = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(Unit) {
        masterAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = config.totalDurationMs.toInt(),
                easing = LinearEasing
            )
        ) {
            viewModel.updateProgress(value)
        }
        viewModel.onAnimationFinished()
        onSplashFinished()
    }

    val currentMs = masterAnim.value * config.totalDurationMs

    // 1. Stage 1: Brand Logo reveal and graceful fade-out
    val logoFadeOutRaw = ((currentMs - config.logoVisibleDurationMs).toFloat() / config.logoFadeOutDurationMs).coerceIn(0f, 1f)
    val logoAlpha = if (currentMs < config.logoVisibleDurationMs) {
        val enterProgress = (currentMs.toFloat() / 350f).coerceIn(0f, 1f)
        0.5f + 0.5f * FastOutSlowInEasing.transform(enterProgress)
    } else {
        1f - FastOutSlowInEasing.transform(logoFadeOutRaw)
    }
    val logoScale = 0.95f + (0.05f * (currentMs.toFloat() / config.logoVisibleDurationMs).coerceIn(0f, 1f))

    // 2. Stage 2: Blue light trails
    val trailsRaw = ((currentMs - config.trailsStartMs).toFloat() / config.trailsDurationMs).coerceIn(0f, 1f)
    val trailsProgress = FastOutSlowInEasing.transform(trailsRaw)

    // 3. Stage 2: Silhouette line drawing
    val silRaw = ((currentMs - config.silhouetteStartMs).toFloat() / config.silhouetteDurationMs).coerceIn(0f, 1f)
    val silhouetteProgress = FastOutSlowInEasing.transform(silRaw)

    // 4. Stage 3: "Welcome" typography fade & scale
    val welcomeRaw = ((currentMs - config.welcomeStartMs).toFloat() / config.welcomeDurationMs).coerceIn(0f, 1f)
    val welcomeProgress = FastOutSlowInEasing.transform(welcomeRaw)
    val welcomeAlpha = welcomeProgress
    val welcomeScale = 0.92f + (0.08f * welcomeProgress)

    // Neon glow pulse
    val glowIntensity = if (currentMs >= config.glowPulseStartMs) {
        val elapsed = (currentMs - config.glowPulseStartMs).toDouble()
        val phase = (elapsed / config.glowPulsePeriodMs) * 2.0 * PI
        (0.78f + 0.22f * sin(phase).toFloat()).coerceIn(0.6f, 1f)
    } else {
        1.0f
    }

    // Subtitle fade
    val subtitleRaw = ((currentMs - config.subtitleStartMs).toFloat() / config.subtitleDurationMs).coerceIn(0f, 1f)
    val subtitleAlpha = FastOutSlowInEasing.transform(subtitleRaw)

    // Specular reflection
    val reflectionAlpha = silhouetteProgress * welcomeAlpha

    // 5. Stage 4: Exit transition into dashboard
    val exitAlpha = if (currentMs >= config.exitTransitionStartMs) {
        val exitRaw = ((currentMs - config.exitTransitionStartMs).toFloat() / config.exitTransitionDurationMs).coerceIn(0f, 1f)
        val trans = FastOutSlowInEasing.transform(exitRaw)
        viewModel.updateExitProgress(trans)
        1f - trans
    } else {
        1f
    }
    val exitScale = 1f + (0.04f * (1f - exitAlpha))

    val splashBackgroundBrush = remember {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF02040A),
                Color(0xFF071026),
                Color(0xFF040A18),
                Color(0xFF010307)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = exitAlpha
                scaleX = exitScale
                scaleY = exitScale
            }
            .background(splashBackgroundBrush)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (viewModel.skipSplash()) {
                    onSplashFinished()
                }
            }
    ) {
        // Stage 1: Brand Logo Presentation
        if (logoAlpha > 0.005f) {
            AutomotiveBrandLogo(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = logoAlpha
                        scaleX = logoScale
                        scaleY = logoScale
                    },
                glowIntensity = glowIntensity
            )
        }

        // Stage 2: Car Silhouette & Neon Light Trails
        if (silhouetteProgress > 0.005f || trailsProgress > 0.005f) {
            CarSilhouetteCanvas(
                trailsProgress = trailsProgress,
                silhouetteProgress = silhouetteProgress,
                reflectionAlpha = reflectionAlpha,
                glowIntensity = glowIntensity,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Stage 3: Welcome Typography
        if (welcomeAlpha > 0.005f) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "WELCOME",
                        style = CockpitTheme.typography.displayMedium.copy(
                            letterSpacing = 16.sp,
                            color = BrandPalette.Cyan.copy(alpha = (welcomeAlpha * 0.40f * glowIntensity).coerceIn(0f, 0.40f)),
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .scale(welcomeScale * 1.02f)
                            .alpha(welcomeAlpha)
                    )

                    Text(
                        text = "WELCOME",
                        style = CockpitTheme.typography.displayMedium.copy(
                            letterSpacing = 16.sp,
                            color = Color(0xFFF4FAFF),
                            shadow = Shadow(
                                color = BrandPalette.CyanNeon.copy(alpha = (welcomeAlpha * 0.85f * glowIntensity).coerceIn(0f, 0.85f)),
                                blurRadius = 14f
                            ),
                            textAlign = TextAlign.Center
                        ),
                        modifier = Modifier
                            .scale(welcomeScale)
                            .alpha(welcomeAlpha)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ENJOY YOUR DRIVE",
                    style = CockpitTheme.typography.labelSmall.copy(
                        letterSpacing = 9.sp,
                        color = Color(0xFF7FAEE8).copy(alpha = subtitleAlpha),
                        textAlign = TextAlign.Center
                    ),
                    modifier = Modifier.alpha(subtitleAlpha)
                )
            }
        }
    }
}
