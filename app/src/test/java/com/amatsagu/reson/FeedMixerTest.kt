package com.amatsagu.reson

import com.amatsagu.reson.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedMixerTest {

    private fun createTestVideo(id: String, channelId: String, title: String): Video {
        return Video(
            id = id,
            channelId = channelId,
            channelTitle = channelId,
            title = title,
            durationText = "10m",
            releaseTimestamp = 1700000000L,
        )
    }

    @Test
    fun testMix_ratioIsRoughly25PercentFollowed() {
        val followed = (1..10).map { createTestVideo("f$it", "FollowedCh${it % 3}", "Followed Video $it") }
        val recommended = (1..30).map { createTestVideo("r$it", "RecCh${it % 5}", "Rec Video $it") }

        val mixed = FeedMixer.mix(followed, recommended, limit = 20)
        assertEquals(20, mixed.size)

        // Every 4th item starting at 0 should be followed content
        val followedCount = mixed.count { it.id.startsWith("f") }
        val recCount = mixed.count { it.id.startsWith("r") }

        assertEquals(5, followedCount) // 25% of 20 = 5
        assertEquals(15, recCount)    // 75% of 20 = 15

        // Slot pattern: 0 -> followed, 1,2,3 -> recommended, 4 -> followed, 5,6,7 -> recommended
        assertTrue(mixed[0].id.startsWith("f"))
        assertTrue(mixed[1].id.startsWith("r"))
        assertTrue(mixed[2].id.startsWith("r"))
        assertTrue(mixed[3].id.startsWith("r"))
        assertTrue(mixed[4].id.startsWith("f"))
    }

    @Test
    fun testMix_antiClustering_noConsecutiveSameChannel() {
        // Diverse set of channels
        val followed = (1..6).map { createTestVideo("f$it", "FollowedCh${it % 3}", "V$it") }
        val recommended = (1..15).map { createTestVideo("r$it", "RecCh${it % 5}", "V$it") }

        val mixed = FeedMixer.mix(followed, recommended, limit = 18, channelWindowSize = 2)

        for (i in 0 until (mixed.size - 1)) {
            val ch1 = mixed[i].channelId
            val ch2 = mixed[i + 1].channelId
            assertFalse("Consecutive videos share the same channel: $ch1 at indices $i and ${i + 1}", ch1 == ch2)
        }
    }

    @Test
    fun testMix_strictlyExcludesWatchedAndHistoryIds() {
        val followed = (1..5).map { createTestVideo("f$it", "ChA", "V$it") }
        val recommended = (1..10).map { createTestVideo("r$it", "ChB", "V$it") }

        val excluded = setOf("f2", "r1", "r5")
        val mixed = FeedMixer.mix(followed, recommended, excludeIds = excluded, limit = 15)

        for (vid in mixed) {
            assertFalse("Excluded ID found in feed: ${vid.id}", vid.id in excluded)
        }
        // Ensure no duplicates exist in result
        val ids = mixed.map { it.id }
        assertEquals(ids.distinct().size, ids.size)
    }

    @Test
    fun testMix_fallbackWhenFollowedPoolRunsOut() {
        // Followed has only 1 video, recommended has 10
        val followed = listOf(createTestVideo("f1", "ChA", "V1"))
        val recommended = (1..10).map { createTestVideo("r$it", "ChRec${it % 3}", "V$it") }

        val mixed = FeedMixer.mix(followed, recommended, limit = 8)
        assertEquals(8, mixed.size)
        assertEquals(1, mixed.count { it.id.startsWith("f") })
        assertEquals(7, mixed.count { it.id.startsWith("r") })
    }

    @Test
    fun testMix_fallbackWhenRecommendedPoolEmpty() {
        val followed = (1..5).map { createTestVideo("f$it", "ChA$it", "V$it") }
        val recommended = emptyList<Video>()

        val mixed = FeedMixer.mix(followed, recommended, limit = 4)
        assertEquals(4, mixed.size)
        assertTrue(mixed.all { it.id.startsWith("f") })
    }

    @Test
    fun testDynamicTopicExtractor_compoundTitle() {
        val video = createTestVideo("1", "c1", "Mitchell Hashimoto Reinvents the Terminal... Again | Standup #80")
        val channel = com.amatsagu.reson.model.Channel(id = "c1", title = "TheStandupPod")
        val query = DynamicTopicExtractor.extractSearchQuery(video, channel)
        assertTrue(
            "Expected topic query to contain Mitchell Hashimoto Terminal, got '$query'",
            query?.contains("Mitchell Hashimoto", ignoreCase = true) == true,
        )
        assertFalse(
            "Expected channel/series noise stripped, got '$query'",
            query?.contains("Standup", ignoreCase = true) == true,
        )
    }

    @Test
    fun testDynamicTopicExtractor_conversationalTitle() {
        val video = createTestVideo("2", "c2", "So, Apple Intelligence is Kinda Broken Right Now")
        val channel = com.amatsagu.reson.model.Channel(id = "c2", title = "SAMTIME")
        val query = DynamicTopicExtractor.extractSearchQuery(video, channel)
        assertTrue(
            "Expected topic query to contain Apple Intelligence, got '$query'",
            query?.contains("Apple Intelligence", ignoreCase = true) == true,
        )
        assertFalse(
            "Expected leading conversational filler 'So' stripped, got '$query'",
            query?.startsWith("So", ignoreCase = true) == true,
        )
    }

    @Test
    fun testDynamicTopicExtractor_polishTitle() {
        val video = createTestVideo("3", "c3", "3 MITY O WYPALENIU")
        val channel = com.amatsagu.reson.model.Channel(id = "c3", title = "Nauka. To Lubię")
        val query = DynamicTopicExtractor.extractSearchQuery(video, channel)
        assertTrue(
            "Expected Polish topic keywords preserved, got '$query'",
            (query?.contains("WYPALENIU", ignoreCase = true) == true) && (query.contains("MITY", ignoreCase = true)),
        )
    }

    @Test
    fun testMix_filtersOutShortsEverywhere() {
        val normalFollowed = createTestVideo("f1", "FollowedCh", "Normal Followed Video")
        val shortFollowed = Video(
            id = "f_short",
            channelId = "FollowedCh",
            channelTitle = "FollowedCh",
            title = "Quick hack #shorts",
            durationText = "30s",
            durationSeconds = 30
        )
        val normalRec = createTestVideo("r1", "RecCh", "Normal Recommended Video")
        val shortRec = Video(
            id = "r_short",
            channelId = "RecCh",
            channelTitle = "RecCh",
            title = "Another tip #short",
            durationText = "45s",
            durationSeconds = 45
        )

        val mixed = FeedMixer.mix(
            followedUnwatched = listOf(normalFollowed, shortFollowed),
            recommended = listOf(normalRec, shortRec),
            limit = 10
        )

        assertFalse(mixed.any { it.id == "f_short" })
        assertFalse(mixed.any { it.id == "r_short" })
        assertEquals(2, mixed.size)
    }

    @Test
    fun testMix_newsAndAmericaBiasDemotion_andIgnoredWhenFollowed() {
        // Biased non-followed channel: "Global News"
        val biasedRec1 = createTestVideo("r_news1", "Global News", "Morning News Headline")
        val biasedRec2 = createTestVideo("r_us1", "America Live", "Discovering America")
        // Unbiased non-followed channel: "Tech Insights"
        val unbiasedRec = createTestVideo("r_tech1", "Tech Insights", "Writing a Compiler in Rust")

        // 1. When non-followed, unbiased recommendation is picked first
        val mixedUnfollowed = FeedMixer.mix(
            followedUnwatched = emptyList(),
            recommended = listOf(biasedRec1, biasedRec2, unbiasedRec),
            limit = 1
        )
        assertEquals("r_tech1", mixedUnfollowed.first().id)

        // 2. When a channel with "News" IS followed, bias is completely ignored
        val followedNewsVideo = createTestVideo("f_news1", "Tech News Daily", "Tech News Episode 100")
        val mixedFollowed = FeedMixer.mix(
            followedUnwatched = listOf(followedNewsVideo),
            recommended = listOf(unbiasedRec),
            followedChannelIds = setOf("Tech News Daily"),
            limit = 2
        )
        // Followed news video is placed in slot 0 as expected
        assertEquals("f_news1", mixedFollowed.first().id)
    }
}

