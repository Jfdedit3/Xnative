package com.jfdedit3.xnative.reader

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GaluserTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SettingsStore.get(app).update { it.copy(retries = false) }
        ProfileCache.clear(app)
    }
    private fun model(server: MockWebServer): GaluserViewModel {
        val base = server.url("/").toString().trimEnd('/')
        return GaluserViewModel(app, FxClient(apiBase = base, rssBase = base))
    }
    private fun row(id: String, handle: String = "alice", repost: Boolean = false) = """{"id":"$id","author":{"screen_name":"$handle","name":"$handle"},${if (repost) "\"reposted_by\":{\"screen_name\":\"alice\"}," else ""}"media":{"all":[{"type":"photo","url":"https://example.org/$id.jpg"},{"type":"video","url":"https://example.org/$id.mp4"}]}}"""
    private fun page(rows: String, cursor: String? = null) = MockResponse().setBody("""{"code":200,"results":[$rows],"cursor":{"bottom":${cursor?.let { "\"$it\"" } ?: "null"}}}""")
    private fun profile(handle: String = "alice") = MockResponse().setBody("""{"code":200,"user":{"screen_name":"$handle","name":"$handle"}}""")
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        fail("Galuser did not finish")
    }
    @Test fun automaticallyRetrievesPagesAndSeparatesOwnImagesAndVideosWithoutReposts() {
        MockWebServer().use { server ->
            val pages = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!request.path.orEmpty().contains("/media?")) return profile()
                    return when (pages.incrementAndGet()) {
                        1 -> page(listOf(row("1"), row("2", "other", true), row("3", "other"), row("4", repost = true)).joinToString(","), "second")
                        2 -> page("${row("5")},${row("1")}", "empty")
                        else -> page("", "endless")
                    }
                }
            }
            val model = model(server)
            try {
                model.open("https://x.com/ALICE", Library())
                await { !model.state.value.loading }
                val state = model.state.value
                assertEquals("alice", state.account)
                assertEquals(setOf("1", "5"), state.posts.map { it.id }.toSet())
                assertEquals(2, galuserMedia(state.posts, state.account, false).size)
                assertEquals(2, galuserMedia(state.posts, state.account, true).size)
                assertTrue(galuserMedia(state.posts, state.account, false).none { it.media.video })
                assertTrue(galuserMedia(state.posts, state.account, true).all { it.media.video })
                assertTrue(state.complete)
                assertNull(state.error)
                assertEquals(3, pages.get())
                assertEquals("alice", SettingsStore.get(app).state.value.galuserAccount)
                assertEquals(setOf("1", "5"), ProfileCache.load(app)["alice"]!!.posts.map { it.id }.toSet())
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun failedPageResumesItsCursorAndKeepsPreviouslyRetrievedMedia() {
        MockWebServer().use { server ->
            val paths = ConcurrentLinkedQueue<String>()
            val calls = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!request.path.orEmpty().contains("/media?")) return profile()
                    paths.add(request.path!!)
                    return when (calls.incrementAndGet()) {
                        1 -> page(row("1"), "next")
                        2 -> MockResponse().setResponseCode(503)
                        else -> page(row("2"))
                    }
                }
            }
            val model = model(server)
            try {
                model.open("alice", Library()); await { !model.state.value.loading }
                assertNotNull(model.state.value.error)
                assertEquals("1", model.state.value.posts.single().id)
                model.resume(); await { !model.state.value.loading }
                assertTrue(model.state.value.complete)
                assertNull(model.state.value.error)
                assertEquals(setOf("1", "2"), model.state.value.posts.map { it.id }.toSet())
                assertEquals(paths.toList()[1], paths.toList()[2])
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun stoppingAnInFlightPageDoesNotCommitItsCursorOrSkipMediaOnResume() {
        MockWebServer().use { server ->
            val calls = AtomicInteger()
            val paths = ConcurrentLinkedQueue<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!request.path.orEmpty().contains("/media?")) return profile()
                    paths.add(request.path!!)
                    val count = calls.incrementAndGet()
                    return if (request.requestUrl!!.queryParameter("cursor") == null) page(row("1"), "second").apply {
                        if (count == 1) setBodyDelay(150, TimeUnit.MILLISECONDS)
                    } else page(row("2"))
                }
            }
            val model = model(server)
            try {
                model.open("alice", Library()); await { calls.get() >= 1 }
                model.stop()
                assertFalse(model.state.value.loading)
                model.resume(); await { !model.state.value.loading }
                assertTrue(model.state.value.complete)
                assertEquals(setOf("1", "2"), model.state.value.posts.map { it.id }.toSet())
                assertEquals(paths.toList()[0], paths.toList()[1])
                Thread.sleep(200)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(model.state.value.complete)
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun pagesContainingOnlyRepostsDoNotEndTheGalleryBeforeOwnMediaArrive() {
        MockWebServer().use { server ->
            val pages = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (!request.path.orEmpty().contains("/media?")) return profile()
                    return if (pages.incrementAndGet() == 1) page(row("repost", "other", true), "own") else page(row("own"))
                }
            }
            val model = model(server)
            try {
                model.open("alice", Library()); await { !model.state.value.loading }
                assertEquals("own", model.state.value.posts.single().id)
                assertEquals(2, pages.get())
                assertTrue(model.state.value.complete)
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun changingUserDoesNotMixInAResponseFromThePreviousUser() {
        MockWebServer().use { server ->
            val aliceRequests = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/2/profile/alice/media?") -> {
                        aliceRequests.incrementAndGet()
                        page(row("alice-post")).setBodyDelay(150, TimeUnit.MILLISECONDS)
                    }
                    request.path.orEmpty().startsWith("/2/profile/bob/media?") -> page(row("bob-post", "bob"))
                    request.path == "/2/profile/bob" -> profile("bob")
                    else -> profile()
                }
            }
            val model = model(server)
            try {
                model.open("alice", Library()); await { aliceRequests.get() > 0 }
                model.open("bob", Library()); await { !model.state.value.loading && model.state.value.user?.handle == "bob" }
                Thread.sleep(200); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("bob", model.state.value.account)
                assertEquals("bob", model.state.value.user!!.handle)
                assertEquals("bob-post", model.state.value.posts.single().id)
                assertEquals("bob", SettingsStore.get(app).state.value.galuserAccount)
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun cachedOwnMediaRemainAvailableWhenAllMediaSourcesFail() {
        MockWebServer().use { server ->
            val own = Post("cached", "https://x.com/alice/status/1", "", "Alice", "alice", media = listOf(Media("https://example.org/cache.jpg")))
            ProfileCache.put(app, "alice", CachedProfile(Profile("alice", "Alice"), listOf(own, own.copy(id = "other", handle = "other"))))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = if (request.path == "/2/profile/alice") profile() else MockResponse().setResponseCode(503)
            }
            val model = model(server)
            try {
                model.open("alice", Library()); await { !model.state.value.loading }
                assertEquals("cached", model.state.value.posts.single().id)
                assertNotNull(model.state.value.error)
                assertFalse(model.state.value.complete)
            } finally { model.viewModelScope.cancel() }
        }
    }
}
