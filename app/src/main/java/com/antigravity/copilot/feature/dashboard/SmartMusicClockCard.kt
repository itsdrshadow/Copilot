package com.antigravity.copilot.feature.dashboard

import com.antigravity.copilot.core.designsystem.components.cockpitSurface
import com.antigravity.copilot.core.designsystem.tokens.CopilotShapes
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.graphics.Bitmap
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.AutomotiveDesignTokens
import com.antigravity.copilot.core.designsystem.AutomotiveTypographyTokens
import com.antigravity.copilot.core.designsystem.CockpitTheme
import com.antigravity.copilot.core.media.MediaPlaybackManager
import com.antigravity.copilot.core.media.MediaPlaybackState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

/**
 * Smart Music Player + Permanent Clock Card:
 * Replaces the old standalone Odometer card.
 *
 * State A: No Music Playing -> Large, high-glanceability digital cockpit clock.
 * State B: Music Playing   -> Simultaneous persistent clock + automotive media player.
 */
@Composable
fun SmartMusicClockCard(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val playbackState by MediaPlaybackManager.playbackState.collectAsStateWithLifecycle()

    // 1-second interval ticker for independent clock and smooth live playback progress
    var tickerTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L)
            tickerTimeMs = System.currentTimeMillis()
        }
    }

    // Settings-provider IPC: re-read once a minute, not on every 1 s tick.
    val is24Hour = remember(context, tickerTimeMs / 60_000L) {
        DateFormat.is24HourFormat(context)
    }

    val calendar = remember(tickerTimeMs) {
        Calendar.getInstance()
    }

    val timeString = remember(calendar, is24Hour) {
        val pattern = if (is24Hour) "HH:mm" else "hh:mm"
        SimpleDateFormat(pattern, Locale.getDefault()).format(calendar.time)
    }

    val amPmString = remember(calendar, is24Hour) {
        if (is24Hour) "" else SimpleDateFormat("a", Locale.getDefault()).format(calendar.time).uppercase()
    }

    val dateString = remember(calendar) {
        SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(calendar.time)
    }

    val currentElapsedRealtime = remember(tickerTimeMs) {
        try { SystemClock.elapsedRealtime() } catch (_: Throwable) { tickerTimeMs }
    }

    val liveProgressMs = remember(playbackState, currentElapsedRealtime) {
        playbackState.calculateLivePositionMs(currentElapsedRealtime)
    }

    val isMusicActive = playbackState.isMusicActive

    val cardBg = CockpitTheme.colors.cardTopLightGradient
    val cardBorder = CockpitTheme.colors.border
    val cornerRadius = AutomotiveDesignTokens.Radii.card
    val cardPadding = 14.dp

    Box(
        modifier = modifier
            .cockpitSurface(cardBg, cardBorder, RoundedCornerShape(cornerRadius), borderWidth = 1.25.dp)
            .padding(cardPadding)
    ) {
        AnimatedContent(
            targetState = isMusicActive,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(200))
            },
            label = "MusicClockStateTransition"
        ) { active ->
            if (!active) {
                // ==================== STATE A: NO MUSIC PLAYING (CLOCK ONLY) ====================
                ClockOnlyState(
                    timeString = timeString,
                    amPmString = amPmString,
                    dateString = dateString,
                    onOpenMusicApp = {
                        if (!MediaPlaybackManager.isNotificationAccessGranted(context)) {
                            android.widget.Toast.makeText(
                                context,
                                "Please allow Notification Access for Copilot to sync Bluetooth Music details",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            MediaPlaybackManager.openNotificationAccessSettings(context)
                        } else {
                            MediaPlaybackManager.openActivePlayerApp(context)
                        }
                    }
                )
            } else {
                // ==================== STATE B: MUSIC PLAYING (CLOCK + MUSIC PLAYER) ====================
                ClockWithMusicState(
                    timeString = timeString,
                    amPmString = amPmString,
                    dateString = dateString,
                    playbackState = playbackState,
                    liveProgressMs = liveProgressMs,
                    onTogglePlayPause = { MediaPlaybackManager.togglePlayPause() },
                    onSkipNext = { MediaPlaybackManager.skipToNext() },
                    onSkipPrevious = { MediaPlaybackManager.skipToPrevious() },
                    onOpenMusicApp = { MediaPlaybackManager.openActivePlayerApp(context) }
                )
            }
        }
    }
}

/**
 * State A: Large Glanceable Digital Clock.
 * Dedicated, clutter-free cockpit clock view when no media is actively playing.
 */
@Composable
private fun ClockOnlyState(
    timeString: String,
    amPmString: String,
    dateString: String,
    onOpenMusicApp: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Header: System Time Label & Quick Launch Music Pill
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CopilotShapes.R6)
                        .background(CockpitTheme.colors.accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = CockpitTheme.colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "CLOCK",
                    style = CockpitTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.1.sp
                    ),
                    color = CockpitTheme.colors.textSecondary,
                    maxLines = 1
                )
            }

            // Permanent Automotive Media Pill
            AutomotiveMusicPill(
                isPlaying = false,
                label = "Media",
                onClick = onOpenMusicApp
            )
        }

        // Center Hero Clock: Big Tabular Time
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.weight(1f)
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = timeString,
                    style = AutomotiveTypographyTokens.NumericClock,
                    color = CockpitTheme.colors.textPrimary
                )

                if (amPmString.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = amPmString,
                        style = CockpitTheme.typography.titleLarge,
                        color = CockpitTheme.colors.accent,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Date & Day Subtitle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    tint = CockpitTheme.colors.textSecondary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = dateString,
                    style = CockpitTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium
                    ),
                    color = CockpitTheme.colors.textSecondary
                )
            }
        }

        // Subtle Bottom Ambient Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Vehicle Time • Synchronized",
                style = CockpitTheme.typography.labelSmall,
                color = CockpitTheme.colors.textSecondary.copy(alpha = 0.6f)
            )
        }
    }
}

