package com.amatsagu.reson.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class ResonDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        try {
            // NORMAL synchronous mode is crash-safe in WAL mode and eliminates fsync latency
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL("PRAGMA temp_store = MEMORY")
            // Negative cache_size specifies memory in KiB (-4000 = ~4 MB RAM cache)
            db.execSQL("PRAGMA cache_size = -4000")

            try {
                db.execSQL("ALTER TABLE videos ADD COLUMN aspect_ratio REAL NOT NULL DEFAULT 0.0")
            } catch (_: Exception) {
            }
            createIndexes(db)
        } catch (e: Exception) {
            Log.w(TAG, "Error applying SQLite pragmas on open: ${e.message}")
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating Reson SQLite database schema (v$DATABASE_VERSION)...")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channels (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                handle TEXT NOT NULL DEFAULT '',
                avatar_url TEXT NOT NULL DEFAULT '',
                avatar_base64 TEXT NOT NULL DEFAULT '',
                subscriber_count INTEGER NOT NULL DEFAULT 0,
                subscriber_count_text TEXT NOT NULL DEFAULT '',
                is_followed INTEGER NOT NULL DEFAULT 1,
                followed_at INTEGER NOT NULL DEFAULT 0,
                last_sync_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS videos (
                id TEXT PRIMARY KEY,
                channel_id TEXT NOT NULL DEFAULT '',
                channel_title TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                duration_seconds INTEGER NOT NULL DEFAULT 0,
                duration_text TEXT NOT NULL DEFAULT '',
                release_date_text TEXT NOT NULL DEFAULT '',
                release_timestamp INTEGER NOT NULL DEFAULT 0,
                thumbnail_url TEXT NOT NULL DEFAULT '',
                webpage_url TEXT NOT NULL DEFAULT '',
                playback_position_ms INTEGER NOT NULL DEFAULT 0,
                playback_duration_ms INTEGER NOT NULL DEFAULT 0,
                playback_progress REAL NOT NULL DEFAULT 0.0,
                last_played_at INTEGER NOT NULL DEFAULT 0,
                is_downloaded INTEGER NOT NULL DEFAULT 0,
                download_file_path TEXT DEFAULT NULL,
                aspect_ratio REAL NOT NULL DEFAULT 0.0,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                search_query TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        createIndexes(db)
        Log.i(TAG, "Reson SQLite database schema and indexes created successfully.")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_channels_followed ON channels(is_followed, followed_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_channel_release ON videos(channel_id, release_timestamp DESC, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_release_created ON videos(release_timestamp DESC, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_created_release ON videos(created_at DESC, release_timestamp DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_downloaded_ordered ON videos(is_downloaded, last_played_at DESC, created_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_last_played ON videos(last_played_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createIndexes(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        createIndexes(db)
    }

    companion object {
        private const val TAG = "Reson:DB"
        const val DATABASE_NAME = "reson.db"
        const val DATABASE_VERSION = 1
    }
}
