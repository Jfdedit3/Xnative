package com.jfdedit3.xnative.reader

import org.junit.Assert.*
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

class FxClientTest {
    private val tweet = """{"id":"20","url":"https://x.com/jack/status/20","text":"hello","author":{"name":"Jack","screen_name":"jack"},"created_timestamp":1142974214,"likes":8,"retweets":2,"media":{"all":[{"url":"https://example.org/a.jpg","type":"photo"}]}}"""
    private val rss = """<?xml version="1.0"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Channel</title><item><title>Hello</title><link>https://x.com/jack/status/20</link><pubDate>Tue, 21 Mar 2006 20:50:14 GMT</pubDate><description><![CDATA[<p>Hello &amp; world</p>]]></description><enclosure url="https://example.org/video.mp4" type="video/mp4"/><media:thumbnail url="https://example.org/thumb.jpg"/></item></channel></rss>"""

    @Test fun acceptsOnlyTwitterStatusLinks() {
        assertEquals("20", Links.postId("Regarde https://x.com/jack/status/20?s=2"))
        assertEquals("20", Links.postId("https://fxtwitter.com/jack/status/20/photo/1"))
        assertEquals("20", Links.postId("https://twitter.com/i/web/status/20"))
        listOf("https://evil.org/jack/status/20", "https://x.com.evil.org/jack/status/20", "https://x.com/jack", "https://x.com/jack/status/nope").forEach {
            assertTrue(runCatching { Links.postId(it) }.isFailure)
        }
        assertEquals("nasa", Links.handle(" @NASA "))
        assertTrue(runCatching { Links.handle("../bad") }.isFailure)
    }
    @Test fun parsesV1AndV2GroupedTimelines() {
        val post = PostParser.api("""{"code":200,"tweet":$tweet}""").single()
        assertEquals(8L, post.likes)
        assertEquals(2L, post.reposts)
        assertEquals(false, post.media.single().video)
        val grouped = PostParser.api("""{"code":200,"results":[{"type":"thread","statuses":[$tweet]},$tweet,{"type":"tombstone","id":"30"}]}""")
        assertEquals(listOf("20"), grouped.map { it.id })
        assertTrue(runCatching { PostParser.api("""{"code":404,"message":"Not found"}""") }.isFailure)
    }
    @Test fun parsesRssDatesEntitiesAndVideo() {
        val post = PostParser.rss(rss, "jack").single()
        assertEquals("Hello & world", post.text)
        assertEquals(1142974214L, post.timestamp)
        assertEquals("https://example.org/thumb.jpg", post.media.single().thumbnail)
        assertTrue(post.media.single().video)
        assertNull(post.likes)
        assertEquals("RSS", post.source)
        assertTrue(runCatching { PostParser.rss("<!DOCTYPE rss><rss/>", "jack") }.isFailure)
        assertTrue(runCatching { PostParser.rss("<html/>", "jack") }.isFailure)
    }
    @Test fun fallsBackToRssOnApiFailure() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody(rss))
            val base = server.url("/").toString().trimEnd('/')
            val result = FxClient(apiBase = base, rssBase = base).timeline("NASA", false)
            assertEquals("RSS", result.single().source)
            assertEquals("/2/profile/nasa/statuses?count=30&safe=1", server.takeRequest().path)
            assertEquals("/nasa/feed.xml?count=30&safe=1", server.takeRequest().path)
        }
    }
    @Test fun rssModeSkipsApi() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(rss))
            val base = server.url("/").toString().trimEnd('/')
            assertEquals(1, FxClient(apiBase = base, rssBase = base).timeline("jack", true).size)
            assertEquals(1, server.requestCount)
            assertEquals("/jack/feed.xml?count=30&safe=1", server.takeRequest().path)
        }
    }
    @Test fun importUsesValidatedIdAndReportsRateLimits() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":200,"tweet":$tweet}"""))
            val base = server.url("/").toString().trimEnd('/')
            val client = FxClient(apiBase = base)
            assertEquals("20", client.status("https://x.com/jack/status/20").id)
            assertEquals("/status/20", server.takeRequest().path)
            server.enqueue(MockResponse().setResponseCode(429))
            val failure = runCatching { client.status("https://x.com/jack/status/20") }.exceptionOrNull()
            assertTrue(failure!!.message!!.contains("limite"))
        }
    }
    @Test fun libraryMergesDeduplicatesAndRoundTripsOffline() {
        val first = PostParser.api("""{"tweet":$tweet}""").single()
        val second = first.copy(id = "21", timestamp = first.timestamp + 1)
        val library = Library(accounts = listOf("jack"), feeds = mapOf("jack" to listOf(first, second)), imported = listOf(first), saved = listOf(first))
        assertEquals(listOf("21", "20"), library.timeline().map { it.id })
        val encoded = PostParser.json.encodeToString(Library.serializer(), library)
        assertEquals(library, PostParser.json.decodeFromString<Library>(encoded))
    }
}
