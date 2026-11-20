package com.amatsagu.reson

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YtDlpManagerTest {

    @Test
    fun testYtDlpState_readyCarriesVersion() {
        val state: YtDlpState = YtDlpState.Ready(version = "2025.01.15", statusMessage = "Ready")
        assertTrue(state is YtDlpState.Ready)
        assertEquals("2025.01.15", (state as YtDlpState.Ready).version)
    }

    @Test
    fun testCleanVersion_stripsYtDlpPrefix() {
        assertEquals("2025.01.15", YtDlpManager.cleanVersion("yt-dlp 2025.01.15"))
        assertEquals("2025.01.15", YtDlpManager.cleanVersion("yt-dlp   2025.01.15"))
        assertEquals("2025.01.15", YtDlpManager.cleanVersion("2025.01.15"))
        assertEquals("2024.12.23", YtDlpManager.cleanVersion("YT-DLP 2024.12.23"))
        assertEquals("Unknown", YtDlpManager.cleanVersion(""))
        assertEquals("Unknown", YtDlpManager.cleanVersion(null))
    }

    @Test
    fun testResult_successAndFailure() {
        val success: Result<String> = Result.Success("Update complete")
        assertTrue(success is Result.Success)
        assertEquals("Update complete", (success as Result.Success).data)

        val failure: Result<String> = Result.Failure(RuntimeException("Network error"), "Failed to fetch")
        assertTrue(failure is Result.Failure)
        assertEquals("Failed to fetch", (failure as Result.Failure).message)
    }

    @Test
    fun testErrorRecovery_canRecoverFlag() {
        val errorState = YtDlpState.Error(message = "Corrupted binaries", canRecover = true)
        assertTrue(errorState.canRecover)
    }

    @Test
    fun testVideo_creationAndDefaults() {
        val video = com.amatsagu.reson.model.Video(
            id = "test_123",
            title = "Test Video",
            channelTitle = "Test Channel",
            durationText = "45m",
            durationSeconds = 2700
        )
        assertEquals("test_123", video.id)
        assertEquals("https://www.youtube.com/watch?v=test_123", video.webpageUrl)
        assertEquals(0f, video.playbackProgress, 0.001f)
    }

    @Test
    fun testFormatRelativeSeconds_compactFormats() {
        assertEquals("just now", YtDlpManager.formatRelativeSeconds(30))
        assertEquals("57m ago", YtDlpManager.formatRelativeSeconds(57 * 60))
        assertEquals("1d3h ago", YtDlpManager.formatRelativeSeconds(27 * 3600))
        assertEquals("3d ago", YtDlpManager.formatRelativeSeconds(3 * 86400))
        assertEquals("1w2d ago", YtDlpManager.formatRelativeSeconds(9 * 86400))
        assertEquals("2mo ago", YtDlpManager.formatRelativeSeconds(65 * 86400))
        assertEquals("4y ago", YtDlpManager.formatRelativeSeconds(4 * 365 * 86400))
    }

    @Test
    fun testCalculateSimilarity_accurateFiltering() {
        val fireshipSim = YtDlpManager.calculateSimilarity("fireship", "Fireship", "@Fireship")
        assertTrue("Expected fireship similarity to be >= 0.95, got $fireshipSim", fireshipSim >= 0.95)

        // Verifies normalized matching matches YouTube's behavior (ignoring spaces / case)
        val smugAlanaWithSpace = YtDlpManager.calculateSimilarity("smug alana", "SmugAlana", "@SmugAlana")
        assertEquals(1.0, smugAlanaWithSpace, 0.001)

        val smugAlanaExact = YtDlpManager.calculateSimilarity("SmugAlana", "SmugAlana", "@SmugAlana")
        assertEquals(1.0, smugAlanaExact, 0.001)

        val mrBeastWithSpace = YtDlpManager.calculateSimilarity("mr beast", "MrBeast", "@MrBeast")
        assertEquals(1.0, mrBeastWithSpace, 0.001)

        val mkbhdSim = YtDlpManager.calculateSimilarity("mkbhd", "Marques Brownlee", "@mkbhd")
        assertTrue("Expected mkbhd handle similarity to be >= 0.95, got $mkbhdSim", mkbhdSim >= 0.95)

        val unrelatedSim = YtDlpManager.calculateSimilarity("how to make pasta", "Pasta Chef", "@pastachef")
        assertTrue("Expected unrelated channel to have similarity < 0.45, got $unrelatedSim", unrelatedSim < 0.45)
    }

    @Test
    fun testMergeSearchResults_channelHighInTop5AndCapsAt30() {
        val videos = (1..35).map {
            com.amatsagu.reson.model.Video(
                id = "v$it",
                title = "Video $it",
                channelTitle = "Test Channel",
                durationText = "10m"
            )
        }
        val channels = listOf(
            com.amatsagu.reson.model.Channel(
                id = "ch1",
                title = "Channel 1",
                subscriberCount = 1_000_000
            ),
            com.amatsagu.reson.model.Channel(
                id = "ch2",
                title = "Channel 2",
                subscriberCount = 500_000
            )
        )

        val merged = YtDlpManager.mergeSearchResults(videos, channels, maxTotal = 30)

        // Must count toward 30 limit exactly
        assertEquals(30, merged.size)

        // Item 0 is Video 1
        assertTrue(merged[0] is com.amatsagu.reson.model.SearchResultItem.Video)
        assertEquals("video_v1", merged[0].id)

        // Item 1 is Channel 1 (in top 5)
        assertTrue(merged[1] is com.amatsagu.reson.model.SearchResultItem.Creator)
        assertEquals("channel_ch1", merged[1].id)

        // Item 4 is Channel 2 (in top 5)
        assertTrue(merged[4] is com.amatsagu.reson.model.SearchResultItem.Creator)
        assertEquals("channel_ch2", merged[4].id)
    }

    @Test
    fun testFormatSubscriberCount() {
        assertEquals("4.3M subscribers", YtDlpManager.formatSubscriberCount(4_340_000))
        assertEquals("462.0k subscribers", YtDlpManager.formatSubscriberCount(462_000))
        assertEquals("500 subscribers", YtDlpManager.formatSubscriberCount(500))
    }

    @Test
    fun testIsHandleExactMatch() {
        assertTrue(YtDlpManager.isHandleExactMatch("SmugAlana", "@SmugAlana"))
        assertTrue(YtDlpManager.isHandleExactMatch("@SmugAlana", "@SmugAlana"))
        assertTrue(YtDlpManager.isHandleExactMatch("smugalana", "@SmugAlana"))
        assertTrue(YtDlpManager.isHandleExactMatch("SmugVODs", "@SmugVODs"))
        org.junit.Assert.assertFalse(YtDlpManager.isHandleExactMatch("smug alana", "@SmugAlana"))
        org.junit.Assert.assertFalse(YtDlpManager.isHandleExactMatch("smug", "@SmugAlana"))
    }

    @Test
    fun testFilterAndRankChannels_skipsUnder1000SubsUnlessExactHandle() {
        val channels = listOf(
            com.amatsagu.reson.model.Channel(
                id = "c1",
                title = "SmugAlana",
                handle = "@SmugAlana",
                subscriberCount = 555_000
            ),
            com.amatsagu.reson.model.Channel(
                id = "c2_small",
                title = "Smug Small Fan",
                handle = "@SmugSmallFan",
                subscriberCount = 450 // < 1000 subs
            ),
            com.amatsagu.reson.model.Channel(
                id = "c3_exact_small",
                title = "Smug VODs",
                handle = "@SmugVODs",
                subscriberCount = 800 // < 1000 subs, but exact handle match when querying "@SmugVODs"
            )
        )

        // Query "smug alana" -> c1 (555k) included; c2_small (<1000 and not exact handle) excluded
        val resultsForGeneral = YtDlpManager.filterAndRankChannels("smug alana", channels, emptyList())
        assertEquals(1, resultsForGeneral.size)
        assertEquals("c1", resultsForGeneral[0].id)

        // Query "SmugVODs" -> matches @SmugVODs handle perfectly, so c3_exact_small is allowed despite < 1000 subs
        val resultsForExactHandle = YtDlpManager.filterAndRankChannels("SmugVODs", channels, emptyList())
        assertTrue(resultsForExactHandle.any { it.id == "c3_exact_small" })
    }

    @Test
    fun testIsRateLimitError() {
        assertTrue(YtDlpManager.isRateLimitError(RuntimeException("HTTP Error 429: Too Many Requests")))
        assertTrue(YtDlpManager.isRateLimitError(RuntimeException("Sign in to confirm you're not a bot")))
        assertTrue(YtDlpManager.isRateLimitError(RuntimeException("Sign in to confirm you are not a bot")))
        assertTrue(YtDlpManager.isRateLimitError(RuntimeException("YouTube returned a CAPTCHA challenge")))
        assertTrue(YtDlpManager.isRateLimitError(RuntimeException("Client temporarily blocked due to rate limit")))
        org.junit.Assert.assertFalse(YtDlpManager.isRateLimitError(RuntimeException("Video unavailable")))
        org.junit.Assert.assertFalse(YtDlpManager.isRateLimitError(RuntimeException("Unknown format 140")))
    }

    @Test
    fun testTaskPriorityWeights() {
        assertTrue(TaskPriority.IMMEDIATE.weight > TaskPriority.INTERACTIVE.weight)
        assertTrue(TaskPriority.INTERACTIVE.weight > TaskPriority.BACKGROUND.weight)
    }
}
