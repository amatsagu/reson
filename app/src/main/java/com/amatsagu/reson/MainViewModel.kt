package com.amatsagu.reson

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amatsagu.reson.data.SearchSuggestClient
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
    val ytDlpState: StateFlow<YtDlpState> = ytDlpManager.state

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var suggestJob: Job? = null

    init {
        addLog("App started. Initializing...")
        loadDefaultContent()

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
     * Toggles follow/unfollow status for a creator channel.
     */
    fun toggleFollowChannel(channel: Channel) {
        _uiState.update { current ->
            val isCurrentlyFollowed = current.followedChannels.contains(channel.id)
            val newFollowedIds = if (isCurrentlyFollowed) {
                addLog("Unfollowed channel: ${channel.title}")
                current.followedChannels - channel.id
            } else {
                addLog("Followed channel: ${channel.title}")
                current.followedChannels + channel.id
            }

            val newFollowedList = if (isCurrentlyFollowed) {
                current.followedChannelList.filter { it.id != channel.id }
            } else {
                current.followedChannelList + channel.copy(isFollowed = true)
            }

            // Sync follow state across search results
            val updatedSearchResults = current.searchResults.map { item ->
                if (item is SearchResultItem.Creator && item.channel.id == channel.id) {
                    SearchResultItem.Creator(item.channel.copy(isFollowed = !isCurrentlyFollowed))
                } else {
                    item
                }
            }

            current.copy(
                followedChannels = newFollowedIds,
                followedChannelList = newFollowedList,
                searchResults = updatedSearchResults
            )
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
