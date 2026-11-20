package com.amatsagu.reson.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amatsagu.reson.R
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.model.Video
import java.util.Locale

/**
 * Formats milliseconds into standard MM:SS or H:MM:SS audio format.
 */
fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

/**
 * Interactive audio progress bar matching Reson's design language:
 * Flat rounded track with NO circle thumb dot, supporting both tap-to-seek and drag-to-seek.
 *
 * Mathematically scaled thickness based on Continue Section progress bar:
 * ContinueCard progress bar is 4dp high across 124dp width (ratio = 4/124).
 * Main audio player thickness scales proportionally with width (size.width * (4f / 124f)),
 * rendered directly in the Draw phase (drawWithCache) for 120 FPS zero-recomposition performance.
 */
@Composable
fun InteractiveResonProgressBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragRatio by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val newRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    onSeek(newRatio)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragRatio = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragRatio = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        isDragging = false
                        onSeek(dragRatio)
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
            .drawWithCache {
                // Mathematically scaled thickness:
                // Continue Section progress bar has height 4dp across 124dp width (ratio = 4/124).
                // Main audio player scales height proportionally to its rendered width:
                val trackHeight = size.width * (4f / 124f)
                val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                val yOffset = (size.height - trackHeight) / 2f

                onDrawBehind {
                    // Background track
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, yOffset),
                        size = Size(size.width, trackHeight),
                        cornerRadius = cornerRadius
                    )

                    // Active progress - read directly in Draw phase to eliminate recompositions
                    val displayRatio = if (isDragging) dragRatio else progress()
                    val clampedRatio = displayRatio.coerceIn(0f, 1f)
                    if (clampedRatio > 0f) {
                        drawRoundRect(
                            color = progressColor,
                            topLeft = Offset(0f, yOffset),
                            size = Size(size.width * clampedRatio, trackHeight),
                            cornerRadius = cornerRadius
                        )
                    }
                }
            }
    )
}

@Composable
fun InteractiveResonProgressBar(
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    InteractiveResonProgressBar(
        progress = { progress },
        onSeek = onSeek,
        modifier = modifier,
        trackColor = trackColor,
        progressColor = progressColor
    )
}

/**
 * Isolated timestamp display to localize text recompositions strictly to once-per-second,
 * keeping the outer player content and lazy lists completely static.
 */
@Composable
fun PlaybackTimestamps(
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
) {
    val pos = positionMs()
    val remainingMs = (durationMs - pos).coerceAtLeast(0L)
    val remainingText = "-${formatPlaybackTime(remainingMs)}"
    val currentPositionText = formatPlaybackTime(pos)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = currentPositionText,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = remainingText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Inner content of the Audio Player showing miniature, title, channel, play/pause with spinner,
 * close button, flat interactive seek bar (no thumb dot), and current/remaining timestamps.
 */
@Composable
fun BottomAudioPlayerContent(
    video: Video,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusHint: String? = null,
) {
    val effectiveDurationMs = remember(durationMs, video.durationSeconds) {
        if (durationMs > 0) durationMs else (video.durationSeconds * 1000L).coerceAtLeast(1000L)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        // Main info row: Thumbnail + Title & Author + Play/Pause & Close buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Video miniature thumbnail
            if (video.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Title and Channel author or diagnostic status hint
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = video.title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))
                if (!statusHint.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusHint,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                } else if (video.channelTitle.isNotBlank()) {
                    Text(
                        text = video.channelTitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Play / Pause Button with Buffering spinner
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = false, radius = 20.dp),
                        onClick = onPlayPauseClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Close Button
            IconButton(
                onClick = onCloseClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // Interactive seek bar (no circle thumb dot)
        InteractiveResonProgressBar(
            progress = {
                if (effectiveDurationMs > 0) (positionMs().toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
            },
            onSeek = { ratio ->
                val targetMs = (ratio * effectiveDurationMs).toLong()
                onSeek(targetMs)
            },
            modifier = Modifier.padding(top = 2.dp)
        )

        // Time indicator bar (Current elapsed on left, remaining on right)
        PlaybackTimestamps(
            positionMs = positionMs,
            durationMs = effectiveDurationMs
        )
    }
}

@Composable
fun BottomAudioPlayerContent(
    video: Video,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
    statusHint: String? = null,
) {
    BottomAudioPlayerContent(
        video = video,
        isPlaying = isPlaying,
        isBuffering = isBuffering,
        positionMs = { positionMs },
        durationMs = durationMs,
        onPlayPauseClick = onPlayPauseClick,
        onSeek = onSeek,
        onCloseClick = onCloseClick,
        modifier = modifier,
        statusHint = statusHint
    )
}

/**
 * Modern unified bottom container merging the active audio player and the 3-tab navigation bar
 * seamlessly into a single cohesive Material 3 surface.
 */
@Composable
fun ResonUnifiedBottomBar(
    currentVideo: Video?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: () -> Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onCloseClick: () -> Unit,
    selectedTab: ResonTab,
    onTabSelected: (ResonTab) -> Unit,
    modifier: Modifier = Modifier,
    statusHint: String? = null,
    isOffline: Boolean = false,
    isRateLimitBlocked: Boolean = false,
    rateLimitMessage: String? = null,
    isBannerDismissed: Boolean = false,
    onDismissBanner: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            val showBanner = !isBannerDismissed && (isOffline || (isRateLimitBlocked && rateLimitMessage != null))
            AnimatedVisibility(
                visible = showBanner,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    NetworkStatusBanner(
                        isRateLimitBlocked = isRateLimitBlocked,
                        rateLimitMessage = rateLimitMessage,
                        onDismiss = onDismissBanner
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        thickness = 0.5.dp
                    )
                }
            }

            AnimatedVisibility(
                visible = currentVideo != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                if (currentVideo != null) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        BottomAudioPlayerContent(
                            video = currentVideo,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            positionMs = positionMs,
                            durationMs = durationMs,
                            onPlayPauseClick = onPlayPauseClick,
                            onSeek = onSeek,
                            onCloseClick = onCloseClick,
                            statusHint = statusHint
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            thickness = 0.5.dp
                        )
                    }
                }
            }

            ResonBottomNavBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected,
                containerColor = Color.Transparent
            )
        }
    }
}

/**
 * Animated banner alerting the user when the device is completely offline
 * or when YouTube is persistently blocking requests (HTTP 429 / Captcha),
 * docked seamlessly on top of the bottom audio player / navigation bar.
 */
@Composable
fun NetworkStatusBanner(
    isRateLimitBlocked: Boolean,
    rateLimitMessage: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = isRateLimitBlocked
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        color = containerColor,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isError) Icons.Outlined.Warning else Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = if (isError) {
                    rateLimitMessage ?: stringResource(R.string.network_youtube_blocked_banner)
                } else {
                    stringResource(R.string.network_offline_banner)
                },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(26.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Legacy standalone audio player composable maintained for backward compatibility.
 */
@Composable
fun BottomAudioPlayer(
    video: Video,
    isPlaying: Boolean,
    isBuffering: Boolean,
    positionMs: Long,
    durationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp
    ) {
        BottomAudioPlayerContent(
            video = video,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = positionMs,
            durationMs = durationMs,
            onPlayPauseClick = onPlayPauseClick,
            onSeek = onSeek,
            onCloseClick = onCloseClick
        )
    }
}
