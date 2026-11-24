package com.amatsagu.reson

import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.Video

object DynamicTopicExtractor {

    private val LEADING_FILLERS = setOf(
        "so", "why", "how", "what", "here", "is", "the", "a", "an", "this", "that", "to", "my",
    )

    private val EPISODE_PATTERN = Regex(
        "^(#|ep\\.?|episode|part|vol\\.?|sezon|odc\\.?|vlog)\\s*\\d+.*$",
        RegexOption.IGNORE_CASE,
    )

    fun extractSearchQuery(video: Video, channel: Channel? = null): String? {
        val title = video.title.trim()
        if (title.isBlank()) return null

        val segments = title.split(Regex("[|:—–•/\\\\]+"))
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        val channelName = channel?.title?.trim().orEmpty()
        val descriptiveSegment = segments.firstOrNull { segment ->
            val isEpisodeCounter = segment.matches(EPISODE_PATTERN)
            val isChannelName = channelName.isNotEmpty() && segment.equals(channelName, ignoreCase = true)
            (!isEpisodeCounter) && (!isChannelName) && (segment.length >= 6)
        } ?: segments.maxByOrNull { it.length } ?: title

        var cleaned = descriptiveSegment.replace(Regex("\\[.*?]|\\(.*?\\)"), " ")

        if (channelName.isNotEmpty()) {
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(channelName)}\\b"), " ")
        }
        val handle = channel?.handle?.removePrefix("@")?.trim().orEmpty()
        if (handle.isNotEmpty()) {
            cleaned = cleaned.replace(Regex("(?i)\\b${Regex.escape(handle)}\\b"), " ")
        }

        cleaned = cleaned.replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val tokens = cleaned.split(" ").filter { it.length >= 2 }
        if (tokens.isEmpty()) return null

        val contentTokens = tokens.dropWhile { it.lowercase() in LEADING_FILLERS }
        val finalTokens = if (contentTokens.size >= 2) contentTokens else tokens

        val query = finalTokens.asSequence().take(5).joinToString(" ").trim()
        return if (query.length >= 4) query else null
    }
}
