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
    fun testEpisode_creationAndDefaults() {
        val episode = com.amatsagu.reson.model.Episode(
            id = "test_123",
            title = "Test Episode",
            channelTitle = "Test Channel",
            durationText = "45m",
            durationSeconds = 2700
        )
        assertEquals("test_123", episode.id)
        assertEquals("https://www.youtube.com/watch?v=test_123", episode.webpageUrl)
        assertEquals(0f, episode.playbackProgress, 0.001f)
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
}
