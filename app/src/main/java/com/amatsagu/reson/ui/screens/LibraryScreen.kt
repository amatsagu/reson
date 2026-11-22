package com.amatsagu.reson.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FileDownloadOff
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amatsagu.reson.R
import com.amatsagu.reson.model.Video
import com.amatsagu.reson.ui.components.VideoItem

/**
 * Sub-view modes for the Library screen.
 */
enum class LibrarySubView {
    Overview,
    FullHistory,
    FullDownloads
}

/**
 * Library screen displaying two primary content streams:
 * 1. Listening History (recent 5 on overview with button to view full list)
 * 2. Locally Downloaded / Offline Videos (recent 5 on overview with button to view full list)
 */
@Composable
fun LibraryScreen(
    historyVideos: List<Video>,
    downloadedVideos: List<Video>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    isAudioBuffering: Boolean,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    onClearHistory: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    queuedDownloadIds: Set<String> = emptySet(),
    downloadingProgressMap: Map<String, Float> = emptyMap(),
) {
    var subView by remember { mutableStateOf(LibrarySubView.Overview) }
    var showClearHistoryDialog by remember { mutableStateOf(value = false) }

    // Intercept back navigation when in sub-views
    BackHandler(enabled = subView != LibrarySubView.Overview) {
        subView = LibrarySubView.Overview
    }

    when (subView) {
        LibrarySubView.Overview -> {
            LibraryOverviewView(
                historyVideos = historyVideos,
                downloadedVideos = downloadedVideos,
                currentPlayingVideoId = currentPlayingVideoId,
                isPlaying = isPlaying,
                isAudioBuffering = isAudioBuffering,
                onPlayVideo = onPlayVideo,
                onDownloadVideo = onDownloadVideo,
                onShowFullHistory = { subView = LibrarySubView.FullHistory },
                onShowFullDownloads = { subView = LibrarySubView.FullDownloads },
                queuedDownloadIds = queuedDownloadIds,
                downloadingProgressMap = downloadingProgressMap,
                contentPadding = contentPadding,
                modifier = modifier
            )
        }
        LibrarySubView.FullHistory -> {
            FullListView(
                title = stringResource(R.string.library_history_title),
                videos = historyVideos,
                currentPlayingVideoId = currentPlayingVideoId,
                isPlaying = isPlaying,
                isAudioBuffering = isAudioBuffering,
                onPlayVideo = onPlayVideo,
                onDownloadVideo = onDownloadVideo,
                onBackClick = { subView = LibrarySubView.Overview },
                emptyIcon = Icons.Outlined.History,
                emptyTitle = stringResource(R.string.library_history_empty_title),
                emptyDesc = stringResource(R.string.library_history_empty_desc),
                headerAction = {
                    if (historyVideos.isNotEmpty()) {
                        IconButton(onClick = { showClearHistoryDialog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.library_clear_history),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                queuedDownloadIds = queuedDownloadIds,
                downloadingProgressMap = downloadingProgressMap,
                contentPadding = contentPadding,
                modifier = modifier
            )
        }
        LibrarySubView.FullDownloads -> {
            FullListView(
                title = stringResource(R.string.library_downloads_title),
                videos = downloadedVideos,
                currentPlayingVideoId = currentPlayingVideoId,
                isPlaying = isPlaying,
                isAudioBuffering = isAudioBuffering,
                onPlayVideo = onPlayVideo,
                onDownloadVideo = onDownloadVideo,
                onBackClick = { subView = LibrarySubView.Overview },
                emptyIcon = Icons.Outlined.FileDownloadOff,
                emptyTitle = stringResource(R.string.library_downloads_empty_title),
                emptyDesc = stringResource(R.string.library_downloads_empty_desc),
                headerAction = null,
                queuedDownloadIds = queuedDownloadIds,
                downloadingProgressMap = downloadingProgressMap,
                contentPadding = contentPadding,
                modifier = modifier
            )
        }
    }

    // Confirmation dialog for clearing playback history
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.DeleteSweep,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(text = stringResource(R.string.library_clear_history_confirm_title))
            },
            text = {
                Text(
                    text = stringResource(R.string.library_clear_history_confirm_desc),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearHistoryDialog = false
                        onClearHistory()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(text = stringResource(R.string.library_clear_history))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(text = stringResource(R.string.settings_delete_cancel))
                }
            }
        )
    }
}

/**
 * Library overview screen presenting the 5 most recent items from History and Downloads.
 */
