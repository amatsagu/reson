package com.amatsagu.reson.data.remote

import android.util.Log
import com.amatsagu.reson.Result
import com.amatsagu.reson.YtDlpManager
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import com.amatsagu.reson.model.Video
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * High-performance, lightweight YouTube RSS/Atom feed parser.
 * Fetches recent uploads from public channel feeds in ~150-200ms without python/yt-dlp overhead.
 * Includes rate-limit detection and safety backoff.
 */
object YouTubeRssFetcher {

    private const val TAG = "Reson:RssFetcher"
    private const val RSS_BASE_URL = "https://www.youtube.com/feeds/videos.xml?channel_id="

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; 2412DPC0AG) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var rateLimitCooldownUntilMs: Long = 0L

    fun isRateLimited(): Boolean = System.currentTimeMillis() < rateLimitCooldownUntilMs

    /**
     * Fetches up to 15 latest uploads for a given channel via Atom RSS.
     * Returns Result.Success(videos) or Result.Failure.
     */
    suspend fun fetchChannelVideos(
        channelId: String,
        fallbackChannelTitle: String = ""
    ): Result<List<Video>> = withContext(ResonDispatchers.Network) {
        val cleanId = channelId.trim()
        if (cleanId.isEmpty() || !cleanId.startsWith("UC")) {
            return@withContext Result.Failure(
                IllegalArgumentException("Invalid YouTube channel ID: $cleanId"),
                "Invalid channel ID"
            )
        }

        if (isRateLimited()) {
            val remainingSec = (rateLimitCooldownUntilMs - System.currentTimeMillis()) / 1000
            Log.w(TAG, "RSS fetch skipped for $cleanId: rate-limit cooldown active ($remainingSec s remaining)")
            return@withContext Result.Failure(
                IllegalStateException("Rate limit cooldown active"),
                "Rate limit active"
            )
        }

        var connection: HttpURLConnection? = null
        try {
            val feedUrl = URL(RSS_BASE_URL + cleanId)
            connection = (feedUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/atom+xml,application/xml,text/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                instanceFollowRedirects = true
            }

            val responseCode = connection.responseCode
            if (responseCode == 429 || responseCode == 503) {
                // Rate limited: activate 5-minute cooldown to protect client IP
                rateLimitCooldownUntilMs = System.currentTimeMillis() + (5 * 60 * 1000L)
                Log.w(TAG, "Received HTTP $responseCode from YouTube RSS for $cleanId. Cooldown activated for 5m.")
                return@withContext Result.Failure(
                    IllegalStateException("HTTP $responseCode from YouTube"),
                    "Rate limited by YouTube"
                )
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                connection.errorStream?.close()
                return@withContext Result.Failure(
                    IllegalStateException("HTTP error $responseCode"),
                    "Failed to fetch feed: HTTP $responseCode"
                )
            }

            val videos = connection.inputStream.use { stream ->
                parseAtomFeed(stream, cleanId, fallbackChannelTitle)
            }
            Log.d(TAG, "RSS fetch for $cleanId succeeded. Found ${videos.size} uploads.")
            Result.Success(videos)
        } catch (e: Throwable) {
            Log.d(TAG, "RSS fetch error for $cleanId: ${e.message}")
            Result.Failure(e, e.message ?: "Failed to fetch RSS feed")
        } finally {
            connection?.disconnect()
        }
    }

    fun parseAtomFeed(
        inputStream: InputStream,
        fallbackChannelId: String,
        fallbackChannelTitle: String
    ): List<Video> {
        val videos = mutableListOf<Video>()
        try {
            val dbFactory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                } catch (_: Throwable) {}
            }
            val dBuilder = dbFactory.newDocumentBuilder()
            val doc = dBuilder.parse(inputStream)
            doc.documentElement.normalize()

            val entries = doc.getElementsByTagName("entry")
            for (i in 0 until entries.length) {
                val entry = entries.item(i) as? org.w3c.dom.Element ?: continue
                var videoId = ""
                val ytVideoIdNodes = entry.getElementsByTagNameNS("http://www.youtube.com/xml/schemas/2015", "videoId")
                if (ytVideoIdNodes.length > 0) {
                    videoId = ytVideoIdNodes.item(0).textContent.trim()
                }
                if (videoId.isEmpty()) {
                    val idNodes = entry.getElementsByTagName("id")
                    if (idNodes.length > 0) {
                        val idText = idNodes.item(0).textContent.trim()
                        if (idText.startsWith("yt:video:")) {
                            videoId = idText.removePrefix("yt:video:")
                        }
                    }
                }
                if (videoId.isEmpty()) continue

                var title = ""
                val titleNodes = entry.getElementsByTagName("title")
                if (titleNodes.length > 0) {
                    title = titleNodes.item(0).textContent.trim()
                }
                if (title.isEmpty() || YtDlpManager.isLockedVideoTitle(title)) continue

                var authorName = fallbackChannelTitle
                val authorNodes = entry.getElementsByTagName("author")
                if (authorNodes.length > 0) {
                    val authorElem = authorNodes.item(0) as? org.w3c.dom.Element
                    val nameNodes = authorElem?.getElementsByTagName("name")
                    if (nameNodes != null && nameNodes.length > 0) {
                        authorName = nameNodes.item(0).textContent.trim()
                    }
                }

                var publishedStr = ""
                val pubNodes = entry.getElementsByTagName("published")
                if (pubNodes.length > 0) {
                    publishedStr = pubNodes.item(0).textContent.trim()
                }
                val timestamp = parseIsoTimestamp(publishedStr)
                val relDate = YtDlpManager.formatRelativeDate(timestamp)

                var thumbnailUrl = "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                val thumbNodes = entry.getElementsByTagNameNS("http://search.yahoo.com/mrss/", "thumbnail")
                if (thumbNodes.length > 0) {
                    val thumbElem = thumbNodes.item(0) as? org.w3c.dom.Element
                    val u = thumbElem?.getAttribute("url")
                    if (!u.isNullOrBlank()) {
                        thumbnailUrl = u
                    }
                }

                videos.add(
                    Video(
                        id = videoId,
                        title = title,
                        channelTitle = authorName,
                        channelId = fallbackChannelId,
                        durationSeconds = 0,
                        durationText = "",
                        releaseDateText = relDate,
                        releaseTimestamp = timestamp,
                        thumbnailUrl = thumbnailUrl,
                        webpageUrl = "https://www.youtube.com/watch?v=$videoId"
                    )
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error parsing Atom feed: ${e.message}", e)
        }
        return videos
    }

    private fun parseIsoTimestamp(isoString: String): Long {
        if (isoString.isBlank()) return 0L
        return try {
            Instant.parse(isoString).epochSecond
        } catch (_: Exception) {
            0L
        }
    }
}
