package com.amatsagu.reson.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import com.amatsagu.reson.R

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
    val aspectRatio: Float = 0f,
)

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
    val lastSyncAt: Long = 0L,
)

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

enum class ResonTab(@StringRes val titleRes: Int) {
    Feed(R.string.tab_feed),
    Channels(R.string.tab_channels),
    Library(R.string.tab_library),
    Settings(R.string.tab_settings)
}
