package com.jfdedit3.xnative.reader

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class MediaRulesTest {
    @Test fun namesRemainStableAcrossOrderAndQualityChanges() {
        val first = Media("https://pbs.twimg.com/media/abc?format=jpg&name=large")
        assertEquals(stableName("123", first), stableName("123", first.copy(url = "https://pbs.twimg.com/media/abc?name=orig&format=jpg")))
        assertNotEquals(stableName("123", first), stableName("123", first.copy(url = "https://pbs.twimg.com/media/def?format=jpg")))
        assertNotEquals(stableName("123", Media("https://example.org/image?id=1")), stableName("123", Media("https://example.org/image?id=2")))
        val batch = listOf(first, first.copy(url = "https://example.org/another.jpg"))
        assertEquals(batch.map { stableName("123", it) }.toSet(), batch.reversed().map { stableName("123", it) }.toSet())
        assertTrue(stableName("123", Media("https://example.org/a.gif")).endsWith(".gif"))
        assertEquals("image/gif", mediaMime(stableName("123", Media("https://example.org/a.gif"))))
        assertTrue(stableName("123", Media("https://example.org/a.webm", video = true)).endsWith(".webm"))
    }
    @Test fun reservedPagesAreNeverTreatedAsProfiles() {
        listOf("home", "explore", "notifications", "messages", "settings", "i", "search").forEach { assertTrue(runCatching { Links.profileHandle("https://x.com/$it") }.isFailure) }
        assertEquals("nasa", Links.profileHandle("https://fxtwitter.com/NASA?lang=fr"))
        assertEquals("nasa", Links.profileHandle("@NASA"))
        assertTrue(runCatching { Links.profileHandle("https://evil.org/NASA") }.isFailure)
        assertTrue(runCatching { Links.profileHandle("https://x.com/NASA/status/20") }.isFailure)
    }
    @Test fun pagesKeepOpaqueCursorAndQueryIsEncoded() {
        val body = """{"code":200,"results":[],"cursor":{"bottom":"a+b/==&x=1","top":null}}"""
        assertEquals("a+b/==&x=1", PostParser.page(body).next)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val client = FxClient(apiBase = server.url("/").toString().trimEnd('/'))
            client.page("NASA", "a+b/==&x=1", media = true)
            val uri = server.takeRequest().requestUrl!!
            assertEquals("/2/profile/nasa/media", uri.encodedPath)
            assertEquals("a+b/==&x=1", uri.queryParameter("cursor"))
            assertNull(uri.queryParameter("x"))
        }
    }
    @Test fun apiRetriesExactlyThreeTimesWhenEnabled() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }
            server.enqueue(MockResponse().setBody("""{"code":200,"results":[],"cursor":{"bottom":null}}"""))
            val client = FxClient(apiBase = server.url("/").toString().trimEnd('/')).apply { retries = true }
            assertTrue(client.page("nasa").posts.isEmpty())
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun favoritesExportIsVersionedAndContainsPortableData() {
        val document = FavoritesExport.json(Library(accounts = listOf("nasa")), mapOf("nasa" to CachedProfile(Profile("nasa", "NASA"))))
        val decoded = PostParser.json.decodeFromString<FavoritesDocument>(document)
        assertEquals("XNative.favorites", decoded.format)
        assertEquals(1, decoded.version)
        assertEquals(listOf("nasa"), decoded.accounts)
        assertEquals("NASA", decoded.profiles["nasa"]!!.user!!.name)
    }
}