/**
 * State B: Simultaneous Permanent Clock + Music Player.
 * Clock remains permanently visible in the top banner while track metadata, artwork,
 * progress and transport controls are cleanly organized below.
 */
@Composable
private fun ClockWithMusicState(
    timeString: String,
    amPmString: String,
    dateString: String,
    playbackState: MediaPlaybackState,
    liveProgressMs: Long,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onOpenMusicApp: () -> Unit
) {
    val durationMs = playbackState.durationMs
    val progressFraction = if (durationMs > 0L) {
        (liveProgressMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    val formattedCurrentTime = remember(liveProgressMs) { formatDuration(liveProgressMs) }
    val formattedTotalTime = remember(durationMs, playbackState.isPlaying) {
        if (durationMs > 0L) formatDuration(durationMs) else if (playbackState.isPlaying) "Live BT" else "--:--"
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ==================== 1. PERMANENT CLOCK BANNER ====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Permanent Clock
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CopilotShapes.R6)
                        .background(CockpitTheme.colors.accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = CockpitTheme.colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Column {
                    Text(
                        text = if (amPmString.isNotBlank()) "$timeString $amPmString" else timeString,
                        style = CockpitTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            fontFeatureSettings = "tnum"
                        ),
                        color = CockpitTheme.colors.textPrimary
                    )
                    Text(
                        text = dateString,
                        style = CockpitTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.Medium
                        ),
                        color = CockpitTheme.colors.textSecondary
                    )
                }
            }

            // Permanent Music Pill
            AutomotiveMusicPill(
                isPlaying = playbackState.isPlaying,
                label = if (playbackState.isPlaying) "Playing" else "Paused",
                onClick = onOpenMusicApp
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==================== 2. TRACK INFO & ARTWORK ROW ====================
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onOpenMusicApp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Album Artwork or Fallback Icon
            val artSize = 56.dp
            Box(
                modifier = Modifier
                    .size(artSize)
                    .cockpitSurface(CockpitTheme.colors.cardElevated, CockpitTheme.colors.border, CopilotShapes.R10),
                contentAlignment = Alignment.Center
            ) {
                if (playbackState.albumArt != null) {
                    Image(
                        bitmap = playbackState.albumArt.asImageBitmap(),
                        contentDescription = "Album Art",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = CockpitTheme.colors.accent,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            // Metadata Column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = playbackState.displayTitle,
                    style = CockpitTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = CockpitTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = playbackState.displayArtist,
                    style = CockpitTheme.typography.bodyMedium,
                    color = CockpitTheme.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==================== 3. PROGRESS BAR & TIMESTAMPS ====================
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            if (durationMs > 0L) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CockpitTheme.colors.accent,
                    trackColor = CockpitTheme.colors.cardElevated
                )
            } else if (playbackState.isPlaying) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = CockpitTheme.colors.accent,
                    trackColor = CockpitTheme.colors.cardElevated
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(CockpitTheme.colors.cardElevated)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formattedCurrentTime,
                    style = CockpitTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = CockpitTheme.colors.textSecondary
                )
                Text(
                    text = formattedTotalTime,
                    style = CockpitTheme.typography.labelSmall.copy(
                        fontFeatureSettings = "tnum"
                    ),
                    color = CockpitTheme.colors.textSecondary
                )
            }
        }

        // ==================== 4. TRANSPORT CONTROLS ====================
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous Button
            IconButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = CockpitTheme.colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            // Play / Pause Hero Button
            val playButtonSize = 46.dp
            Box(
                modifier = Modifier
                    .size(playButtonSize)
                    .clip(CircleShape)
                    .background(CockpitTheme.colors.accent)
                    .clickable(onClick = onTogglePlayPause),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    tint = CockpitTheme.colors.onAccent,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            // Next Button
            IconButton(
                onClick = onSkipNext,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = CockpitTheme.colors.textPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/**
 * Helper to format milliseconds to mm:ss or hh:mm:ss string.
 */
fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val seconds = totalSeconds % 60L
    val minutes = (totalSeconds / 60L) % 60L
    val hours = totalSeconds / 3600L

    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

/**
 * Automotive Equalizer Media Pill:
 * Permanently displayed in the top header.
 * Uses high-tech GraphicEq audio frequency spectrum bars icon.
 */
@Composable
private fun AutomotiveMusicPill(
    isPlaying: Boolean,
    label: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .defaultMinSize(minHeight = 44.dp)
            .cockpitSurface(CockpitTheme.colors.cardElevated, if (isPlaying) CockpitTheme.colors.accent.copy(alpha = 0.6f) else CockpitTheme.colors.border, CopilotShapes.R12, borderWidth = 1.2.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = rememberRipple(bounded = true, color = CockpitTheme.colors.accent),
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.GraphicEq,
            contentDescription = "Media Player",
            tint = if (isPlaying) CockpitTheme.colors.accent else CockpitTheme.colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            maxLines = 1,
            softWrap = false,
            style = CockpitTheme.typography.labelSmall.copy(
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium
            ),
            color = if (isPlaying) CockpitTheme.colors.accent else CockpitTheme.colors.textSecondary
        )
    }
}
