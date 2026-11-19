package com.amatsagu.reson

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * State representing the lifecycle and health of yt-dlp.
 * In Go terms, think of this as an enum/tagged union representing current engine health.
 */
sealed interface YtDlpState {
    data object Uninitialized : YtDlpState
    data object Initializing : YtDlpState
    data class Ready(val version: String, val statusMessage: String? = null) : YtDlpState
    data class Updating(val currentVersion: String, val message: String = "Downloading latest yt-dlp...") : YtDlpState
    data class Error(val message: String, val canRecover: Boolean = true) : YtDlpState
}

/**
 * Result wrapper for yt-dlp operations (like Go's `(result, error)` tuple).
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: Throwable, val message: String) : Result<Nothing>()
}

/**
 * Singleton manager for yt-dlp operations on Android.
 * Handles initialization, self-patching (auto-updates), self-recovery on corruption,
 * and command execution.
 */
class YtDlpManager private constructor(private val appContext: Context) {

    private val _state = MutableStateFlow<YtDlpState>(YtDlpState.Uninitialized)
    val state: StateFlow<YtDlpState> = _state.asStateFlow()

    private val mutex = Mutex()

    companion object {
        private const val TAG = "Reson:YtDlp"

        @Volatile
        private var instance: YtDlpManager? = null

        fun getInstance(context: Context): YtDlpManager {
            return instance ?: synchronized(this) {
                instance ?: YtDlpManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Formats release date into compact relative time to save space:
         * e.g. "57m ago", "1d3h ago", "3d ago", "2w ago", "4mo ago", "2y ago".
         */
        fun formatRelativeDate(timestampSec: Long, rawUploadDate: String = ""): String {
            val nowSec = System.currentTimeMillis() / 1000L
            val targetSec = if (timestampSec > 0) {
                timestampSec
            } else if (rawUploadDate.length == 8 && rawUploadDate.all { it.isDigit() }) {
                try {
                    val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    val parsed = sdf.parse(rawUploadDate)
                    parsed?.time?.div(1000L) ?: 0L
                } catch (_: Exception) {
                    0L
                }
            } else {
                0L
            }

            if (targetSec <= 0L) {
                return if (rawUploadDate.isNotBlank()) rawUploadDate else ""
            }

            val diffSec = maxOf(0L, nowSec - targetSec)
            return formatRelativeSeconds(diffSec)
        }

        fun formatRelativeSeconds(diffSec: Long): String {
            if (diffSec < 60) return "just now"
            if (diffSec < 3600) {
                val m = diffSec / 60
                return "${m}m ago"
            }
            if (diffSec < 86400) {
                val h = diffSec / 3600
                val remM = (diffSec % 3600) / 60
                return if (remM > 0 && h < 6) "${h}h${remM}m ago" else "${h}h ago"
            }
            val days = diffSec / 86400
            val remH = (diffSec % 86400) / 3600
            if (days < 7) {
                return if (remH > 0) "${days}d${remH}h ago" else "${days}d ago"
            }
            if (days < 30) {
                val w = days / 7
                val remD = days % 7
                return if (remD > 0) "${w}w${remD}d ago" else "${w}w ago"
            }
            if (days < 365) {
                val mo = days / 30
                return "${mo}mo ago"
            }
            val y = days / 365
            val remMo = (days % 365) / 30
            return if (remMo > 0 && y < 3) "${y}y${remMo}mo ago" else "${y}y ago"
        }
    }

    /**
     * Initializes yt-dlp and FFmpeg. If initialization fails (e.g., file corruption from an interrupted
     * write/update), it performs an automatic self-recovery by wiping the corrupted binaries and re-extracting.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (_state.value is YtDlpState.Ready) {
                return@withContext true
            }

            _state.value = YtDlpState.Initializing
            Log.i(TAG, "Initializing yt-dlp...")

            try {
                // Initialize core yt-dlp binaries
                YoutubeDL.getInstance().init(appContext)

                // Initialize FFmpeg (for audio postprocessing / remuxing)
                try {
                    FFmpeg.getInstance().init(appContext)
                    Log.i(TAG, "FFmpeg initialized successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "FFmpeg initialization warning: ${e.message}")
                }

                val version = getVersionName()
                _state.value = YtDlpState.Ready(version = version, statusMessage = "yt-dlp is ready ($version)")
                Log.i(TAG, "yt-dlp initialized successfully, version: $version")
                true
            } catch (e: Throwable) {
                Log.e(TAG, "yt-dlp initialization failed. Attempting self-recovery...", e)
                performSelfRecovery()
            }
        }
    }

    /**
     * Self-recovery routine:
     * Wipes any corrupted or partially-downloaded binaries in app's internal storage
     * and attempts a clean re-initialization from the APK's bundled assets.
     */
    suspend fun performSelfRecovery(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.w(TAG, "Starting self-recovery: cleaning up binaries in filesDir...")
            _state.value = YtDlpState.Initializing

            // Clean directories where youtubedl-android extracts binaries
            val filesDir = appContext.filesDir
            val targets = listOf(
                File(filesDir, "youtubedl"),
                File(filesDir, "no-pie"),
                File(filesDir, "bin"),
                File(appContext.cacheDir, "youtubedl")
            )

            for (target in targets) {
                if (target.exists()) {
                    target.deleteRecursively()
                    Log.d(TAG, "Deleted: ${target.absolutePath}")
                }
            }

            // Retry initialization with a clean slate
            YoutubeDL.getInstance().init(appContext)
            try {
                FFmpeg.getInstance().init(appContext)
            } catch (_: Exception) {}

            val version = getVersionName()
            _state.value = YtDlpState.Ready(
                version = version,
                statusMessage = "Recovered successfully. Re-initialized to $version"
            )
            Log.i(TAG, "Self-recovery succeeded! Current version: $version")
            true
        } catch (recoveryError: Throwable) {
            val msg = "Self-recovery failed: ${recoveryError.message ?: "Unknown error"}"
            Log.e(TAG, msg, recoveryError)
            _state.value = YtDlpState.Error(message = msg, canRecover = true)
            false
        }
    }

    /**
     * Self-patching / Updates yt-dlp to the latest release directly from upstream GitHub releases.
     * Can target STABLE or MASTER channels.
     */
    suspend fun update(channel: UpdateChannel = UpdateChannel.STABLE): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val currentVersion = getVersionName()
            _state.value = YtDlpState.Updating(currentVersion = currentVersion)
            Log.i(TAG, "Checking for yt-dlp updates (channel: $channel)...")

            try {
                val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
                val newVersion = getVersionName()

                val message = when (updateStatus) {
                    UpdateStatus.DONE -> "Successfully updated yt-dlp from $currentVersion to $newVersion"
                    UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date ($newVersion)"
                    null -> "Update status unknown. Current version: $newVersion"
                }

                Log.i(TAG, message)
                _state.value = YtDlpState.Ready(version = newVersion, statusMessage = message)
                Result.Success(message)
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Failed to update yt-dlp: ${e.message}", e)
                // If update fails, do not brick the app — keep the existing working version if possible
                val fallbackVersion = getVersionName()
                val errorMsg = "Update failed: ${e.message}. Remaining on $fallbackVersion"
                _state.value = YtDlpState.Ready(version = fallbackVersion, statusMessage = errorMsg)
                Result.Failure(e, errorMsg)
            } catch (e: Throwable) {
                val errorMsg = "Unexpected error during update: ${e.message}"
                Log.e(TAG, errorMsg, e)
                _state.value = YtDlpState.Error(message = errorMsg, canRecover = true)
                Result.Failure(e, errorMsg)
            }
        }
    }

    /**
     * Fetches metadata for a given YouTube URL (podcast episode, playlist, or video).
     * If an extraction error occurs (which commonly happens when YouTube alters player APIs),
     * it can automatically trigger self-patching and retry once.
     */
    suspend fun fetchVideoInfo(url: String, autoRetryWithUpdate: Boolean = true): Result<VideoInfo> = withContext(Dispatchers.IO) {
        try {
            ensureReady()
            val info = YoutubeDL.getInstance().getInfo(url)
            Result.Success(info)
        } catch (e: YoutubeDLException) {
            Log.w(TAG, "yt-dlp extraction error for URL: $url: ${e.message}")
            if (autoRetryWithUpdate) {
                Log.i(TAG, "Attempting auto-patch update and retry...")
                val updateResult = update(UpdateChannel.STABLE)
                if (updateResult is Result.Success) {
                    return@withContext try {
                        val retryInfo = YoutubeDL.getInstance().getInfo(url)
                        Result.Success(retryInfo)
                    } catch (retryError: Throwable) {
                        Result.Failure(retryError, "Extraction failed even after updating: ${retryError.message}")
                    }
                }
            }
            Result.Failure(e, e.message ?: "Failed to fetch video information")
        } catch (e: Throwable) {
            Result.Failure(e, e.message ?: "Unexpected error")
        }
    }

    /**
     * Executes custom yt-dlp arguments and returns the raw response.
     */
    suspend fun executeRaw(request: YoutubeDLRequest): Result<YoutubeDLResponse> = withContext(Dispatchers.IO) {
        try {
            ensureReady()
            val response = YoutubeDL.getInstance().execute(request)
            Result.Success(response)
        } catch (e: Throwable) {
            Result.Failure(e, e.message ?: "Command execution failed")
        }
    }

    /**
     * Performs a fast search using yt-dlp with --flat-playlist and Android player client.
     * Returns a list of parsed Episode items (up to maxResults).
     */
    suspend fun searchContent(
        query: String,
        maxResults: Int = 30
    ): Result<List<com.amatsagu.reson.model.Episode>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Success(emptyList())

        try {
            ensureReady()
            val isDirectUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true)

            val searchTarget = if (isDirectUrl) trimmed else "ytsearch$maxResults:$trimmed"

            val request = YoutubeDLRequest(searchTarget).apply {
                addOption("--flat-playlist")
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--no-warnings")
                addOption("--extractor-args", "youtubetab:approximate_date;youtube:player_client=android")
            }

            Log.i(TAG, "Executing fast search for '$trimmed' (target: $searchTarget)")
            val response = YoutubeDL.getInstance().execute(request)
            val episodes = mutableListOf<com.amatsagu.reson.model.Episode>()

            for (line in response.out.lines()) {
                val jsonLine = line.trim()
                if (jsonLine.isEmpty() || !jsonLine.startsWith("{")) continue
                try {
                    val json = org.json.JSONObject(jsonLine)
                    val id = json.optString("id", "")
                    if (id.isEmpty()) continue

                    val title = json.optString("title", "Untitled")
                    val uploader = json.optString("uploader").ifEmpty {
                        json.optString("channel", "Unknown Channel")
                    }
                    val duration = json.optInt("duration", 0)
                    val timestamp = json.optLong("timestamp", 0L)
                    val rawDate = json.optString("upload_date", "")
                    val releaseDateText = formatRelativeDate(timestamp, rawDate)

                    var thumb = json.optString("thumbnail", "")
                    if (thumb.isEmpty() && id.isNotEmpty()) {
                        thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
                    }
                    val webpageUrl = if (isDirectUrl) trimmed else "https://www.youtube.com/watch?v=$id"

                    episodes.add(
                        com.amatsagu.reson.model.Episode(
                            id = id,
                            title = title,
                            channelTitle = uploader,
                            durationSeconds = duration,
                            durationText = formatDuration(duration),
                            releaseDateText = releaseDateText,
                            thumbnailUrl = thumb,
                            webpageUrl = webpageUrl
                        )
                    )
                } catch (parseEx: Exception) {
                    Log.d(TAG, "Skipping line in search output: ${parseEx.message}")
                }
            }

            Log.i(TAG, "Search completed. Found ${episodes.size} episodes.")
            Result.Success(episodes)
        } catch (e: Throwable) {
            Log.e(TAG, "Search failed for '$trimmed': ${e.message}", e)
            Result.Failure(e, e.message ?: "Failed to perform search")
        }
    }

    private fun formatDuration(seconds: Int): String {
        if (seconds <= 0) return "Unknown"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${secs}s"
        }
    }

    private fun ensureReady() {
        if (_state.value !is YtDlpState.Ready) {
            throw IllegalStateException("yt-dlp is not ready. Current state: ${_state.value}")
        }
    }

    private fun getVersionName(): String {
        return try {
            YoutubeDL.getInstance().versionName(appContext) ?: "Unknown"
        } catch (_: Exception) {
            "Unknown"
        }
    }
}
