package com.amatsagu.reson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.amatsagu.reson.ui.components.MiniPlayerBar
import com.amatsagu.reson.ui.components.ResonBottomNavBar
import com.amatsagu.reson.ui.components.SearchOverlay
import com.amatsagu.reson.ui.components.SearchPillBar
import com.amatsagu.reson.ui.components.VideoItem
import com.amatsagu.reson.ui.theme.ResonTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ResonTheme(dynamicColor = true) {
                ResonApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun ResonApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ytDlpState by viewModel.ytDlpState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    uiState.currentPlayingVideo?.let { video ->
                        MiniPlayerBar(
                            video = video,
                            isPlaying = uiState.isPlaying,
                            onPlayPauseClick = { viewModel.togglePlayPause() },
                            onClick = {}
                        )
                    }

                    ResonBottomNavBar(
                        selectedTab = uiState.selectedTab,
                        onTabSelected = { viewModel.selectTab(it) }
                    )
                }
            }
        ) { innerPadding ->
            when (uiState.selectedTab) {
                ResonTab.Feed -> {
                    FeedScreen(
                        uiState = uiState,
                        onSearchClick = { viewModel.openSearch() },
                        onPlayVideo = { viewModel.playVideo(it) },
                        onDownloadVideo = { viewModel.downloadVideo(it) },
                        isFeedLoadingMore = uiState.isFeedLoadingMore,
                        onLoadMore = { viewModel.loadMoreFeedVideos() },
                        contentPadding = innerPadding
                    )
                }
                ResonTab.Channels -> {
                    ChannelsScreen(
                        followedChannels = uiState.followedChannelList,
                        selectedChannelId = uiState.selectedChannelId,
                        videos = uiState.channelVideos,
                        isSyncing = uiState.isSyncingChannels,
                        isChannelLoadingMore = uiState.isChannelLoadingMore,
                        onSelectChannel = { viewModel.selectChannelFilter(it) },
                        onFollowClick = { viewModel.toggleFollowChannel(it) },
                        onPlayVideo = { viewModel.playVideo(it) },
                        onDownloadVideo = { viewModel.downloadVideo(it) },
                        onLoadMore = { viewModel.loadMoreChannelVideos() },
                        onSearchClick = { viewModel.openSearch() },
                        contentPadding = innerPadding
                    )
                }
                ResonTab.Library -> {
                    LibraryScreen(
                        ytDlpState = ytDlpState,
                        onCheckUpdate = { viewModel.checkUpdate() },
                        onSelfRepair = { viewModel.selfRepair() },
                        contentPadding = innerPadding
                    )
                }
            }
        }

        // Fullscreen Search Overlay
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
            onPlayVideo = { viewModel.playVideo(it) },
            onDownloadVideo = { viewModel.downloadVideo(it) },
            onFollowChannel = { viewModel.toggleFollowChannel(it) }
        )

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
    onSearchClick: () -> Unit,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    isFeedLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    contentPadding: PaddingValues,
) {
    val hasContent = uiState.continueVideos.isNotEmpty() || uiState.feedVideos.isNotEmpty()
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

        if (!hasContent) {
            // Friendly centered empty state encouraging user to search
            EmptyFeedState(
                onSearchClick = onSearchClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 32.dp)
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (uiState.continueVideos.isNotEmpty()) {
                    item(key = "continue_section", contentType = "continue") {
                        ContinueSection(
                            videos = uiState.continueVideos,
                            onPlayClick = onPlayVideo
                        )
                    }
                }

                if (uiState.feedVideos.isNotEmpty()) {
                    item(key = "recommended_header", contentType = "header") {
                        AdaptiveSectionHeader(
                            fullTitle = stringResource(R.string.section_recommended_content),
                            shortTitle = stringResource(R.string.section_recommended_short),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    items(
                        items = uiState.feedVideos,
                        key = { it.id },
                        contentType = { "feed_video" }
                    ) { video ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            VideoItem(
                                video = video,
                                onPlayClick = { onPlayVideo(video) },
                                onDownloadClick = { onDownloadVideo(video) }
                            )
                        }
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

@Composable
fun ChannelsScreen(
    followedChannels: List<Channel>,
    selectedChannelId: String?,
    videos: List<Video>,
    isSyncing: Boolean,
    isChannelLoadingMore: Boolean,
    onSelectChannel: (String?) -> Unit,
    onFollowClick: (Channel) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onDownloadVideo: (Video) -> Unit,
    onLoadMore: () -> Unit,
    onSearchClick: () -> Unit,
    contentPadding: PaddingValues,
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

    if (followedChannels.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
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
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Screen Header
            item(key = "channels_screen_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AdaptiveSectionHeader(
                        fullTitle = stringResource(R.string.channels_title),
                        shortTitle = stringResource(R.string.channels_title)
                    )

                    if (isSyncing) {
                        Text(
                            text = stringResource(R.string.channels_syncing),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

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

                AdaptiveSectionHeader(
                    fullTitle = fullHeader,
                    shortTitle = shortHeader,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
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
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        VideoItem(
                            video = video,
                            onPlayClick = { onPlayVideo(video) },
                            onDownloadClick = { onDownloadVideo(video) }
                        )
                    }
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

@Composable
fun LibraryScreen(
    ytDlpState: YtDlpState,
    onCheckUpdate: () -> Unit,
    onSelfRepair: () -> Unit,
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.library_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.library_engine_status),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                val statusText = when (ytDlpState) {
                    is YtDlpState.Ready -> stringResource(R.string.status_ready, ytDlpState.version)
                    is YtDlpState.Updating -> stringResource(R.string.status_updating)
                    is YtDlpState.Initializing -> stringResource(R.string.status_initializing)
                    is YtDlpState.Error -> stringResource(R.string.status_error, ytDlpState.message)
                    is YtDlpState.Uninitialized -> stringResource(R.string.status_uninitialized)
                }

                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdate) {
                        Text(stringResource(R.string.action_check_update))
                    }
                    OutlinedButton(onClick = onSelfRepair) {
                        Text(stringResource(R.string.action_self_repair))
                    }
                }
            }
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Empty Feed (Fresh Start)", showBackground = true)
@Composable
fun ResonFeedEmptyPreview() {
    ResonTheme(dynamicColor = true) {
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
    ResonTheme(dynamicColor = true) {
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
