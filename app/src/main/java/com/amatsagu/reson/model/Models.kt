package com.amatsagu.reson.model

import androidx.annotation.StringRes
import com.amatsagu.reson.R

/**
 * Representation of a podcast episode / video item.
 * In Go terms, this is our core domain struct.
 */
data class Episode(
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
 * Bottom navigation destinations with localized string resource IDs.
 */
enum class ResonTab(@StringRes val titleRes: Int) {
    Feed(R.string.tab_feed),
    Channels(R.string.tab_channels),
    Library(R.string.tab_library)
}
