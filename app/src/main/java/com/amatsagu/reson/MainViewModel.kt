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
import com.amatsagu.reson.data.remote.YouTubeRssFetcher
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
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    val lastFeedSyncedAt: Long = 0L,
    val lastChannelsSyncedAt: Long = 0L,

    // Library Content
    val historyVideos: List<Video> = emptyList(),
    val downloadedVideos: List<Video> = emptyList(),

    // Pagination & Infinite Scroll states
    val isFeedRefreshing: Boolean = false,
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

    // In-memory download queue & progress state
    val queuedDownloadIds: Set<String> = emptySet(),
    val downloadingProgressMap: Map<String, Float> = emptyMap(),
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

    private val userPreferences = UserPreferences.getInstance(application)
    val themeColor: StateFlow<ThemeColor> = userPreferences.themeColor
    val showRecommended: StateFlow<Boolean> = userPreferences.showRecommended
    val language: StateFlow<String> = userPreferences.language
    val totalListeningTimeMs: StateFlow<Long> = userPreferences.totalListeningTimeMs

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val audioPlayerManager: AudioPlayerManager = AudioPlayerManager(
        context = application,
        initialSpeed = 1.0f,
        onProgressUpdate = { video, positionMs, durationMs ->
            progressChannel.trySend(ProgressUpdate(video.id, positionMs, durationMs))
        },
        onListeningTimeDelta = { deltaMs, sync ->
            userPreferences.addListeningTime(deltaMs, sync)
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

    private val _databaseStats = MutableStateFlow<DatabaseStats?>(null)
    val databaseStats: StateFlow<DatabaseStats?> = _databaseStats.asStateFlow()

    // In-memory sequential download queue & active execution
    private val downloadQueueList = mutableListOf<Video>()
    private val activeVideoOperations = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private var activeDownloadJob: Job? = null
    private var activeDownloadVideo: Video? = null

    init {
        addLog("App started. Initializing...")

        _uiState.update {
            it.copy(
                lastFeedSyncedAt = userPreferences.lastFeedSyncTime.value,
                lastChannelsSyncedAt = userPreferences.lastChannelsSyncTime.value
            )
        }

        // Initial database stats load and clean missing offline files
        refreshDatabaseStats()
        viewModelScope.launch(ResonDispatchers.Database) {
            databaseManager.syncDownloadedState()
        }

        // Progress persistence single worker: consumes updates on bounded Database dispatcher
        viewModelScope.launch(ResonDispatchers.Database) {
            for (update in progressChannel) {
                databaseManager.savePlaybackProgress(update.videoId, update.positionMs, update.durationMs, notify = false)
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
            databaseManager.observeFeedVideos(limit = PAGE_SIZE).collect { videos ->
                _uiState.update { current ->
                    val continueIds = current.continueVideos.map { it.id }.toSet()
                    val historyIds = current.historyVideos.map { it.id }.toSet()
                    val excluded = continueIds + historyIds
                    val filtered = videos.filterNot { it.id in excluded }
                    current.copy(feedVideos = filtered)
                }
            }
        }

        // Background check: replenish discovery pool with fresh collaborator/similar-creator content
        viewModelScope.launch {
            replenishDiscoveryFeedIfNeeded(force = false)
        }

        // Observe uploads for the channels section (all followed channels within the last month)
        viewModelScope.launch {
            databaseManager.observeVideosFromFollowedWithinMonth(limit = PAGE_SIZE).collect { videos ->
                _uiState.update { current ->
                    if (current.selectedChannelId == null) {
                        current.copy(
                            channelVideos = videos,
                            canLoadMoreChannelVideos = videos.size >= PAGE_SIZE
                        )
                    } else {
                        current
                    }
                }
            }
        }

        // Observe continue listening items (in-progress videos) and ensure feed excludes them
        viewModelScope.launch {
            databaseManager.observeContinueListening().collect { continueVids ->
                _uiState.update { current ->
                    val continueIds = continueVids.map { it.id }.toSet()
                    val historyIds = current.historyVideos.map { it.id }.toSet()
                    val excluded = continueIds + historyIds
                    current.copy(
                        continueVideos = continueVids,
                        feedVideos = current.feedVideos.filterNot { it.id in excluded }
                    )
                }
            }
        }

        // Observe listening history and ensure feed excludes watched items
        viewModelScope.launch {
            databaseManager.observePlaybackHistory().collect { histVids ->
                _uiState.update { current ->
                    val historyIds = histVids.map { it.id }.toSet()
                    val continueIds = current.continueVideos.map { it.id }.toSet()
                    val excluded = continueIds + historyIds
                    current.copy(
                        historyVideos = histVids,
                        feedVideos = current.feedVideos.filterNot { it.id in excluded }
                    )
                }
            }
        }

        // Observe locally downloaded videos
        viewModelScope.launch {
            databaseManager.observeDownloadedVideos().collect { videos ->
                _uiState.update { it.copy(downloadedVideos = videos) }
            }
        }

        // Observe Audio Player playback state.
        // High-frequency position ticks are isolated to audioPlaybackState to maintain rock-solid 120 FPS
        // on the main screen and avoid unnecessary whole-screen recompositions during scrolling.
        viewModelScope.launch {
            audioPlayerManager.playbackState.collect { playback ->
                _playbackSpeed.value = playback.playbackSpeed
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
            // Concurrently query related videos and relevant channels (up to 8 channels to prevent premature cutoff)
            val videoDeferred = async { ytDlpManager.searchContent(trimmed, maxResults = 30) }
            val channelDeferred = async { ytDlpManager.searchChannels(trimmed, maxResults = 8) }

            val videoResult = videoDeferred.await()
            val channelResult = channelDeferred.await()

            when (videoResult) {
                is Result.Success -> {
                    val rawVideos = videoResult.data
                    val rawChannels = (if (channelResult is Result.Success) channelResult.data else emptyList()).toMutableList()

                    // Also harvest channel candidates directly from rawVideos if not already present in rawChannels
                    val existingChannelIds = rawChannels.map { it.id }.toMutableSet()
                    for (v in rawVideos) {
                        if (v.channelId.isNotBlank() && !existingChannelIds.contains(v.channelId) && v.channelTitle.isNotBlank()) {
                            val sim = YtDlpManager.calculateSimilarity(trimmed, v.channelTitle, "")
                            if (sim >= 0.60) {
                                existingChannelIds.add(v.channelId)
                                rawChannels.add(
                                    Channel(
                                        id = v.channelId,
                                        title = v.channelTitle
                                    )
                                )
                            }
                        }
                    }

                    val followed = _uiState.value.followedChannels

                    // Filter out random channels and rank by similarity, subs & activity on Default dispatcher
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
     * Filters the channels section:
     * - When channelId is null (All channels): shows all videos from all followed channels within the last 1 month, ordered by release date.
     * - When channelId is specified: shows all videos from that specific channel with full infinite scroll catalog.
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
                databaseManager.getVideosFromFollowedWithinMonth(limit = PAGE_SIZE, offset = 0)
            } else {
                val cached = databaseManager.getVideosForChannel(channelId, limit = PAGE_SIZE, offset = 0)
                if (cached.size < PAGE_SIZE) {
                    val ch = _uiState.value.followedChannelList.find { it.id == channelId }
                        ?: databaseManager.getFollowedChannels().find { it.id == channelId }
                    val result = withContext(ResonDispatchers.Network) {
                        ytDlpManager.fetchChannelVideos(
                            channelId = channelId,
                            handle = ch?.handle.orEmpty(),
                            channelTitle = ch?.title.orEmpty(),
                            startIndex = 1,
                            maxResults = PAGE_SIZE
                        )
                    }
                    if (result is Result.Success && result.data.isNotEmpty()) {
                        databaseManager.upsertVideos(result.data, defaultChannelId = channelId)
                        databaseManager.getVideosForChannel(channelId, limit = PAGE_SIZE, offset = 0)
                    } else {
                        cached
                    }
                } else {
                    cached
                }
            }
            val canLoadMore = if (channelId == null) {
                videos.size >= PAGE_SIZE
            } else {
                videos.isNotEmpty()
            }
            _uiState.update { it.copy(channelVideos = videos, canLoadMoreChannelVideos = canLoadMore) }
        }
    }

    /**
     * Pull-to-refresh for Feed: forces fresh discovery sampling from rotating followed creator seeds.
     */
    fun refreshFeed() {
        if (_uiState.value.isFeedRefreshing) return
        viewModelScope.launch {
            _uiState.update { it.copy(isFeedRefreshing = true) }
            replenishDiscoveryFeedIfNeeded(force = true)
            val continueIds = _uiState.value.continueVideos.map { it.id }.toSet()
            val historyIds = _uiState.value.historyVideos.map { it.id }.toSet()
            val freshFeed = databaseManager.getFeedVideos(
                limit = PAGE_SIZE,
                offset = 0,
                excludeIds = continueIds + historyIds
            )
            val now = System.currentTimeMillis()
            userPreferences.updateLastFeedSyncTime(now)
            _uiState.update {
                it.copy(
                    feedVideos = freshFeed,
                    isFeedRefreshing = false,
                    canLoadMoreFeed = freshFeed.size >= PAGE_SIZE,
                    lastFeedSyncedAt = now
                )
            }
        }
    }

    /**
     * Infinite scroll pagination: loads next page of feed videos using the anti-clustering 25%/75% mix.
     * Automatically filters out any items currently present in continue listening or history lists.
     * If the local recommended pool runs low, fetches the next batch of discovery videos from YouTube.
     */
    fun loadMoreFeedVideos() {
        val state = _uiState.value
        if (state.isFeedLoadingMore || !state.canLoadMoreFeed || state.feedVideos.isEmpty() || feedPaginationJob?.isActive == true) return

        feedPaginationJob = viewModelScope.launch(ResonDispatchers.Database) {
            _uiState.update { it.copy(isFeedLoadingMore = true) }
            val currentFeedIds = _uiState.value.feedVideos.map { it.id }.toSet()
            val continueIds = _uiState.value.continueVideos.map { it.id }.toSet()
            val historyIds = _uiState.value.historyVideos.map { it.id }.toSet()
            val excludeIds = currentFeedIds + continueIds + historyIds

            var nextBatch = databaseManager.getFeedVideos(limit = PAGE_SIZE, offset = currentFeedIds.size, excludeIds = excludeIds)

            // If local DB doesn't have enough videos to fill the requested page, replenish discovery from YouTube and re-query
            if (nextBatch.size < PAGE_SIZE) {
                replenishDiscoveryFeedIfNeeded(force = true)
                nextBatch = databaseManager.getFeedVideos(limit = PAGE_SIZE, offset = currentFeedIds.size, excludeIds = excludeIds)
            }

            _uiState.update { current ->
                val existingIds = current.feedVideos.map { it.id }.toSet()
                val newUnique = nextBatch.filter {
                    !existingIds.contains(it.id) && !continueIds.contains(it.id) && !historyIds.contains(it.id)
                }
                current.copy(
                    feedVideos = current.feedVideos + newUnique,
                    isFeedLoadingMore = false,
                    canLoadMoreFeed = newUnique.isNotEmpty()
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
     * - When viewing All Channels: loads next batch from DB cut off to 1 month.
     * - When viewing a specific channel: loads from DB; if DB is exhausted, fetches next windowed slice from YouTube.
     */
    fun loadMoreChannelVideos() {
        val state = _uiState.value
        if (state.isChannelLoadingMore || !state.canLoadMoreChannelVideos || state.channelVideos.isEmpty() || channelPaginationJob?.isActive == true) return

        channelPaginationJob = viewModelScope.launch(ResonDispatchers.Database) {
            _uiState.update { it.copy(isChannelLoadingMore = true) }
            val currentOffset = _uiState.value.channelVideos.size
            val selectedId = _uiState.value.selectedChannelId

            val nextBatch = if (selectedId == null) {
                databaseManager.getVideosFromFollowedWithinMonth(limit = PAGE_SIZE, offset = currentOffset)
            } else {
                var localBatch = databaseManager.getVideosForChannel(selectedId, limit = PAGE_SIZE, offset = currentOffset)
                if (localBatch.isEmpty()) {
                    // Fetch next page directly from YouTube via yt-dlp windowed pagination
                    val ch = _uiState.value.followedChannelList.find { it.id == selectedId }
                        ?: databaseManager.getFollowedChannels().find { it.id == selectedId }
                    val result = withContext(ResonDispatchers.Network) {
                        ytDlpManager.fetchChannelVideos(
                            channelId = selectedId,
                            handle = ch?.handle.orEmpty(),
                            channelTitle = ch?.title.orEmpty(),
                            startIndex = currentOffset + 1,
                            maxResults = PAGE_SIZE
                        )
                    }
                    if (result is Result.Success && result.data.isNotEmpty()) {
                        databaseManager.upsertVideos(result.data, defaultChannelId = selectedId)
                        localBatch = databaseManager.getVideosForChannel(selectedId, limit = PAGE_SIZE, offset = currentOffset)
                        if (localBatch.isEmpty()) {
                            result.data
                        } else {
                            localBatch
                        }
                    } else {
                        emptyList()
                    }
                } else {
                    localBatch
                }
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

            // 2. Fetch latest videos for this channel via yt-dlp (up to 20 to capture recent uploads)
            val result = ytDlpManager.fetchChannelVideos(
                channelId = channel.id,
                handle = channel.handle,
                channelTitle = channel.title,
                maxResults = 20
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
     * Syncs followed channels:
     * - Uses 15-minute smart cache TTL: skips network requests for any channel synced within 15 minutes unless [force] is true.
     * - Uses fast lightweight YouTube Atom RSS feed (~150ms per channel) with graceful yt-dlp fallback.
     * - Uses bounded concurrency (Semaphore of 3) to execute in parallel while strictly preventing rate limits.
     * - Updates SQLite last_sync_at per channel and global sync timestamps.
     */
    fun syncFollowedChannels(force: Boolean = false) {
        if (_uiState.value.isSyncingChannels) return

        viewModelScope.launch {
            val channels = databaseManager.getFollowedChannels()
            if (channels.isEmpty()) return@launch

            val now = System.currentTimeMillis()
            val ttlMillis = 15 * 60 * 1000L // 15-minute smart cache TTL

            val channelsToSync = if (force) {
                channels
            } else {
                channels.filter { (now - it.lastSyncAt) > ttlMillis }
            }

            if (channelsToSync.isEmpty()) {
                addLog("All ${channels.size} followed channels are fresh within TTL (15m). Skipping network sync.")
                return@launch
            }

            _uiState.update { it.copy(isSyncingChannels = true) }
            addLog("Checking updates for ${channelsToSync.size} followed channels (parallel RSS)...")

            val semaphore = Semaphore(3)

            withContext(ResonDispatchers.Network) {
                val jobs = channelsToSync.map { ch ->
                    async {
                        semaphore.withPermit {
                            // Ensure avatar is cached
                            if (ch.avatarBase64.isBlank() && ch.avatarUrl.isNotBlank()) {
                                val base64 = AvatarManager.downloadAvatarAsBase64(ch.avatarUrl)
                                if (!base64.isNullOrBlank()) {
                                    databaseManager.updateChannelAvatarBase64(ch.id, base64)
                                }
                            }

                            // 1. Attempt ultra-fast lightweight RSS fetch (~150ms)
                            var videos: List<Video>? = null
                            if (!YouTubeRssFetcher.isRateLimited() && ch.id.startsWith("UC")) {
                                val rssResult = YouTubeRssFetcher.fetchChannelVideos(ch.id, ch.title)
                                if (rssResult is Result.Success && rssResult.data.isNotEmpty()) {
                                    videos = rssResult.data
                                }
                            }

                            // 2. Fallback to yt-dlp if RSS was empty or failed
                            if (videos.isNullOrEmpty()) {
                                val ytResult = ytDlpManager.fetchChannelVideos(
                                    channelId = ch.id,
                                    handle = ch.handle,
                                    channelTitle = ch.title,
                                    maxResults = 20
                                )
                                if (ytResult is Result.Success) {
                                    videos = ytResult.data
                                }
                            }

                            if (!videos.isNullOrEmpty()) {
                                databaseManager.upsertVideos(videos, defaultChannelId = ch.id)
                            }
                            databaseManager.updateChannelLastSyncTime(ch.id, System.currentTimeMillis())

                            // Polite 40ms inter-channel delay to avoid burst patterns
                            delay(40)
                        }
                    }
                }
                jobs.awaitAll()
            }

            // Also replenish discovery recommendations based on followed channels
            replenishDiscoveryFeedIfNeeded(force = force)

            val finishedTime = System.currentTimeMillis()
            userPreferences.updateLastChannelsSyncTime(finishedTime)
            userPreferences.updateLastFeedSyncTime(finishedTime)

            _uiState.update {
                it.copy(
                    isSyncingChannels = false,
                    lastChannelsSyncedAt = finishedTime,
                    lastFeedSyncedAt = finishedTime
                )
            }
            addLog("Channels sync completed for ${channelsToSync.size} channels.")
        }
    }

    private var discoverySeedIndex = 0
    private var discoveryVariant = 0

    /**
     * Replenishes the discovery video pool in SQLite using rotating seeds from followed channels.
     * Searches for collaborations, podcasts, interviews, and peer creator topics to discover
     * recommended videos from other channels in the same ecosystem.
     */
    suspend fun replenishDiscoveryFeedIfNeeded(force: Boolean = false) = withContext(ResonDispatchers.Database) {
        val unplayedDiscoveryCount = databaseManager.getRecommendedDiscoveryVideos(limit = 40, offset = 0).size
        if (!force && unplayedDiscoveryCount >= 30) return@withContext

        val followedChannels = databaseManager.getFollowedChannels()
        if (followedChannels.isEmpty()) return@withContext

        val seed1 = followedChannels[discoverySeedIndex % followedChannels.size]
        val seed2 = if (followedChannels.size > 1) {
            followedChannels[(discoverySeedIndex + 1) % followedChannels.size]
        } else {
            seed1
        }
        discoverySeedIndex = (discoverySeedIndex + 1) % followedChannels.size
        val currentVariant = discoveryVariant++

        // Generate smart discovery search queries that return OTHER creators
        val query1: String
        val query2: String

        if (seed1.id != seed2.id) {
            // Multiple followed channels: sample collaborators / podcasts across both
            query1 = if (currentVariant % 2 == 0) "${seed1.title} collab" else "${seed1.title} podcast"
            query2 = if (currentVariant % 2 == 0) "${seed2.title} podcast" else "${seed2.title} collab"
        } else {
            // Single followed channel: diversify between collabs, podcasts, and video topic queries
            when (currentVariant % 3) {
                0 -> {
                    query1 = "${seed1.title} collab"
                    query2 = "${seed1.title} podcast"
                }
                1 -> {
                    // Extract topic words from a recent upload to find peer creators covering same subject
                    val seedVideos = databaseManager.getVideosForChannel(seed1.id, limit = 5, offset = 0)
                    val sampleVideo = seedVideos.firstOrNull { it.title.length > 5 }
                    val cleanTopic = if (sampleVideo != null) {
                        sampleVideo.title
                            .replace(Regex("(?i)\\b${Regex.escape(seed1.title)}\\b"), "")
                            .replace(Regex("[^a-zA-Z0-9 ]"), " ")
                            .trim()
                            .split(Regex("\\s+"))
                            .filter { it.length > 2 }
                            .take(4)
                            .joinToString(" ")
                    } else ""
                    query1 = cleanTopic.ifEmpty { "${seed1.title} interview" }
                    query2 = "${seed1.title} podcast"
                }
                else -> {
                    query1 = "${seed1.title} interview"
                    query2 = "${seed1.title} with"
                }
            }
        }

        withContext(ResonDispatchers.Network) {
            val followedIds = followedChannels.map { it.id }.toSet()
            val followedTitles = followedChannels.map { it.title.lowercase().trim() }.toSet()

            val d1 = async {
                ytDlpManager.searchContent(query1, maxResults = 20, priority = TaskPriority.BACKGROUND)
            }
            val d2 = async {
                ytDlpManager.searchContent(query2, maxResults = 20, priority = TaskPriority.BACKGROUND)
            }

            val res1 = (d1.await() as? Result.Success)?.data.orEmpty()
            val res2 = (d2.await() as? Result.Success)?.data.orEmpty()

            val candidates = (res1 + res2).filterNot {
                it.channelId in followedIds ||
                it.channelTitle.lowercase().trim() in followedTitles ||
                YtDlpManager.isLockedVideoTitle(it.title)
            }

            if (candidates.isNotEmpty()) {
                databaseManager.upsertVideos(candidates)
                addLog("Stocked ${candidates.size} new discovery videos from queries: '$query1', '$query2'")
            }
        }
    }

    /**
     * Pull-to-refresh for the Channels section:
     * - In "All Channels" view: syncs all followed channels and caches new uploads.
     * - In specific channel view: fetches the latest uploads for that specific channel from YouTube.
     */
    fun refreshChannelsSection() {
        val selectedId = _uiState.value.selectedChannelId
        if (selectedId == null) {
            syncFollowedChannels(force = true)
        } else {
            viewModelScope.launch {
                _uiState.update { it.copy(isSyncingChannels = true) }
                val ch = _uiState.value.followedChannelList.find { it.id == selectedId }
                    ?: databaseManager.getFollowedChannels().find { it.id == selectedId }

                // Try fast RSS first (~150ms), fallback to yt-dlp
                var newVideos: List<Video>? = null
                if (!YouTubeRssFetcher.isRateLimited() && selectedId.startsWith("UC")) {
                    val rssResult = YouTubeRssFetcher.fetchChannelVideos(selectedId, ch?.title.orEmpty())
                    if (rssResult is Result.Success && rssResult.data.isNotEmpty()) {
                        newVideos = rssResult.data
                    }
                }

                if (newVideos.isNullOrEmpty()) {
                    val result = withContext(ResonDispatchers.Network) {
                        ytDlpManager.fetchChannelVideos(
                            channelId = selectedId,
                            handle = ch?.handle.orEmpty(),
                            channelTitle = ch?.title.orEmpty(),
                            startIndex = 1,
                            maxResults = PAGE_SIZE
                        )
                    }
                    if (result is Result.Success) {
                        newVideos = result.data
                    }
                }

                if (!newVideos.isNullOrEmpty()) {
                    databaseManager.upsertVideos(newVideos, defaultChannelId = selectedId)
                    databaseManager.updateChannelLastSyncTime(selectedId, System.currentTimeMillis())
                    val updated = databaseManager.getVideosForChannel(selectedId, limit = PAGE_SIZE, offset = 0)
                    val now = System.currentTimeMillis()
                    userPreferences.updateLastChannelsSyncTime(now)
                    _uiState.update {
                        it.copy(
                            channelVideos = updated,
                            canLoadMoreChannelVideos = updated.size >= PAGE_SIZE,
                            lastChannelsSyncedAt = now
                        )
                    }
                }
                _uiState.update { it.copy(isSyncingChannels = false) }
            }
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

        // 1. Check if video has a local downloaded file on disk -> PRIORITIZE INSTANT LOCAL PLAYBACK
        val localFile = databaseManager.getDownloadedFile(video.id)
        if (localFile != null && localFile.exists() && localFile.length() > 0) {
            addLog("Prioritizing local offline audio for: ${video.title} (${localFile.length() / 1024} KB)")
            val localVideo = video.copy(
                isDownloaded = true,
                downloadFilePath = localFile.absolutePath,
                playbackDurationMs = effectiveDuration
            )
            _uiState.update {
                it.copy(
                    currentPlayingVideo = localVideo,
                    isPlaying = false,
                    isAudioBuffering = true,
                    playbackPositionMs = video.playbackPositionMs,
                    playbackDurationMs = effectiveDuration,
                    playbackProgress = video.playbackProgress,
                    networkHintMessage = null,
                    playbackErrorMessage = null
                )
            }
            viewModelScope.launch(ResonDispatchers.Database) {
                databaseManager.upsertVideo(localVideo)
            }
            audioPlayerManager.play(
                video = localVideo,
                mediaUri = android.net.Uri.fromFile(localFile).toString(),
                isLocal = true,
                initialPositionMs = video.playbackPositionMs
            )
            return
        }

        // 2. Otherwise proceed with online streaming extraction
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
                        mediaUri = streamResult.data,
                        isLocal = false,
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

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        audioPlayerManager.setPlaybackSpeed(speed)
    }

    fun closePlayer() {
        playJob?.cancel()
        playWatchdogJob?.cancel()
        audioPlayerManager.stop()
        _playbackSpeed.value = 1.0f
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

    fun flushPlaybackProgressAndListeningTime(sync: Boolean = true) {
        audioPlayerManager.flushListeningTime(sync = sync)
        audioPlayerManager.saveCurrentProgress()
    }

    override fun onCleared() {
        super.onCleared()
        flushPlaybackProgressAndListeningTime(sync = true)
        progressChannel.close()
        activeDownloadJob?.cancel()
        activeDownloadVideo?.let { ytDlpManager.cancelDownload(it.id) }
        synchronized(downloadQueueList) {
            downloadQueueList.clear()
        }
        audioPlayerManager.release()
    }

    fun downloadVideo(video: Video) {
        val videoId = video.id

        // 1. If currently downloading, cancel it and trigger next in queue
        if (activeDownloadVideo?.id == videoId) {
            addLog("Cancelling active download: ${video.title}")
            activeDownloadJob?.cancel()
            ytDlpManager.cancelDownload(videoId)
            _uiState.update {
                it.copy(downloadingProgressMap = it.downloadingProgressMap - videoId)
            }
            activeDownloadJob = null
            activeDownloadVideo = null
            activeVideoOperations.remove(videoId)
            triggerNextDownload()
            return
        }

        // 2. If waiting in the in-memory queue, remove it immediately
        synchronized(downloadQueueList) {
            val removed = downloadQueueList.removeAll { it.id == videoId }
            if (removed) {
                _uiState.update {
                    it.copy(queuedDownloadIds = it.queuedDownloadIds - videoId)
                }
                activeVideoOperations.remove(videoId)
                addLog("Removed from download queue: ${video.title}")
                return
            }
        }

        // Debounce spam clicks / concurrent operations on the same video
        if (activeVideoOperations.contains(videoId)) return

        // 3. If already downloaded, delete the offline file from disk and update DB
        if (video.isDownloaded || databaseManager.getDownloadedFile(videoId) != null) {
            activeVideoOperations.add(videoId)
            // Immediately update in-memory state across all lists for 0ms visual latency
            _uiState.update { current ->
                current.copy(
                    downloadedVideos = current.downloadedVideos.filter { it.id != videoId },
                    continueVideos = current.continueVideos.map { if (it.id == videoId) it.copy(isDownloaded = false, downloadFilePath = null) else it },
                    feedVideos = current.feedVideos.map { if (it.id == videoId) it.copy(isDownloaded = false, downloadFilePath = null) else it },
                    channelVideos = current.channelVideos.map { if (it.id == videoId) it.copy(isDownloaded = false, downloadFilePath = null) else it },
                    historyVideos = current.historyVideos.map { if (it.id == videoId) it.copy(isDownloaded = false, downloadFilePath = null) else it },
                    searchResults = current.searchResults.map { item ->
                        if (item is SearchResultItem.Video && item.video.id == videoId) {
                            SearchResultItem.Video(item.video.copy(isDownloaded = false, downloadFilePath = null))
                        } else item
                    },
                    currentPlayingVideo = if (current.currentPlayingVideo?.id == videoId) {
                        current.currentPlayingVideo.copy(isDownloaded = false, downloadFilePath = null)
                    } else current.currentPlayingVideo
                )
            }
            viewModelScope.launch(ResonDispatchers.Database) {
                try {
                    databaseManager.deleteDownloadedFile(videoId)
                    addLog("Deleted offline download for: ${video.title}")
                    refreshDatabaseStats()
                } finally {
                    activeVideoOperations.remove(videoId)
                }
            }
            return
        }

        // 4. Otherwise enqueue or start download immediately
        activeVideoOperations.add(videoId)
        synchronized(downloadQueueList) {
            if (activeDownloadVideo == null) {
                startDownload(video)
            } else {
                if (!downloadQueueList.any { it.id == videoId }) {
                    downloadQueueList.add(video)
                    _uiState.update {
                        it.copy(queuedDownloadIds = it.queuedDownloadIds + videoId)
                    }
                    addLog("Queued for download (#${downloadQueueList.size}): ${video.title}")
                }
                activeVideoOperations.remove(videoId)
            }
        }
    }

    private fun startDownload(video: Video) {
        val videoId = video.id
        activeDownloadVideo = video
        activeVideoOperations.add(videoId)
        // Immediately give clear feedback: 0.01f progress
        _uiState.update {
            it.copy(
                queuedDownloadIds = it.queuedDownloadIds - videoId,
                downloadingProgressMap = it.downloadingProgressMap + (videoId to 0.01f)
            )
        }
        addLog("Starting download: ${video.title}...")

        activeDownloadJob = viewModelScope.launch(ResonDispatchers.Network) {
            try {
                val result = ytDlpManager.downloadAudio(videoId) { progress ->
                    _uiState.update {
                        it.copy(downloadingProgressMap = it.downloadingProgressMap + (videoId to progress))
                    }
                }
                when (result) {
                    is Result.Success -> {
                        val file = result.data
                        val updatedVideo = video.copy(isDownloaded = true, downloadFilePath = file.absolutePath)
                        // ONLY commit to SQLite upon complete, verified disk write
                        databaseManager.upsertVideo(updatedVideo)
                        databaseManager.updateDownloadStatus(videoId, isDownloaded = true, filePath = file.absolutePath)
                        // Optimistically update in-memory lists so trash icon immediately appears everywhere
                        _uiState.update { current ->
                            val existsInDownloaded = current.downloadedVideos.any { it.id == videoId }
                            val newDownloadedList = if (existsInDownloaded) {
                                current.downloadedVideos.map { if (it.id == videoId) updatedVideo else it }
                            } else {
                                listOf(updatedVideo) + current.downloadedVideos
                            }
                            current.copy(
                                downloadedVideos = newDownloadedList,
                                continueVideos = current.continueVideos.map { if (it.id == videoId) updatedVideo else it },
                                feedVideos = current.feedVideos.map { if (it.id == videoId) updatedVideo else it },
                                channelVideos = current.channelVideos.map { if (it.id == videoId) updatedVideo else it },
                                historyVideos = current.historyVideos.map { if (it.id == videoId) updatedVideo else it },
                                searchResults = current.searchResults.map { item ->
                                    if (item is SearchResultItem.Video && item.video.id == videoId) {
                                        SearchResultItem.Video(updatedVideo)
                                    } else item
                                },
                                currentPlayingVideo = if (current.currentPlayingVideo?.id == videoId) updatedVideo else current.currentPlayingVideo
                            )
                        }
                        addLog("Downloaded ${video.title} (${file.length() / 1024} KB)")
                        refreshDatabaseStats()
                    }
                    is Result.Failure -> {
                        addLog("Download failed for ${video.title}: ${result.message}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                addLog("Download cancelled: ${video.title}")
                ytDlpManager.cancelDownload(videoId)
                throw e
            } catch (e: Throwable) {
                addLog("Download error for ${video.title}: ${e.message}")
            } finally {
                _uiState.update {
                    it.copy(downloadingProgressMap = it.downloadingProgressMap - videoId)
                }
                activeDownloadVideo = null
                activeDownloadJob = null
                activeVideoOperations.remove(videoId)
                triggerNextDownload()
            }
        }
    }

    private fun triggerNextDownload() {
        if (activeDownloadVideo != null) return
        val nextVideo = synchronized(downloadQueueList) {
            if (downloadQueueList.isNotEmpty()) downloadQueueList.removeAt(0) else null
        }
        if (nextVideo != null) {
            startDownload(nextVideo)
        }
    }

    fun clearPlaybackHistory() {
        _uiState.update { current ->
            current.copy(
                historyVideos = emptyList(),
                continueVideos = emptyList(),
                feedVideos = current.feedVideos.map { v -> v.copy(playbackProgress = 0f, playbackPositionMs = 0L) },
                channelVideos = current.channelVideos.map { v -> v.copy(playbackProgress = 0f, playbackPositionMs = 0L) }
            )
        }
        viewModelScope.launch(ResonDispatchers.Database) {
            databaseManager.clearPlaybackHistory()
            refreshDatabaseStats()
            addLog("Cleared listening history.")
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
            withContext(ResonDispatchers.Database) {
                databaseManager.syncDownloadedState()
            }
            refreshDatabaseStats()
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
