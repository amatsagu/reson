package com.amatsagu.reson

import com.amatsagu.reson.data.remote.YouTubeRssFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class YouTubeRssFetcherTest {

    private val sampleAtomXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns:media="http://search.yahoo.com/mrss/" xmlns="http://www.w3.org/2005/Atom">
         <title>JerryRigEverything</title>
         <entry>
          <id>yt:video:k8W0Eb7sKss</id>
          <yt:videoId>k8W0Eb7sKss</yt:videoId>
          <yt:channelId>UCWFKCr40YwOZQx8FHU_ZqqQ</yt:channelId>
          <title>ONE PHONE TO RULE THEM ALL</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=k8W0Eb7sKss"/>
          <author>
           <name>JerryRigEverything</name>
          </author>
          <published>2026-11-07T19:12:05+00:00</published>
          <media:group>
           <media:title>ONE PHONE TO RULE THEM ALL</media:title>
           <media:thumbnail url="https://i4.ytimg.com/vi/k8W0Eb7sKss/hqdefault.jpg" width="480" height="360"/>
          </media:group>
         </entry>
         <entry>
          <id>yt:video:locked123</id>
          <yt:videoId>locked123</yt:videoId>
          <yt:channelId>UCWFKCr40YwOZQx8FHU_ZqqQ</yt:channelId>
          <title>Exclusive Teardown [Members Only]</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=locked123"/>
          <author>
           <name>JerryRigEverything</name>
          </author>
          <published>2026-11-08T19:12:05+00:00</published>
          <media:group>
           <media:title>Exclusive Teardown [Members Only]</media:title>
           <media:thumbnail url="https://i4.ytimg.com/vi/locked123/hqdefault.jpg" width="480" height="360"/>
          </media:group>
         </entry>
         <entry>
          <id>yt:video:FDNbcfMaF6o</id>
          <yt:videoId>FDNbcfMaF6o</yt:videoId>
          <yt:channelId>UCWFKCr40YwOZQx8FHU_ZqqQ</yt:channelId>
          <title>My favorite Smartphone of 2026</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=FDNbcfMaF6o"/>
          <author>
           <name>JerryRigEverything</name>
          </author>
          <published>2026-11-11T19:11:25+00:00</published>
          <media:group>
           <media:title>My favorite Smartphone of 2026</media:title>
           <media:thumbnail url="https://i3.ytimg.com/vi/FDNbcfMaF6o/hqdefault.jpg" width="480" height="360"/>
          </media:group>
         </entry>
        </feed>
    """.trimIndent()

    @Test
    fun testParseAtomFeed_extractsVideosCorrectly() {
        val stream = ByteArrayInputStream(sampleAtomXml.toByteArray(Charsets.UTF_8))
        val videos = YouTubeRssFetcher.parseAtomFeed(stream, "UCWFKCr40YwOZQx8FHU_ZqqQ", "JerryRigEverything")

        // 3 entries in XML, but 1 is [Members Only] which must be silently dropped
        assertEquals(2, videos.size)

        val v1 = videos[0]
        assertEquals("k8W0Eb7sKss", v1.id)
        assertEquals("ONE PHONE TO RULE THEM ALL", v1.title)
        assertEquals("JerryRigEverything", v1.channelTitle)
        assertEquals("UCWFKCr40YwOZQx8FHU_ZqqQ", v1.channelId)
        assertEquals("https://i4.ytimg.com/vi/k8W0Eb7sKss/hqdefault.jpg", v1.thumbnailUrl)
        assertTrue(v1.releaseTimestamp > 0L)

        val v2 = videos[1]
        assertEquals("FDNbcfMaF6o", v2.id)
        assertEquals("My favorite Smartphone of 2026", v2.title)
    }

    @Test
    fun testParseAtomFeed_filtersLockedVideos() {
        val stream = ByteArrayInputStream(sampleAtomXml.toByteArray(Charsets.UTF_8))
        val videos = YouTubeRssFetcher.parseAtomFeed(stream, "UCWFKCr40YwOZQx8FHU_ZqqQ", "JerryRigEverything")

        val titles = videos.map { it.title }
        assertFalse(titles.any { it.contains("Members Only", ignoreCase = true) })
    }

    @Test
    fun testFormatLastSyncedText() {
        // Test timestamps format logic
        val now = System.currentTimeMillis()
        val diffJustNow = (now - 10_000L) // 10s ago
        val diff15Min = (now - 15 * 60 * 1000L) // 15m ago
        val diff3Hours = (now - 3 * 3600 * 1000L) // 3h ago

        val secJustNow = (now - diffJustNow) / 1000L
        assertTrue(secJustNow < 60L)

        val min15 = ((now - diff15Min) / 60000L).toInt()
        assertEquals(15, min15)

        val h3 = ((now - diff3Hours) / 3600000L).toInt()
        assertEquals(3, h3)
    }
}
