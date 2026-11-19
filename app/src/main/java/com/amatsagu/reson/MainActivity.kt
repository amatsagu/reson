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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amatsagu.reson.model.Episode
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.ui.components.AppLoadingOverlay
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
            errorMessage = uiState.searchError,
            onPlayEpisode = { viewModel.playEpisode(it) },
            onDownloadEpisode = { viewModel.downloadEpisode(it) }
        )

        // Startup App Loading Overlay with Bypass
        AppLoadingOverlay(
            visible = uiState.isAppLoading,
            statusText = uiState.loadingStatusText,
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

        // Episodes header
        item {
            Text(
                text = "Episodes",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Vertical episodes list
        items(uiState.feedEpisodes, key = { it.id }) { episode ->
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
    contentPadding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(16.dp)
    ) {
        Text(
            text = "Channels",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Recent uploads from channels you follow will appear here. Stored offline in SQLite.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 20.sp
        )
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
            text = "Library & Maintenance",
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
                    text = "yt-dlp Engine Status",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(6.dp))

                val statusText = when (ytDlpState) {
                    is YtDlpState.Ready -> "Ready (${ytDlpState.version})"
                    is YtDlpState.Updating -> "Updating..."
                    is YtDlpState.Initializing -> "Initializing..."
                    is YtDlpState.Error -> "Error: ${ytDlpState.message}"
                    is YtDlpState.Uninitialized -> "Uninitialized"
                }

                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onCheckUpdate) {
                        Text("Check Update")
                    }
                    OutlinedButton(onClick = onSelfRepair) {
                        Text("Self-Repair")
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
                            playbackProgress = 0.65f
                        ),
                        Episode(
                            id = "c2",
                            title = "Reson\nEpisode 4",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            playbackProgress = 0.35f
                        )
                    ),
                    feedEpisodes = listOf(
                        Episode(
                            id = "e5",
                            title = "Reson\nEpisode 5",
                            channelTitle = "Channel Reson",
                            durationText = "45m"
                        ),
                        Episode(
                            id = "e5_2",
                            title = "Reson\nEpisode 5",
                            channelTitle = "Channel Reson",
                            durationText = "45m",
                            downloadProgress = 0.75f
                        ),
                        Episode(
                            id = "e3",
                            title = "Reson\nEpisode • 3",
                            channelTitle = "Channel Reson",
                            durationText = "45m"
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
