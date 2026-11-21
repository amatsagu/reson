package com.amatsagu.reson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.model.Video
import com.amatsagu.reson.ui.components.AdaptiveSectionHeader
import com.amatsagu.reson.ui.components.AllChannelsCard
import com.amatsagu.reson.ui.components.AppLoadingOverlay
import com.amatsagu.reson.ui.components.ChannelAvatarCard
import com.amatsagu.reson.ui.components.ChannelItem
import com.amatsagu.reson.ui.components.ContinueSection
import com.amatsagu.reson.ui.components.ResonUnifiedBottomBar
import com.amatsagu.reson.ui.components.SearchOverlay
import com.amatsagu.reson.ui.components.SearchPillBar
import com.amatsagu.reson.ui.components.VideoItem
import com.amatsagu.reson.ui.screens.LibraryScreen
import com.amatsagu.reson.ui.screens.SettingsScreen
import com.amatsagu.reson.ui.theme.ResonTheme
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.foundation.shape.RoundedCornerShape

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
            ResonTheme(themeColor = themeColor) {
                ResonApp(viewModel = viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.flushPlaybackProgressAndListeningTime(sync = true)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

@Composable
fun ResonApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ytDlpState by viewModel.ytDlpState.collectAsStateWithLifecycle()
    val showRecommended by viewModel.showRecommended.collectAsStateWithLifecycle()
    val themeColor by viewModel.themeColor.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val databaseStats by viewModel.databaseStats.collectAsStateWithLifecycle()
    val totalListeningTimeMs by viewModel.totalListeningTimeMs.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                ResonUnifiedBottomBar(
                    currentVideo = uiState.currentPlayingVideo,
                    isPlaying = uiState.isPlaying,
                    isBuffering = uiState.isAudioBuffering,
                    positionMs = { viewModel.getCurrentPlaybackPositionMs() },
                    durationMs = uiState.playbackDurationMs,
                    onPlayPauseClick = { viewModel.togglePlayPause() },
                    onSeek = { viewModel.seekTo(it) },
                    onCloseClick = { viewModel.closePlayer() },
                    selectedTab = uiState.selectedTab,
                    onTabSelected = { tab ->
                        if (uiState.isSearchActive) {
                            viewModel.closeSearch()
                        }
                        viewModel.selectTab(tab)
                    },
                    statusHint = uiState.playerStatusHint,
                    playbackSpeed = playbackSpeed,
                    onSpeedChange = { viewModel.setPlaybackSpeed(it) },
                    isOffline = uiState.isOffline,
                    isRateLimitBlocked = uiState.isRateLimitBlocked,
                    rateLimitMessage = uiState.rateLimitBlockedMessage,
                    isBannerDismissed = uiState.isNetworkBannerDismissed,
                    onDismissBanner = { viewModel.dismissNetworkBanner() }
                )
            }
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (uiState.selectedTab) {
                    ResonTab.Feed -> {
                        FeedScreen(
                            uiState = uiState,
                            showRecommended = showRecommended,
                            onSearchClick = { viewModel.openSearch() },
                            onPlayVideo = { viewModel.playVideo(it) },
                            onDownloadVideo = { viewModel.downloadVideo(it) },
                            isFeedLoadingMore = uiState.isFeedLoadingMore,
                            onLoadMore = { viewModel.loadMoreFeedVideos() },
                            onLoadMoreContinue = { viewModel.loadMoreContinueVideos() },
                            onRefresh = { viewModel.refreshFeed() },
                            contentPadding = innerPadding
                        )
                    }
                    ResonTab.Channels -> {
                        ChannelsScreen(
                            followedChannels = uiState.followedChannelList,
                            selectedChannelId = uiState.selectedChannelId,
                            videos = uiState.channelVideos,
                            isSyncing = uiState.isSyncingChannels,
                            isChannelLoading = uiState.isChannelLoading,
                            isChannelLoadingMore = uiState.isChannelLoadingMore,
                            currentPlayingVideoId = uiState.currentPlayingVideo?.id,
                            isPlaybackActive = uiState.isPlaying,
                            isAudioBuffering = uiState.isAudioBuffering,
                            onSelectChannel = { viewModel.selectChannelFilter(it) },
                            onFollowClick = { viewModel.toggleFollowChannel(it) },
                            onPlayVideo = { viewModel.playVideo(it) },
                            onDownloadVideo = { viewModel.downloadVideo(it) },
                            onLoadMore = { viewModel.loadMoreChannelVideos() },
                            onSearchClick = { viewModel.openSearch() },
                            onRefresh = { viewModel.refreshChannelsSection() },
                            queuedDownloadIds = uiState.queuedDownloadIds,
                            downloadingProgressMap = uiState.downloadingProgressMap,
                            lastChannelsSyncedAt = uiState.lastChannelsSyncedAt,
                            contentPadding = innerPadding
                        )
                    }
                    ResonTab.Library -> {
                        LibraryScreen(
                            historyVideos = uiState.historyVideos,
                            downloadedVideos = uiState.downloadedVideos,
                            currentPlayingVideoId = uiState.currentPlayingVideo?.id,
                            isPlaying = uiState.isPlaying,
                            isAudioBuffering = uiState.isAudioBuffering,
                            onPlayVideo = { viewModel.playVideo(it) },
                            onDownloadVideo = { viewModel.downloadVideo(it) },
                            onClearHistory = { viewModel.clearPlaybackHistory() },
                            queuedDownloadIds = uiState.queuedDownloadIds,
                            downloadingProgressMap = uiState.downloadingProgressMap,
                            contentPadding = innerPadding
                        )
                    }
                    ResonTab.Settings -> {
                        SettingsScreen(
                            ytDlpState = ytDlpState,
                            databaseStats = databaseStats,
                            totalListeningTimeMs = totalListeningTimeMs,
                            currentThemeColor = themeColor,
                            showRecommended = showRecommended,
                            currentLanguage = language,
                            onThemeColorSelected = { viewModel.setThemeColor(it) },
                            onShowRecommendedChanged = { viewModel.setShowRecommended(it) },
                            onLanguageSelected = { viewModel.setLanguage(it) },
                            onCheckUpdate = { viewModel.checkUpdate() },
                            onSelfRepair = { viewModel.selfRepair() },
                            onExportDatabase = { out, cb -> viewModel.exportDatabase(out, cb) },
                            onImportDatabase = { input, cb -> viewModel.importDatabase(input, cb) },
                            onClearHistory = { viewModel.clearPlaybackHistory() },
                            onDeleteAllData = { cb -> viewModel.deleteAllData(cb) },
                            contentPadding = innerPadding
                        )
                    }
                }

                // Search Overlay placed inside Scaffold content area so unified bottom bar remains docked below
                SearchOverlay(
                    visible = uiState.isSearchActive,
                    query = uiState.searchQuery,
                    onQueryChange = { viewModel.onSearchQueryChange(it) },
                    onSearch = { viewModel.executeSearch(it) },
                    onDismiss = { viewModel.closeSearch() },
                    suggestions = uiState.suggestions,
                    isSearching = uiState.isSearching,
                    searchResults = uiState.searchResults,
                    isSearchLoadingMore = uiState.isSearchLoadingMore,
                    onLoadMore = { viewModel.loadMoreSearchResults() },
                    errorRes = uiState.searchErrorRes,
                    errorArg = uiState.searchErrorArg,
                    currentPlayingVideoId = uiState.currentPlayingVideo?.id,
                    isPlaybackActive = uiState.isPlaying,
                    isAudioBuffering = uiState.isAudioBuffering,
                    onPlayVideo = { viewModel.playVideo(it) },
                    onDownloadVideo = { viewModel.downloadVideo(it) },
                    onFollowChannel = { viewModel.toggleFollowChannel(it) },
                    queuedDownloadIds = uiState.queuedDownloadIds,
                    downloadingProgressMap = uiState.downloadingProgressMap,
                    contentPadding = innerPadding
                )

            }
        }

        // Startup App Loading Overlay with Bypass
        val loadingStatus = if (uiState.loadingStatusArg != null) {
            stringResource(uiState.loadingStatusRes, uiState.loadingStatusArg!!)
        } else {
            stringResource(uiState.loadingStatusRes)
        }

        AppLoadingOverlay(
            visible = uiState.isAppLoading,
            statusText = loadingStatus,
            onBypass = { viewModel.bypassLoading() }
        )
    }
}

