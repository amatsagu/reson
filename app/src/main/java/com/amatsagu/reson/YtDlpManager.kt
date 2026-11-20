package com.amatsagu.reson

import android.content.Context
import android.util.Log
import com.amatsagu.reson.core.network.NetworkMonitor
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL.UpdateStatus
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_android.mapper.VideoInfo
import com.yausername.ffmpeg.FFmpeg
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.SearchResultItem
import com.amatsagu.reson.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.math.log10
import kotlin.random.Random

/**
 * Coroutine context element to detect if execution is already within queueWorkerJob,
 * avoiding re-entrancy deadlocks on nested executeQueued calls.
 */
private object QueueWorkerElement : CoroutineContext.Element {
    override val key: CoroutineContext.Key<QueueWorkerElement> = Key
    object Key : CoroutineContext.Key<QueueWorkerElement>
}

/**
 * Priority levels for yt-dlp execution to ensure smooth UI responsiveness
 * while strictly preventing rate limits.
 */
enum class TaskPriority(val weight: Int) {
    IMMEDIATE(3),   // Direct user playback & automatic stream recovery
    INTERACTIVE(2), // Search queries, autocomplete, UI exploration
    BACKGROUND(1)   // Channel sync, avatar caching, version updates
}

/**
 * Task item stored in the PriorityBlockingQueue.
 */
private class QueuedTask<T>(
    val id: Long,
    val priority: TaskPriority,
    val tag: String,
    val block: suspend () -> T,
    val deferred: CompletableDeferred<T>
) : Comparable<QueuedTask<*>> {
    override fun compareTo(other: QueuedTask<*>): Int {
        val pComp = other.priority.weight.compareTo(this.priority.weight)
        if (pComp != 0) return pComp
        return this.id.compareTo(other.id)
    }

    suspend fun run() {
        if (!deferred.isActive) return
        try {
            val result = block()
            deferred.complete(result)
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
        }
    }
}

/**
 * State representing the lifecycle and health of yt-dlp.
 */
sealed interface YtDlpState {
    data object Uninitialized : YtDlpState
    data object Initializing : YtDlpState
    data class Ready(val version: String, val statusMessage: String? = null) : YtDlpState
    data class Updating(val currentVersion: String, val message: String = "Downloading updates...") : YtDlpState
    data class Error(val message: String, val canRecover: Boolean = true) : YtDlpState
}

/**
 * Result wrapper for engine operations.
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Failure(val error: Throwable, val message: String) : Result<Nothing>()
}

/**
 * Observable rate-limit and blocking status for YouTube operations.
 */
sealed interface RateLimitStatus {
    data object Normal : RateLimitStatus
    data class Throttled(val backoffSeconds: Long) : RateLimitStatus
    data class Blocked(val message: String, val backoffSeconds: Long) : RateLimitStatus
}

/**
 * Singleton manager for yt-dlp operations on Android.
 * Features smart serialized priority queuing, rate-limit avoidance with exponential backoff,
 * multi-client playback fallback (Android -> iOS -> TV), and network handoff awareness.
 */
class YtDlpManager private constructor(private val appContext: Context) {

    private val _state = MutableStateFlow<YtDlpState>(YtDlpState.Uninitialized)
    val state: StateFlow<YtDlpState> = _state.asStateFlow()

    private val _rateLimitStatus = MutableStateFlow<RateLimitStatus>(RateLimitStatus.Normal)
    val rateLimitStatus: StateFlow<RateLimitStatus> = _rateLimitStatus.asStateFlow()

    private val mutex = Mutex()
    private val queueScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val priorityQueue = PriorityBlockingQueue<QueuedTask<*>>()
    private val wakeSignal = KChannel<Unit>(KChannel.CONFLATED)
    private val taskCounter = AtomicLong(0)
    private val networkMonitor = NetworkMonitor.getInstance(appContext)

    @Volatile
    private var lastRequestFinishEpochMs: Long = 0L

    @Volatile
    private var rateLimitCooldownUntilEpochMs: Long = 0L

    @Volatile
    private var consecutiveRateLimits: Int = 0

    private val streamUrlCache = mutableMapOf<String, Pair<String, Long>>() // videoId -> (streamUrl, expiryEpochMs)
    private val streamCacheMutex = Mutex()

