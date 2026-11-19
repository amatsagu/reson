package com.amatsagu.reson.model

import androidx.annotation.StringRes
import com.amatsagu.reson.R

/**
 * Domain model representing a YouTube video item.
 */
data class Video(
    val id: String,
    val title: String,
    val channelTitle: String,
    val durationText: String,
    val durationSeconds: Int = 0,
    val releaseDateText: String = "",
    val thumbnailUrl: String = "",
    val webpageUrl: String = "https://www.youtube.com/watch?v=$id",
    val playbackProgress: Float = 0f,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float? = null
)

/**
 * Representation of a creator / YouTube channel.
 */
data class Channel(
    val id: String,
    val title: String,
    val handle: String = "",
    val avatarUrl: String = "",
    val subscriberCount: Long = 0L,
    val subscriberCountText: String = "",
    val isFollowed: Boolean = false,
    val lastVideoReleaseSec: Long = 0L
)

/**
 * Union type for heterogeneous search results (videos and followable creators).
 */
sealed interface SearchResultItem {
    val id: String

    data class Video(val video: com.amatsagu.reson.model.Video) : SearchResultItem {
        override val id: String = "video_${video.id}"
    }

    data class Creator(val channel: Channel) : SearchResultItem {
        override val id: String = "channel_${channel.id}"
    }
}

/**
 * Bottom navigation destinations with localized string resource IDs.
 */
enum class ResonTab(@StringRes val titleRes: Int) {
    Feed(R.string.tab_feed),
    Channels(R.string.tab_channels),
    Library(R.string.tab_library)
}
