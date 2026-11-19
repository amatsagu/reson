package com.amatsagu.reson

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.amatsagu.reson.data.SearchSuggestClient
import com.amatsagu.reson.model.Episode
import com.amatsagu.reson.model.ResonTab
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import kotlinx.coroutines.Job
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
    val searchResults: List<Episode> = emptyList(),
    val isSearching: Boolean = false,
    @StringRes val searchErrorRes: Int? = null,
    val searchErrorArg: String? = null,

    // Feed / Home Content
    val continueEpisodes: List<Episode> = emptyList(),
    val feedEpisodes: List<Episode> = emptyList(),

    // Mini Player State
    val currentPlayingEpisode: Episode? = null,
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
     * Executes YouTube fast search for top 30 episodes.
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

        addLog("Executing fast search for: '$trimmed'")

        viewModelScope.launch {
            when (val result = ytDlpManager.searchContent(trimmed, maxResults = 30)) {
                is Result.Success -> {
                    addLog("Search completed. Found ${result.data.size} episodes.")
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchResults = result.data,
                            searchErrorRes = if (result.data.isEmpty()) R.string.search_no_results else null,
                            searchErrorArg = if (result.data.isEmpty()) trimmed else null
                        )
                    }
                }
                is Result.Failure -> {
                    addLog("Search failed: ${result.message}")
                    _uiState.update {
                        it.copy(
                            isSearching = false,
                            searchErrorRes = R.string.search_failed,
                            searchErrorArg = result.message
                        )
                    }
                }
            }
        }
    }

    fun playEpisode(episode: Episode) {
        addLog("Playing episode: ${episode.title}")
        _uiState.update {
            it.copy(
                currentPlayingEpisode = episode,
                isPlaying = true
            )
        }
    }

    fun togglePlayPause() {
        _uiState.update { current ->
            current.copy(isPlaying = !current.isPlaying)
        }
    }

    fun downloadEpisode(episode: Episode) {
        addLog("Download requested for: ${episode.title}")
        _uiState.update { current ->
            val updatedFeed = current.feedEpisodes.map {
                if (it.id == episode.id) it.copy(downloadProgress = 0.5f) else it
            }
            val updatedSearch = current.searchResults.map {
                if (it.id == episode.id) it.copy(downloadProgress = 0.5f) else it
            }
            current.copy(feedEpisodes = updatedFeed, searchResults = updatedSearch)
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
     * Seeds initial feed and continue items matching the design mockup.
     */
    private fun loadDefaultContent() {
        val continueList = listOf(
            Episode(
                id = "mock_c1",
                title = "Reson\nEpisode 5",
                channelTitle = "Channel Reson",
                durationText = "45m",
                durationSeconds = 2700,
                releaseDateText = "27.11.26",
                playbackProgress = 0.65f
            ),
            Episode(
                id = "mock_c2",
                title = "Reson\nEpisode 4",
                channelTitle = "Channel Reson",
                durationText = "45m",
                durationSeconds = 2700,
                releaseDateText = "27.11.26",
                playbackProgress = 0.35f
            )
        )

        val feedList = listOf(
            Episode(
                id = "mock_e5",
                title = "Reson\nEpisode 5",
                channelTitle = "Channel Reson",
                durationText = "45m",
                durationSeconds = 2700,
                releaseDateText = "27.11.26",
                isDownloaded = false
            ),
            Episode(
                id = "mock_e4",
                title = "Reson\nEpisode 5",
                channelTitle = "Channel Reson",
                durationText = "45m",
                durationSeconds = 2700,
                releaseDateText = "27.11.26",
                downloadProgress = 0.75f
            ),
            Episode(
                id = "mock_e3",
                title = "Reson\nEpisode • 3",
                channelTitle = "Channel Reson",
                durationText = "45m",
                durationSeconds = 2700,
                releaseDateText = "27.11.26",
                isDownloaded = false
            ),
            Episode(
                id = "mock_e2",
                title = "Reson\nEpisode 2",
                channelTitle = "Channel Reson",
                durationText = "52m",
                durationSeconds = 3120,
                releaseDateText = "27.11.26",
                isDownloaded = true
            )
        )

        _uiState.update {
            it.copy(
                continueEpisodes = continueList,
                feedEpisodes = feedList,
                currentPlayingEpisode = continueList.first()
            )
        }
    }
}
