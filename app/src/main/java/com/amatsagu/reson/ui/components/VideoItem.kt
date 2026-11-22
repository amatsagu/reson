package com.amatsagu.reson.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amatsagu.reson.R
import com.amatsagu.reson.model.Video

private val VideoThumbnailShape = RoundedCornerShape(12.dp)
private val DualActionCapsuleShape = RoundedCornerShape(22.dp)

/**
 * Row representing a YouTube video with 16:9 thumbnail, metadata, and play/download actions.
 * Optimized with static shapes, direct string formatting, and fixed dimensions for 120fps scrolling.
 */
@Composable
fun VideoItem(
    video: Video,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isBuffering: Boolean = false,
    downloadProgress: Float? = video.downloadProgress,
    isQueued: Boolean = video.isDownloadQueued,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (video.thumbnailUrl.isNotBlank()) {
            AsyncImage(
                model = video.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(VideoThumbnailShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(VideoThumbnailShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = video.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )

            val duration = when {
                (video.durationText.isNotBlank() && (video.durationText != "Unknown")) -> video.durationText
                video.durationSeconds > 0 -> com.amatsagu.reson.YtDlpManager.formatDuration(video.durationSeconds)
                video.playbackDurationMs > 0L -> com.amatsagu.reson.YtDlpManager.formatDuration((video.playbackDurationMs / 1000L).toInt())
                else -> ""
            }

            val channelAndDuration = if (duration.isNotBlank()) {
                "${video.channelTitle} · $duration"
            } else {
                video.channelTitle
            }

            Spacer(modifier = Modifier.height(3.dp))

            Text(
                text = channelAndDuration,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (video.releaseDateText.isNotBlank()) {
                Text(
                    text = video.releaseDateText,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        val effectiveProgress = downloadProgress ?: video.downloadProgress
        val effectiveQueued = isQueued || video.isDownloadQueued

        DualActionCapsule(
            isDownloaded = video.isDownloaded,
            downloadProgress = effectiveProgress,
            isQueued = effectiveQueued,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            onPlayClick = onPlayClick,
            onDownloadClick = onDownloadClick
        )
    }
}

@Composable
fun DualActionCapsule(
    isDownloaded: Boolean,
    downloadProgress: Float?,
    onPlayClick: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
    isQueued: Boolean = false,
    isPlaying: Boolean = false,
    isBuffering: Boolean = false,
) {
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(DualActionCapsuleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight()
                .clickable(onClick = onPlayClick),
            contentAlignment = Alignment.Center
        ) {
            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.action_pause else R.string.action_play),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )

        Box(
            modifier = Modifier
                .width(44.dp)
                .fillMaxHeight()
                .clickable(onClick = onDownloadClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                downloadProgress != null -> {
                    CircularProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                isQueued -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        trackColor = MaterialTheme.colorScheme.outlineVariant
                    )
                }
                isDownloaded -> {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete_download),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = stringResource(R.string.action_download),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