    init {
        startQueueWorker()
        queueScope.launch {
            networkMonitor.transitions.collect { transition ->
                when (transition) {
                    is com.amatsagu.reson.core.network.NetworkTransition.Switched,
                    is com.amatsagu.reson.core.network.NetworkTransition.Restored -> {
                        // Reset rate limits and cooldowns on IP / network route change
                        Log.i(TAG, "Network transition observed. Resetting rate limit cooldowns.")
                        consecutiveRateLimits = 0
                        rateLimitCooldownUntilEpochMs = 0L
                        _rateLimitStatus.value = RateLimitStatus.Normal
                        wakeSignal.trySend(Unit)
                    }
                    is com.amatsagu.reson.core.network.NetworkTransition.Lost -> {}
                }
            }
        }
    }

    companion object {
        private const val TAG = "Reson:YtDlp"

        @Volatile
        private var instance: YtDlpManager? = null

        fun getInstance(context: Context): YtDlpManager {
            return instance ?: synchronized(this) {
                instance ?: YtDlpManager(context.applicationContext).also { instance = it }
            }
        }

        fun isRateLimitError(error: Throwable): Boolean {
            val msg = (error.message ?: "").lowercase()
            return msg.contains("429") ||
                    msg.contains("too many requests") ||
                    msg.contains("confirm you're not a bot") ||
                    msg.contains("sign in to confirm") ||
                    msg.contains("rate limit") ||
                    msg.contains("rate-limit") ||
                    msg.contains("blocked") ||
                    msg.contains("captcha")
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

        fun formatSubscriberCount(count: Long): String {
            if (count <= 0) return ""
            return when {
                count >= 1_000_000 -> {
                    val m = count / 1_000_000.0
                    String.format(Locale.US, "%.1fM subscribers", m)
                }
                count >= 1_000 -> {
                    val k = count / 1_000.0
                    String.format(Locale.US, "%.1fk subscribers", k)
                }
                count == 1L -> "1 subscriber"
                else -> "$count subscribers"
            }
        }

        /**
         * Cleans version string by removing any engine prefixes like "yt-dlp "
         * so only clean semantic/date version numbers are displayed in the UI.
         */
        fun cleanVersion(raw: String?): String {
            if (raw.isNullOrBlank()) return "Unknown"
            val cleaned = raw.replace("(?i)yt-dlp\\s*".toRegex(), "").trim()
            return cleaned.ifEmpty { raw.trim() }
        }

        /**
         * Computes similarity between search query and channel name/handle (0.0 to 1.0).
         * Normalizes alphanumeric text (ignoring spaces, case, and punctuation) to match YouTube's behavior.
         */
        fun calculateSimilarity(query: String, title: String, handle: String): Double {
            val qNorm = query.lowercase().filter { it.isLetterOrDigit() }
            val tNorm = title.lowercase().filter { it.isLetterOrDigit() }
            val hNorm = handle.lowercase().removePrefix("@").filter { it.isLetterOrDigit() }

            if (qNorm.isEmpty() || tNorm.isEmpty()) return 0.0

            if (qNorm == tNorm || (hNorm.isNotEmpty() && qNorm == hNorm)) return 1.0

            if (tNorm.startsWith(qNorm) || (hNorm.isNotEmpty() && hNorm.startsWith(qNorm)) || qNorm.startsWith(tNorm)) {
                return 0.92
            }

            if (tNorm.contains(qNorm) || (hNorm.isNotEmpty() && hNorm.contains(qNorm)) || qNorm.contains(tNorm)) {
                return 0.85
            }

            val titleExpanded = title.replace("(?<=[a-z])(?=[A-Z])".toRegex(), " ")
            val handleExpanded = handle.replace("(?<=[a-z])(?=[A-Z])".toRegex(), " ")
            val qWords = query.lowercase().split("[^a-zA-Z0-9]+".toRegex()).filter { it.isNotEmpty() }.toSet()
            val tWords = "$titleExpanded $handleExpanded".lowercase().split("[^a-zA-Z0-9]+".toRegex()).filter { it.isNotEmpty() }.toSet()

            if (qWords.isNotEmpty() && tWords.isNotEmpty()) {
                val intersection = qWords.intersect(tWords).size
                val overlap = intersection.toDouble() / qWords.size
                if (overlap >= 0.4) {
                    return 0.65 + (0.3 * overlap)
                }
            }

            return 0.0
        }

        /**
         * Checks if the search query matches the channel handle perfectly (with or without '@' prefix).
         */
        fun isHandleExactMatch(query: String, handle: String): Boolean {
            val q = query.trim().lowercase().removePrefix("@")
            val h = handle.trim().lowercase().removePrefix("@")
            return q.isNotEmpty() && q == h
        }

        /**
         * Filters out random channels with low similarity (< 0.45), and sorts matching channels
         * by similarity score, subscriber count, and whether they have active recent videos.
         */
        fun filterAndRankChannels(
            query: String,
            channels: List<Channel>,
            videos: List<Video>
        ): List<Channel> {
            val scored = channels.mapNotNull { ch ->
                val isExactHandle = isHandleExactMatch(query, ch.handle)
                if (ch.subscriberCount < 1000 && !isExactHandle) {
                    return@mapNotNull null
                }

                val similarity = calculateSimilarity(query, ch.title, ch.handle)
                if (similarity < 0.45) return@mapNotNull null

                val matchingVideo = videos.firstOrNull { v ->
                    v.channelTitle.equals(ch.title, ignoreCase = true) ||
                            (ch.handle.isNotBlank() && v.channelTitle.contains(ch.handle.removePrefix("@"), ignoreCase = true))
                }

                val hasRecentVideo = matchingVideo != null
                val subScore = log10(ch.subscriberCount.coerceAtLeast(100L).toDouble())
                val totalScore = (similarity * 10.0) + subScore + (if (hasRecentVideo) 3.0 else 0.0)

                ch to totalScore
            }

            return scored.sortedByDescending { it.second }.map { it.first }
        }

        /**
         * Combines videos and channels into a single unified search list up to maxTotal (default 30).
         */
        fun mergeSearchResults(
            videos: List<Video>,
            channels: List<Channel>,
            maxTotal: Int = 30
        ): List<SearchResultItem> {
            val result = mutableListOf<SearchResultItem>()
            val videoIter = videos.iterator()
            val channelIter = channels.iterator()

            if (videoIter.hasNext()) {
                result.add(SearchResultItem.Video(videoIter.next()))
            }

            if (channelIter.hasNext() && result.size < maxTotal) {
                result.add(SearchResultItem.Creator(channelIter.next()))
            }

            var videoCount = 0
            while (videoIter.hasNext() && videoCount < 2 && result.size < maxTotal) {
                result.add(SearchResultItem.Video(videoIter.next()))
                videoCount++
            }

            if (channelIter.hasNext() && result.size < maxTotal) {
                result.add(SearchResultItem.Creator(channelIter.next()))
            }

            while (videoIter.hasNext() && result.size < maxTotal) {
                result.add(SearchResultItem.Video(videoIter.next()))
            }

            while (channelIter.hasNext() && result.size < maxTotal) {
                result.add(SearchResultItem.Creator(channelIter.next()))
            }

            return result
        }
    }

    /**
     * Continuous background queue worker: enforces single concurrency on yt-dlp native binaries,
     * respects polite request spacing to avoid IP blocks, and pauses during rate limits or network drops.
     */
    private fun startQueueWorker() {
        queueScope.launch {
            while (isActive) {
                val task = priorityQueue.poll()
                if (task == null) {
                    wakeSignal.receive()
                    continue
                }

                if (!task.deferred.isActive) {
                    continue
                }

                // If device is offline, pause queue until network is validated
                while (!networkMonitor.isOnline() && isActive) {
                    Log.d(TAG, "yt-dlp queue paused: device is offline. Waiting for network...")
                    delay(800)
                }

                val now = System.currentTimeMillis()
                // Rate-limit circuit breaker backoff: only bypass for IMMEDIATE playback requests
                if (now < rateLimitCooldownUntilEpochMs && task.priority != TaskPriority.IMMEDIATE) {
                    val waitMs = (rateLimitCooldownUntilEpochMs - now).coerceAtLeast(0L)
                    Log.w(TAG, "yt-dlp queue under rate-limit cooldown (${waitMs / 1000}s remaining). Waiting before executing '${task.tag}'...")
                    delay(waitMs.coerceAtMost(3000L))
                    priorityQueue.offer(task)
                    continue
                }

                if (now >= rateLimitCooldownUntilEpochMs && _rateLimitStatus.value is RateLimitStatus.Throttled) {
                    _rateLimitStatus.value = RateLimitStatus.Normal
                }

                // Enforce polite request spacing to avoid triggering YouTube's bot detection
                val minSpacing = when (task.priority) {
                    TaskPriority.IMMEDIATE -> 100L
                    TaskPriority.INTERACTIVE -> 350L
                    TaskPriority.BACKGROUND -> 1500L + Random.nextLong(200, 700)
                }
                val elapsedSinceLast = now - lastRequestFinishEpochMs
                if (elapsedSinceLast < minSpacing) {
                    delay(minSpacing - elapsedSinceLast)
                }

                try {
                    withContext(QueueWorkerElement) {
                        task.run()
                    }
                    lastRequestFinishEpochMs = System.currentTimeMillis()
                    if (consecutiveRateLimits > 0) {
                        consecutiveRateLimits = 0
                        _rateLimitStatus.value = RateLimitStatus.Normal
                    }
                } catch (e: Throwable) {
                    lastRequestFinishEpochMs = System.currentTimeMillis()
                    if (isRateLimitError(e)) {
                        consecutiveRateLimits++
                        val backoff = (15_000L * (1 shl consecutiveRateLimits.coerceAtMost(3))) + Random.nextLong(1000, 3000)
                        rateLimitCooldownUntilEpochMs = System.currentTimeMillis() + backoff
                        val backoffSec = backoff / 1000L
                        if (consecutiveRateLimits >= 2) {
                            val msg = "YouTube is persistently blocking requests (HTTP 429 / Captcha). Please wait or try switching networks/VPN."
                            Log.e(TAG, "Persistent YouTube block: $msg Circuit breaker active for ${backoffSec}s", e)
                            _rateLimitStatus.value = RateLimitStatus.Blocked(message = msg, backoffSeconds = backoffSec)
                        } else {
                            Log.w(TAG, "YouTube rate limit detected on '${task.tag}'. Cooldown active for ${backoffSec}s", e)
                            _rateLimitStatus.value = RateLimitStatus.Throttled(backoffSeconds = backoffSec)
                        }
                    }
                }
            }
        }
    }

    /**
     * Manually resets rate-limit circuit breaker and returns status to Normal.
     */
    fun resetRateLimitStatus() {
        consecutiveRateLimits = 0
        rateLimitCooldownUntilEpochMs = 0L
        _rateLimitStatus.value = RateLimitStatus.Normal
        wakeSignal.trySend(Unit)
    }

    /**
     * Enqueues an operation into the smart rate-limited priority queue.
     * Prevents re-entrancy deadlock by executing directly inline if already within queue worker.
     */
    suspend fun <T> executeQueued(
        priority: TaskPriority,
        tag: String,
        block: suspend () -> T
    ): T {
        if (coroutineContext[QueueWorkerElement.Key] != null) {
            return block()
        }
        val deferred = CompletableDeferred<T>()
        val task = QueuedTask(
            id = taskCounter.incrementAndGet(),
            priority = priority,
            tag = tag,
            block = block,
            deferred = deferred
        )
        priorityQueue.offer(task)
        wakeSignal.trySend(Unit)
        return deferred.await()
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
                YoutubeDL.getInstance().init(appContext)

                try {
                    FFmpeg.getInstance().init(appContext)
                    Log.i(TAG, "FFmpeg initialized successfully")
                } catch (e: Exception) {
                    Log.w(TAG, "FFmpeg initialization warning: ${e.message}")
                }

                val version = getVersionName()
                _state.value = YtDlpState.Ready(version = version, statusMessage = "Ready ($version)")
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
    suspend fun performSelfRecovery(
        priority: TaskPriority = TaskPriority.IMMEDIATE
    ): Boolean = withContext(Dispatchers.IO) {
        executeQueued(priority, "SelfRecovery") {
            try {
                Log.w(TAG, "Starting self-recovery: cleaning up binaries in filesDir...")
                _state.value = YtDlpState.Initializing

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
    }

    /**
     * Self-patching / Updates yt-dlp to the latest release directly from upstream GitHub releases.
     */
    suspend fun update(
        channel: UpdateChannel = UpdateChannel.STABLE,
        priority: TaskPriority = TaskPriority.BACKGROUND
    ): Result<String> = withContext(Dispatchers.IO) {
        executeQueued(priority, "Update:$channel") {
            mutex.withLock {
                val currentVersion = getVersionName()
                _state.value = YtDlpState.Updating(currentVersion = currentVersion)
                Log.i(TAG, "Checking for yt-dlp updates (channel: $channel)...")

                try {
                    val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
                    val newVersion = getVersionName()

                    val message = when (updateStatus) {
                        UpdateStatus.DONE -> "Successfully updated to $newVersion"
                        UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date ($newVersion)"
                        null -> "Update status unknown. Current version: $newVersion"
                    }

                    Log.i(TAG, message)
                    _state.value = YtDlpState.Ready(version = newVersion, statusMessage = message)
                    Result.Success(message)
                } catch (e: YoutubeDLException) {
                    Log.e(TAG, "Failed to update yt-dlp: ${e.message}", e)
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
    }

    /**
     * Fetches metadata for a given YouTube URL or video.
     */
    suspend fun fetchVideoInfo(
        url: String,
        autoRetryWithUpdate: Boolean = true,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<VideoInfo> = withContext(Dispatchers.IO) {
        executeQueued(priority, "Info:$url") {
            try {
                ensureReady()
                val info = YoutubeDL.getInstance().getInfo(url)
                Result.Success(info)
            } catch (e: YoutubeDLException) {
                Log.w(TAG, "yt-dlp extraction error for URL: $url: ${e.message}")
                if (autoRetryWithUpdate) {
                    Log.i(TAG, "Attempting auto-patch update and retry...")
                    val updateResult = update(UpdateChannel.STABLE, priority = TaskPriority.IMMEDIATE)
                    if (updateResult is Result.Success) {
                        return@executeQueued try {
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
    }

    /**
     * Invalidates any cached stream URL for the given videoId so next playback forces fresh extraction.
     */
    suspend fun invalidateStreamCache(videoId: String) {
        streamCacheMutex.withLock {
            streamUrlCache.remove(videoId)
        }
    }

    /**
     * Extracts direct HTTP streaming audio URL with multi-client fallback:
     * 1) Android client API (fastest ~700ms)
     * 2) iOS client API (rate-limit / bot-challenge bypass)
     * 3) TV embedded client (isolated backend quota)
     */
    suspend fun getAudioStreamUrl(
        videoId: String,
        forceRefresh: Boolean = false,
        priority: TaskPriority = TaskPriority.IMMEDIATE,
        autoRetryWithUpdate: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = streamCacheMutex.withLock { streamUrlCache[videoId] }
            if (cached != null) {
                val (url, expiryMs) = cached
                if (now < (expiryMs - 15 * 60 * 1000L)) {
                    Log.d(TAG, "Serving cached stream URL for $videoId (valid for ${(expiryMs - now) / 60000} more minutes)")
                    return@withContext Result.Success(url)
                }
            }
        }

        executeQueued(priority, "Stream:$videoId") {
            try {
                ensureReady()
                val videoUrl = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"

                val clientOptions = listOf(
                    "youtube:player_client=android,web",
                    "youtube:player_client=ios,web",
                    "youtube:player_client=tv_embedded",
                    ""
                )

                val formatOptions = listOf(
                    "ba[ext=m4a]/ba/b",
                    "bestaudio/best",
                    "best"
                )

                var lastException: Throwable? = null
                outerLoop@ for (format in formatOptions) {
                    for (clientArg in clientOptions) {
                        try {
                            val request = YoutubeDLRequest(videoUrl).apply {
                                addOption("-f", format)
                                addOption("-g")
                                addOption("--no-playlist")
                                addOption("--skip-download")
                                addOption("--no-warnings")
                                if (clientArg.isNotBlank()) {
                                    addOption("--extractor-args", clientArg)
                                }
                            }
                            val response = YoutubeDL.getInstance().execute(request)
                            val streamUrl = response.out.trim().lines().firstOrNull { it.startsWith("http") }
                            if (!streamUrl.isNullOrBlank()) {
                                var expiryMs = System.currentTimeMillis() + (4 * 3600 * 1000L)
                                val expireMatch = Regex("[?&]expire=(\\d+)").find(streamUrl)
                                if (expireMatch != null) {
                                    val expireSec = expireMatch.groupValues[1].toLongOrNull()
                                    if (expireSec != null && expireSec > 0) {
                                        expiryMs = expireSec * 1000L
                                    }
                                }
                                streamCacheMutex.withLock {
                                    streamUrlCache[videoId] = Pair(streamUrl, expiryMs)
                                }
                                Log.d(TAG, "Extracted fresh stream URL for $videoId via client '$clientArg' format '$format' (expires in ${(expiryMs - System.currentTimeMillis()) / 60000} mins)")
                                return@executeQueued Result.Success(streamUrl)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Stream extraction with client '$clientArg' (format '$format') failed: ${e.message}")
                            lastException = e
                            if (isRateLimitError(e)) {
                                break@outerLoop
                            }
                        }
                    }
                }

                if (autoRetryWithUpdate && lastException is YoutubeDLException) {
                    Log.i(TAG, "Attempting auto-patch update and retry for stream URL...")
                    val updateResult = update(UpdateChannel.STABLE, priority = TaskPriority.IMMEDIATE)
                    if (updateResult is Result.Success) {
                        return@executeQueued getAudioStreamUrl(videoId, forceRefresh = true, priority = priority, autoRetryWithUpdate = false)
                    }
                }

                val isBlocked = consecutiveRateLimits >= 2 || (lastException != null && isRateLimitError(lastException))
                val failureMsg = if (isBlocked) {
                    "YouTube is blocking requests (HTTP 429 / Captcha). Waiting before retry."
                } else {
                    "Failed to extract audio stream URL"
                }

                Result.Failure(lastException ?: IllegalStateException("No stream URL returned"), failureMsg)
            } catch (e: Throwable) {
                Result.Failure(e, e.message ?: "Unexpected error extracting stream URL")
            }
        }
    }

    /**
     * Executes custom yt-dlp arguments and returns the raw response.
     */
    suspend fun executeRaw(
        request: YoutubeDLRequest,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<YoutubeDLResponse> = withContext(Dispatchers.IO) {
        executeQueued(priority, "Raw") {
            try {
                ensureReady()
                val response = YoutubeDL.getInstance().execute(request)
                Result.Success(response)
            } catch (e: Throwable) {
                Result.Failure(e, e.message ?: "Command execution failed")
            }
        }
    }

    /**
     * Performs a fast search using yt-dlp with --flat-playlist and Android player client.
     * Supports windowed pagination via startIndex and maxResults.
     */
    suspend fun searchContent(
        query: String,
        maxResults: Int = 30,
        startIndex: Int = 1,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<List<Video>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Success(emptyList())

        executeQueued(priority, "Search:$trimmed:$startIndex") {
            try {
                ensureReady()
                val isDirectUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true)

                val targetEnd = startIndex + maxResults - 1
                val searchTarget = if (isDirectUrl) trimmed else "ytsearch$targetEnd:$trimmed"

                val request = YoutubeDLRequest(searchTarget).apply {
                    addOption("--flat-playlist")
                    addOption("--dump-json")
                    addOption("--skip-download")
                    addOption("--no-warnings")
                    addOption("--playlist-start", startIndex.toString())
                    addOption("--playlist-end", targetEnd.toString())
                    addOption("--extractor-args", "youtubetab:approximate_date")
                }

                Log.i(TAG, "Executing fast search for '$trimmed' (target: $searchTarget, range: $startIndex..$targetEnd)")
                val response = YoutubeDL.getInstance().execute(request)
                val videos = mutableListOf<Video>()

                for (line in response.out.lines()) {
                    val jsonLine = line.trim()
                    if (jsonLine.isEmpty() || !jsonLine.startsWith("{")) continue
                    try {
                        val json = org.json.JSONObject(jsonLine)
                        val id = json.optCleanString("id")
                        if (id.isEmpty()) continue

                        val title = json.optCleanString("title", "Untitled")
                        val uploader = json.optCleanString("uploader").ifEmpty {
                            json.optCleanString("channel").ifEmpty {
                                json.optCleanString("uploader_id").ifEmpty {
                                    json.optCleanString("creator").ifEmpty {
                                        json.optCleanString("channel_id").ifEmpty {
                                            "Unknown Channel"
                                        }
                                    }
                                }
                            }
                        }
                        val duration = json.optInt("duration", 0)
                        val timestamp = json.optLong("timestamp", 0L)
                        val rawDate = json.optCleanString("upload_date")
                        val releaseDateText = formatRelativeDate(timestamp, rawDate)

                        var thumb = ""
                        val thumbs = json.optJSONArray("thumbnails")
                        if (thumbs != null && thumbs.length() > 0) {
                            for (i in 0 until thumbs.length()) {
                                val tObj = thumbs.optJSONObject(i) ?: continue
                                val w = tObj.optInt("width", 0)
                                val u = tObj.optCleanString("url")
                                if (u.isNotEmpty() && (w in 300..600)) {
                                    thumb = u
                                    break
                                }
                            }
                            if (thumb.isEmpty()) {
                                thumb = thumbs.optJSONObject(0)?.optCleanString("url") ?: ""
                            }
                        }
                        if (thumb.isEmpty()) {
                            thumb = if (id.isNotEmpty()) {
                                "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                            } else {
                                json.optCleanString("thumbnail")
                            }
                        }
                        val parsedChannelId = json.optCleanString("channel_id")
                        val webpageUrl = if (isDirectUrl) trimmed else "https://www.youtube.com/watch?v=$id"

                        videos.add(
                            Video(
                                id = id,
                                title = title,
                                channelTitle = uploader,
                                durationSeconds = duration,
                                durationText = formatDuration(duration),
                                releaseDateText = releaseDateText,
                                releaseTimestamp = timestamp,
                                thumbnailUrl = thumb,
                                webpageUrl = webpageUrl,
                                channelId = parsedChannelId,
                            )
                        )
                    } catch (parseEx: Exception) {
                        Log.d(TAG, "Skipping line in search output: ${parseEx.message}")
                    }
                }

                Log.i(TAG, "Search completed. Found ${videos.size} videos.")
                Result.Success(videos)
            } catch (e: Throwable) {
                Log.e(TAG, "Search failed for '$trimmed': ${e.message}", e)
                Result.Failure(e, e.message ?: "Failed to perform search")
            }
        }
    }

    /**
     * Searches YouTube channels for the query using the type=channel filter (sp=EgIQAg%3D%3D).
     */
    suspend fun searchChannels(
        query: String,
        maxResults: Int = 3,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<List<Channel>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Success(emptyList())

        executeQueued(priority, "SearchChannels:$trimmed") {
            try {
                ensureReady()
                val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
                val channelSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAg%3D%3D"

                val request = YoutubeDLRequest(channelSearchUrl).apply {
                    addOption("--flat-playlist")
                    addOption("--dump-json")
                    addOption("--skip-download")
                    addOption("--no-warnings")
                    addOption("--playlist-end", maxResults.toString())
                }

                Log.i(TAG, "Searching channels for '$trimmed'")
                val response = YoutubeDL.getInstance().execute(request)
                val channels = mutableListOf<Channel>()

                for (line in response.out.lines()) {
                    val jsonLine = line.trim()
                    if (jsonLine.isEmpty() || !jsonLine.startsWith("{")) continue
                    try {
                        val json = org.json.JSONObject(jsonLine)
                        val id = json.optCleanString("id").ifEmpty {
                            json.optCleanString("channel_id")
                        }
                        if (id.isEmpty()) continue

                        val title = json.optCleanString("title").ifEmpty {
                            json.optCleanString("channel")
                        }
                        if (title.isEmpty()) continue

                        val handle = json.optCleanString("uploader_id").ifEmpty {
                            json.optCleanString("uploader")
                        }
                        val subs = json.optLong("channel_follower_count", 0L)
                        val subsText = formatSubscriberCount(subs)

                        var avatar = ""
                        val thumbs = json.optJSONArray("thumbnails")
                        if (thumbs != null && thumbs.length() > 0) {
                            for (i in 0 until thumbs.length()) {
                                val tObj = thumbs.optJSONObject(i) ?: continue
                                val w = tObj.optInt("width", 0)
                                val u = tObj.optCleanString("url")
                                if (u.isNotEmpty() && (w in 88..250)) {
                                    avatar = u
                                    break
                                }
                            }
                            if (avatar.isEmpty()) {
                                avatar = thumbs.getJSONObject(0).optCleanString("url")
                            }
                        }
                        if (avatar.startsWith("//")) {
                            avatar = "https:$avatar"
                        }

                        channels.add(
                            Channel(
                                id = id,
                                title = title,
                                handle = handle,
                                avatarUrl = avatar,
                                subscriberCount = subs,
                                subscriberCountText = subsText
                            )
                        )
                    } catch (e: Exception) {
                        Log.d(TAG, "Skipping channel parse error: ${e.message}")
                    }
                }

                Result.Success(channels)
            } catch (e: Throwable) {
                Log.w(TAG, "Channel search encountered error: ${e.message}")
                Result.Success(emptyList())
            }
        }
    }

    /**
     * Fetches recent videos from a channel using its ID or handle with --flat-playlist and --playlist-end.
     * Guaranteed to extract up to maxResults videos cleanly.
     */
    suspend fun fetchChannelVideos(
        channelId: String,
        handle: String = "",
        channelTitle: String = "",
        maxResults: Int = 5,
        priority: TaskPriority = TaskPriority.BACKGROUND
    ): Result<List<Video>> = withContext(Dispatchers.IO) {
        val target = when {
            channelId.startsWith("UC") -> "https://www.youtube.com/channel/$channelId/videos"
            handle.isNotBlank() && handle.startsWith("@") -> "https://www.youtube.com/$handle/videos"
            handle.isNotBlank() -> "https://www.youtube.com/@$handle/videos"
            channelId.isNotBlank() -> "https://www.youtube.com/channel/$channelId/videos"
            else -> ""
        }

        if (target.isEmpty() && channelTitle.isBlank()) {
            return@withContext Result.Success(emptyList())
        }

        val primaryResult = if (target.isNotEmpty()) {
            searchContent(target, maxResults = maxResults, priority = priority)
        } else {
            searchContent("ytsearch$maxResults:$channelTitle", maxResults = maxResults, priority = priority)
        }

        when (primaryResult) {
            is Result.Success -> {
                val videos = primaryResult.data.map { v ->
                    v.copy(
                        channelId = channelId.ifEmpty { v.channelId },
                        channelTitle = channelTitle.ifEmpty { v.channelTitle }
                    )
                }
                if (videos.isNotEmpty()) {
                    Result.Success(videos)
                } else if (channelTitle.isNotBlank() && target.isNotEmpty()) {
                    val fallback = searchContent("ytsearch$maxResults:$channelTitle", maxResults = maxResults, priority = priority)
                    if (fallback is Result.Success) {
                        Result.Success(fallback.data.map { it.copy(channelId = channelId) })
                    } else fallback
                } else {
                    Result.Success(emptyList())
                }
            }
            is Result.Failure -> {
                if (channelTitle.isNotBlank() && target.isNotEmpty()) {
                    val fallback = searchContent("ytsearch$maxResults:$channelTitle", maxResults = maxResults, priority = priority)
                    if (fallback is Result.Success) {
                        Result.Success(fallback.data.map { it.copy(channelId = channelId) })
                    } else primaryResult
                } else primaryResult
            }
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
            cleanVersion(YoutubeDL.getInstance().versionName(appContext))
        } catch (_: Exception) {
            "Unknown"
        }
    }
}

/**
 * Safely extracts a trimmed non-"null" string from JSONObject.
 */
private fun org.json.JSONObject.optCleanString(key: String, fallback: String = ""): String {
    val s = optString(key, fallback).trim()
    return if (s.isEmpty() || s.equals("null", ignoreCase = true)) fallback else s
}
