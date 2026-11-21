package com.amatsagu.reson.data.local.dao

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.amatsagu.reson.model.Channel

/**
 * Data access operations for the channels table with pre-resolved cursor column indices.
 */
class ChannelDao {

    fun upsertChannel(
        db: SQLiteDatabase,
        channel: Channel,
        isFollowed: Boolean = true,
        followedAt: Long = System.currentTimeMillis(),
    ) {
        val values = ContentValues().apply {
            put("id", channel.id)
            put("title", channel.title)
            put("handle", channel.handle)
            put("avatar_url", channel.avatarUrl)
            put("avatar_base64", channel.avatarBase64)
            put("subscriber_count", channel.subscriberCount)
            put("subscriber_count_text", channel.subscriberCountText)
            put("is_followed", if (isFollowed) 1 else 0)
            if (isFollowed) {
                put("followed_at", if (channel.followedAt > 0) channel.followedAt else followedAt)
            }
        }
        db.insertWithOnConflict("channels", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateAvatarBase64(db: SQLiteDatabase, channelId: String, avatarBase64: String): Boolean {
        val values = ContentValues().apply {
            put("avatar_base64", avatarBase64)
        }
        return db.update("channels", values, "id = ?", arrayOf(channelId)) > 0
    }

    fun setFollowStatus(
        db: SQLiteDatabase,
        channelId: String,
        isFollowed: Boolean,
        followedAt: Long = System.currentTimeMillis(),
    ): Boolean {
        val values = ContentValues().apply {
            put("is_followed", if (isFollowed) 1 else 0)
            put("followed_at", if (isFollowed) followedAt else 0L)
        }
        val affected = db.update("channels", values, "id = ?", arrayOf(channelId))
        return affected > 0
    }

    fun isChannelFollowed(db: SQLiteDatabase, channelId: String): Boolean {
        db.rawQuery("SELECT 1 FROM channels WHERE id = ? AND is_followed = 1 LIMIT 1", arrayOf(channelId)).use { cursor ->
            return cursor.moveToFirst()
        }
    }

    fun getFollowedChannels(db: SQLiteDatabase): List<Channel> {
        val query = "SELECT * FROM channels WHERE is_followed = 1 ORDER BY followed_at DESC"
        return queryChannels(db, query)
    }

    fun getFollowedChannelIds(db: SQLiteDatabase): Set<String> {
        val ids = mutableSetOf<String>()
        db.rawQuery("SELECT id FROM channels WHERE is_followed = 1", null).use { cursor ->
            while (cursor.moveToNext()) {
                ids.add(cursor.getString(0))
            }
        }
        return ids
    }

    fun getChannelById(db: SQLiteDatabase, channelId: String): Channel? {
        db.rawQuery("SELECT * FROM channels WHERE id = ? LIMIT 1", arrayOf(channelId)).use { cursor ->
            return if (cursor.moveToFirst()) cursorToChannel(cursor, ChannelCursorIndices(cursor)) else null
        }
    }

    fun updateLastSyncTime(db: SQLiteDatabase, channelId: String, timestamp: Long = System.currentTimeMillis()) {
        val values = ContentValues().apply {
            put("last_sync_at", timestamp)
        }
        db.update("channels", values, "id = ?", arrayOf(channelId))
    }

    fun deleteChannel(db: SQLiteDatabase, channelId: String): Boolean {
        return db.delete("channels", "id = ?", arrayOf(channelId)) > 0
    }

    fun countFollowed(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COUNT(*) FROM channels WHERE is_followed = 1", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
    }

    private fun queryChannels(
        db: SQLiteDatabase,
        sql: String,
        selectionArgs: Array<String>? = null
    ): List<Channel> {
        val channels = mutableListOf<Channel>()
        db.rawQuery(sql, selectionArgs).use { cursor ->
            if (cursor.moveToFirst()) {
                val indices = ChannelCursorIndices(cursor)
                do {
                    channels.add(cursorToChannel(cursor, indices))
                } while (cursor.moveToNext())
            }
        }
        return channels
    }

    private class ChannelCursorIndices(cursor: Cursor) {
        val id = cursor.getColumnIndexOrThrow("id")
        val title = cursor.getColumnIndexOrThrow("title")
        val handle = cursor.getColumnIndexOrThrow("handle")
        val avatarUrl = cursor.getColumnIndexOrThrow("avatar_url")
        val avatarBase64 = cursor.getColumnIndex("avatar_base64")
        val subscriberCount = cursor.getColumnIndexOrThrow("subscriber_count")
        val subscriberCountText = cursor.getColumnIndexOrThrow("subscriber_count_text")
        val isFollowed = cursor.getColumnIndexOrThrow("is_followed")
        val followedAt = cursor.getColumnIndexOrThrow("followed_at")
        val lastSyncAt = cursor.getColumnIndex("last_sync_at")
    }

    private fun cursorToChannel(cursor: Cursor, indices: ChannelCursorIndices): Channel {
        val id = cursor.getString(indices.id)
        val title = cursor.getString(indices.title)
        val handle = cursor.getString(indices.handle)
        val avatarUrl = cursor.getString(indices.avatarUrl)
        val avatarBase64 = if (indices.avatarBase64 >= 0) cursor.getString(indices.avatarBase64) ?: "" else ""
        val subs = cursor.getLong(indices.subscriberCount)
        val subsText = cursor.getString(indices.subscriberCountText)
        val isFollowed = cursor.getInt(indices.isFollowed) == 1
        val followedAt = cursor.getLong(indices.followedAt)
        val lastSyncAt = if (indices.lastSyncAt >= 0) cursor.getLong(indices.lastSyncAt) else 0L
        return Channel(
            id = id,
            title = title,
            handle = handle,
            avatarUrl = avatarUrl,
            avatarBase64 = avatarBase64,
            subscriberCount = subs,
            subscriberCountText = subsText,
            isFollowed = isFollowed,
            followedAt = followedAt,
            lastSyncAt = lastSyncAt,
        )
    }
}
