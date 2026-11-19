package com.amatsagu.reson

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI State for the Main Screen.
 * In Go terms, this is the view-model struct returned to the template/frontend.
 */
data class MainUiState(
    val urlInput: String = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
    val isInspecting: Boolean = false,
    val inspectedVideo: VideoInfo? = null,
    val errorMessage: String? = null,
    val diagnosticLogs: List<String> = emptyList()
)

/**
 * ViewModel orchestrating business logic between UI and YtDlpManager.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val ytDlpManager = YtDlpManager.getInstance(application)

    val ytDlpState: StateFlow<YtDlpState> = ytDlpManager.state

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        addLog("App initialized. Connecting to yt-dlp...")
        // Start observation
        viewModelScope.launch {
            ytDlpState.collect { state ->
                when (state) {
                    is YtDlpState.Uninitialized -> addLog("yt-dlp is uninitialized")
                    is YtDlpState.Initializing -> addLog("yt-dlp initializing runtime...")
                    is YtDlpState.Ready -> addLog("yt-dlp is ready (version: ${state.version})")
                    is YtDlpState.Updating -> addLog("yt-dlp updating to latest upstream release...")
                    is YtDlpState.Error -> addLog("yt-dlp error: ${state.message}")
                }
            }
        }
    }

    fun onUrlChange(newUrl: String) {
        _uiState.update { it.copy(urlInput = newUrl) }
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

    fun inspectPodcastOrVideo() {
        val url = _uiState.value.urlInput.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Please enter a valid URL") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isInspecting = true,
                    inspectedVideo = null,
                    errorMessage = null
                )
            }
            addLog("Inspecting URL: $url")

            val result = ytDlpManager.fetchVideoInfo(url, autoRetryWithUpdate = true)
            when (result) {
                is Result.Success -> {
                    addLog("Successfully extracted info: \"${result.data.title}\" by ${result.data.uploader}")
                    _uiState.update {
                        it.copy(
                            isInspecting = false,
                            inspectedVideo = result.data,
                            errorMessage = null
                        )
                    }
                }
                is Result.Failure -> {
                    addLog("Extraction error: ${result.message}")
                    _uiState.update {
                        it.copy(
                            isInspecting = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(diagnosticLogs = emptyList()) }
    }

    private fun addLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val formatted = "[$timestamp] $message"
        _uiState.update { current ->
            current.copy(diagnosticLogs = (current.diagnosticLogs + formatted).takeLast(100))
        }
    }
}
