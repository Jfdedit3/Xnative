package com.jfdedit3.xnative.reader

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ProfileTest {
    private val body = """{"code":200,"user":{"id":"11348282","screen_name":"NASA","name":"NASA","description":"Space & science ✨","avatar_url":"https://example.org/avatar.jpg","banner_url":"https://example.org/banner.jpg","location":"Pale Blue Dot","website":{"url":"https://nasa.gov","display_url":"nasa.gov"},"joined":"Wed Dec 19 20:20:32 +0000 2007","followers":92322930,"following":115,"statuses":74438,"verification":{"verified":true,"type":"government"},"protected":false}}"""

    @Test fun decodesCompleteProfileEnvelope() {
        val user = PostParser.profile(body)
        assertEquals("NASA", user.handle)
        assertEquals("Space & science ✨", user.description)
        assertEquals("https://example.org/banner.jpg", user.banner)
        assertEquals("https://example.org/avatar.jpg", user.avatar)
        assertEquals("nasa.gov", user.websiteLabel)
        assertEquals("Pale Blue Dot", user.location)
        assertEquals(92322930L, user.followers)
        assertEquals(115L, user.following)
        assertEquals(74438L, user.statuses)
        assertTrue(user.verified)
        assertFalse(user.protected)
    }
    @Test fun distinguishesMissingCountsFromZeroAndSupportsPrivateAccounts() {
        val user = PostParser.profile("""{"code":200,"user":{"screen_name":"private","name":"","protected":true,"followers":0,"website":null,"verification":null}}""")
        assertEquals("private", user.name)
        assertTrue(user.protected)
        assertFalse(user.verified)
        assertEquals(0L, user.followers)
        assertNull(user.following)
        assertNull(user.statuses)
        assertEquals("", user.website)
    }
    @Test fun rejectsErrorsAndMalformedProfiles() {
        listOf("""{"code":404,"message":"User not found"}""", """{"code":200}""", """{"user":{"screen_name":"../invalid"}}""").forEach {
            assertTrue(runCatching { PostParser.profile(it) }.isFailure)
        }
    }
    @Test fun requestsValidatedProfileEndpointAndPropagatesHttpErrors() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(body))
            val client = FxClient(apiBase = server.url("/").toString().trimEnd('/'))
            assertEquals("NASA", client.profile(" @NASA ").handle)
            assertEquals("/2/profile/nasa", server.takeRequest().path)
            assertTrue(runCatching { client.profile("../invalid") }.isFailure)
            assertEquals(1, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(404))
            assertTrue(runCatching { client.profile("missing") }.exceptionOrNull()!!.message!!.contains("introuvable"))
        }
    }
}
