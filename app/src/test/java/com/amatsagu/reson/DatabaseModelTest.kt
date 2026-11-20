package com.amatsagu.reson

import com.amatsagu.reson.data.local.DatabaseStats
import com.amatsagu.reson.model.Channel
import com.amatsagu.reson.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DatabaseModelTest {

    @Test
    fun testDatabaseStats_creationAndTotals() {
        val stats = DatabaseStats(
            followedChannelCount = 5,
            totalVideoCount = 150,
            continueListeningCount = 3,
            downloadedVideoCount = 12,
            databaseSizeBytes = 1024 * 1024,
        )
        assertEquals(5, stats.followedChannelCount)
        assertEquals(150, stats.totalVideoCount)
        assertEquals(3, stats.continueListeningCount)
        assertEquals(12, stats.downloadedVideoCount)
        assertEquals(1048576, stats.databaseSizeBytes)
    }

    @Test
    fun testVideoPersistenceFields_defaultsAndAssignment() {
        val video = Video(
            id = "vid_1",
            title = "Video Title",
            channelTitle = "Channel Name",
            durationText = "15m",
            channelId = "UC123",
            playbackPositionMs = 300_000L,
            playbackDurationMs = 900_000L,
            lastPlayedAt = 1700000000L,
        )
        assertEquals("UC123", video.channelId)
        assertEquals(300_000L, video.playbackPositionMs)
        assertEquals(900_000L, video.playbackDurationMs)
        assertEquals(1700000000L, video.lastPlayedAt)
        assertFalse(video.isDownloaded)
    }

    @Test
    fun testChannelPersistenceFields_defaultsAndAssignment() {
        val channel = Channel(
            id = "ch_1",
            title = "Test Channel",
            handle = "@TestChannel",
            isFollowed = true,
            followedAt = 1700000000L,
        )
        assertEquals("ch_1", channel.id)
        assertTrue(channel.isFollowed)
        assertEquals(1700000000L, channel.followedAt)
    }
}