@Composable
fun FeedScreen(
    uiState: MainUiState,
    showRecommended: Boolean = true,
    onSearchClick: () -> Unit,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    isFeedLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onLoadMoreContinue: () -> Unit = {},
    onRefresh: () -> Unit = {},
    contentPadding: PaddingValues,
) {
    val hasContent = uiState.continueVideos.isNotEmpty() || (showRecommended && uiState.feedVideos.isNotEmpty())
    val listState = rememberLazyListState()

    val shouldLoadMore = remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && showRecommended) {
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        // Search bar pill stays pinned at the top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            SearchPillBar(
                query = "",
                onClick = onSearchClick
            )
        }

        if (uiState.isFeedRefreshing || uiState.isSyncingChannels) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.primary
            )
        }

        PullToRefreshBox(
            isRefreshing = uiState.isFeedRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (!hasContent) {
                if (!showRecommended && uiState.continueVideos.isEmpty()) {
                    // Empty state when user disabled recommendations and has no continue listening items
                    RecommendationsDisabledFeedState(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp)
                    )
                } else {
                    // Friendly centered empty state encouraging user to search
                    EmptyFeedState(
                        onSearchClick = onSearchClick,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 32.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (uiState.continueVideos.isNotEmpty()) {
                        item(key = "continue_section", contentType = "continue") {
                            ContinueSection(
                                videos = uiState.continueVideos,
                                currentPlayingVideoId = uiState.currentPlayingVideo?.id,
                                isPlaybackActive = uiState.isPlaying,
                                isAudioBuffering = uiState.isAudioBuffering,
                                onPlayClick = onPlayVideo,
                                onLoadMore = onLoadMoreContinue,
                                isContinueLoadingMore = uiState.isContinueLoadingMore
                            )
                        }
                    }

                    if (showRecommended && uiState.feedVideos.isNotEmpty()) {
                        item(key = "recommended_header", contentType = "header") {
                            val syncText = rememberFormattedLastSynced(
                                timestamp = uiState.lastFeedSyncedAt,
                                isSyncing = uiState.isFeedRefreshing || uiState.isSyncingChannels
                            )
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                            ) {
                                AdaptiveSectionHeader(
                                    fullTitle = stringResource(R.string.section_recommended_content),
                                    shortTitle = stringResource(R.string.section_recommended_short)
                                )
                                Text(
                                    text = syncText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                                )
                            }
                        }

                        items(
                            items = uiState.feedVideos,
                            key = { it.id },
                            contentType = { "feed_video" }
                        ) { video ->
                            val isPlaying = video.id == uiState.currentPlayingVideo?.id && uiState.isPlaying
                            val isBuffering = video.id == uiState.currentPlayingVideo?.id && uiState.isAudioBuffering
                            val isQueued = video.id in uiState.queuedDownloadIds || video.isDownloadQueued
                            val activeProgress = uiState.downloadingProgressMap[video.id] ?: video.downloadProgress
                            VideoItem(
                                video = video,
                                isPlaying = isPlaying,
                                isBuffering = isBuffering,
                                isQueued = isQueued,
                                downloadProgress = activeProgress,
                                onPlayClick = { onPlayVideo(video) },
                                onDownloadClick = { onDownloadVideo(video) },
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        if (isFeedLoadingMore) {
                            item(key = "feed_loading_more_indicator", contentType = "loading_spinner") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    } else if (!showRecommended) {
                        item(key = "recommendations_disabled_message") {
                            RecommendationsDisabledFeedState(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 32.dp, vertical = 24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Friendly empty state displayed when neither continue watching nor recommended items are present.
 * Encourages the user to start searching and discovering content.
 */
@Composable
fun EmptyFeedState(
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onSearchClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.TravelExplore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.feed_empty_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.feed_empty_description),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

/**
 * Friendly empty state displayed when recommended content is disabled by user preference.
 * Matches the exact styling and proportions of [EmptyFeedState].
 */
@Composable
fun RecommendationsDisabledFeedState(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.settings_recommended_disabled_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_recommended_disabled_desc),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

@Composable
fun ChannelsScreen(
    followedChannels: List<Channel>,
    selectedChannelId: String?,
    videos: List<Video>,
    isSyncing: Boolean,
    isChannelLoading: Boolean = false,
    isChannelLoadingMore: Boolean,
    currentPlayingVideoId: String? = null,
    isPlaybackActive: Boolean = false,
    isAudioBuffering: Boolean = false,
    onSelectChannel: (String?) -> Unit,
    onFollowClick: (Channel) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    onLoadMore: () -> Unit,
    onSearchClick: () -> Unit,
    onRefresh: () -> Unit = {},
    contentPadding: PaddingValues,
    queuedDownloadIds: Set<String> = emptySet(),
    downloadingProgressMap: Map<String, Float> = emptyMap(),
    lastChannelsSyncedAt: Long = 0L,
) {
    val listState = rememberLazyListState()

    val shouldLoadMore = remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 0 && lastVisible >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = isSyncing,
        onRefresh = onRefresh,
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (isSyncing || isChannelLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (followedChannels.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(onClick = onSearchClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Subscriptions,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(42.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.channels_empty_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.channels_empty_description),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Top Horizontal Swipeable Channels Carousel
                item(key = "channels_carousel") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item(key = "all_channels") {
                            AllChannelsCard(
                                isSelected = selectedChannelId == null,
                                onClick = { onSelectChannel(null) }
                            )
                        }

                        items(
                            items = followedChannels,
                            key = { it.id }
                        ) { channel ->
                            ChannelAvatarCard(
                                channel = channel,
                                isSelected = selectedChannelId == channel.id,
                                onClick = {
                                    onSelectChannel(if (selectedChannelId == channel.id) null else channel.id)
                                }
                            )
                        }
                    }
                }

                // Section Header for Videos
                item(key = "recent_videos_header") {
                    val selectedChannel = followedChannels.find { it.id == selectedChannelId }
                    val (fullHeader, shortHeader) = if (selectedChannel != null) {
                        selectedChannel.title to selectedChannel.title
                    } else {
                        stringResource(R.string.channels_recent_videos) to stringResource(R.string.channels_recent_short)
                    }
                    val syncText = rememberFormattedLastSynced(
                        timestamp = lastChannelsSyncedAt,
                        isSyncing = isSyncing
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        AdaptiveSectionHeader(
                            fullTitle = fullHeader,
                            shortTitle = shortHeader
                        )
                        Text(
                            text = syncText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
                        )
                    }
                }

                // Recent Uploads List (max 5 per channel when in "All" view)
                if (videos.isEmpty()) {
                    item(key = "no_videos_placeholder") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isSyncing) stringResource(R.string.channels_syncing) else stringResource(R.string.channels_no_videos),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    items(
                        items = videos,
                        key = { it.id },
                        contentType = { "channel_video" }
                    ) { video ->
                        val isPlaying = video.id == currentPlayingVideoId && isPlaybackActive
                        val isBuffering = video.id == currentPlayingVideoId && isAudioBuffering
                        val isQueued = video.id in queuedDownloadIds || video.isDownloadQueued
                        val activeProgress = downloadingProgressMap[video.id] ?: video.downloadProgress
                        VideoItem(
                            video = video,
                            isPlaying = isPlaying,
                            isBuffering = isBuffering,
                            isQueued = isQueued,
                            downloadProgress = activeProgress,
                            onPlayClick = { onPlayVideo(video) },
                            onDownloadClick = { onDownloadVideo(video) },
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    if (isChannelLoadingMore) {
                        item(key = "channel_loading_more_indicator", contentType = "loading_spinner") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

fun formatLastSyncedText(
    timestamp: Long,
    isSyncing: Boolean,
    context: android.content.Context
): String {
    if (isSyncing) {
        return context.getString(R.string.status_syncing_in_progress)
    }
    if (timestamp <= 0L) {
        return context.getString(R.string.status_not_synced_yet)
    }
    val diffSeconds = ((System.currentTimeMillis() - timestamp) / 1000L).coerceAtLeast(0L)
    return when {
        diffSeconds < 60L -> context.getString(R.string.status_updated_just_now)
        diffSeconds < 3600L -> {
            val minutes = (diffSeconds / 60L).toInt().coerceAtLeast(1)
            context.getString(R.string.status_updated_minutes_ago, minutes)
        }
        diffSeconds < 86400L -> {
            val hours = (diffSeconds / 3600L).toInt().coerceAtLeast(1)
            context.getString(R.string.status_updated_hours_ago, hours)
        }
        else -> {
            val days = (diffSeconds / 86400L).toInt().coerceAtLeast(1)
            context.getString(R.string.status_updated_days_ago, days)
        }
    }
}

@Composable
fun rememberFormattedLastSynced(timestamp: Long, isSyncing: Boolean): String {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(timestamp, isSyncing) {
        formatLastSyncedText(timestamp, isSyncing, context)
    }
}



@androidx.compose.ui.tooling.preview.Preview(name = "Empty Feed (Fresh Start)", showBackground = true)
@Composable
fun ResonFeedEmptyPreview() {
    ResonTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FeedScreen(
                uiState = MainUiState(
                    continueVideos = emptyList(),
                    feedVideos = emptyList()
                ),
                onSearchClick = {},
                onPlayVideo = {},
                onDownloadVideo = {},
                isFeedLoadingMore = false,
                onLoadMore = {},
                contentPadding = PaddingValues(0.dp)
            )
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Populated Feed", showBackground = true)
@Composable
fun ResonFeedWithContentPreview() {
    ResonTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FeedScreen(
                uiState = MainUiState(
                    continueVideos = listOf(
                        Video(
                            id = "c1",
                            title = "Designing the Future of Mobile Audio",
                            channelTitle = "Reson Studio",
                            durationText = "45m",
                            releaseDateText = "57m ago",
                            playbackProgress = 0.65f,
                        ),
                        Video(
                            id = "c2",
                            title = "Why Clean Architecture Actually Matters",
                            channelTitle = "Software Crafts",
                            durationText = "38m",
                            releaseDateText = "1d3h ago",
                            playbackProgress = 0.35f,
                        ),
                    ),
                    feedVideos = listOf(
                        Video(
                            id = "e1",
                            title = "Deep Dive into Local AI & Neural Engines",
                            channelTitle = "Latent Space",
                            durationText = "42m",
                            releaseDateText = "2d ago",
                        ),
                        Video(
                            id = "e2",
                            title = "Building Reson: The Journey to Offline Audio",
                            channelTitle = "Reson Engineering",
                            durationText = "34m",
                            releaseDateText = "4d ago",
                            downloadProgress = 0.75f,
                        ),
                        Video(
                            id = "e3",
                            title = "The Architecture of Modern Kotlin & Compose",
                            channelTitle = "Android Developers",
                            durationText = "28m",
                            releaseDateText = "1w ago",
                        ),
                    ),
                ),
                onSearchClick = {},
                onPlayVideo = {},
                onDownloadVideo = {},
                isFeedLoadingMore = false,
                onLoadMore = {},
                contentPadding = PaddingValues(0.dp)
            )
        }
    }
}
