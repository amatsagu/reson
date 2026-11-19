package com.amatsagu.reson

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.Episode
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.ui.components.AdaptiveSectionHeader
import com.amatsagu.reson.ui.components.AppLoadingOverlay
import com.amatsagu.reson.ui.components.ChannelItem
import com.amatsagu.reson.ui.components.ContinueSection
import com.amatsagu.reson.ui.components.EpisodeItem
import com.amatsagu.reson.ui.components.MiniPlayerBar
import com.amatsagu.reson.ui.components.ResonBottomNavBar
import com.amatsagu.reson.ui.components.SearchOverlay
import com.amatsagu.reson.ui.components.SearchPillBar
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
                    uiState.currentPlayingEpisode?.let { episode ->
                        MiniPlayerBar(
                            episode = episode,
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
                        onPlayEpisode = { viewModel.playEpisode(it) },
                        onDownloadEpisode = { viewModel.downloadEpisode(it) },
                        contentPadding = innerPadding
                    )
                }
                ResonTab.Channels -> {
                    ChannelsScreen(
                        followedChannels = uiState.followedChannelList,
                        onFollowClick = { viewModel.toggleFollowChannel(it) },
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
            errorRes = uiState.searchErrorRes,
            errorArg = uiState.searchErrorArg,
            onPlayEpisode = { viewModel.playEpisode(it) },
            onDownloadEpisode = { viewModel.downloadEpisode(it) },
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
    onPlayEpisode: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Search bar pill
        item {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                SearchPillBar(
                    query = "",
                    onClick = onSearchClick
                )
            }
        }

        // Continue section
        item {
            ContinueSection(
                episodes = uiState.continueEpisodes,
                onPlayClick = onPlayEpisode
            )
        }

        // Recommended content header (adapts to "Recommended" when width is constrained)
        item {
            AdaptiveSectionHeader(
                fullTitle = stringResource(R.string.section_recommended_content),
                shortTitle = stringResource(R.string.section_recommended_short),
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Vertical episodes list
        items(
            items = uiState.feedEpisodes,
            key = { it.id },
            contentType = { "feed_episode" }
        ) { episode ->
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                EpisodeItem(
                    episode = episode,
                    onPlayClick = { onPlayEpisode(episode) },
                    onDownloadClick = { onDownloadEpisode(episode) }
                )
            }
        }
    }
}

@Composable
fun ChannelsScreen(
    followedChannels: List<Channel>,
    onFollowClick: (Channel) -> Unit,
    contentPadding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "channels_title", contentType = "header") {
            Text(
                text = stringResource(R.string.channels_title),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        if (followedChannels.isEmpty()) {
            item(key = "channels_empty", contentType = "empty_state") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.channels_empty_description),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 20.sp
                )
            }
        } else {
            items(
                items = followedChannels,
                key = { it.id },
                contentType = { "channel" }
            ) { channel ->
                ChannelItem(
                    channel = channel,
                    onFollowClick = { onFollowClick(channel) }
                )
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

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
fun ResonFeedPreview() {
    ResonTheme(dynamicColor = true) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            FeedScreen(
                uiState = MainUiState(
                    continueEpisodes = listOf(
                        Episode(
                            id = "c1",
                            title = "Reson\nEpisode 5",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            releaseDateText = "57m ago",
                            playbackProgress = 0.65f
                        ),
                        Episode(
                            id = "c2",
                            title = "Reson\nEpisode 4",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            releaseDateText = "1d3h ago",
                            playbackProgress = 0.35f
                        )
                    ),
                    feedEpisodes = listOf(
                        Episode(
                            id = "e5",
                            title = "Reson\nEpisode 5",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            releaseDateText = "57m ago"
                        ),
                        Episode(
                            id = "e5_2",
                            title = "Reson\nEpisode 5",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            releaseDateText = "1d3h ago",
                            downloadProgress = 0.75f
                        ),
                        Episode(
                            id = "e3",
                            title = "Reson\nEpisode • 3",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            releaseDateText = "3d ago"
                        )
                    )
                ),
                onSearchClick = {},
                onPlayEpisode = {},
                onDownloadEpisode = {},
                contentPadding = PaddingValues(0.dp)
            )
        }
    }
}
