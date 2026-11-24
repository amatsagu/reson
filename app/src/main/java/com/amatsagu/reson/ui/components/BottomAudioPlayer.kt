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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.time.Duration.Companion.milliseconds
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

val AVAILABLE_PLAYBACK_SPEEDS: List<Float> = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

fun formatPlaybackSpeed(speed: Float): String {
    return if (speed == speed.toLong().toFloat()) {
        "${speed.toInt()}x"
    } else {
        "${speed}x"
    }
}

@Composable
fun PlaybackSpeedDialog(
    currentSpeed: Float,
    onSpeedSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.playback_speed_title),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AVAILABLE_PLAYBACK_SPEEDS.forEach { speed ->
                    val isSelected = currentSpeed == speed
                    val label = if (speed == 1.0f) "1x (Normal)" else formatPlaybackSpeed(speed)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                else Color.Transparent
                            )
                            .clickable { onSpeedSelect(speed) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = label,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        }
    )
}

@Composable
fun InteractiveResonProgressBar(
    progress: () -> Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    progressColor: Color = MaterialTheme.colorScheme.primary,
) {
    var isDragging by remember { mutableStateOf(value = false) }
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
                // Ratio matches ContinueCard progress bar (4dp / 124dp)
                val trackHeight = size.width * (4f / 124f)
                val cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                val yOffset = (size.height - trackHeight) / 2f

                onDrawBehind {
                    drawRoundRect(
                        color = trackColor,
                        topLeft = Offset(0f, yOffset),
                        size = Size(size.width, trackHeight),
                        cornerRadius = cornerRadius
                    )

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
fun PlaybackTimestamps(
    positionMs: () -> Long,
    durationMs: Long,
    modifier: Modifier = Modifier,
    playbackSpeed: Float = 1.0f,
    onSpeedClick: () -> Unit = {},
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
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val isCustomSpeed = playbackSpeed != 1.0f
        val speedTextColor = if (isCustomSpeed) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        val speedBgModifier = if (isCustomSpeed) {
            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
        } else {
            Modifier
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .then(speedBgModifier)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onSpeedClick
                )
                .padding(horizontal = 8.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = formatPlaybackSpeed(playbackSpeed),
                fontSize = 11.sp,
                fontWeight = if (isCustomSpeed) FontWeight.SemiBold else FontWeight.Medium,
                color = speedTextColor
            )
        }
        Text(
            text = remainingText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

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
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
) {
    val effectiveDurationMs = remember(durationMs, video.durationSeconds) {
        if (durationMs > 0) durationMs else (video.durationSeconds * 1000L).coerceAtLeast(1000L)
    }

    var currentPos by remember(video.id) { mutableLongStateOf(positionMs()) }
    var showSpeedDialog by remember { mutableStateOf(value = false) }

    LaunchedEffect(isPlaying, video.id) {
        if (isPlaying) {
            while (isActive) {
                currentPos = positionMs()
                delay(250.milliseconds)
            }
        } else {
            currentPos = positionMs()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

        InteractiveResonProgressBar(
            progress = {
                if (effectiveDurationMs > 0) (currentPos.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f) else 0f
            },
            onSeek = { ratio ->
                val targetMs = (ratio * effectiveDurationMs).toLong()
                currentPos = targetMs
                onSeek(targetMs)
            },
            modifier = Modifier.padding(top = 2.dp)
        )

        PlaybackTimestamps(
            positionMs = { currentPos },
            durationMs = effectiveDurationMs,
            playbackSpeed = playbackSpeed,
            onSpeedClick = { showSpeedDialog = true }
        )

        if (showSpeedDialog) {
            PlaybackSpeedDialog(
                currentSpeed = playbackSpeed,
                onSpeedSelect = { speed ->
                    onSpeedChange(speed)
                    showSpeedDialog = false
                },
                onDismiss = { showSpeedDialog = false }
            )
        }
    }
}

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
    playbackSpeed: Float = 1.0f,
    onSpeedChange: (Float) -> Unit = {},
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
            val showBanner = !isBannerDismissed && (isOffline || (isRateLimitBlocked && (rateLimitMessage != null)))
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
                            statusHint = statusHint,
                            playbackSpeed = playbackSpeed,
                            onSpeedChange = onSpeedChange
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

@Composable
fun NetworkStatusBanner(
    isRateLimitBlocked: Boolean,
    rateLimitMessage: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isRateLimitBlocked) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val contentColor = if (isRateLimitBlocked) {
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
                imageVector = if (isRateLimitBlocked) Icons.Outlined.Warning else Icons.Outlined.WifiOff,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Text(
                text = if (isRateLimitBlocked) {
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
