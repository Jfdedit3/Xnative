package com.jfdedit3.xnative.reader

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProfileMediaPagerTest {
    private fun page(id: String? = "1", cursor: String? = null): MockResponse {
        val rows = id?.let { """{"id":"$it","author":{"screen_name":"test","name":"Test"},"media":{"all":[{"url":"https://example.org/$it.jpg","type":"photo"}]}}""" }.orEmpty()
        return MockResponse().setBody("""{"code":200,"results":[$rows],"cursor":{"bottom":${cursor?.let { "\"$it\"" } ?: "null"}}}""")
    }
    private fun pager(server: MockWebServer): ProfileMediaPager {
        val base = server.url("/").toString().trimEnd('/')
        return ProfileMediaPager(FxClient(apiBase = base, rssBase = base))
    }
    @Test fun emptyPageEndsPaginationEvenWhenServerReturnsAnotherCursor() {
        MockWebServer().use { server ->
            server.enqueue(page("1", "first")); server.enqueue(page(null, "endless"))
            val pager = pager(server)
            assertEquals(1, pager.next("test").posts.size)
            assertFalse(pager.complete)
            assertNull(pager.next("test").next)
            assertTrue(pager.complete)
            assertTrue(runCatching { pager.next("test") }.isFailure)
            assertEquals(2, server.requestCount)
            assertNull(pager.notice)
        }
    }
    @Test fun failedLaterPageResumesItsCursorWithoutDiscardingProgress() {
        MockWebServer().use { server ->
            server.enqueue(page("1", "second")); server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(page("2"))
            val pager = pager(server)
            assertEquals("1", pager.next("test").posts.single().id)
            assertTrue(runCatching { pager.next("test") }.isFailure)
            assertFalse(pager.complete)
            assertEquals("2", pager.next("test").posts.single().id)
            assertTrue(pager.complete)
            server.takeRequest()
            assertEquals(server.takeRequest().path, server.takeRequest().path)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun unavailableMediaFallsBackToPaginatedStatusesAndLabelsTheSource() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(page("1", "next-status")); server.enqueue(page("2"))
            val pager = pager(server)
            assertEquals("1", pager.next("test").posts.single().id)
            assertTrue(pager.notice!!.contains("publications accessibles"))
            assertFalse(pager.complete)
            assertEquals("2", pager.next("test").posts.single().id)
            assertTrue(pager.complete)
            assertTrue(server.takeRequest().path!!.contains("/media?"))
            assertTrue(server.takeRequest().path!!.contains("/statuses?"))
            assertTrue(server.takeRequest().path!!.contains("/statuses?count=100&safe=1&cursor=next-status"))
        }
    }
    @Test fun rssFallbackIsExplicitlyLimitedToRecentPosts() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404)); server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setBody("""<rss><channel><item><link>https://x.com/test/status/1</link><enclosure url="https://example.org/1.jpg" type="image/jpeg"/></item></channel></rss>"""))
            val pager = pager(server)
            assertEquals(1, pager.next("test").posts.single().media.size)
            assertTrue(pager.complete)
            assertTrue(pager.notice!!.contains("RSS récent"))
            server.takeRequest(); server.takeRequest()
            assertEquals("/test/feed.xml?count=100&safe=1", server.takeRequest().path)
        }
    }
    @Test fun repeatedCursorFailsWithoutLoopingOrMarkingHistoryComplete() {
        MockWebServer().use { server ->
            server.enqueue(page("1", "same")); server.enqueue(page("2", "same")); server.enqueue(page("2"))
            val pager = pager(server)
            pager.next("test")
            assertTrue(runCatching { pager.next("test") }.exceptionOrNull()!!.message!!.contains("répète"))
            assertFalse(pager.complete)
            assertEquals("2", pager.next("test").posts.single().id)
            assertTrue(pager.complete)
        }
    }
    @Test fun failureOfAllSourcesLeavesFirstPageRetryable() {
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
            server.enqueue(page("1"))
            val pager = pager(server)
            assertTrue(runCatching { pager.next("test") }.isFailure)
            assertFalse(pager.complete)
            assertNull(pager.notice)
            assertEquals("1", pager.next("test").posts.single().id)
            assertTrue(pager.complete)
        }
    }
}