@Composable
private fun LibraryOverviewView(
    historyVideos: List<Video>,
    downloadedVideos: List<Video>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    isAudioBuffering: Boolean,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    onShowFullHistory: () -> Unit,
    onShowFullDownloads: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    queuedDownloadIds: Set<String> = emptySet(),
    downloadingProgressMap: Map<String, Float> = emptyMap(),
) {
    val top5History = remember(historyVideos) { historyVideos.take(5) }
    val top5Downloads = remember(downloadedVideos) { downloadedVideos.take(5) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // =========================================================================
        // 1. History Section
        // =========================================================================
        item(key = "history_section_header") {
            LibrarySectionHeader(
                title = stringResource(R.string.library_history_title),
                onSeeAllClick = if (historyVideos.isNotEmpty()) onShowFullHistory else null
            )
        }

        if (top5History.isEmpty()) {
            item(key = "history_empty_state") {
                LibraryEmptySectionPlaceholder(
                    icon = Icons.Outlined.History,
                    title = stringResource(R.string.library_history_empty_title),
                    subtitle = stringResource(R.string.library_history_empty_desc)
                )
            }
        } else {
            items(
                items = top5History,
                key = { "history_${it.id}" }
            ) { video ->
                val isVideoPlaying = video.id == currentPlayingVideoId && isPlaying
                val isVideoBuffering = video.id == currentPlayingVideoId && isAudioBuffering
                val isQueued = (video.id in queuedDownloadIds) || video.isDownloadQueued
                val activeProgress = downloadingProgressMap[video.id] ?: video.downloadProgress
                VideoItem(
                    video = video,
                    isPlaying = isVideoPlaying,
                    isBuffering = isVideoBuffering,
                    isQueued = isQueued,
                    downloadProgress = activeProgress,
                    onPlayClick = { onPlayVideo(video) },
                    onDownloadClick = { onDownloadVideo(video) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (historyVideos.size > 5) {
                item(key = "history_view_all_button") {
                    OutlinedButton(
                        onClick = onShowFullHistory,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_view_entire_history, historyVideos.size),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        item(key = "history_divider") {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        // =========================================================================
        // 2. Offline Videos / Downloads Section
        // =========================================================================
        item(key = "downloads_section_header") {
            LibrarySectionHeader(
                title = stringResource(R.string.library_downloads_title),
                onSeeAllClick = if (downloadedVideos.isNotEmpty()) onShowFullDownloads else null
            )
        }

        if (top5Downloads.isEmpty()) {
            item(key = "downloads_empty_state") {
                LibraryEmptySectionPlaceholder(
                    icon = Icons.Outlined.FileDownloadOff,
                    title = stringResource(R.string.library_downloads_empty_title),
                    subtitle = stringResource(R.string.library_downloads_empty_desc),
                )
            }
        } else {
            items(
                items = top5Downloads,
                key = { "downloaded_${it.id}" }
            ) { video ->
                val isVideoPlaying = (video.id == currentPlayingVideoId) && isPlaying
                val isVideoBuffering = (video.id == currentPlayingVideoId) && isAudioBuffering
                val isQueued = (video.id in queuedDownloadIds) || video.isDownloadQueued
                val activeProgress = downloadingProgressMap[video.id] ?: video.downloadProgress
                VideoItem(
                    video = video,
                    isPlaying = isVideoPlaying,
                    isBuffering = isVideoBuffering,
                    isQueued = isQueued,
                    downloadProgress = activeProgress,
                    onPlayClick = { onPlayVideo(video) },
                    onDownloadClick = { onDownloadVideo(video) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            if (downloadedVideos.size > 5) {
                item(key = "downloads_view_all_button") {
                    OutlinedButton(
                        onClick = onShowFullDownloads,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.library_view_all_downloads, downloadedVideos.size),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full scrollable list view for either History or Downloads.
 */
@Composable
private fun FullListView(
    title: String,
    videos: List<Video>,
    currentPlayingVideoId: String?,
    isPlaying: Boolean,
    isAudioBuffering: Boolean,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    onBackClick: () -> Unit,
    emptyIcon: ImageVector,
    emptyTitle: String,
    emptyDesc: String,
    headerAction: (@Composable () -> Unit)?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    queuedDownloadIds: Set<String> = emptySet(),
    downloadingProgressMap: Map<String, Float> = emptyMap(),
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Navigation Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.search_results_count, videos.size),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            headerAction?.invoke()
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

        if (videos.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                LibraryEmptySectionPlaceholder(
                    icon = emptyIcon,
                    title = emptyTitle,
                    subtitle = emptyDesc
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = videos,
                    key = { it.id }
                ) { video ->
                    val isVideoPlaying = (video.id == currentPlayingVideoId) && isPlaying
                    val isVideoBuffering = (video.id == currentPlayingVideoId) && isAudioBuffering
                    val isQueued = (video.id in queuedDownloadIds) || video.isDownloadQueued
                    val activeProgress = downloadingProgressMap[video.id] ?: video.downloadProgress
                    VideoItem(
                        video = video,
                        isPlaying = isVideoPlaying,
                        isBuffering = isVideoBuffering,
                        isQueued = isQueued,
                        downloadProgress = activeProgress,
                        onPlayClick = { onPlayVideo(video) },
                        onDownloadClick = { onDownloadVideo(video) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySectionHeader(
    title: String,
    onSeeAllClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        onSeeAllClick?.let { onClick ->
            TextButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.library_see_all),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LibraryEmptySectionPlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )
    }
}
