package com.amatsagu.reson.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.amatsagu.reson.R

/**
 * Domain model representing a YouTube video item.
 */
@Immutable
data class Video(
    val id: String,
    val title: String,
    val channelTitle: String,
    val durationText: String,
    val durationSeconds: Int = 0,
    val releaseDateText: String = "",
    val releaseTimestamp: Long = 0L,
    val thumbnailUrl: String = "",
    val webpageUrl: String = "https://www.youtube.com/watch?v=$id",
    val playbackProgress: Float = 0f,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadFilePath: String? = null,
    val isDownloadQueued: Boolean = false,
    val channelId: String = "",
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val lastPlayedAt: Long = 0L,
)

/**
 * Representation of a creator / YouTube channel.
 */
@Immutable
data class Channel(
    val id: String,
    val title: String,
    val handle: String = "",
    val avatarUrl: String = "",
    val avatarBase64: String = "",
    val subscriberCount: Long = 0L,
    val subscriberCountText: String = "",
    val isFollowed: Boolean = false,
    val lastVideoReleaseSec: Long = 0L,
    val followedAt: Long = 0L,
)

/**
 * Union type for heterogeneous search results (videos and followable creators).
 */
@Immutable
sealed interface SearchResultItem {
    val id: String

    @Immutable
    data class Video(val video: com.amatsagu.reson.model.Video) : SearchResultItem {
        override val id: String = "video_${video.id}"
    }

    @Immutable
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
    Library(R.string.tab_library),
    Settings(R.string.tab_settings)
}
