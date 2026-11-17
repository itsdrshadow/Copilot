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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.CockpitTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Brand splash: logo → light trails + car silhouette → "Welcome" → fade into the dashboard.
 *
 * Performance: the animation clock is read only inside graphicsLayer / draw lambdas, so a frame
 * re-draws cached layers instead of recomposing the tree. Text styles are constant (an animated
 * text shadow would re-layout text every frame). Stages are added / removed via derivedStateOf,
 * i.e. a handful of recompositions over the whole splash.
 */
@Composable
fun WelcomeSplashScreen(
    viewModel: SplashViewModel,
    onSplashFinished: () -> Unit,
    modifier: Modifier = Modifier,
    /** False while the app's main UI is still being composed behind the splash. */
    contentReady: Boolean = true
) {
    val config = viewModel.config
    val masterAnim = remember { Animatable(0f) }
    val interactionSource = remember { MutableInteractionSource() }
    val ready by rememberUpdatedState(contentReady)

    LaunchedEffect(Unit) {
        // Hold the first (static) logo frame until the heavy first composition of the dashboard
        // and map has run, then let a couple of frames settle, so the animation starts smooth.
        snapshotFlow { ready }.first { it }
        repeat(3) { withFrameNanos { } }
        launch {
            delay(config.exitTransitionStartMs)
            viewModel.onExitStarted()
        }
        masterAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = config.totalDurationMs.toInt(), easing = LinearEasing)
        )
        viewModel.onAnimationFinished()
        onSplashFinished()
    }

    // Elapsed ms; only ever called from layer / draw lambdas or derivedStateOf.
    val ms: () -> Float = { masterAnim.value * config.totalDurationMs }
    fun eased(start: Long, duration: Long): Float =
        FastOutSlowInEasing.transform(((ms() - start) / duration).coerceIn(0f, 1f))

    val logoAlpha: () -> Float = {
        val t = ms()
        if (t < config.logoVisibleDurationMs) {
            0.5f + 0.5f * FastOutSlowInEasing.transform((t / 350f).coerceIn(0f, 1f))
        } else {
            1f - eased(config.logoVisibleDurationMs, config.logoFadeOutDurationMs)
        }
    }
    val logoScale: () -> Float = { 0.95f + 0.05f * (ms() / config.logoVisibleDurationMs).coerceIn(0f, 1f) }
    val trails: () -> Float = { eased(config.trailsStartMs, config.trailsDurationMs) }
    val silhouette: () -> Float = { eased(config.silhouetteStartMs, config.silhouetteDurationMs) }
    val welcome: () -> Float = { eased(config.welcomeStartMs, config.welcomeDurationMs) }
    val subtitle: () -> Float = { eased(config.subtitleStartMs, config.subtitleDurationMs) }
    val glow: () -> Float = {
        val t = ms()
        if (t >= config.glowPulseStartMs) {
            val phase = ((t - config.glowPulseStartMs) / config.glowPulsePeriodMs) * 2.0 * PI
            (0.78f + 0.22f * sin(phase).toFloat()).coerceIn(0.6f, 1f)
        } else {
            1f
        }
    }
    val exit: () -> Float = { eased(config.exitTransitionStartMs, config.exitTransitionDurationMs) }

    val showLogo by remember { derivedStateOf { ms() < config.logoVisibleDurationMs + config.logoFadeOutDurationMs } }
    val showCar by remember { derivedStateOf { ms() >= minOf(config.trailsStartMs, config.silhouetteStartMs) } }
    val showWelcome by remember { derivedStateOf { ms() >= config.welcomeStartMs } }

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
    val welcomeBase = CockpitTheme.typography.displayMedium
    val welcomeGlowStyle = remember(welcomeBase) {
        welcomeBase.copy(letterSpacing = 16.sp, color = BrandPalette.Cyan.copy(alpha = 0.40f), textAlign = TextAlign.Center)
    }
    val welcomeStyle = remember(welcomeBase) {
        welcomeBase.copy(
            letterSpacing = 16.sp,
            color = Color(0xFFF4FAFF),
            shadow = Shadow(color = BrandPalette.CyanNeon.copy(alpha = 0.85f), blurRadius = 14f),
            textAlign = TextAlign.Center
        )
    }
    val subtitleBase = CockpitTheme.typography.labelSmall
    val subtitleStyle = remember(subtitleBase) {
        subtitleBase.copy(letterSpacing = 9.sp, color = Color(0xFF7FAEE8), textAlign = TextAlign.Center)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                val e = exit()
                alpha = 1f - e
                scaleX = 1f + 0.04f * e
                scaleY = 1f + 0.04f * e
            }
            .background(splashBackgroundBrush)
            .clickable(interactionSource = interactionSource, indication = null) {
                if (viewModel.skipSplash()) onSplashFinished()
            }
    ) {
        // Stage 1: brand logo (static drawing in its own layer; only alpha / scale animate)
        if (showLogo) {
            AutomotiveBrandLogo(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = logoAlpha()
                        val s = logoScale()
                        scaleX = s
                        scaleY = s
                    }
            )
        }

        // Stage 2: light trails + car silhouette (draw-phase only)
        if (showCar) {
            CarSilhouetteCanvas(
                trails = trails,
                silhouette = silhouette,
                reflection = { silhouette() * welcome() },
                glow = glow,
                modifier = Modifier.fillMaxSize()
            )
        }

        // Stage 3: welcome typography
        if (showWelcome) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 60.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.graphicsLayer {
                        val w = welcome()
                        alpha = w
                        scaleX = 0.92f + 0.08f * w
                        scaleY = 0.92f + 0.08f * w
                    }
                ) {
                    Text(
                        text = "WELCOME",
                        style = welcomeGlowStyle,
                        modifier = Modifier.graphicsLayer {
                            alpha = glow()
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    )
                    Text(text = "WELCOME", style = welcomeStyle)
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ENJOY YOUR DRIVE",
                    style = subtitleStyle,
                    modifier = Modifier.graphicsLayer { alpha = subtitle() }
                )
            }
        }
    }
}
