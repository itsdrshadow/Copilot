package com.antigravity.copilot.feature.dashboard

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.antigravity.copilot.core.designsystem.*
import com.antigravity.copilot.core.media.MediaPlaybackManager

@Composable
fun MediaPlaybackPill(
    modifier: Modifier = Modifier,
    isVeryCompact: Boolean = false
) {
    val context = LocalContext.current
    val playbackState by MediaPlaybackManager.playbackState.collectAsState()

    val bgBrush = Brush.horizontalGradient(
        colors = listOf(
            CockpitTheme.colors.card,
            CockpitTheme.colors.cardElevated
        )
    )
    val cyanAccent = CockpitTheme.colors.accent
    val textPrimary = CockpitTheme.colors.textPrimary
    val textSecondary = CockpitTheme.colors.textSecondary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(if (isVeryCompact) 14.dp else 18.dp))
            .background(bgBrush)
            .border(
                width = 1.dp,
                color = if (playbackState.isPlaying) cyanAccent.copy(alpha = 0.45f) else CockpitTheme.colors.border,
                shape = RoundedCornerShape(if (isVeryCompact) 14.dp else 18.dp)
            )
            .padding(horizontal = if (isVeryCompact) 8.dp else 12.dp, vertical = if (isVeryCompact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: Album Art or Music Icon (Click opens app)
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { MediaPlaybackManager.openActivePlayerApp(context) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isVeryCompact) 8.dp else 10.dp)
        ) {
            val albumArt = playbackState.albumArt
            Box(
                modifier = Modifier
                    .size(if (isVeryCompact) 32.dp else 38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CockpitTheme.colors.cardElevated),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(
                        bitmap = albumArt.asImageBitmap(),
                        contentDescription = "Album Art",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Music",
                        tint = if (playbackState.isPlaying) cyanAccent else textSecondary,
                        modifier = Modifier.size(if (isVeryCompact) 18.dp else 22.dp)
                    )
                }
            }

            // Track Title & Artist
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = playbackState.displayTitle,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = if (isVeryCompact) 11.sp else 13.5.sp,
                        color = textPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = playbackState.displayArtist,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = if (isVeryCompact) 9.sp else 11.sp,
                        color = textSecondary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Right: Transport Controls
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (isVeryCompact) 0.dp else 4.dp)
        ) {
            IconButton(
                onClick = { MediaPlaybackManager.skipToPrevious() },
                modifier = Modifier.size(if (isVeryCompact) 30.dp else 36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = textPrimary,
                    modifier = Modifier.size(if (isVeryCompact) 18.dp else 22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(if (isVeryCompact) 32.dp else 38.dp)
                    .clip(CircleShape)
                    .background(if (playbackState.isPlaying) CockpitTheme.colors.accentSoft else CockpitTheme.colors.cardElevated)
                    .border(
                        1.dp,
                        if (playbackState.isPlaying) cyanAccent else CockpitTheme.colors.border,
                        CircleShape
                    )
                .clickable { MediaPlaybackManager.togglePlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (playbackState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    tint = if (playbackState.isPlaying) cyanAccent else textPrimary,
                    modifier = Modifier.size(if (isVeryCompact) 18.dp else 22.dp)
                )
            }

            IconButton(
                onClick = { MediaPlaybackManager.skipToNext() },
                modifier = Modifier.size(if (isVeryCompact) 30.dp else 36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = textPrimary,
                    modifier = Modifier.size(if (isVeryCompact) 18.dp else 22.dp)
                )
            }
        }
    }
}
