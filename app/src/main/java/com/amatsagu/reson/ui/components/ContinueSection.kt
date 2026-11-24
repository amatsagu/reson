package com.amatsagu.reson.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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

@Composable
fun ContinueSection(
    videos: List<Video>,
    onPlayClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    currentPlayingVideoId: String? = null,
    isPlaybackActive: Boolean = false,
    isAudioBuffering: Boolean = false,
    onLoadMore: () -> Unit = {},
    isContinueLoadingMore: Boolean = false,
) {
    if (videos.isEmpty()) return

    val rowState = rememberLazyListState()

    val shouldLoadMore = remember {
        derivedStateOf {
            val total = rowState.layoutInfo.totalItemsCount
            val lastVisible = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            (total > 0) && (lastVisible >= total - 2)
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            onLoadMore()
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        AdaptiveSectionHeader(
            fullTitle = stringResource(R.string.section_continue_listening),
            shortTitle = stringResource(R.string.section_continue_short),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyRow(
            state = rowState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(
                items = videos,
                key = { it.id },
                contentType = { "continue_video" }
            ) { video ->
                val isPlaying = video.id == currentPlayingVideoId && isPlaybackActive
                val isBuffering = video.id == currentPlayingVideoId && isAudioBuffering
                ContinueCard(
                    video = video,
                    isPlaying = isPlaying,
                    isBuffering = isBuffering,
                    onPlayClick = { onPlayClick(video) }
                )
            }

            if (isContinueLoadingMore) {
                item(key = "continue_loading_spinner", contentType = "loading_spinner") {
                    Box(
                        modifier = Modifier
                            .width(60.dp)
                            .height(96.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ContinueCard(
    video: Video,
    onPlayClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    isBuffering: Boolean = false,
) {
    Card(
        modifier = modifier
            .width(260.dp)
            .height(96.dp)
            .clickable(onClick = onPlayClick),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (video.thumbnailUrl.isNotBlank()) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(16.dp))
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

            Spacer(modifier = Modifier.width(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = video.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 18.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))

                    val formattedDuration = remember(video.durationSeconds, video.durationText, video.playbackDurationMs) {
                        when {
                            video.durationSeconds > 0 -> com.amatsagu.reson.YtDlpManager.formatDuration(video.durationSeconds)
                            video.playbackDurationMs > 0L -> com.amatsagu.reson.YtDlpManager.formatDuration((video.playbackDurationMs / 1000L).toInt())
                            video.durationText.isNotBlank() && video.durationText != "Unknown" -> video.durationText
                            else -> ""
                        }
                    }

                    val subtitle = remember(video.channelTitle, formattedDuration, video.releaseDateText) {
                        buildString {
                            append(video.channelTitle)
                            if (formattedDuration.isNotBlank()) {
                                append(" · ")
                                append(formattedDuration)
                            }
                            if (video.releaseDateText.isNotBlank()) {
                                append(" · ")
                                append(video.releaseDateText)
                            }
                        }
                    }

                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                ResonProgressBar(
                    progress = video.playbackProgress,
                    height = 4.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
        }
    }
}
