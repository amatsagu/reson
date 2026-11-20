package com.amatsagu.reson

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import com.amatsagu.reson.core.player.AudioPlayerManager
import com.amatsagu.reson.data.SearchSuggestClient
import com.amatsagu.reson.data.local.AvatarManager
import com.amatsagu.reson.data.local.DatabaseManager
import com.amatsagu.reson.data.local.DatabaseStats
import com.amatsagu.reson.data.local.ThemeColor
import com.amatsagu.reson.data.local.UserPreferences
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.ResonTab
import com.amatsagu.reson.model.SearchResultItem
import com.amatsagu.reson.model.Video
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Complete UI State for Reson application with localized string resource references.
 */
@Immutable
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

    // Pagination & Infinite Scroll states
    val isFeedLoadingMore: Boolean = false,
    val canLoadMoreFeed: Boolean = true,
    val isContinueLoadingMore: Boolean = false,
    val canLoadMoreContinue: Boolean = true,
    val isChannelLoadingMore: Boolean = false,
    val canLoadMoreChannelVideos: Boolean = true,
    val isSearchLoadingMore: Boolean = false,
    val canLoadMoreSearch: Boolean = true,

    // Bottom Audio Player State
    val currentPlayingVideo: Video? = null,
    val isPlaying: Boolean = false,
    val isAudioBuffering: Boolean = false,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val playbackProgress: Float = 0f,

    // Network & Rate-Limit Diagnostics Hints
    val isOffline: Boolean = false,
    val isRateLimitBlocked: Boolean = false,
    val rateLimitBlockedMessage: String? = null,
    val networkHintMessage: String? = null,
    val playbackErrorMessage: String? = null,
    val isNetworkBannerDismissed: Boolean = false,
) {
    /**
     * Reassuring inline player status hint for users during stalls, slow connections, or blocks.
     */
    val playerStatusHint: String?
        get() = playbackErrorMessage
            ?: networkHintMessage
            ?: if (isOffline) "No connection • Offline" else null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ytDlpManager = YtDlpManager.getInstance(application)
    private val databaseManager = DatabaseManager.getInstance(application)
    val ytDlpState: StateFlow<YtDlpState> = ytDlpManager.state

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Internal conflated worker queue for persisting playback progress without allocating coroutines per tick
    private data class ProgressUpdate(val videoId: String, val positionMs: Long, val durationMs: Long)
    private val progressChannel = KChannel<ProgressUpdate>(KChannel.CONFLATED)

    // Strict internal worker tracking: guarantees only 1 active job per task type and cancels stale jobs
    private var suggestJob: Job? = null
    private var searchJob: Job? = null
    private var playJob: Job? = null
    private var playWatchdogJob: Job? = null
    private var channelFilterJob: Job? = null
    private var feedPaginationJob: Job? = null
    private var continuePaginationJob: Job? = null
    private var channelPaginationJob: Job? = null
    private var searchPaginationJob: Job? = null
    private var updateJob: Job? = null
    private var selfRepairJob: Job? = null

    private val audioPlayerManager: AudioPlayerManager = AudioPlayerManager(
        context = application,
        onProgressUpdate = { video, positionMs, durationMs ->
            progressChannel.trySend(ProgressUpdate(video.id, positionMs, durationMs))
        },
        onStreamRecoveryRequested = { video, positionMs ->
            addLog("Stream recovery requested for ${video.title} at $positionMs ms")
            ytDlpManager.invalidateStreamCache(video.id)
            val freshResult = ytDlpManager.getAudioStreamUrl(
                videoId = video.id,
                forceRefresh = true,
                priority = TaskPriority.IMMEDIATE
            )
            if (freshResult is Result.Success) {
                audioPlayerManager.resumeWithFreshUrl(freshResult.data, positionMs)
                addLog("Stream recovered successfully on new connection")
            } else {
                addLog("Stream recovery failed: ${(freshResult as Result.Failure).message}")
            }
        }
    )

    /** Dedicated playback state flow to isolate high-frequency position updates from main UI state */
    val audioPlaybackState: StateFlow<com.amatsagu.reson.core.player.PlaybackState> = audioPlayerManager.playbackState

    private val userPreferences = UserPreferences.getInstance(application)
    val themeColor: StateFlow<ThemeColor> = userPreferences.themeColor
    val showRecommended: StateFlow<Boolean> = userPreferences.showRecommended
    val language: StateFlow<String> = userPreferences.language
    val totalListeningTimeMs: StateFlow<Long> = userPreferences.totalListeningTimeMs

    private val _databaseStats = MutableStateFlow<DatabaseStats?>(null)
    val databaseStats: StateFlow<DatabaseStats?> = _databaseStats.asStateFlow()

    init {
        addLog("App started. Initializing...")

        // Initial database stats load
        refreshDatabaseStats()

        // Progress persistence single worker: consumes updates on bounded Database dispatcher
        viewModelScope.launch(ResonDispatchers.Database) {
            var lastPositionMs = 0L
            for (update in progressChannel) {
                databaseManager.savePlaybackProgress(update.videoId, update.positionMs, update.durationMs, notify = false)
                val delta = update.positionMs - lastPositionMs
                if (delta in 1L..10_000L) {
                    userPreferences.addListeningTime(delta)
                }
                lastPositionMs = update.positionMs
            }
        }

        // Observe continuous network connectivity state (offline vs online)
        viewModelScope.launch {
            com.amatsagu.reson.core.network.NetworkMonitor.getInstance(application).networkState.collect { netState ->
                val offline = !netState.isConnected || !netState.isValidated
                _uiState.update { it.copy(isOffline = offline) }
            }
        }

        // Observe YouTube rate-limit & persistent blocking state
        viewModelScope.launch {
            ytDlpManager.rateLimitStatus.collect { status ->
                when (status) {
                    is RateLimitStatus.Blocked -> {
                        addLog("YouTube persistent block detected: ${status.message}")
                        _uiState.update {
                            it.copy(
                                isRateLimitBlocked = true,
                                rateLimitBlockedMessage = status.message,
                                isNetworkBannerDismissed = false
                            )
                        }
                    }
                    is RateLimitStatus.Throttled -> {
                        addLog("YouTube gentle cooldown active (${status.backoffSeconds}s)")
                        _uiState.update {
                            it.copy(
                                isRateLimitBlocked = false,
                                rateLimitBlockedMessage = null
                            )
                        }
                    }
                    is RateLimitStatus.Normal -> {
                        _uiState.update {
                            it.copy(
                                isRateLimitBlocked = false,
                                rateLimitBlockedMessage = null,
                                isNetworkBannerDismissed = false
                            )
                        }
                    }
                }
            }
        }

        // Observe network connectivity transitions for smart logging and diagnostics
        viewModelScope.launch {
            com.amatsagu.reson.core.network.NetworkMonitor.getInstance(application).transitions.collect { transition ->
                when (transition) {
                    is com.amatsagu.reson.core.network.NetworkTransition.Lost -> {
                        addLog("Network connection lost (${transition.lastState.displayName})")
                        _uiState.update { it.copy(isNetworkBannerDismissed = false) }
                    }
                    is com.amatsagu.reson.core.network.NetworkTransition.Switched -> {
                        addLog("Network switched: ${transition.from.displayName} -> ${transition.to.displayName}")
                        _uiState.update { it.copy(isNetworkBannerDismissed = false) }
                    }
                    is com.amatsagu.reson.core.network.NetworkTransition.Restored -> {
                        addLog("Network restored: ${transition.to.displayName}")
                        _uiState.update { it.copy(isNetworkBannerDismissed = false) }
                    }
                }
            }
        }

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

        // Observe feed videos (from followed channels), excluding any items in continue listening
        viewModelScope.launch {
            databaseManager.observeFeedVideos().collect { videos ->
                _uiState.update { current ->
                    val continueIds = current.continueVideos.map { it.id }.toSet()
                    current.copy(feedVideos = videos.filterNot { it.id in continueIds })
                }
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

        // Observe continue listening items (in-progress videos) and ensure feed excludes them
        viewModelScope.launch {
            databaseManager.observeContinueListening().collect { videos ->
                _uiState.update { current ->
                    val continueIds = videos.map { it.id }.toSet()
                    current.copy(
                        continueVideos = videos,
                        feedVideos = current.feedVideos.filterNot { it.id in continueIds }
                    )
                }
            }
        }

        // Observe Audio Player playback state.
        // High-frequency position ticks are isolated to audioPlaybackState to maintain rock-solid 120 FPS
        // on the main screen and avoid unnecessary whole-screen recompositions during scrolling.
        viewModelScope.launch {
            audioPlayerManager.playbackState.collect { playback ->
                _uiState.update { current ->
                    if (current.isPlaying != playback.isPlaying ||
                        current.isAudioBuffering != playback.isBuffering ||
                        current.playbackDurationMs != playback.durationMs ||
                        current.playbackErrorMessage != playback.errorMessage
                    ) {
                        current.copy(
                            isPlaying = playback.isPlaying,
                            isAudioBuffering = playback.isBuffering,
                            playbackDurationMs = playback.durationMs,
                            playbackErrorMessage = playback.errorMessage
                        )
                    } else {
                        current
                    }
                }
                if (playback.isPlaying) {
                    playWatchdogJob?.cancel()
                    _uiState.update { it.copy(networkHintMessage = null) }
                }
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
        if (tab == ResonTab.Settings) {
            refreshDatabaseStats()
        }
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
        val queryChanged = newQuery != _uiState.value.searchQuery
        _uiState.update {
            it.copy(
                searchQuery = newQuery,
                searchResults = if (queryChanged) emptyList() else it.searchResults,
                canLoadMoreSearch = if (queryChanged) false else it.canLoadMoreSearch,
                isSearchLoadingMore = if (queryChanged) false else it.isSearchLoadingMore,
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
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                searchQuery = trimmed,
                isSearching = true,
                isSearchLoadingMore = false,
                canLoadMoreSearch = true,
                suggestions = emptyList(),
                searchErrorRes = null,
                searchErrorArg = null
            )
        }

        addLog("Executing unified search for: '$trimmed'...")

        searchJob = viewModelScope.launch(ResonDispatchers.Network) {
            // Concurrently query related videos and relevant channels
            val videoDeferred = async { ytDlpManager.searchContent(trimmed, maxResults = 30) }
            val channelDeferred = async { ytDlpManager.searchChannels(trimmed, maxResults = 3) }

            val videoResult = videoDeferred.await()
            val channelResult = channelDeferred.await()

            when (videoResult) {
                is Result.Success -> {
                    val rawVideos = videoResult.data
                    val rawChannels = if (channelResult is Result.Success) channelResult.data else emptyList()
                    val followed = _uiState.value.followedChannels

                    // Filter out random channels (similarity < 0.6) and rank by similarity, subs & activity on Default dispatcher
                    val (filteredChannels, mergedResults) = withContext(ResonDispatchers.Computation) {
                        val filtered = YtDlpManager.filterAndRankChannels(
                            query = trimmed,
                            channels = rawChannels,
                            videos = rawVideos
                        ).map { ch ->
                            ch.copy(isFollowed = followed.contains(ch.id))
                        }

                        val merged = YtDlpManager.mergeSearchResults(
                            videos = rawVideos,
                            channels = filtered,
                            maxTotal = 30
                        )
                        filtered to merged
                    }

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
        _uiState.update {
            it.copy(
                selectedChannelId = channelId,
                canLoadMoreChannelVideos = true,
                isChannelLoadingMore = false
            )
        }
        channelFilterJob?.cancel()
        channelFilterJob = viewModelScope.launch(ResonDispatchers.Database) {
            val videos = if (channelId == null) {
                databaseManager.getRecentVideosFromFollowed(maxPerChannel = 5, limit = PAGE_SIZE, offset = 0)
            } else {
                databaseManager.getVideosForChannel(channelId, limit = PAGE_SIZE, offset = 0)
            }
            _uiState.update { it.copy(channelVideos = videos, canLoadMoreChannelVideos = videos.size >= PAGE_SIZE) }
        }
    }

    /**
     * Infinite scroll pagination: loads next page of feed videos from followed channels.
     * Automatically filters out any items currently present in the continue listening list.
     */
    fun loadMoreFeedVideos() {
        val state = _uiState.value
        if (state.isFeedLoadingMore || !state.canLoadMoreFeed || state.feedVideos.isEmpty() || feedPaginationJob?.isActive == true) return

        feedPaginationJob = viewModelScope.launch(ResonDispatchers.Database) {
            _uiState.update { it.copy(isFeedLoadingMore = true) }
            val currentOffset = _uiState.value.feedVideos.size
            val nextBatch = databaseManager.getFeedVideos(limit = PAGE_SIZE, offset = currentOffset)

            _uiState.update { current ->
                val continueIds = current.continueVideos.map { it.id }.toSet()
                val existingIds = current.feedVideos.map { it.id }.toSet()
                val newUnique = nextBatch.filter { !existingIds.contains(it.id) && !continueIds.contains(it.id) }
                current.copy(
                    feedVideos = current.feedVideos + newUnique,
                    isFeedLoadingMore = false,
                    canLoadMoreFeed = nextBatch.size >= PAGE_SIZE
                )
            }
        }
    }

    /**
     * Pseudo-infinite scroll pagination for continue listening horizontal carousel.
     */
    fun loadMoreContinueVideos() {
        val state = _uiState.value
        if (state.isContinueLoadingMore || !state.canLoadMoreContinue || state.continueVideos.isEmpty() || continuePaginationJob?.isActive == true) return

        continuePaginationJob = viewModelScope.launch(ResonDispatchers.Database) {
            _uiState.update { it.copy(isContinueLoadingMore = true) }
            val currentOffset = _uiState.value.continueVideos.size
            val nextBatch = databaseManager.getContinueListeningVideos(limit = 10, offset = currentOffset)

            _uiState.update { current ->
                val existingIds = current.continueVideos.map { it.id }.toSet()
                val newUnique = nextBatch.filter { !existingIds.contains(it.id) }
                val updatedContinue = current.continueVideos + newUnique
                val continueIds = updatedContinue.map { it.id }.toSet()

                current.copy(
                    continueVideos = updatedContinue,
                    feedVideos = current.feedVideos.filterNot { it.id in continueIds },
                    isContinueLoadingMore = false,
                    canLoadMoreContinue = nextBatch.size >= 10
                )
            }
        }
    }

    /**
     * Infinite scroll pagination: loads next page of channel uploads.
     */
    fun loadMoreChannelVideos() {
        val state = _uiState.value
        if (state.isChannelLoadingMore || !state.canLoadMoreChannelVideos || state.channelVideos.isEmpty() || channelPaginationJob?.isActive == true) return

        channelPaginationJob = viewModelScope.launch(ResonDispatchers.Database) {
            _uiState.update { it.copy(isChannelLoadingMore = true) }
            val currentOffset = _uiState.value.channelVideos.size
            val selectedId = _uiState.value.selectedChannelId

            val nextBatch = if (selectedId == null) {
                databaseManager.getRecentVideosFromFollowed(maxPerChannel = 5, limit = PAGE_SIZE, offset = currentOffset)
            } else {
                databaseManager.getVideosForChannel(selectedId, limit = PAGE_SIZE, offset = currentOffset)
            }

            _uiState.update { current ->
                val existingIds = current.channelVideos.map { it.id }.toSet()
                val newUnique = nextBatch.filter { !existingIds.contains(it.id) }
                current.copy(
                    channelVideos = current.channelVideos + newUnique,
                    isChannelLoadingMore = false,
                    canLoadMoreChannelVideos = nextBatch.size >= PAGE_SIZE
                )
            }
        }
    }

    /**
     * Infinite scroll pagination: loads next page of search results via yt-dlp.
     */
    fun loadMoreSearchResults() {
        val state = _uiState.value
        if (state.isSearchLoadingMore || !state.canLoadMoreSearch || state.isSearching || state.searchQuery.isBlank() || searchPaginationJob?.isActive == true) return

        searchPaginationJob = viewModelScope.launch(ResonDispatchers.Network) {
            _uiState.update { it.copy(isSearchLoadingMore = true) }
            val currentVideoCount = _uiState.value.searchResults.count { it is SearchResultItem.Video }
            val nextStartIndex = currentVideoCount + 1

            val result = ytDlpManager.searchContent(
                query = state.searchQuery,
                maxResults = PAGE_SIZE,
                startIndex = nextStartIndex
            )

            when (result) {
                is Result.Success -> {
                    val newVideos = result.data
                    _uiState.update { current ->
                        val existingIds = current.searchResults.map { it.id }.toSet()
                        val newUniqueItems = newVideos
                            .filter { !existingIds.contains("video_${it.id}") }
                            .map { SearchResultItem.Video(it) }

                        current.copy(
                            searchResults = current.searchResults + newUniqueItems,
                            isSearchLoadingMore = false,
                            canLoadMoreSearch = newVideos.size >= PAGE_SIZE
                        )
                    }
                }
                is Result.Failure -> {
                    _uiState.update {
                        it.copy(
                            isSearchLoadingMore = false,
                            canLoadMoreSearch = false
                        )
                    }
                }
            }
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
        if (_uiState.value.currentPlayingVideo?.id == video.id) {
            togglePlayPause()
            return
        }

        addLog("Playing video: ${video.title}")
        val effectiveDuration = if (video.playbackDurationMs > 0) video.playbackDurationMs else (video.durationSeconds * 1000L)

        playJob?.cancel()
        playWatchdogJob?.cancel()
        val isOffline = !com.amatsagu.reson.core.network.NetworkMonitor.getInstance(getApplication()).isOnline()
        val initialHint = if (isOffline) "No internet connection. Waiting for network…" else null

        _uiState.update {
            it.copy(
                currentPlayingVideo = video,
                isPlaying = false,
                isAudioBuffering = true,
                playbackPositionMs = video.playbackPositionMs,
                playbackDurationMs = effectiveDuration,
                playbackProgress = video.playbackProgress,
                networkHintMessage = initialHint,
                playbackErrorMessage = null
            )
        }

        // 20-second watchdog: if stream extraction or audio startup takes >20s, reassure user
        playWatchdogJob = viewModelScope.launch {
            delay(20_000L)
            if (_uiState.value.isAudioBuffering && _uiState.value.currentPlayingVideo?.id == video.id && !_uiState.value.isPlaying) {
                _uiState.update { current ->
                    if (current.currentPlayingVideo?.id == video.id && current.isAudioBuffering && !current.isPlaying) {
                        current.copy(
                            networkHintMessage = if (current.isOffline) {
                                "No internet connection. Waiting for network…"
                            } else if (current.isRateLimitBlocked) {
                                "YouTube is blocking requests (429). Retrying…"
                            } else {
                                "Slow connection. Still loading audio…"
                            }
                        )
                    } else current
                }
            }
        }

        playJob = viewModelScope.launch(ResonDispatchers.Network) {
            try {
                databaseManager.upsertVideo(video)
            } catch (e: Exception) {
                addLog("Failed to upsert video on play: ${e.message}")
            }
            addLog("Extracting audio stream for: ${video.title}...")
            val streamResult = ytDlpManager.getAudioStreamUrl(video.id)
            when (streamResult) {
                is Result.Success -> {
                    addLog("Stream URL obtained. Starting playback...")
                    audioPlayerManager.play(
                        video = video,
                        streamUrl = streamResult.data,
                        initialPositionMs = video.playbackPositionMs
                    )
                }
                is Result.Failure -> {
                    addLog("Audio stream extraction failed: ${streamResult.message}")
                    playWatchdogJob?.cancel()
                    _uiState.update {
                        it.copy(
                            isAudioBuffering = false,
                            isPlaying = false,
                            playbackErrorMessage = streamResult.message
                        )
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        audioPlayerManager.togglePlayPause()
    }

    fun getCurrentPlaybackPositionMs(): Long = audioPlayerManager.getCurrentPositionMs()

    fun seekTo(positionMs: Long) {
        audioPlayerManager.seekTo(positionMs)
        val video = _uiState.value.currentPlayingVideo
        if (video != null) {
            val duration = _uiState.value.playbackDurationMs
            progressChannel.trySend(ProgressUpdate(video.id, positionMs, duration))
        }
    }

    fun closePlayer() {
        playJob?.cancel()
        playWatchdogJob?.cancel()
        audioPlayerManager.stop()
        _uiState.update {
            it.copy(
                currentPlayingVideo = null,
                isPlaying = false,
                isAudioBuffering = false,
                playbackPositionMs = 0L,
                playbackProgress = 0f,
                networkHintMessage = null,
                playbackErrorMessage = null
            )
        }
    }

    fun dismissNetworkBanner() {
        _uiState.update { it.copy(isNetworkBannerDismissed = true) }
    }

    override fun onCleared() {
        super.onCleared()
        progressChannel.close()
        audioPlayerManager.release()
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
        if (updateJob?.isActive == true) return
        updateJob = viewModelScope.launch(ResonDispatchers.Network) {
            addLog("Triggered manual update check...")
            val result = ytDlpManager.update(UpdateChannel.STABLE)
            when (result) {
                is Result.Success -> addLog("Update result: ${result.data}")
                is Result.Failure -> addLog("Update failed: ${result.message}")
            }
        }
    }

    fun selfRepair() {
        if (selfRepairJob?.isActive == true) return
        selfRepairJob = viewModelScope.launch(ResonDispatchers.Network) {
            addLog("Triggered clean self-repair / recovery...")
            val success = ytDlpManager.performSelfRecovery()
            if (success) {
                addLog("Self-repair completed successfully.")
            } else {
                addLog("Self-repair failed. Check permissions or network.")
            }
        }
    }

    fun setThemeColor(color: ThemeColor) = userPreferences.setThemeColor(color)
    fun setShowRecommended(show: Boolean) = userPreferences.setShowRecommended(show)
    fun setLanguage(lang: String) = userPreferences.setLanguage(lang)

    fun refreshDatabaseStats() {
        viewModelScope.launch(ResonDispatchers.Database) {
            _databaseStats.value = databaseManager.getStats()
        }
    }

    fun exportDatabase(outputStream: OutputStream, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(ResonDispatchers.Database) {
            val result = databaseManager.exportDatabase(outputStream)
            withContext(ResonDispatchers.Main) {
                if (result.isSuccess) {
                    onResult(true, null)
                } else {
                    onResult(false, result.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun importDatabase(inputStream: InputStream, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch(ResonDispatchers.Database) {
            val result = databaseManager.importDatabase(inputStream)
            withContext(ResonDispatchers.Main) {
                if (result.isSuccess) {
                    refreshDatabaseStats()
                    onResult(true, null)
                } else {
                    onResult(false, result.exceptionOrNull()?.message)
                }
            }
        }
    }

    fun deleteAllData(onComplete: () -> Unit) {
        viewModelScope.launch(ResonDispatchers.Database) {
            closePlayer()
            databaseManager.clearAll()
            userPreferences.resetPreferences()
            withContext(ResonDispatchers.Main) {
                _uiState.update {
                    it.copy(
                        continueVideos = emptyList(),
                        feedVideos = emptyList(),
                        channelVideos = emptyList(),
                        followedChannels = emptySet(),
                        followedChannelList = emptyList(),
                        searchResults = emptyList(),
                        currentPlayingVideo = null
                    )
                }
                refreshDatabaseStats()
                onComplete()
            }
        }
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        Log.i("Reson:VM", "[$timestamp] $message")
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

    companion object {
        private const val PAGE_SIZE = 25
    }
}
