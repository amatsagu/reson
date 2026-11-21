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
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.SearchResultItem
import com.amatsagu.reson.model.Video
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
    private val networkDispatcher = ResonDispatchers.Network
    private val queueScope = CoroutineScope(SupervisorJob() + networkDispatcher)
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

    private val streamUrlCache = object : LinkedHashMap<String, Pair<String, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, Long>>?): Boolean {
            return size > 50
        }
    } // videoId -> (streamUrl, expiryEpochMs)
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
        private const val MAX_STREAM_CACHE_SIZE = 50

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
         * Checks if an extraction failure was caused by supporter/member restrictions, paywalls,
         * or private authentication gates that cannot be bypassed without user login.
         */
        fun isLockedVideoError(error: Throwable): Boolean {
            val msg = (error.message ?: "").lowercase()
            return msg.contains("members-only") ||
                    msg.contains("members only") ||
                    msg.contains("join this channel") ||
                    msg.contains("subscriber-only") ||
                    msg.contains("subscriber only") ||
                    msg.contains("this video is available to this channel's members") ||
                    msg.contains("requires payment") ||
                    msg.contains("private video")
        }

        /**
         * Identifies whether a raw video entry from yt-dlp is locked/supporters-only/members-only/paywalled
         * or an upcoming unreleased premiere, so it is silently dropped across all searches and feeds.
         */
        fun isLockedVideo(json: org.json.JSONObject): Boolean {
            val availability = json.optCleanString("availability").lowercase()
            if (availability in setOf("subscriber_only", "needs_auth", "premium_only", "private", "rental", "purchase")) {
                return true
            }

            if (json.optBoolean("requires_payment", false) ||
                json.optBoolean("paywalled", false) ||
                json.optBoolean("is_unplayable", false)
            ) {
                return true
            }

            val liveStatus = json.optCleanString("live_status").lowercase()
            if (liveStatus == "is_upcoming") {
                return true
            }

            val badgeStr = json.optCleanString("badge").lowercase()
            if (badgeStr.isNotEmpty() && (badgeStr.contains("member") || badgeStr.contains("supporter") || badgeStr.contains("subscriber_only") || badgeStr.contains("premium"))) {
                return true
            }

            val badgesArray = json.optJSONArray("badges")
            if (badgesArray != null) {
                for (i in 0 until badgesArray.length()) {
                    val b = badgesArray.opt(i)?.toString()?.lowercase() ?: ""
                    if (b.contains("member") || b.contains("supporter") || b.contains("subscriber_only") || b.contains("paywall") || b.contains("premium")) {
                        return true
                    }
                }
            }

            val title = json.optCleanString("title")
            return isLockedVideoTitle(title)
        }

        /**
         * Checks a video title for explicit supporters/members-only badges or bracketed tags.
         */
        fun isLockedVideoTitle(title: String): Boolean {
            if (title.isBlank()) return false
            val regexBracket = "(?i)[\\[(](?:members?|supporters?|subscribers?)[-\\s]only[])]".toRegex()
            if (regexBracket.containsMatchIn(title)) return true

            val regexDelim = "(?i)(?:^|[-|•:])\\s*(?:members?|supporters?)[-\\s]only\\s*(?:$|[-|•:])".toRegex()
            return regexDelim.containsMatchIn(title)
        }

        private val SHORTS_TITLE_REGEX = "(?i)(?:#shorts?\\b|[\\[(]shorts?[])]<!--|/shorts/|(?:^|[-|•:])\\s*shorts?\\s*(?:$|[-|•:]))".replace("<!--", "").toRegex()
        private val BIAS_NEWS_AMERICA_REGEX = "(?i)\\b(news|america[ns]?)\\b".toRegex()

        /**
         * Checks whether a video title indicates a YouTube Short.
         */
        fun isShortVideoTitle(title: String): Boolean {
            if (title.isBlank()) return false
            return SHORTS_TITLE_REGEX.containsMatchIn(title)
        }

        /**
         * Identifies whether a raw video entry from yt-dlp is a YouTube Short,
         * checking url patterns, title/description tags, tags array, and duration <= 60 seconds.
         */
        fun isShortVideo(json: org.json.JSONObject): Boolean {
            val url = json.optCleanString("url")
            val webpageUrl = json.optCleanString("webpage_url")
            val originalUrl = json.optCleanString("original_url")
            if (url.contains("/shorts/") || webpageUrl.contains("/shorts/") || originalUrl.contains("/shorts/")) {
                return true
            }

            val title = json.optCleanString("title")
            if (isShortVideoTitle(title)) {
                return true
            }

            val desc = json.optCleanString("description")
            if (desc.isNotEmpty() && isShortVideoTitle(desc)) {
                return true
            }

            val tags = json.optJSONArray("tags")
            if (tags != null) {
                for (i in 0 until tags.length()) {
                    val tag = tags.optString(i).trim().lowercase()
                    if (tag == "shorts" || tag == "short" || tag == "#shorts" || tag == "#short") {
                        return true
                    }
                }
            }

            val duration = json.optInt("duration", 0)
            if (duration in 1..60) {
                return true
            }

            return false
        }

        /**
         * Checks if a domain Video object is a YouTube Short.
         */
        fun isShortVideo(video: Video): Boolean {
            if (isShortVideoTitle(video.title)) return true
            if (video.webpageUrl.contains("/shorts/")) return true
            if (video.durationSeconds in 1..60) return true
            return false
        }

        /**
         * Checks whether a channel or video has a hidden bias towards 'news' or 'america'.
         * Returns false if the channel is followed.
         */
        fun hasNewsOrAmericaBias(
            title: String,
            channelTitle: String = "",
            handle: String = "",
            isFollowed: Boolean = false
        ): Boolean {
            if (isFollowed) return false
            return BIAS_NEWS_AMERICA_REGEX.containsMatchIn(title) ||
                    BIAS_NEWS_AMERICA_REGEX.containsMatchIn(channelTitle) ||
                    BIAS_NEWS_AMERICA_REGEX.containsMatchIn(handle)
        }

        /**
         * Formats release date into compact relative time to save space:
         * e.g. "57m ago", "1d3h ago", "3d ago", "2w ago", "4mo ago", "2y ago".
         */
        fun formatRelativeDate(timestampSec: Long, rawUploadDate: String = ""): String {
            val nowSec = System.currentTimeMillis() / 1000L
            val targetSec = if (timestampSec > 0) {
                timestampSec
            } else if ((rawUploadDate.length == 8) && rawUploadDate.all { it.isDigit() }) {
                try {
                    val sdf = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).apply {
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
                return rawUploadDate.ifBlank { "" }
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
         * Parses text subscriber count (e.g. "4.34M subscribers", "462K subscribers", "12 subscribers") into a Long.
         */
        fun parseSubscriberCount(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            val cleaned = text.trim()
            val regex = "([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMbB])?".toRegex()
            val match = regex.find(cleaned) ?: return 0L
            val numStr = match.groupValues[1]
            val suffix = match.groupValues[2].uppercase()
            val value = numStr.toDoubleOrNull() ?: return 0L
            return when (suffix) {
                "K" -> (value * 1_000).toLong()
                "M" -> (value * 1_000_000).toLong()
                "B" -> (value * 1_000_000_000).toLong()
                else -> value.toLong()
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
            videos: List<Video>,
            followedIds: Set<String> = emptySet()
        ): List<Channel> {
            val qNorm = query.lowercase().filter { it.isLetterOrDigit() }

            val scored = channels.mapNotNull { ch ->
                val isFollowed = ch.isFollowed || ch.id in followedIds
                val isExactHandle = isHandleExactMatch(query, ch.handle)
                val similarity = calculateSimilarity(query, ch.title, ch.handle)
                if (similarity < 0.45) return@mapNotNull null

                val matchingVideo = videos.firstOrNull { v ->
                    v.channelTitle.equals(ch.title, ignoreCase = true) ||
                            (ch.handle.isNotBlank() && v.channelTitle.contains(ch.handle.removePrefix("@"), ignoreCase = true))
                }

                val hasRecentVideo = matchingVideo != null

                val tNorm = ch.title.lowercase().filter { it.isLetterOrDigit() }
                val hNorm = ch.handle.lowercase().removePrefix("@").filter { it.isLetterOrDigit() }
                val titleOrHandleContainsQuery = qNorm.isNotEmpty() && (tNorm.contains(qNorm) || hNorm.contains(qNorm))

                // Drop tiny channels under 1000 subscribers unless exact handle, or if subscriber count is unknown (<=0)
                // but title/handle strongly matches query or has a recent video in results.
                if (ch.subscriberCount in 1..999 && !isExactHandle) {
                    return@mapNotNull null
                }
                if (ch.subscriberCount <= 0L && !isExactHandle && !hasRecentVideo && !titleOrHandleContainsQuery) {
                    return@mapNotNull null
                }

                val subScore = log10(ch.subscriberCount.coerceAtLeast(100L).toDouble())
                val bonus = if (titleOrHandleContainsQuery) 2.5 else 0.0

                // Hidden bias: lower priority if title or handle contains "news" or "america" (unless followed)
                val biasPenalty = if (!isFollowed && hasNewsOrAmericaBias(ch.title, handle = ch.handle)) 15.0 else 0.0

                val totalScore = (similarity * 10.0) + subScore + (if (hasRecentVideo) 3.0 else 0.0) + bonus - biasPenalty

                ch to totalScore
            }

            return scored.sortedByDescending { it.second }.map { it.first }
        }

        /**
         * Combines videos and channels into a single unified search list up to maxTotal (default 30).
         * Automatically filters out shorts and demotes non-followed items with "news" or "america".
         */
        fun mergeSearchResults(
            videos: List<Video>,
            channels: List<Channel>,
            maxTotal: Int = 30,
            followedChannelIds: Set<String> = emptySet(),
            followedChannelTitles: Set<String> = emptySet()
        ): List<SearchResultItem> {
            // Filter out shorts
            val nonShortVideos = videos.filterNot { isShortVideo(it) }

            // Hidden bias: rank non-followed items with "news" or "america" lower
            val (unbiasedVideos, biasedVideos) = nonShortVideos.partition { v ->
                val isFollowed = v.channelId in followedChannelIds || v.channelTitle in followedChannelTitles
                isFollowed || !hasNewsOrAmericaBias(v.title, v.channelTitle)
            }
            val orderedVideos = unbiasedVideos + biasedVideos

            val (unbiasedChannels, biasedChannels) = channels.partition { ch ->
                val isFollowed = ch.isFollowed || ch.id in followedChannelIds
                isFollowed || !hasNewsOrAmericaBias(ch.title, handle = ch.handle)
            }
            val orderedChannels = unbiasedChannels + biasedChannels

            val result = mutableListOf<SearchResultItem>()
            val videoIter = orderedVideos.iterator()
            val channelIter = orderedChannels.iterator()

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
        // Bound priority queue to avoid unbounded accumulation in pathological offline states
        if (priorityQueue.size > 50) {
            priorityQueue.removeIf { !it.deferred.isActive }
        }
        priorityQueue.offer(task)
        wakeSignal.trySend(Unit)
        return try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            deferred.cancel(e)
            throw e
        }
    }

    /**
     * Initializes yt-dlp and FFmpeg. If initialization fails (e.g., file corruption from an interrupted
     * write/update), it performs an automatic self-recovery by wiping the corrupted binaries and re-extracting.
     */
    suspend fun initialize(): Boolean = withContext(networkDispatcher) {
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
    ): Boolean = withContext(networkDispatcher) {
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

    @Volatile
    private var lastUpdateCheckEpochMs: Long = 0L

    /**
     * Self-patching / Updates yt-dlp to the latest release directly from upstream GitHub releases.
     * Includes 1-hour throttling for automatic checks unless force = true.
     */
    suspend fun update(
        channel: UpdateChannel = UpdateChannel.STABLE,
        priority: TaskPriority = TaskPriority.BACKGROUND,
        force: Boolean = false
    ): Result<String> = withContext(networkDispatcher) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastUpdateCheckEpochMs) < (1 * 3600 * 1000L)) {
            val v = getVersionName()
            Log.d(TAG, "yt-dlp update check skipped (checked within last 1h, current version: $v)")
            return@withContext Result.Success("Already up to date ($v)")
        }

        executeQueued(priority, "Update:$channel") {
            mutex.withLock {
                lastUpdateCheckEpochMs = System.currentTimeMillis()
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
    ): Result<VideoInfo> = withContext(networkDispatcher) {
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
    ): Result<String> = withContext(networkDispatcher) {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            val cached = streamCacheMutex.withLock {
                val entry = streamUrlCache[videoId]
                if (entry != null && now >= entry.second) {
                    streamUrlCache.remove(videoId)
                    null
                } else {
                    entry
                }
            }
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
                    "bestaudio/best",
                    "ba/b",
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
                                    val currentTime = System.currentTimeMillis()
                                    if (streamUrlCache.size >= MAX_STREAM_CACHE_SIZE / 2) {
                                        streamUrlCache.entries.removeIf { it.value.second <= currentTime }
                                    }
                                    streamUrlCache[videoId] = Pair(streamUrl, expiryMs)
                                }
                                Log.d(TAG, "Extracted fresh stream URL for $videoId via client '$clientArg' format '$format' (expires in ${(expiryMs - System.currentTimeMillis()) / 60000} mins)")
                                return@executeQueued Result.Success(streamUrl)
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "Stream extraction with client '$clientArg' (format '$format') failed: ${e.message}")
                            lastException = e
                            if (isRateLimitError(e) || isLockedVideoError(e)) {
                                break@outerLoop
                            }
                        }
                    }
                }

                if (autoRetryWithUpdate && lastException is YoutubeDLException && !isLockedVideoError(lastException)) {
                    Log.i(TAG, "Attempting auto-patch update and retry for stream URL...")
                    val updateResult = update(UpdateChannel.STABLE, priority = TaskPriority.IMMEDIATE)
                    if (updateResult is Result.Success) {
                        return@executeQueued getAudioStreamUrl(videoId, forceRefresh = true, priority = priority, autoRetryWithUpdate = false)
                    }
                }

                val isBlocked = consecutiveRateLimits >= 2 || (lastException != null && isRateLimitError(lastException))
                val isLocked = lastException != null && isLockedVideoError(lastException)
                val failureMsg = if (isBlocked) {
                    "YouTube is blocking requests (HTTP 429 / Captcha). Waiting before retry."
                } else if (isLocked) {
                    "This video is restricted to channel members or supporters."
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
     * Downloads the best audio stream for offline playback into internal storage.
     * Uses fast direct HTTP stream download with native yt-dlp fallback.
     */
    suspend fun downloadAudio(
        videoId: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> = withContext(networkDispatcher) {
        val downloadsDir = File(appContext.filesDir, "downloads")
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        // 1. Check if already completely downloaded on disk
        val existing = downloadsDir.listFiles { _, name ->
            name.startsWith(videoId) && !name.endsWith(".part") && !name.endsWith(".download") && !name.endsWith(".ytdl")
        }?.firstOrNull { it.length() > 1024 }
        if (existing != null) {
            Log.d(TAG, "Audio already downloaded for $videoId: ${existing.name} (${existing.length()} bytes)")
            onProgress(1f)
            return@withContext Result.Success(existing)
        }

        // Clean up any stale partial downloads before starting
        downloadsDir.listFiles { _, name ->
            name.startsWith(videoId) && (name.contains(".download") || name.endsWith(".part") || name.endsWith(".ytdl"))
        }?.forEach { it.delete() }

        executeQueued(TaskPriority.IMMEDIATE, "Download:$videoId") {
            try {
                ensureReady()

                // Strategy 1: Direct stream URL download (fast, reliable, smooth progress)
                val streamResult = getAudioStreamUrl(videoId, forceRefresh = false, priority = TaskPriority.IMMEDIATE)
                if (streamResult is Result.Success) {
                    val streamUrl = streamResult.data
                    val tempFile = File(downloadsDir, "$videoId.download")
                    try {
                        Log.d(TAG, "Attempting direct stream download for $videoId...")
                        val detectedExt = downloadStreamToFile(streamUrl, tempFile, onProgress)
                        if (detectedExt != null && tempFile.exists() && tempFile.length() > 1024) {
                            val finalFile = File(downloadsDir, "$videoId.$detectedExt")
                            if (finalFile.exists()) finalFile.delete()
                            if (tempFile.renameTo(finalFile)) {
                                Log.i(TAG, "Direct stream download successful: ${finalFile.name} (${finalFile.length() / 1024} KB, format: $detectedExt)")
                                onProgress(1f)
                                return@executeQueued Result.Success(finalFile)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Direct stream download failed for $videoId: ${e.message}. Falling back to yt-dlp downloader.")
                        if (tempFile.exists()) tempFile.delete()
                    }
                }

                // Strategy 2: Native yt-dlp download execution with atomic temp rename
                val videoUrl = if (videoId.startsWith("http")) videoId else "https://www.youtube.com/watch?v=$videoId"
                val outputTemplate = File(downloadsDir, "$videoId.download.%(ext)s").absolutePath
                val clientOptions = listOf(
                    "youtube:player_client=android,web",
                    "youtube:player_client=ios,web",
                    ""
                )

                var lastError: Throwable? = null
                for (clientArg in clientOptions) {
                    try {
                        val request = YoutubeDLRequest(videoUrl).apply {
                            addOption("-f", "bestaudio/best")
                            addOption("-o", outputTemplate)
                            addOption("--no-playlist")
                            addOption("--no-warnings")
                            if (clientArg.isNotBlank()) {
                                addOption("--extractor-args", clientArg)
                            }
                        }
                        val processId = "dl_$videoId"
                        Log.d(TAG, "Executing yt-dlp download for $videoId (client: '$clientArg')...")
                        YoutubeDL.getInstance().execute(request, processId) { progress, _, _ ->
                            val normalized = (progress / 100f).coerceIn(0f, 1f)
                            onProgress(normalized)
                        }

                        val downloadedTemp = downloadsDir.listFiles { _, name ->
                            name.startsWith("$videoId.download") && !name.endsWith(".part") && !name.endsWith(".ytdl")
                        }?.firstOrNull { it.length() > 1024 }

                        if (downloadedTemp != null && downloadedTemp.exists()) {
                            val ext = downloadedTemp.extension.ifBlank { "webm" }
                            val finalFile = File(downloadsDir, "$videoId.$ext")
                            if (finalFile.exists()) finalFile.delete()
                            if (downloadedTemp.renameTo(finalFile)) {
                                Log.i(TAG, "yt-dlp download successful: ${finalFile.name} (${finalFile.length() / 1024} KB)")
                                onProgress(1f)
                                return@executeQueued Result.Success(finalFile)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "yt-dlp download failed with client '$clientArg': ${e.message}")
                        lastError = e
                        if (isRateLimitError(e)) break
                    }
                }

                // Cleanup any partial files if yt-dlp failed
                downloadsDir.listFiles { _, name ->
                    name.startsWith(videoId) && (name.contains(".download") || name.endsWith(".part") || name.endsWith(".ytdl"))
                }?.forEach { it.delete() }

                val err = lastError ?: IllegalStateException("Download file not found on disk")
                Result.Failure(err, err.message ?: "Failed to download audio file")
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error in downloadAudio for $videoId", e)
                Result.Failure(e, e.message ?: "Download failed")
            }
        }
    }

    /**
     * Cancels an ongoing download process and cleans up any temp download files.
     */
    fun cancelDownload(videoId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById("dl_$videoId")
            val downloadsDir = File(appContext.filesDir, "downloads")
            if (downloadsDir.exists()) {
                downloadsDir.listFiles { _, name ->
                    name.startsWith(videoId) && (name.contains(".download") || name.endsWith(".part") || name.endsWith(".ytdl"))
                }?.forEach { it.delete() }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel download for $videoId", e)
        }
    }

    private fun detectAudioExtension(contentType: String?, url: String): String {
        val ct = contentType?.lowercase() ?: ""
        val u = url.lowercase()
        return when {
            ct.contains("webm") || u.contains("mime=audio%2fwebm") || u.contains("mime=audio/webm") -> "webm"
            ct.contains("mp4") || ct.contains("m4a") || u.contains("mime=audio%2fmp4") || u.contains("mime=audio/mp4") -> "m4a"
            ct.contains("opus") -> "opus"
            ct.contains("ogg") -> "ogg"
            ct.contains("mp3") || ct.contains("mpeg") -> "mp3"
            else -> "webm"
        }
    }

    private fun downloadStreamToFile(
        streamUrl: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ): String? {
        var currentUrl = streamUrl
        var redirectCount = 0
        while (redirectCount < 5) {
            var connection: java.net.HttpURLConnection? = null
            try {
                connection = (java.net.URL(currentUrl).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    )
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }

                val code = connection.responseCode
                if (code == java.net.HttpURLConnection.HTTP_MOVED_PERM ||
                    code == java.net.HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == 307 || code == 308
                ) {
                    val loc = connection.getHeaderField("Location")
                    if (!loc.isNullOrBlank()) {
                        currentUrl = loc
                        redirectCount++
                        continue
                    }
                }

                if (code !in 200..299) {
                    connection.errorStream?.close()
                    throw java.io.IOException("HTTP $code from stream server")
                }

                val contentType = connection.contentType
                val detectedExt = detectAudioExtension(contentType, currentUrl)
                val totalBytes = connection.contentLengthLong
                connection.inputStream.use { input ->
                    destFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var bytesRead = 0L
                        var read: Int
                        var lastUpdate = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            bytesRead += read
                            val now = System.currentTimeMillis()
                            if (totalBytes > 0 && now - lastUpdate > 100) {
                                lastUpdate = now
                                onProgress((bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 0.99f))
                            }
                        }
                        output.flush()
                    }
                }
                return if (destFile.exists() && destFile.length() > 0) detectedExt else null
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    /**
     * Executes custom yt-dlp arguments and returns the raw response.
     */
    suspend fun executeRaw(
        request: YoutubeDLRequest,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<YoutubeDLResponse> = withContext(networkDispatcher) {
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
    ): Result<List<Video>> = withContext(networkDispatcher) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Success(emptyList())

        executeQueued(priority, "Search:$trimmed:$startIndex") {
            try {
                ensureReady()
                val isDirectUrl = trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true)
                val isAlreadySearchTarget = trimmed.startsWith("ytsearch", ignoreCase = true)

                val targetEnd = startIndex + maxResults - 1
                val searchTarget = when {
                    isDirectUrl || isAlreadySearchTarget -> trimmed
                    else -> "ytsearch$targetEnd:$trimmed"
                }

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
                        // Silently drop locked/supporters-only/members-only/paywalled videos and shorts
                        if (isLockedVideo(json) || isShortVideo(json)) {
                            Log.d(TAG, "Silently dropping locked/short video: ${json.optCleanString("id")}")
                            continue
                        }

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
                        val rawTimestamp = json.optLong("timestamp", 0L)
                        val rawDate = json.optCleanString("upload_date")
                        val timestamp = if (rawTimestamp > 0L) {
                            rawTimestamp
                        } else if (rawDate.length == 8 && rawDate.all { it.isDigit() }) {
                            try {
                                val sdf = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                                }
                                sdf.parse(rawDate)?.time?.div(1000L) ?: 0L
                            } catch (_: Exception) {
                                0L
                            }
                        } else {
                            0L
                        }
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
     * Attempts ultra-fast direct HTTP parsing of ytInitialData first (~300-800ms, immune to lockupViewModel issues),
     * falling back to yt-dlp CLI if needed.
     */
    suspend fun searchChannels(
        query: String,
        maxResults: Int = 8,
        priority: TaskPriority = TaskPriority.INTERACTIVE
    ): Result<List<Channel>> = withContext(networkDispatcher) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.Success(emptyList())

        val encodedQuery = URLEncoder.encode(trimmed, "UTF-8")
        val channelSearchUrl = "https://www.youtube.com/results?search_query=$encodedQuery&sp=EgIQAg%3D%3D"

        // 1. Direct HTTP extraction first
        try {
            val directChannels = fetchChannelsDirectHttp(channelSearchUrl, maxResults)
            if (directChannels.isNotEmpty()) {
                Log.i(TAG, "Direct channel search for '$trimmed' succeeded with ${directChannels.size} channels")
                return@withContext Result.Success(directChannels)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Direct channel search failed for '$trimmed', falling back to yt-dlp: ${e.message}")
        }

        // 2. Fallback to yt-dlp CLI
        executeQueued(priority, "SearchChannels:$trimmed") {
            try {
                ensureReady()
                val request = YoutubeDLRequest(channelSearchUrl).apply {
                    addOption("--flat-playlist")
                    addOption("--dump-json")
                    addOption("--skip-download")
                    addOption("--no-warnings")
                    addOption("--playlist-end", maxResults.toString())
                }

                Log.i(TAG, "Searching channels with yt-dlp for '$trimmed'")
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
                        var subs = json.optLong("channel_follower_count", 0L)
                        var subsText = json.optCleanString("channel_follower_count_text").ifEmpty {
                            json.optCleanString("subscribers")
                        }
                        if (subs == 0L && subsText.isNotEmpty()) {
                            subs = parseSubscriberCount(subsText)
                        } else if (subs > 0L && subsText.isEmpty()) {
                            subsText = formatSubscriberCount(subs)
                        }

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
                                subscriberCountText = subsText.ifEmpty { formatSubscriberCount(subs) }
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

    private data class ParsedChannel(
        val id: String,
        val title: String,
        val handle: String,
        val subs: Long,
        val subsText: String,
        val avatar: String
    )

    private fun fetchChannelsDirectHttp(channelSearchUrl: String, maxResults: Int): List<Channel> {
        val conn = (java.net.URL(channelSearchUrl).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            connectTimeout = 6000
            readTimeout = 8000
            instanceFollowRedirects = true
        }

        val html = conn.inputStream.bufferedReader().use { it.readText() }
        val idx = html.indexOf("ytInitialData = ")
        if (idx == -1) return emptyList()

        val start = idx + "ytInitialData = ".length
        var end = html.indexOf(";</script>", start)
        if (end == -1) {
            end = html.indexOf(";\n", start)
        }
        if (end == -1) {
            end = html.indexOf("};", start)
            if (end != -1) end += 1
        }
        if (end <= start) return emptyList()

        val jsonStr = html.substring(start, end).trim()
        val root = org.json.JSONObject(jsonStr)

        val rawRenderers = mutableListOf<org.json.JSONObject>()
        extractRenderersRecursive(root, rawRenderers)

        val channels = mutableListOf<Channel>()
        val seenIds = mutableSetOf<String>()

        for (item in rawRenderers) {
            if (channels.size >= maxResults) break
            val parsed = parseChannelItem(item) ?: continue
            if (parsed.id.isEmpty() || seenIds.contains(parsed.id)) continue
            seenIds.add(parsed.id)

            channels.add(
                Channel(
                    id = parsed.id,
                    title = parsed.title,
                    handle = parsed.handle,
                    avatarUrl = parsed.avatar,
                    subscriberCount = parsed.subs,
                    subscriberCountText = parsed.subsText.ifEmpty { formatSubscriberCount(parsed.subs) }
                )
            )
        }

        return channels
    }

    private fun extractRenderersRecursive(obj: Any, out: MutableList<org.json.JSONObject>) {
        when (obj) {
            is org.json.JSONObject -> {
                if (obj.has("channelRenderer")) {
                    obj.optJSONObject("channelRenderer")?.let { out.add(it) }
                } else if (obj.has("lockupViewModel")) {
                    obj.optJSONObject("lockupViewModel")?.let { out.add(it) }
                } else {
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        extractRenderersRecursive(obj.get(k), out)
                    }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until obj.length()) {
                    extractRenderersRecursive(obj.get(i), out)
                }
            }
        }
    }

    private fun parseChannelItem(obj: org.json.JSONObject): ParsedChannel? {
        val id = obj.optCleanString("channelId").ifEmpty {
            obj.optCleanString("contentId")
        }
        if (id.isEmpty()) return null

        val title = obj.optJSONObject("title")?.optCleanString("simpleText")?.ifEmpty {
            obj.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optCleanString("text")
        } ?: obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.optJSONObject("title")?.optCleanString("content") ?: ""

        if (title.isEmpty()) return null

        val navUrl = obj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optCleanString("canonicalBaseUrl")
            ?: obj.optJSONObject("rendererContext")?.optJSONObject("commandContext")?.optJSONObject("onTap")
                ?.optJSONObject("innertubeCommand")?.optJSONObject("browseEndpoint")?.optCleanString("canonicalBaseUrl")
            ?: ""

        val s1 = obj.optJSONObject("subscriberCountText")?.optCleanString("simpleText") ?: ""
        val s2 = obj.optJSONObject("videoCountText")?.optCleanString("simpleText") ?: ""

        var handle = ""
        if (navUrl.startsWith("/@")) {
            handle = navUrl.removePrefix("/")
        } else if (s1.startsWith("@")) {
            handle = s1
        } else if (s2.startsWith("@")) {
            handle = s2
        }

        var subs = 0L
        var subsText = ""
        if (s1.contains("subscriber", ignoreCase = true)) {
            subsText = s1
            subs = parseSubscriberCount(s1)
        } else if (s2.contains("subscriber", ignoreCase = true)) {
            subsText = s2
            subs = parseSubscriberCount(s2)
        } else {
            val p1 = parseSubscriberCount(s1)
            val p2 = parseSubscriberCount(s2)
            if (p1 > 0) {
                subs = p1
                subsText = s1
            } else if (p2 > 0) {
                subs = p2
                subsText = s2
            }
        }

        var avatar = ""
        val thumbs = obj.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?: obj.optJSONObject("image")?.optJSONArray("sources")

        if (thumbs != null && thumbs.length() > 0) {
            for (i in 0 until thumbs.length()) {
                val t = thumbs.optJSONObject(i) ?: continue
                val u = t.optCleanString("url")
                val w = t.optInt("width", 0)
                if (u.isNotEmpty() && (w in 88..250)) {
                    avatar = u
                    break
                }
            }
            if (avatar.isEmpty()) {
                avatar = thumbs.optJSONObject(0)?.optCleanString("url") ?: ""
            }
        }
        if (avatar.startsWith("//")) {
            avatar = "https:$avatar"
        }

        return ParsedChannel(id, title, handle, subs, subsText, avatar)
    }

    /**
     * Fetches videos from a channel using its ID or handle with windowed pagination (--playlist-start and --playlist-end).
     * Automatically filters out private, membership-only, and locked videos.
     */
    suspend fun fetchChannelVideos(
        channelId: String,
        handle: String = "",
        channelTitle: String = "",
        maxResults: Int = 30,
        startIndex: Int = 1,
        priority: TaskPriority = TaskPriority.BACKGROUND
    ): Result<List<Video>> = withContext(networkDispatcher) {
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
            searchContent(target, maxResults = maxResults, startIndex = startIndex, priority = priority)
        } else {
            val targetEnd = startIndex + maxResults - 1
            searchContent("ytsearch$targetEnd:$channelTitle", maxResults = maxResults, startIndex = startIndex, priority = priority)
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
                    val fallback = searchContent("ytsearch$maxResults:$channelTitle", maxResults = maxResults, startIndex = startIndex, priority = priority)
                    if (fallback is Result.Success) {
                        Result.Success(fallback.data.map { it.copy(channelId = channelId) })
                    } else fallback
                } else {
                    Result.Success(emptyList())
                }
            }
            is Result.Failure -> {
                if (channelTitle.isNotBlank() && target.isNotEmpty()) {
                    val fallback = searchContent("ytsearch$maxResults:$channelTitle", maxResults = maxResults, startIndex = startIndex, priority = priority)
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
