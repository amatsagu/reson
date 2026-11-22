package com.amatsagu.reson.data.local

import android.content.Context
import android.util.Log
import com.amatsagu.reson.core.coroutines.ResonDispatchers
import com.amatsagu.reson.data.local.dao.ChannelDao
import com.amatsagu.reson.data.local.dao.VideoDao
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.Video
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Statistics snapshot of the SQLite database.
 */
data class DatabaseStats(
    val followedChannelCount: Long,
    val totalVideoCount: Long,
    val continueListeningCount: Long,
    val downloadedVideoCount: Long,
    val databaseSizeBytes: Long,
    val totalCacheSizeBytes: Long = databaseSizeBytes,
)

/**
 * Central thread-safe Database Manager for Reson.
 * Designed similarly to YtDlpManager with clean suspend APIs and reactive Kotlin Flows.
 */
class DatabaseManager private constructor(private val appContext: Context) {

    private var dbHelper = ResonDatabaseHelper(appContext)
    private val channelDao = ChannelDao()
    private val videoDao = VideoDao()
    private val mutex = Mutex()
    private val dispatcher = ResonDispatchers.Database

    // Invalidation triggers to notify reactive Flows when data mutations occur
    private val channelInvalidationTrigger = MutableSharedFlow<Unit>(replay = 1).apply {
        tryEmit(Unit)
    }
    private val videoInvalidationTrigger = MutableSharedFlow<Unit>(replay = 1).apply {
        tryEmit(Unit)
    }

    /* -------------------------------------------------------------------------
     * Channels / Creators API
     * ------------------------------------------------------------------------- */

