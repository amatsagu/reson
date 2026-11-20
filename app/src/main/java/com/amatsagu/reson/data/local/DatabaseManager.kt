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
     * Checks if a channel is currently followed.
     */
    suspend fun isChannelFollowed(channelId: String): Boolean = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        channelDao.isChannelFollowed(db, channelId)
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

    /* -------------------------------------------------------------------------
     * Videos / Feed API
     * ------------------------------------------------------------------------- */

    /**
     * Bulk inserts or updates videos in a single high-performance SQLite transaction.
     */
    suspend fun upsertVideos(videos: List<Video>, defaultChannelId: String = ""): Unit = withContext(dispatcher) {
        if (videos.isEmpty()) return@withContext
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.upsertVideos(db, videos, defaultChannelId)
            Log.d(TAG, "Upserted ${videos.size} videos (default channel: '$defaultChannelId')")
        }
        notifyVideoChanged()
    }

    /**
     * Inserts or updates a single video.
     */
    suspend fun upsertVideo(video: Video, channelId: String = ""): Unit = withContext(dispatcher) {
        mutex.withLock {
            val db = dbHelper.writableDatabase
            videoDao.upsertVideo(db, video, channelId)
        }
        notifyVideoChanged()
    }

    /**
     * Fetches a video by its unique YouTube ID.
     */
    suspend fun getVideoById(videoId: String): Video? = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getVideoById(db, videoId)
    }

    /**
     * Gets recent videos from all followed channels with pagination support.
     */
    suspend fun getFeedVideos(limit: Int = 30, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getFeedVideos(db, limit, offset)
    }

    /**
     * Reactive Flow of recent uploads from followed channels (initial page).
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
     * Reactive Flow of videos for a specific channel.
     */
    fun observeVideosForChannel(channelId: String, limit: Int = 30): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getVideosForChannel(channelId, limit, offset = 0))
        }
    }.flowOn(dispatcher)

    /**
     * Gets recent uploads from followed channels, limited to maxPerChannel with pagination support.
     */
    suspend fun getRecentVideosFromFollowed(maxPerChannel: Int = 5, limit: Int = 30, offset: Int = 0): List<Video> = withContext(dispatcher) {
        val db = dbHelper.readableDatabase
        videoDao.getRecentVideosFromFollowedChannels(db, maxPerChannel, limit, offset)
    }

    /**
     * Reactive Flow observing recent uploads from followed channels (maxPerChannel).
     */
    fun observeRecentVideosFromFollowed(maxPerChannel: Int = 5, limit: Int = 30): Flow<List<Video>> = flow {
        videoInvalidationTrigger.collect {
            emit(getRecentVideosFromFollowed(maxPerChannel, limit, offset = 0))
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
     * Marks a video as completed (progress = 1.0f) so it exits the continue listening list.
     */
    suspend fun markVideoCompleted(videoId: String): Boolean = withContext(dispatcher) {
        val video = getVideoById(videoId) ?: return@withContext false
        val durationMs = if (video.playbackDurationMs > 0) video.playbackDurationMs else (video.durationSeconds * 1000L)
        savePlaybackProgress(videoId, durationMs, durationMs, notify = true)
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
            dbDir.listFiles()?.filter { it.name.startsWith(ResonDatabaseHelper.DATABASE_NAME) }?.sumOf { it.length() }
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
            it.isFile && (it.name.startsWith("offline_") || it.name.endsWith(".mp3") || it.name.endsWith(".m4a"))
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
     * Clears all channels, videos, and search history tables.
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
        }
        notifyChannelChanged()
        notifyVideoChanged()
    }

    fun getDatabaseSchemaVersion(): Int = ResonDatabaseHelper.DATABASE_VERSION

    /**
     * Flushes WAL and exports the SQLite database file to the provided output stream.
     */
    suspend fun exportDatabase(outputStream: OutputStream): kotlin.Result<Unit> = withContext(dispatcher) {
        mutex.withLock {
            try {
                val db = dbHelper.writableDatabase
                db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }

                val dbFile = appContext.getDatabasePath(ResonDatabaseHelper.DATABASE_NAME)
                if (!dbFile.exists()) {
                    return@withContext kotlin.Result.failure(IllegalStateException("Database file not found"))
                }

                FileInputStream(dbFile).use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
                kotlin.Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export database", e)
                kotlin.Result.failure(e)
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
