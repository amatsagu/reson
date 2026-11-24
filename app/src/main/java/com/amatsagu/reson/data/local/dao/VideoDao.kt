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
        val effectiveChannelId = video.channelId.ifEmpty { channelId }
        val values = toContentValues(video, effectiveChannelId)

        // Try inserting with CONFLICT_IGNORE if row is new
        val rowId = db.insertWithOnConflict("videos", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        if (rowId == -1L) {
            // Row already exists: update fields while preserving existing playback, download, and created_at states
            values.remove("created_at")
            val existing = getVideoById(db, video.id)
            if (existing != null) {
                if (video.playbackPositionMs == 0L && (existing.playbackPositionMs > 0L)) {
                    values.put("playback_position_ms", existing.playbackPositionMs)
                    values.put("playback_duration_ms", existing.playbackDurationMs)
                    values.put("playback_progress", existing.playbackProgress)
                    values.put("last_played_at", existing.lastPlayedAt)
                }
                if (video.durationText.isBlank() && existing.durationText.isNotBlank()) {
                    values.put("duration_text", existing.durationText)
                }
                if (video.durationSeconds <= 0 && existing.durationSeconds > 0) {
                    values.put("duration_seconds", existing.durationSeconds)
                }
                if (video.playbackDurationMs <= 0L && existing.playbackDurationMs > 0L) {
                    values.put("playback_duration_ms", existing.playbackDurationMs)
                }
                if (video.releaseDateText.isBlank() && existing.releaseDateText.isNotBlank()) {
                    values.put("release_date_text", existing.releaseDateText)
                }
                if (video.releaseTimestamp <= 0L && existing.releaseTimestamp > 0L) {
                    values.put("release_timestamp", existing.releaseTimestamp)
                }
                if (!video.isDownloaded && existing.isDownloaded) {
                    values.put("is_downloaded", 1)
                    existing.downloadFilePath?.let { values.put("download_file_path", it) }
                }
            }
            db.update("videos", values, "id = ?", arrayOf(video.id))
        }
    }

    fun upsertVideos(db: SQLiteDatabase, videos: List<Video>, defaultChannelId: String = "") {
        if (videos.isEmpty()) return
        db.beginTransaction()
        try {
            for (video in videos) {
                upsertVideo(db, video, defaultChannelId)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getVideoById(db: SQLiteDatabase, videoId: String): Video? {
        db.rawQuery("SELECT * FROM videos WHERE id = ? LIMIT 1", arrayOf(videoId)).use { cursor ->
            return if (cursor.moveToFirst()) cursorToVideo(cursor, VideoCursorIndices(cursor)) else null
        }
    }

    fun getFeedVideos(db: SQLiteDatabase, limit: Int = 30, offset: Int = 0): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = """
            SELECT v.* FROM videos v
            INNER JOIN channels c ON v.channel_id = c.id
            WHERE c.is_followed = 1
              AND (v.aspect_ratio <= 0.0 OR v.aspect_ratio >= 1.0)
              AND v.title NOT LIKE '%#shorts%' AND v.title NOT LIKE '%#short%' AND v.webpage_url NOT LIKE '%/shorts/%'
              AND v.title NOT LIKE '%[Private video]%' AND v.title NOT LIKE '%[Deleted video]%'
              AND v.title NOT LIKE '%members-only%' AND v.title NOT LIKE '%members only%'
            ORDER BY v.release_timestamp DESC, v.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    /**
     * Fetches unwatched videos from followed channels (last_played_at == 0 and playback_progress == 0).
     */
    fun getUnwatchedFollowedVideos(
        db: SQLiteDatabase,
        limit: Int = 30,
        offset: Int = 0,
    ): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = """
            SELECT v.* FROM videos v
            INNER JOIN channels c ON v.channel_id = c.id
            WHERE c.is_followed = 1
              AND v.last_played_at = 0
              AND v.playback_progress = 0
              AND (v.aspect_ratio <= 0.0 OR v.aspect_ratio >= 1.0)
              AND v.title NOT LIKE '%#shorts%' AND v.title NOT LIKE '%#short%' AND v.webpage_url NOT LIKE '%/shorts/%'
              AND v.title NOT LIKE '%[Private video]%' AND v.title NOT LIKE '%[Deleted video]%'
              AND v.title NOT LIKE '%members-only%' AND v.title NOT LIKE '%members only%'
            ORDER BY v.release_timestamp DESC, v.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    /**
     * Fetches recommended discovery videos (channels NOT followed) that have not been watched yet.
     * Hidden bias: Sorts videos/channels without "news" or "america" before biased ones.
     */
    fun getRecommendedDiscoveryVideos(
        db: SQLiteDatabase,
        limit: Int = 30,
        offset: Int = 0
    ): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = """
            SELECT v.* FROM videos v
            LEFT JOIN channels c ON v.channel_id = c.id
            WHERE (c.is_followed IS NULL OR c.is_followed = 0)
              AND v.last_played_at = 0
              AND v.playback_progress = 0
              AND (v.aspect_ratio <= 0.0 OR v.aspect_ratio >= 1.0)
              AND v.title NOT LIKE '%#shorts%' AND v.title NOT LIKE '%#short%' AND v.webpage_url NOT LIKE '%/shorts/%'
              AND v.title NOT LIKE '%[Private video]%' AND v.title NOT LIKE '%[Deleted video]%'
              AND v.title NOT LIKE '%members-only%' AND v.title NOT LIKE '%members only%'
            ORDER BY 
              CASE 
                WHEN LOWER(v.title) LIKE '%news%' OR LOWER(v.title) LIKE '%america%' 
                     OR LOWER(v.channel_title) LIKE '%news%' OR LOWER(v.channel_title) LIKE '%america%' 
                THEN 1 
                ELSE 0 
              END ASC,
              v.created_at DESC, v.release_timestamp DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    fun getContinueListeningVideos(db: SQLiteDatabase, limit: Int = 10, offset: Int = 0): List<Video> {
        val safeLimit = limit.coerceIn(1, 100)
        val safeOffset = offset.coerceAtLeast(0)
        val query = """
            SELECT * FROM videos
            WHERE last_played_at > 0 
              AND (playback_progress > 0.01 OR playback_position_ms > 3000) 
              AND playback_progress < 0.98
            ORDER BY last_played_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    fun getVideosForChannel(db: SQLiteDatabase, channelId: String, limit: Int = 30, offset: Int = 0): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = "SELECT * FROM videos WHERE channel_id = ? AND (aspect_ratio <= 0.0 OR aspect_ratio >= 1.0) AND title NOT LIKE '%#shorts%' AND title NOT LIKE '%#short%' AND webpage_url NOT LIKE '%/shorts/%' AND title NOT LIKE '%[Private video]%' AND title NOT LIKE '%[Deleted video]%' AND title NOT LIKE '%members-only%' AND title NOT LIKE '%members only%' ORDER BY release_timestamp DESC, created_at DESC LIMIT ? OFFSET ?"
        return queryVideos(db, query, arrayOf(channelId, safeLimit.toString(), safeOffset.toString()))
    }

    /**
     * Fetches all videos from followed channels released within the last 1 month (cutoff: minTimestampSec),
     * ordered by release date/time (newest first).
     */
    fun getVideosFromFollowedWithinMonth(
        db: SQLiteDatabase,
        minTimestampSec: Long,
        minCreatedAtMs: Long,
        limit: Int = 30,
        offset: Int = 0,
    ): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = """
            SELECT v.* FROM videos v
            INNER JOIN channels c ON v.channel_id = c.id
            WHERE c.is_followed = 1
              AND ((v.release_timestamp > 0 AND v.release_timestamp >= ?) OR (v.release_timestamp <= 0 AND v.created_at >= ?))
              AND (v.aspect_ratio <= 0.0 OR v.aspect_ratio >= 1.0)
              AND v.title NOT LIKE '%#shorts%' AND v.title NOT LIKE '%#short%' AND v.webpage_url NOT LIKE '%/shorts/%'
              AND v.title NOT LIKE '%[Private video]%' AND v.title NOT LIKE '%[Deleted video]%'
              AND v.title NOT LIKE '%members-only%' AND v.title NOT LIKE '%members only%'
            ORDER BY v.release_timestamp DESC, v.created_at DESC
            LIMIT ? OFFSET ?
        """.trimIndent()
        return queryVideos(db, query, arrayOf(minTimestampSec.toString(), minCreatedAtMs.toString(), safeLimit.toString(), safeOffset.toString()))
    }

    fun getDownloadedVideos(db: SQLiteDatabase, limit: Int = 100, offset: Int = 0): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = "SELECT * FROM videos WHERE is_downloaded = 1 ORDER BY last_played_at DESC, created_at DESC LIMIT ? OFFSET ?"
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    fun getPlaybackHistory(db: SQLiteDatabase, limit: Int = 100, offset: Int = 0): List<Video> {
        val safeLimit = limit.coerceIn(1, 500)
        val safeOffset = offset.coerceAtLeast(0)
        val query = "SELECT * FROM videos WHERE last_played_at > 0 ORDER BY last_played_at DESC LIMIT ? OFFSET ?"
        return queryVideos(db, query, arrayOf(safeLimit.toString(), safeOffset.toString()))
    }

    fun clearPlaybackHistory(db: SQLiteDatabase): Boolean {
        val query = "UPDATE videos SET last_played_at = 0, playback_position_ms = 0, playback_progress = 0.0 WHERE last_played_at > 0"
        db.execSQL(query)
        return true
    }

    fun updatePlaybackProgress(
        db: SQLiteDatabase,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        timestamp: Long = System.currentTimeMillis(),
        trimHistory: Boolean = false,
    ): Boolean {
        val progress = if (durationMs > 0) (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f
        val values = ContentValues().apply {
            put("playback_position_ms", positionMs)
            put("playback_duration_ms", durationMs)
            put("playback_progress", progress)
            put("last_played_at", timestamp)
        }
        val updated = db.update("videos", values, "id = ?", arrayOf(videoId)) > 0
        if (updated && trimHistory) {
            trimListeningHistory(db, 100)
        }
        return updated
    }

    /**
     * Trims listening history to at most maxRecords (default 100) by clearing playback state
     * on the oldest records beyond the limit.
     */
    fun trimListeningHistory(db: SQLiteDatabase, maxRecords: Int = 100) {
        val safeMax = maxRecords.coerceIn(10, 1000)
        val query = """
            UPDATE videos
            SET last_played_at = 0, playback_position_ms = 0, playback_progress = 0.0
            WHERE last_played_at > 0 AND id NOT IN (
                SELECT id FROM videos
                WHERE last_played_at > 0
                ORDER BY last_played_at DESC
                LIMIT ?
            )
        """.trimIndent()
        db.execSQL(query, arrayOf(safeMax.toString()))
    }

    /**
     * Safely bounds the unplayed discovery video cache to prevent indefinite accumulation.
     * STRICT SAFETY GUARANTEES:
     * 1. NEVER deletes any video that has been played (last_played_at > 0, playback_progress > 0, playback_position_ms > 0)
     * 2. NEVER deletes any video in Continue Listening or History
     * 3. NEVER deletes any downloaded video (is_downloaded = 1 or download_file_path is non-null)
     * 4. NEVER deletes any video from followed channels (matched by channel_id OR channel_title)
     * 5. NEVER deletes based on age/timestamp (preserves offline feed browsing without unexpected drops)
     * 6. ONLY removes excess unplayed discovery items if the unplayed pool exceeds [maxDiscoveryCount] (default 600)
     */
    fun pruneOldDiscoveryVideos(db: SQLiteDatabase, maxDiscoveryCount: Int = 600) {
        val safeMaxDiscovery = maxDiscoveryCount.coerceIn(50, 2000)
        val countQuery = """
            SELECT COUNT(*) FROM videos v
            WHERE v.is_downloaded = 0
              AND v.download_file_path IS NULL
              AND v.last_played_at = 0
              AND v.playback_progress = 0
              AND v.playback_position_ms = 0
              AND NOT EXISTS (
                  SELECT 1 FROM channels c 
                  WHERE c.is_followed = 1 
                    AND (c.id = v.channel_id OR (c.title IS NOT NULL AND c.title = v.channel_title))
              )
        """.trimIndent()

        val totalUnplayedDiscovery = db.rawQuery(countQuery, null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

        // If within safety quota, do not delete anything
        if (totalUnplayedDiscovery <= safeMaxDiscovery) return

        val toDeleteCount = totalUnplayedDiscovery - safeMaxDiscovery
        val deleteQuery = """
            DELETE FROM videos
            WHERE id IN (
                SELECT v.id FROM videos v
                WHERE v.is_downloaded = 0
                  AND v.download_file_path IS NULL
                  AND v.last_played_at = 0
                  AND v.playback_progress = 0
                  AND v.playback_position_ms = 0
                  AND NOT EXISTS (
                      SELECT 1 FROM channels c 
                      WHERE c.is_followed = 1 
                        AND (c.id = v.channel_id OR (c.title IS NOT NULL AND c.title = v.channel_title))
                  )
                ORDER BY v.created_at ASC
                LIMIT ?
            )
        """.trimIndent()
        db.execSQL(deleteQuery, arrayOf(toDeleteCount.toString()))
    }

    /**
     * Clears all unplayed, non-downloaded discovery videos from non-followed channels.
     */
    fun clearUnplayedDiscoveryVideos(db: SQLiteDatabase) {
        val deleteQuery = """
            DELETE FROM videos
            WHERE is_downloaded = 0
              AND download_file_path IS NULL
              AND last_played_at = 0
              AND playback_progress = 0
              AND playback_position_ms = 0
              AND NOT EXISTS (
                  SELECT 1 FROM channels c 
                  WHERE c.is_followed = 1 
                    AND (c.id = videos.channel_id OR (c.title IS NOT NULL AND c.title = videos.channel_title))
              )
        """.trimIndent()
        db.execSQL(deleteQuery)
    }

    /**
     * Purges all YouTube Shorts from SQLite storage.
     */
    fun deleteShortVideos(db: SQLiteDatabase): Int {
        val where = "(aspect_ratio > 0.0 AND aspect_ratio < 1.0) OR id = 'EKmQ-TwVLts' OR title LIKE '%#shorts%' OR title LIKE '%#short%' OR webpage_url LIKE '%/shorts/%' OR title LIKE '%[Private video]%' OR title LIKE '%[Deleted video]%' OR title LIKE '%members-only%' OR title LIKE '%members only%'"
        return db.delete("videos", where, null)
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
        val durationText = if (video.durationSeconds > 0) {
            com.amatsagu.reson.YtDlpManager.formatDuration(video.durationSeconds)
        } else {
            video.durationText
        }
        return ContentValues().apply {
            put("id", video.id)
            put("channel_id", effectiveChannelId)
            put("channel_title", video.channelTitle)
            put("title", video.title)
            put("duration_seconds", video.durationSeconds)
            put("duration_text", durationText)
            put("release_date_text", video.releaseDateText)
            put("release_timestamp", if (video.releaseTimestamp > 0) video.releaseTimestamp else 0L)
            put("thumbnail_url", video.thumbnailUrl)
            put("webpage_url", video.webpageUrl)
            put("playback_progress", video.playbackProgress)
            put("playback_position_ms", video.playbackPositionMs)
            put("playback_duration_ms", video.playbackDurationMs)
            put("last_played_at", if (video.lastPlayedAt > 0) video.lastPlayedAt else 0L)
            put("is_downloaded", if (video.isDownloaded) 1 else 0)
            if (video.downloadFilePath != null) {
                put("download_file_path", video.downloadFilePath)
            }
            put("aspect_ratio", video.aspectRatio)
            put("created_at", System.currentTimeMillis())
        }
    }

    private fun queryVideos(
        db: SQLiteDatabase,
        sql: String,
        selectionArgs: Array<String>? = null
    ): List<Video> {
        val videos = mutableListOf<Video>()
        db.rawQuery(sql, selectionArgs).use { cursor ->
            if (cursor.moveToFirst()) {
                val indices = VideoCursorIndices(cursor)
                do {
                    videos.add(cursorToVideo(cursor, indices))
                } while (cursor.moveToNext())
            }
        }
        return videos
    }

    private class VideoCursorIndices(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow("id")
        val title = cursor.getColumnIndexOrThrow("title")
        val channelTitle = cursor.getColumnIndexOrThrow("channel_title")
        val durationText = cursor.getColumnIndexOrThrow("duration_text")
        val durationSeconds = cursor.getColumnIndexOrThrow("duration_seconds")
        val releaseDateText = cursor.getColumnIndexOrThrow("release_date_text")
        val releaseTimestamp = cursor.getColumnIndex("release_timestamp")
        val thumbnailUrl = cursor.getColumnIndexOrThrow("thumbnail_url")
        val webpageUrl = cursor.getColumnIndexOrThrow("webpage_url")
        val playbackProgress = cursor.getColumnIndexOrThrow("playback_progress")
        val isDownloaded = cursor.getColumnIndexOrThrow("is_downloaded")
        val downloadFilePath = cursor.getColumnIndex("download_file_path")
        val channelId = cursor.getColumnIndexOrThrow("channel_id")
        val playbackPositionMs = cursor.getColumnIndexOrThrow("playback_position_ms")
        val playbackDurationMs = cursor.getColumnIndexOrThrow("playback_duration_ms")
        val lastPlayedAt = cursor.getColumnIndexOrThrow("last_played_at")
        val aspectRatio = cursor.getColumnIndex("aspect_ratio")
    }

    private fun cursorToVideo(cursor: Cursor, indices: VideoCursorIndices): Video {
        val releaseTimestamp = if (indices.releaseTimestamp >= 0) cursor.getLong(indices.releaseTimestamp) else 0L
        val downloadFilePath = if (indices.downloadFilePath >= 0) cursor.getString(indices.downloadFilePath) else null
        val aspectRatio = if (indices.aspectRatio >= 0) cursor.getFloat(indices.aspectRatio) else 0f
        val durationSeconds = cursor.getInt(indices.durationSeconds)
        val rawDurationText = cursor.getString(indices.durationText)
        val durationText = if (durationSeconds > 0) {
            com.amatsagu.reson.YtDlpManager.formatDuration(durationSeconds)
        } else {
            rawDurationText
        }
        return Video(
            id = cursor.getString(indices.id),
            title = cursor.getString(indices.title),
            channelTitle = cursor.getString(indices.channelTitle),
            durationText = durationText,
            durationSeconds = durationSeconds,
            releaseDateText = cursor.getString(indices.releaseDateText),
            releaseTimestamp = releaseTimestamp,
            thumbnailUrl = cursor.getString(indices.thumbnailUrl),
            webpageUrl = cursor.getString(indices.webpageUrl),
            playbackProgress = cursor.getFloat(indices.playbackProgress),
            isDownloaded = cursor.getInt(indices.isDownloaded) == 1,
            downloadProgress = null,
            downloadFilePath = downloadFilePath,
            channelId = cursor.getString(indices.channelId),
            playbackPositionMs = cursor.getLong(indices.playbackPositionMs),
            playbackDurationMs = cursor.getLong(indices.playbackDurationMs),
            lastPlayedAt = cursor.getLong(indices.lastPlayedAt),
            aspectRatio = aspectRatio,
        )
    }
}
