package com.amatsagu.reson.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.amatsagu.reson.model.Video

/**
 * Data access operations for the videos table.
 */
class VideoDao {

    fun upsertVideo(db: SQLiteDatabase, video: Video, channelId: String = "") {
        val values = toContentValues(video, channelId)
        db.insertWithOnConflict("videos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertVideos(db: SQLiteDatabase, videos: List<Video>, defaultChannelId: String = "") {
        if (videos.isEmpty()) return
        db.beginTransaction()
        try {
            for (video in videos) {
                val effectiveChannelId = video.channelId.ifEmpty { defaultChannelId }
                val values = toContentValues(video, effectiveChannelId)
                db.insertWithOnConflict("videos", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getVideoById(db: SQLiteDatabase, videoId: String): Video? {
        db.rawQuery("SELECT * FROM videos WHERE id = ? LIMIT 1", arrayOf(videoId)).use { cursor ->
            return if (cursor.moveToFirst()) cursorToVideo(cursor) else null
        }
    }

    fun getFeedVideos(db: SQLiteDatabase, limit: Int = 50): List<Video> {
        val videos = mutableListOf<Video>()
        // Join with followed channels so we only fetch content from active subscriptions
        val query = """
            SELECT v.* FROM videos v
            INNER JOIN channels c ON v.channel_id = c.id
            WHERE c.is_followed = 1
            ORDER BY v.release_timestamp DESC, v.created_at DESC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                videos.add(cursorToVideo(cursor))
            }
        }
        return videos
    }

    fun getContinueListeningVideos(db: SQLiteDatabase, limit: Int = 10): List<Video> {
        val videos = mutableListOf<Video>()
        // In-progress: between 2% and 95% complete, sorted by most recently listened
        val query = """
            SELECT * FROM videos
            WHERE playback_progress > 0.02 AND playback_progress < 0.95
            ORDER BY last_played_at DESC
            LIMIT ?
        """.trimIndent()
        db.rawQuery(query, arrayOf(limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                videos.add(cursorToVideo(cursor))
            }
        }
        return videos
    }

    fun getVideosForChannel(db: SQLiteDatabase, channelId: String, limit: Int = 50): List<Video> {
        val videos = mutableListOf<Video>()
        val query = "SELECT * FROM videos WHERE channel_id = ? ORDER BY release_timestamp DESC LIMIT ?"
        db.rawQuery(query, arrayOf(channelId, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                videos.add(cursorToVideo(cursor))
            }
        }
        return videos
    }

    fun getDownloadedVideos(db: SQLiteDatabase): List<Video> {
        val videos = mutableListOf<Video>()
        val query = "SELECT * FROM videos WHERE is_downloaded = 1 ORDER BY last_played_at DESC, created_at DESC"
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                videos.add(cursorToVideo(cursor))
            }
        }
        return videos
    }

    fun updatePlaybackProgress(
        db: SQLiteDatabase,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
    ): Boolean {
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        val values = ContentValues().apply {
            put("playback_position_ms", positionMs)
            put("playback_duration_ms", durationMs)
            put("playback_progress", progress)
            put("last_played_at", timestamp)
        }
        return db.update("videos", values, "id = ?", arrayOf(videoId)) > 0
    }

    fun updateDownloadStatus(db: SQLiteDatabase, videoId: String, isDownloaded: Boolean, filePath: String?): Boolean {
        val values = ContentValues().apply {
            put("is_downloaded", if (isDownloaded) 1 else 0)
            put("download_file_path", filePath)
        }
        return db.update("videos", values, "id = ?", arrayOf(videoId)) > 0
    }

    fun countVideos(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM videos", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    fun countDownloaded(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM videos WHERE is_downloaded = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun toContentValues(video: Video, channelId: String): ContentValues {
        val effectiveChannelId = video.channelId.ifEmpty { channelId }
        return ContentValues().apply {
            put("id", video.id)
            put("channel_id", effectiveChannelId)
            put("channel_title", video.channelTitle)
            put("title", video.title)
            put("duration_seconds", video.durationSeconds)
            put("duration_text", video.durationText)
            put("release_date_text", video.releaseDateText)
            put("thumbnail_url", video.thumbnailUrl)
            put("webpage_url", video.webpageUrl)
            put("playback_progress", video.playbackProgress)
            put("playback_position_ms", video.playbackPositionMs)
            put("playback_duration_ms", video.playbackDurationMs)
            put("last_played_at", if (video.lastPlayedAt > 0) video.lastPlayedAt else 0L)
            put("is_downloaded", if (video.isDownloaded) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }
    }

    private fun cursorToVideo(cursor: Cursor): Video {
        return Video(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
            channelTitle = cursor.getString(cursor.getColumnIndexOrThrow("channel_title")),
            durationText = cursor.getString(cursor.getColumnIndexOrThrow("duration_text")),
            durationSeconds = cursor.getInt(cursor.getColumnIndexOrThrow("duration_seconds")),
            releaseDateText = cursor.getString(cursor.getColumnIndexOrThrow("release_date_text")),
            thumbnailUrl = cursor.getString(cursor.getColumnIndexOrThrow("thumbnail_url")),
            webpageUrl = cursor.getString(cursor.getColumnIndexOrThrow("webpage_url")),
            playbackProgress = cursor.getFloat(cursor.getColumnIndexOrThrow("playback_progress")),
            isDownloaded = cursor.getInt(cursor.getColumnIndexOrThrow("is_downloaded")) == 1,
            channelId = cursor.getString(cursor.getColumnIndexOrThrow("channel_id")),
            playbackPositionMs = cursor.getLong(cursor.getColumnIndexOrThrow("playback_position_ms")),
            playbackDurationMs = cursor.getLong(cursor.getColumnIndexOrThrow("playback_duration_ms")),
            lastPlayedAt = cursor.getLong(cursor.getColumnIndexOrThrow("last_played_at")),
        )
    }
}
