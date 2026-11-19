package com.amatsagu.reson.model

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
    val thumbnailUrl: String = "",
    val webpageUrl: String = "https://www.youtube.com/watch?v=$id",
    val playbackProgress: Float = 0f,
    val isDownloaded: Boolean = false,
    val downloadProgress: Float? = null
)

/**
 * Bottom navigation destinations.
 */
enum class ResonTab(val label: String) {
    Feed("Feed"),
    Channels("Channels"),
    Library("Library")
}
