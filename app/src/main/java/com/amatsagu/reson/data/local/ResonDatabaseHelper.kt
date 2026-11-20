package com.amatsagu.reson.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * SQLite open helper managing creation, indexing, migrations, and WAL mode for Reson.
 */
class ResonDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "Creating Reson SQLite database schema (v$DATABASE_VERSION)...")

        // 1. Followed creators / channels table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS channels (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                handle TEXT NOT NULL DEFAULT '',
                avatar_url TEXT NOT NULL DEFAULT '',
                subscriber_count INTEGER NOT NULL DEFAULT 0,
                subscriber_count_text TEXT NOT NULL DEFAULT '',
                is_followed INTEGER NOT NULL DEFAULT 1,
                followed_at INTEGER NOT NULL DEFAULT 0,
                last_sync_at INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        // 2. Videos / episodes table (with playback state, downloads, and channel relationship)
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
                created_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (channel_id) REFERENCES channels (id) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        // 3. Search history table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS search_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                search_query TEXT NOT NULL UNIQUE,
                timestamp INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // Performance Indexes
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_channels_followed ON channels(is_followed, followed_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_channel_id ON videos(channel_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_last_played ON videos(last_played_at DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_downloaded ON videos(is_downloaded)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_videos_release ON videos(release_timestamp DESC)")

        Log.i(TAG, "Reson SQLite database schema and indexes created successfully.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading database from $oldVersion to $newVersion...")
        // Handle incremental schema migrations here in future versions
    }

    companion object {
        private const val TAG = "Reson:DB"
        const val DATABASE_NAME = "reson.db"
        const val DATABASE_VERSION = 1
    }
}
