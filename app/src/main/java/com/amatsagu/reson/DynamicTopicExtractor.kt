package com.amatsagu.reson

import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.Video

/**
 * Dynamically extracts high-signal search topic keywords from video titles and channels
 * without hardcoding domains, genres, or static keywords.
 */
object DynamicTopicExtractor {

    private val LEADING_FILLERS = setOf(
        "so", "why", "how", "what", "here", "is", "the", "a", "an", "this", "that", "to", "my"
    )

    private val EPISODE_PATTERN = Regex(
        "^(#|ep\\.?|episode|part|vol\\.?|sezon|odc\\.?|vlog)\\s*\\d+.*$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Extracts a concise, topic-focused search query from a video's title.
     * Retains Unicode characters so it works across languages (English, Polish, etc.).
     */
    fun extractSearchQuery(video: Video, channel: Channel? = null): String? {
        val title = video.title.trim()
        if (title.isBlank()) return null

        // 1. Split compound titles by common video title separators (e.g. "Title | Series #1", "Topic: Subtopic")
        val segments = title.split(Regex("[|:—–•/\\\\]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // 2. Select the most descriptive segment (discarding episode counters, short tags, and channel names)
        val channelName = channel?.title?.trim().orEmpty()
        val descriptiveSegment = segments.firstOrNull { segment ->
            val isEpisodeCounter = segment.matches(EPISODE_PATTERN)
            val isChannelName = channelName.isNotEmpty() && segment.equals(channelName, ignoreCase = true)
            !isEpisodeCounter && !isChannelName && segment.length >= 6
        } ?: segments.maxByOrNull { it.length } ?: title

        // 3. Strip bracketed noise (e.g. "[Official Video]", "(4K 60FPS)", "[CC]")
        var cleaned = descriptiveSegment.replace(Regex("\\[.*?]|\\(.*?\\)"), " ")

        // 4. Strip the channel title or handle if it appears as an isolated token in the segment
        if (channelName.isNotEmpty()) {
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(channelName)}\\b"), " ")
        }
        val handle = channel?.handle?.removePrefix("@")?.trim().orEmpty()
        if (handle.isNotEmpty()) {
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(handle)}\\b"), " ")
        }

        // 5. Retain only letters, numbers, and spaces across all languages (Unicode)
        cleaned = cleaned.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = cleaned.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        // 6. Strip leading conversational/rhetorical filler words
        val contentTokens = tokens.dropWhile { it.lowercase() in LEADING_FILLERS }
        val finalTokens = if (contentTokens.size >= 2) contentTokens else tokens

        // 7. Take 2 to 5 descriptive words to form a focused, high-relevance search query
        val query = finalTokens.take(5).joinToString(" ").trim()
        return if (query.length >= 4) query else null
    }
}
