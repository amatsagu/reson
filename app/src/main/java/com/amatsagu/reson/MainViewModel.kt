package com.amatsagu.reson

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amatsagu.reson.data.SearchSuggestClient
import com.amatsagu.reson.data.local.AvatarManager
import com.amatsagu.reson.data.local.DatabaseManager
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.model.SearchResultItem
import com.amatsagu.reson.model.Video
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Complete UI State for Reson application with localized string resource references.
 */
data class MainUiState(
    // App startup loading overlay
    val isAppLoading: Boolean = true,
    @StringRes val loadingStatusRes: Int = R.string.loading_starting_engine,
    val loadingStatusArg: String? = null,

    // Navigation
    val selectedTab: ResonTab = ResonTab.Feed,

    // Search state
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val searchResults: List<SearchResultItem> = emptyList(),
    val isSearching: Boolean = false,
    @StringRes val searchErrorRes: Int? = null,
    val searchErrorArg: String? = null,

    // Channel subscriptions
    val followedChannels: Set<String> = emptySet(),
    val followedChannelList: List<Channel> = emptyList(),
    val selectedChannelId: String? = null, // null = "All"
    val channelVideos: List<Video> = emptyList(),
    val isSyncingChannels: Boolean = false,

    // Feed / Home Content
    val continueVideos: List<Video> = emptyList(),
    val feedVideos: List<Video> = emptyList(),

    // Mini Player State
    val currentPlayingVideo: Video? = null,
    val isPlaying: Boolean = false,

    // Diagnostics
    val diagnosticLogs: List<String> = emptyList()
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ytDlpManager = YtDlpManager.getInstance(application)
    private val databaseManager = DatabaseManager.getInstance(application)
    val ytDlpState: StateFlow<YtDlpState> = ytDlpManager.state

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var suggestJob: Job? = null

    init {
        addLog("App started. Initializing...")

        // Observe followed channels from SQLite
        viewModelScope.launch {
            databaseManager.observeFollowedChannels().collect { channels ->
                _uiState.update { it.copy(followedChannelList = channels) }
            }
        }

        // Observe followed channel IDs from SQLite for fast lookup & sync search results
        viewModelScope.launch {
            databaseManager.observeFollowedChannelIds().collect { ids ->
                _uiState.update { current ->
                    val updatedSearch = current.searchResults.map { item ->
                        if (item is SearchResultItem.Creator) {
                            SearchResultItem.Creator(item.channel.copy(isFollowed = ids.contains(item.channel.id)))
                        } else item
                    }
                    current.copy(
                        followedChannels = ids,
                        searchResults = updatedSearch
                    )
                }
            }
        }

        // Observe feed videos (from followed channels)
        viewModelScope.launch {
            databaseManager.observeFeedVideos().collect { videos ->
                _uiState.update { it.copy(feedVideos = videos) }
            }
        }

        // Observe recent uploads for the channels section (max 5 per channel)
        viewModelScope.launch {
            databaseManager.observeRecentVideosFromFollowed(maxPerChannel = 5).collect { videos ->
                _uiState.update { current ->
                    if (current.selectedChannelId == null) {
                        current.copy(channelVideos = videos)
                    } else {
                        current
                    }
                }
            }
        }

        // Observe continue listening items
        viewModelScope.launch {
            databaseManager.observeContinueListening().collect { videos ->
                _uiState.update { it.copy(continueVideos = videos) }
            }
        }

        // Observe yt-dlp initialization & update
        viewModelScope.launch {
            ytDlpState.collect { state ->
                when (state) {
                    is YtDlpState.Uninitialized -> {
                        _uiState.update {
                            it.copy(
                                loadingStatusRes = R.string.loading_connecting_engine,
                                loadingStatusArg = null
                            )
                        }
                    }
                    is YtDlpState.Initializing -> {
                        _uiState.update {
                            it.copy(
                                loadingStatusRes = R.string.loading_initializing_runtime,
                                loadingStatusArg = null
                            )
                        }
                        addLog("yt-dlp initializing runtime...")
                    }
                    is YtDlpState.Updating -> {
                        _uiState.update {
                            it.copy(
                                loadingStatusRes = R.string.loading_updating_upstream,
                                loadingStatusArg = null
                            )
                        }
                        addLog("yt-dlp updating to latest upstream release...")
                    }
                    is YtDlpState.Ready -> {
                        addLog("yt-dlp is ready (version: ${state.version})")
                        _uiState.update {
                            it.copy(
                                loadingStatusRes = R.string.status_ready,
                                loadingStatusArg = state.version
                            )
                        }
                        // Auto-dismiss loading screen after a short moment when ready
                        delay(600)
                        _uiState.update { it.copy(isAppLoading = false) }

                        // Background sync stale channels after engine is ready
                        syncFollowedChannels()
                    }
                    is YtDlpState.Error -> {
                        addLog("yt-dlp error: ${state.message}")
                        _uiState.update {
                            it.copy(
                                loadingStatusRes = R.string.status_offline_mode,
                                loadingStatusArg = state.message,
                                isAppLoading = false
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Bypasses the initial loading screen immediately (e.g. for offline use).
     */
    fun bypassLoading() {
        addLog("User bypassed startup loading (continuing offline)")
        _uiState.update { it.copy(isAppLoading = false) }
    }

    fun selectTab(tab: ResonTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun openSearch() {
        _uiState.update { it.copy(isSearchActive = true) }
    }

    fun closeSearch() {
        _uiState.update {
            it.copy(
                isSearchActive = false,
                suggestions = emptyList(),
                searchErrorRes = null,
                searchErrorArg = null
            )
        }
    }

    /**
     * Handles typing in search bar with debounced autocomplete suggestions.
     */
    fun onSearchQueryChange(newQuery: String) {
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                searchErrorRes = null,
                searchErrorArg = null
            )
        }

        suggestJob?.cancel()
        if (newQuery.trim().length >= 2) {
            suggestJob = viewModelScope.launch {
                delay(250) // 250ms debounce
                val suggestions = SearchSuggestClient.getSuggestions(newQuery)
                _uiState.update { it.copy(suggestions = suggestions) }
            }
        } else {
            _uiState.update { it.copy(suggestions = emptyList()) }
        }
    }

    /**
     * Executes YouTube fast search for top 30 videos.
     */
    fun executeSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        suggestJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = trimmed,
                isSearching = true,
                suggestions = emptyList(),
                searchErrorRes = null,
                searchErrorArg = null
            )
        }

        addLog("Executing unified search for: '$trimmed'...")

        viewModelScope.launch {
            // Concurrently query related videos and relevant channels
            val videoDeferred = async { ytDlpManager.searchContent(trimmed, maxResults = 30) }
            val channelDeferred = async { ytDlpManager.searchChannels(trimmed, maxResults = 3) }

            val videoResult = videoDeferred.await()
            val channelResult = channelDeferred.await()

            when (videoResult) {
                is Result.Success -> {
                    val rawVideos = videoResult.data
                    val rawChannels = if (channelResult is Result.Success) channelResult.data else emptyList()

                    // Filter out random channels (similarity < 0.6) and rank by similarity, subs & activity
                    val filteredChannels = YtDlpManager.filterAndRankChannels(
                        query = trimmed,
                        channels = rawChannels,
                        videos = rawVideos
                    ).map { ch ->
                        ch.copy(isFollowed = _uiState.value.followedChannels.contains(ch.id))
                    }

                    // Assemble composite list up to 30 items with channels placed high (top 5)
                    val mergedResults = YtDlpManager.mergeSearchResults(
                        videos = rawVideos,
                        channels = filteredChannels,
                        maxTotal = 30
                    )

                    addLog("Search completed. Found ${rawVideos.size} videos and ${filteredChannels.size} matching channels.")
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = mergedResults,
                            searchErrorRes = if (mergedResults.isEmpty()) R.string.search_no_results else null,
                            searchErrorArg = if (mergedResults.isEmpty()) trimmed else null
                        )
                    }
                }
                is Result.Failure -> {
                    addLog("Search failed: ${videoResult.message}")
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchErrorRes = R.string.search_failed,
                            searchErrorArg = videoResult.message
                        )
                    }
                }
            }
        }
    }

    /**
     * Filters the recent uploads list to a specific channel (or null for All channels).
     */
    fun selectChannelFilter(channelId: String?) {
        _uiState.update { it.copy(selectedChannelId = channelId) }
        viewModelScope.launch {
            val videos = if (channelId == null) {
                databaseManager.getRecentVideosFromFollowed(maxPerChannel = 5)
            } else {
                databaseManager.getVideosForChannel(channelId, limit = 50)
            }
            _uiState.update { it.copy(channelVideos = videos) }
        }
    }

    /**
     * Toggles follow/unfollow status for a creator channel.
     * Persisted in SQLite with background avatar caching and recent uploads sync.
     */
    fun toggleFollowChannel(channel: Channel) {
        val isCurrentlyFollowed = _uiState.value.followedChannels.contains(channel.id)

        // Optimistically update search results immediately for instant UI feedback
        _uiState.update { current ->
            val updatedSearchResults = current.searchResults.map { item ->
                if (item is SearchResultItem.Creator && item.channel.id == channel.id) {
                    SearchResultItem.Creator(item.channel.copy(isFollowed = !isCurrentlyFollowed))
                } else {
                    item
                }
            }
            current.copy(searchResults = updatedSearchResults)
        }

        viewModelScope.launch {
            if (isCurrentlyFollowed) {
                addLog("Unfollowing channel: ${channel.title}")
                databaseManager.unfollowChannel(channel.id)
                if (_uiState.value.selectedChannelId == channel.id) {
                    selectChannelFilter(null)
                }
            } else {
                addLog("Following channel: ${channel.title}")
                val followTimestamp = System.currentTimeMillis()
                databaseManager.followChannel(channel.copy(isFollowed = true, followedAt = followTimestamp))
                syncChannelInBackground(channel)
            }
        }
    }

    /**
     * Background task to download channel avatar as compact Base64 and fetch recent uploads.
     */
    private fun syncChannelInBackground(channel: Channel) {
        viewModelScope.launch {
            // 1. Download avatar as Base64 if not already cached
            if (channel.avatarBase64.isBlank() && channel.avatarUrl.isNotBlank()) {
                val base64 = AvatarManager.downloadAvatarAsBase64(channel.avatarUrl)
                if (!base64.isNullOrBlank()) {
                    databaseManager.updateChannelAvatarBase64(channel.id, base64)
                    addLog("Cached offline avatar for ${channel.title}")
                }
            }

            // 2. Fetch latest videos for this channel via yt-dlp
            val result = ytDlpManager.fetchChannelVideos(
                channelId = channel.id,
                handle = channel.handle,
                channelTitle = channel.title,
                maxResults = 5
            )
            when (result) {
                is Result.Success -> {
                    if (result.data.isNotEmpty()) {
                        databaseManager.upsertVideos(result.data, defaultChannelId = channel.id)
                        addLog("Synced ${result.data.size} uploads for ${channel.title}")
                    }
                }
                is Result.Failure -> {
                    addLog("Could not fetch uploads for ${channel.title}: ${result.message}")
                }
            }
        }
    }

    /**
     * Syncs all followed channels: downloads any missing avatars and checks for new uploads.
     */
    fun syncFollowedChannels() {
        viewModelScope.launch {
            val channels = databaseManager.getFollowedChannels()
            if (channels.isEmpty()) return@launch

            _uiState.update { it.copy(isSyncingChannels = true) }
            addLog("Checking updates for ${channels.size} followed channels...")

            for (ch in channels) {
                // Ensure avatar is cached
                if (ch.avatarBase64.isBlank() && ch.avatarUrl.isNotBlank()) {
                    val base64 = AvatarManager.downloadAvatarAsBase64(ch.avatarUrl)
                    if (!base64.isNullOrBlank()) {
                        databaseManager.updateChannelAvatarBase64(ch.id, base64)
                    }
                }

                // Check for new uploads
                val result = ytDlpManager.fetchChannelVideos(
                    channelId = ch.id,
                    handle = ch.handle,
                    channelTitle = ch.title,
                    maxResults = 5
                )
                if (result is Result.Success && result.data.isNotEmpty()) {
                    databaseManager.upsertVideos(result.data, defaultChannelId = ch.id)
                }
            }

            _uiState.update { it.copy(isSyncingChannels = false) }
            addLog("Channels sync completed.")
        }
    }

    fun playVideo(video: Video) {
        addLog("Playing video: ${video.title}")
        _uiState.update {
            it.copy(
                currentPlayingVideo = video,
                isPlaying = true
            )
        }
        viewModelScope.launch {
            databaseManager.upsertVideo(video)
            databaseManager.savePlaybackProgress(
                videoId = video.id,
                positionMs = if (video.playbackPositionMs > 0) video.playbackPositionMs else 1000L,
                durationMs = if (video.playbackDurationMs > 0) video.playbackDurationMs else (video.durationSeconds * 1000L)
            )
        }
    }

    fun togglePlayPause() {
        _uiState.update { current ->
            current.copy(isPlaying = !current.isPlaying)
        }
    }

    fun downloadVideo(video: Video) {
        addLog("Download requested for: ${video.title}")
        _uiState.update { current ->
            val updatedFeed = current.feedVideos.map {
                if (it.id == video.id) it.copy(downloadProgress = 0.5f) else it
            }
            val updatedSearch = current.searchResults.map { item ->
                if (item is SearchResultItem.Video && item.video.id == video.id) {
                    SearchResultItem.Video(item.video.copy(downloadProgress = 0.5f))
                } else {
                    item
                }
            }
            current.copy(feedVideos = updatedFeed, searchResults = updatedSearch)
        }
    }

    fun checkUpdate() {
        viewModelScope.launch {
            addLog("Triggered manual update check...")
            val result = ytDlpManager.update(UpdateChannel.STABLE)
            when (result) {
                is Result.Success -> addLog("Update result: ${result.data}")
                is Result.Failure -> addLog("Update failed: ${result.message}")
            }
        }
    }

    fun selfRepair() {
        viewModelScope.launch {
            addLog("Triggered clean self-repair / recovery...")
            val success = ytDlpManager.performSelfRecovery()
            if (success) {
                addLog("Self-repair completed successfully.")
            } else {
                addLog("Self-repair failed. Check permissions or network.")
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "[$timestamp] $message"
        _uiState.update { current ->
            current.copy(diagnosticLogs = (current.diagnosticLogs + formatted).takeLast(100))
        }
    }

    /**
     * Fresh startup state: both continue listening and recommended feeds start empty
     * until the user searches, follows channels, or plays content.
     */
    private fun loadDefaultContent() {
        _uiState.update {
            it.copy(
                continueVideos = emptyList(),
                feedVideos = emptyList(),
                currentPlayingVideo = null,
            )
        }
    }
}