    /**
     * Follows a channel, inserting or updating its record with is_followed = 1.
     * Automatically notifies active observers.
     */
    suspend fun followChannel(channel: Channel): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            channelDao.upsertChannel(db, channel, isFollowed = true)
            Log.d(TAG, "Followed channel: ${channel.title} (${channel.id})")
        }
        notifyChannelChanged()
        notifyVideoChanged()
    }

    /**
     * Unfollows a channel (sets is_followed = 0).
     */
    suspend fun unfollowChannel(channelId: String): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            val updated = channelDao.setFollowStatus(db, channelId, isFollowed = false)
            Log.d(TAG, "Unfollowed channel $channelId: $updated")
            updated
        }
        if (success) {
            notifyChannelChanged()
            notifyVideoChanged()
        }
        success
    }

    /**
     * Returns a snapshot of all currently followed channels sorted by follow time.
     */
    suspend fun getFollowedChannels(): List<Channel> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        channelDao.getFollowedChannels(db)
    }

    /**
     * Returns a Set of followed channel IDs for fast O(1) membership checks.
     */
    suspend fun getFollowedChannelIds(): Set<String> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        channelDao.getFollowedChannelIds(db)
    }

    /**
     * Updates stored base64 image data for an offline cached channel avatar.
     */
    suspend fun updateChannelAvatarBase64(channelId: String, avatarBase64: String): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            val updated = channelDao.updateAvatarBase64(db, channelId, avatarBase64)
            Log.d(TAG, "Updated avatar base64 for channel $channelId: $updated (${avatarBase64.length} chars)")
            updated
        }
        if (success) {
            notifyChannelChanged()
        }
        success
    }

    /**
     * Updates both avatar URL and base64 for a channel and notifies observers.
     */
    suspend fun updateChannelAvatarUrlAndBase64(channelId: String, avatarUrl: String, avatarBase64: String): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            val updated = channelDao.updateAvatarUrlAndBase64(db, channelId, avatarUrl, avatarBase64)
            Log.d(TAG, "Updated avatar URL and base64 for channel $channelId: $updated")
            updated
        }
        if (success) {
            notifyChannelChanged()
        }
        success
    }

    /**
     * Reactive Flow observing followed channels. Re-emits automatically on mutation.
     */
    fun observeFollowedChannels(): Flow<List<Channel>> = flow {
        channelInvalidationTrigger.collect {
            emit(getFollowedChannels())
        }
    }.flowOn(dispatcher)

    /**
     * Reactive Flow observing followed channel IDs set.
     */
    fun observeFollowedChannelIds(): Flow<Set<String>> = flow {
        channelInvalidationTrigger.collect {
            emit(getFollowedChannelIds())
        }
    }.flowOn(dispatcher)

    /**
     * Updates the last sync timestamp for a channel.
     */
    suspend fun updateChannelLastSyncTime(channelId: String, timestamp: Long = System.currentTimeMillis()): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            channelDao.updateLastSyncTime(db, channelId, timestamp)
        }
    }

    /* -------------------------------------------------------------------------
     * Videos / Feed API
     * ------------------------------------------------------------------------- */

    /**
     * Bulk inserts or updates videos in a single high-performance SQLite transaction.
     * Silently ignores any locked/supporters-only videos and shorts.
     */
    suspend fun upsertVideos(videos: List<Video>, defaultChannelId: String = ""): Unit = withContext(dispatcher) {
        val validVideos = videos.filterNot { 
            com.amatsagu.reson.YtDlpManager.isLockedVideoTitle(it.title) ||
            com.amatsagu.reson.YtDlpManager.isShortVideo(it)
        }
        if (validVideos.isEmpty()) return@withContext
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.upsertVideos(db, validVideos, defaultChannelId)
            Log.d(TAG, "Upserted ${validVideos.size} videos (default channel: '$defaultChannelId')")
        }
        notifyVideoChanged()
    }

    /**
     * Inserts or updates a single video.
     * Silently ignores any locked/supporters-only video or short.
     */
    suspend fun upsertVideo(video: Video, channelId: String = ""): Unit = withContext(dispatcher) {
        if (com.amatsagu.reson.YtDlpManager.isLockedVideoTitle(video.title) ||
            com.amatsagu.reson.YtDlpManager.isShortVideo(video)) return@withContext
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.upsertVideo(db, video, channelId)
        }
        notifyVideoChanged()
    }

    /**
     * Gets recent videos from all followed channels with pagination support.
     * Uses balanced distribution across followed channels (up to 5 recent uploads per channel)
     * to prevent high-frequency uploaders from starving the feed.
     */
    /**
     * Gets unwatched recommended discovery videos from non-followed channels.
     */
    suspend fun getRecommendedDiscoveryVideos(limit: Int = 30, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getRecommendedDiscoveryVideos(db, limit, offset)
    }

    /**
     * Fetches feed videos using the anti-clustering 25% followed unwatched / 75% recommended discovery mix.
     * Guaranteed:
     * - Zero duplicates
     * - Zero watched/in-progress videos
     * - Dispersed creators to prevent clustering
     */
    suspend fun getFeedVideos(
        limit: Int = 30,
        offset: Int = 0,
        excludeIds: Set<String> = emptySet(),
    ): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase

        // In a 1:3 ratio, followed represents 1/4 of items, recommended represents 3/4
        val followedOffset = offset / 4
        val recOffset = (offset * 3) / 4

        val followed = videoDao.getUnwatchedFollowedVideos(db, limit = limit, offset = followedOffset)
        val recommended = videoDao.getRecommendedDiscoveryVideos(db, limit = limit * 2, offset = recOffset)

        val followedIds = channelDao.getFollowedChannelIds(db)
        val followedChannels = channelDao.getFollowedChannels(db)
        val followedTitles = followedChannels.map { it.title }.toSet()

        val mixed = com.amatsagu.reson.FeedMixer.mix(
            followedUnwatched = followed,
            recommended = recommended,
            excludeIds = excludeIds,
            limit = limit,
            followedChannelIds = followedIds,
            followedChannelTitles = followedTitles
        )

        if (mixed.isNotEmpty()) {
            mixed
        } else if (offset == 0) {
            // Fallback for fresh installs where no discovery videos have been fetched yet:
            videoDao.getFeedVideos(db, limit, offset).filterNot { it.id in excludeIds }
        } else {
            emptyList()
        }
    }

    /**
     * Reactive Flow of mixed feed videos (initial page).
     */
    fun observeFeedVideos(limit: Int = 30): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getFeedVideos(limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /**
     * Gets videos for a specific channel with pagination support.
     */
    suspend fun getVideosForChannel(channelId: String, limit: Int = 30, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getVideosForChannel(db, channelId, limit, offset)
    }

    /**
     * Gets all videos from followed channels released within the last 1 month (cutoff: 30 days ago),
     * ordered by release date/time DESC with pagination support.
     */
    suspend fun getVideosFromFollowedWithinMonth(limit: Int = 30, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val nowMs = System.currentTimeMillis()
        val oneMonthAgoSec = (nowMs / 1000L) - (30L * 24L * 3600L)
        val oneMonthAgoMs = nowMs - (30L * 24L * 3600L * 1000L)
        val db = dbHelper.readableDatabase
        videoDao.getVideosFromFollowedWithinMonth(
            db = db,
            minTimestampSec = oneMonthAgoSec,
            minCreatedAtMs = oneMonthAgoMs,
            limit = limit,
            offset = offset
        )
    }

    /**
     * Reactive Flow observing all videos from all followed channels within the last 1 month.
     */
    fun observeVideosFromFollowedWithinMonth(limit: Int = 30): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getVideosFromFollowedWithinMonth(limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /* -------------------------------------------------------------------------
     * Playback & Continue Listening API
     * ------------------------------------------------------------------------- */

    /**
     * Updates playback progress for a video, recording lastPlayedAt timestamp.
     */
    suspend fun savePlaybackProgress(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        notify: Boolean = false,
    ): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.updatePlaybackProgress(db, videoId, positionMs, durationMs, trimHistory = notify)
        }
        if (success && notify) {
            notifyVideoChanged()
        }
        success
    }

    /**
     * Fetches in-progress videos (< 98% complete) for the Continue section with pagination support.
     */
    suspend fun getContinueListeningVideos(limit: Int = 10, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getContinueListeningVideos(db, limit, offset)
    }

    /**
     * Reactive Flow observing in-progress continue listening videos.
     */
    fun observeContinueListening(limit: Int = 10): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getContinueListeningVideos(limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /* -------------------------------------------------------------------------
     * Downloads API
     * ------------------------------------------------------------------------- */

    /**
     * Finds the real downloaded audio file on disk for the given videoId, if present and non-empty.
     */
    fun getDownloadedFile(videoId: String): File? {
        val db = dbHelper.readableDatabase
        val dbVideo = videoDao.getVideoById(db, videoId)
        val path = dbVideo?.downloadFilePath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.exists() && (file.length() > 0)) {
                return file
            }
        }

        val downloadsDir = File(appContext.filesDir, "downloads")
        if (downloadsDir.exists()) {
            val file = downloadsDir.listFiles { _, name ->
                name.startsWith(videoId) && !name.endsWith(".part") && !name.endsWith(".download") && !name.endsWith(".ytdl")
            }?.firstOrNull { it.length() > 0 }
            if (file != null) return file
        }

        val fallback = File(appContext.filesDir, "offline_$videoId.mp3")
        if (fallback.exists() && (fallback.length() > 0)) {
            return fallback
        }

        return null
    }

    /**
     * Deletes the local offline file and resets SQLite download state.
     * Thoroughly removes any completed audio files as well as partial download fragments.
     */
    suspend fun deleteDownloadedFile(videoId: String): Boolean = withContext(dispatcher) {
        var fileDeleted = false
        val downloadsDir = File(appContext.filesDir, "downloads")
        if (downloadsDir.exists()) {
            downloadsDir.listFiles { _, name ->
                name.startsWith(videoId)
            }?.forEach { file ->
                if (file.delete()) {
                    fileDeleted = true
                }
            }
        }

        val fallback = File(appContext.filesDir, "offline_${videoId}.mp3")
        if (fallback.exists() && fallback.delete()) {
            fileDeleted = true
        }

        val dbSuccess = updateDownloadStatus(videoId, isDownloaded = false, filePath = null)
        fileDeleted || dbSuccess
    }

    /**
     * Scans SQLite downloaded records and disk files for complete bidirectional consistency:
     * 1. Cleans up any unfinished / interrupted download files (*.download, *.part, *.ytdl)
     * 2. Clears SQLite ghost records whose local files are missing on disk
     * 3. Deletes orphaned completed files on disk that have no committed DB record
     */
    suspend fun syncDownloadedState(): Unit = withContext(dispatcher) {
        val downloadsDir = File(appContext.filesDir, "downloads")
        // 1. Clean up temporary files left behind by app kills or crashes
        if (downloadsDir.exists()) {
            downloadsDir.listFiles { _, name ->
                name.endsWith(".download") || name.endsWith(".part") || name.endsWith(".ytdl")
            }?.forEach { it.delete() }
        }

        val db = dbHelper.writableDatabase
        val downloaded = videoDao.getDownloadedVideos(db, 500)
        val validDownloadedIds = mutableSetOf<String>()
        var changed = false

        // 2. Verify all DB records against physical files
        for (v in downloaded) {
            val file = getDownloadedFile(v.id)
            if (file == null || !file.exists() || file.length() == 0L) {
                videoDao.updateDownloadStatus(db, v.id, isDownloaded = false, filePath = null)
                changed = true
            } else {
                validDownloadedIds.add(v.id)
            }
        }

        // 3. Remove uncommitted orphan files from disk
        if (downloadsDir.exists()) {
            downloadsDir.listFiles { _, name ->
                !name.endsWith(".download") && !name.endsWith(".part") && !name.endsWith(".ytdl")
            }?.forEach { file ->
                val videoId = file.nameWithoutExtension
                if (videoId !in validDownloadedIds) {
                    Log.i(TAG, "Deleting uncommitted orphan download: ${file.name}")
                    file.delete()
                }
            }
        }

        if (changed) {
            notifyVideoChanged()
        }
        // Keep SQLite lean and performant by safely pruning excess unplayed discovery items
        videoDao.pruneOldDiscoveryVideos(db, 600)
    }

    /**
     * Safely bounds unplayed discovery videos (channels not followed) to keep SQLite database lean.
     */
    suspend fun pruneOldDiscoveryVideos(maxDiscoveryCount: Int = 600): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.pruneOldDiscoveryVideos(db, maxDiscoveryCount)
        }
    }

    /**
     * Clears unplayed discovery videos (channels not followed) to refresh the discovery pool.
     */
    suspend fun clearUnplayedDiscoveryVideos(): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.clearUnplayedDiscoveryVideos(db)
        }
        notifyVideoChanged()
    }

    /**
     * Purges all YouTube Shorts and unplayed zero-duration videos from SQLite storage.
     */
    suspend fun deleteShortVideos(): Int = withContext(dispatcher) {
        val deleted = mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.deleteShortVideos(db)
        }
        if (deleted > 0) {
            notifyVideoChanged()
            Log.d(TAG, "Purged $deleted shorts / zero-duration videos from SQLite database")
        }
        deleted
    }

    /**
     * Updates download status and stored file path.
     */
    suspend fun updateDownloadStatus(
        videoId: String,
        isDownloaded: Boolean,
        filePath: String? = null,
    ): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.updateDownloadStatus(db, videoId, isDownloaded, filePath)
        }
        if (success) {
            notifyVideoChanged()
        }
        success
    }

    /**
     * Gets all downloaded videos.
     */
    suspend fun getDownloadedVideos(limit: Int = 100, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getDownloadedVideos(db, limit, offset)
    }

    /**
     * Reactive Flow observing downloaded videos.
     */
    fun observeDownloadedVideos(limit: Int = 100): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getDownloadedVideos(limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /* -------------------------------------------------------------------------
     * History API
     * ------------------------------------------------------------------------- */

    /**
     * Gets listening history videos ordered by lastPlayedAt DESC.
     */
    suspend fun getPlaybackHistory(limit: Int = 100, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getPlaybackHistory(db, limit, offset)
    }

    /**
     * Reactive Flow observing listening history videos.
     */
    fun observePlaybackHistory(limit: Int = 100): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getPlaybackHistory(limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /**
     * Clears all playback history.
     */
    suspend fun clearPlaybackHistory(): Boolean = withContext(dispatcher) {
        val success = mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.clearPlaybackHistory(db)
        }
        if (success) {
            notifyVideoChanged()
        }
        success
    }

    /* -------------------------------------------------------------------------
     * Maintenance & Diagnostics
     * ------------------------------------------------------------------------- */

    /**
     * Gathers high-level statistics about stored channels, videos, and database size.
     */
    suspend fun getStats(): DatabaseStats = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        val channels = channelDao.countFollowed(db)
        val videos = videoDao.countVideos(db)
        val continueCount = videoDao.getContinueListeningVideos(db, 100).size.toLong()
        val downloaded = videoDao.countDownloaded(db)
        // SQLite DB files size (including WAL and SHM if present)
        val dbFile = appContext.getDatabasePath(ResonDatabaseHelper.DATABASE_NAME)
        val dbDir = dbFile.parentFile
        val dbFilesSize = if (dbDir != null && dbDir.exists()) {
            dbDir.listFiles()?.asSequence()?.filter { it.name.startsWith(ResonDatabaseHelper.DATABASE_NAME) }?.sumOf { it.length() }
                ?: (if (dbFile.exists()) dbFile.length() else 0L)
        } else {
            if (dbFile.exists()) dbFile.length() else 0L
        }

        // Locally downloaded files size
        var downloadedFilesSize = 0L
        val downloadsDir = File(appContext.filesDir, "downloads")
        if (downloadsDir.exists()) {
            downloadedFilesSize += downloadsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        appContext.filesDir.listFiles()?.filter {
            it.isFile && (it.name.startsWith("offline_") || it.name.endsWith(".mp3") || it.name.endsWith(".m4a") || it.name.endsWith(".webm") || it.name.endsWith(".opus"))
        }?.forEach {
            downloadedFilesSize += it.length()
        }
        appContext.getExternalFilesDir(null)?.let { extDir ->
            val extDownloads = File(extDir, "downloads")
            if (extDownloads.exists()) {
                downloadedFilesSize += extDownloads.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        }

        val totalCacheSize = dbFilesSize + downloadedFilesSize

        DatabaseStats(
            followedChannelCount = channels,
            totalVideoCount = videos,
            continueListeningCount = continueCount,
            downloadedVideoCount = downloaded,
            databaseSizeBytes = dbFilesSize,
            totalCacheSizeBytes = totalCacheSize,
        )
    }

    /**
     * Clears all channels, videos, and search history tables, and wipes downloaded media files.
     */
    suspend fun clearAll(): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                db.delete("videos", null, null)
                db.delete("channels", null, null)
                db.delete("search_history", null, null)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            try {
                val downloadsDir = File(appContext.filesDir, "downloads")
                downloadsDir.listFiles()?.forEach { it.delete() }
                appContext.filesDir.listFiles()?.filter {
                    it.isFile && (it.name.startsWith("offline_") || it.name.endsWith(".mp3") || it.name.endsWith(".m4a") || it.name.endsWith(".webm") || it.name.endsWith(".opus"))
                }?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.w("DatabaseManager", "Failed to clear media files: ${e.message}")
            }
        }
        notifyChannelChanged()
        notifyVideoChanged()
    }

    /**
     * Flushes WAL and exports the SQLite database file to the provided output stream.
     */
    suspend fun exportDatabase(outputStream: OutputStream): Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }

                val dbFile = appContext.getDatabasePath(ResonDatabaseHelper.DATABASE_NAME)
                if (!dbFile.exists()) {
                    return@withContext Result.failure(IllegalStateException("Database file not found"))
                }

                FileInputStream(dbFile).use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export database", e)
                Result.failure(e)
            }
        }
    }

    /**
     * Validates SQLite magic header, replaces database file, clears WAL/SHM, and reopens helper.
     */
    suspend fun importDatabase(inputStream: InputStream): kotlin.Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            val tempFile = File(appContext.cacheDir, "reson_import_temp.db")
            try {
                FileOutputStream(tempFile).use { out ->
                    inputStream.copyTo(out)
                }

                // Verify SQLite 3 magic header ("SQLite format 3\000")
                val header = ByteArray(16)
                FileInputStream(tempFile).use { it.read(header) }
                val magic = String(header, Charsets.US_ASCII)
                if (!magic.startsWith("SQLite format 3")) {
                    tempFile.delete()
                    return@withContext kotlin.Result.failure(IllegalArgumentException("Selected file is not a valid SQLite database"))
                }

                // Close existing database and replace files
                dbHelper.close()

                val dbFile = appContext.getDatabasePath(ResonDatabaseHelper.DATABASE_NAME)
                val walFile = File(dbFile.path + "-wal")
                val shmFile = File(dbFile.path + "-shm")

                if (walFile.exists()) walFile.delete()
                if (shmFile.exists()) shmFile.delete()

                tempFile.copyTo(dbFile, overwrite = true)
                tempFile.delete()

                // Re-open and verify schema
                dbHelper = ResonDatabaseHelper(appContext)
                val newDb = dbHelper.writableDatabase
                newDb.rawQuery("SELECT 1", null).use { it.moveToFirst() }

                try {
                    newDb.execSQL("""
                        DELETE FROM videos 
                        WHERE (duration_seconds BETWEEN 1 AND 60)
                           OR (duration_seconds = 0 AND last_played_at = 0 AND is_downloaded = 0)
                           OR webpage_url LIKE '%/shorts/%'
                           OR title LIKE '%#shorts%'
                           OR title LIKE '%#short%'
                    """.trimIndent())
                } catch (pe: Exception) {
                    Log.e(TAG, "Error purging shorts during import: ${pe.message}", pe)
                }

                notifyChannelChanged()
                notifyVideoChanged()
                kotlin.Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import database", e)
                tempFile.delete()
                kotlin.Result.failure(e)
            }
        }
    }

    private fun notifyChannelChanged() {
        channelInvalidationTrigger.tryEmit(Unit)
    }

    private fun notifyVideoChanged() {
        videoInvalidationTrigger.tryEmit(Unit)
    }

    companion object {
        private const val TAG = "Reson:DbMgr"

        @Volatile
        private var instance: DatabaseManager? = null

        fun getInstance(context: Context): DatabaseManager {
            return instance ?: synchronized(this) {
                instance ?: DatabaseManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
