package com.amatsagu.reson

import com.amatsagu.reson.ui.components.AVAILABLE_PLAYBACK_SPEEDS
import com.amatsagu.reson.ui.components.formatPlaybackSpeed
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackUnitTest {
    @Test
    fun playbackSpeedOptions_containsExpectedTempos() {
        assertEquals(listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f), AVAILABLE_PLAYBACK_SPEEDS)
    }

    @Test
    fun formatPlaybackSpeed_formatsCorrectly() {
        assertEquals("0.75x", formatPlaybackSpeed(0.75f))
        assertEquals("1x", formatPlaybackSpeed(1.0f))
        assertEquals("1.25x", formatPlaybackSpeed(1.25f))
        assertEquals("1.5x", formatPlaybackSpeed(1.5f))
        assertEquals("2x", formatPlaybackSpeed(2.0f))
        assertEquals("3x", formatPlaybackSpeed(3.0f))
    }

    @Test
    fun rewindCalculation_respectsThresholdAndBounds() {
        fun calculateRewindTarget(currentPosMs: Long, pauseDurationMs: Long): Long {
            return if (pauseDurationMs >= 60_000L) {
                (currentPosMs - 15_000L).coerceAtLeast(0L)
            } else {
                currentPosMs
            }
        }

        // Less than 1 minute paused -> no rewind
        assertEquals(30_000L, calculateRewindTarget(30_000L, 59_999L))
        assertEquals(100_000L, calculateRewindTarget(100_000L, 10_000L))

        // >= 1 minute paused -> rewind 15 seconds
        assertEquals(45_000L, calculateRewindTarget(60_000L, 60_000L))
        assertEquals(85_000L, calculateRewindTarget(100_000L, 120_000L))

        // Position less than 15s when paused >= 1 min -> clamped to 0 (00:00)
        assertEquals(0L, calculateRewindTarget(10_000L, 65_000L))
        assertEquals(0L, calculateRewindTarget(5_000L, 90_000L))
    }

    @Test
    fun playbackState_defaultsTo1x() {
        val state = com.amatsagu.reson.core.player.PlaybackState()
        assertEquals(1.0f, state.playbackSpeed)
    }
}
