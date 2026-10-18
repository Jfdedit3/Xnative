package com.jfdedit3.xnative.reader

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SearchViewModelTest {
    private lateinit var app: Application
    private val response = """{"code":200,"tweet":{"id":"20","text":"The linked post","author":{"name":"Jack","screen_name":"jack"},"media":{"all":[{"type":"photo","url":"https://example.org/a.jpg"},{"type":"video","url":"https://example.org/b.mp4","thumbnail_url":"https://example.org/b.jpg"}]}}}"""
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        SettingsStore.get(app).update { it.copy(retries = false) }
    }
    private fun model(server: MockWebServer): SearchViewModel = SearchViewModel(app, FxClient(apiBase = server.url("/").toString().trimEnd('/')))
    private fun await(model: SearchViewModel) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!model.state.value.loading) return
            Thread.sleep(5)
        }
        fail("Search did not finish")
    }
    @Test fun postLinkInAccountsModeShowsOnlyTheLinkedPostAndAllItsMedia() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            val model = model(server)
            try {
                model.search("https://x.com/jack/status/20?s=46", posts = false)
                await(model)
                val state = model.state.value
                assertTrue(state.directPost)
                assertEquals("20", state.posts.single().id)
                assertEquals("The linked post", state.posts.single().text)
                assertEquals(2, state.posts.single().media.size)
                assertTrue(state.accounts.isEmpty())
                assertNull(state.next)
                assertNull(state.error)
                assertEquals("/status/20", server.takeRequest().path)
                assertEquals(1, server.requestCount)
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun supportedPostLinksUseTheStatusEndpointRatherThanGlobalSearch() {
        listOf("https://twitter.com/jack/status/20", "https://fxtwitter.com/jack/status/20/photo/2", "Regarde https://fixupx.com/jack/status/20?s=1").forEach { link ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(response))
                val model = model(server)
                try {
                    model.search(link, posts = true)
                    await(model)
                    assertEquals("20", model.state.value.posts.single().id)
                    assertTrue(model.state.value.directPost)
                    assertEquals("/status/20", server.takeRequest().path)
                } finally { model.viewModelScope.cancel() }
            }
        }
    }
    @Test fun unavailableLinkedPostShowsAnErrorWithoutUnrelatedCachedResults() {
        MockWebServer().use { server ->
            LibraryStorage.write(app, Library(saved = listOf(Post("99", "https://x.com/other/status/99", "Cached post", "Other", "other"))))
            server.enqueue(MockResponse().setResponseCode(404))
            val model = model(server)
            try {
                model.search("https://x.com/jack/status/20", posts = true)
                await(model)
                val state = model.state.value
                assertTrue(state.directPost)
                assertTrue(state.posts.isEmpty())
                assertTrue(state.accounts.isEmpty())
                assertNotNull(state.error)
                assertFalse(state.error!!.contains("cache local"))
                assertEquals(1, server.requestCount)
            } finally { model.viewModelScope.cancel() }
        }
    }
    @Test fun accountSearchAfterALinkReplacesTheDirectPostResult() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(response))
            server.enqueue(MockResponse().setBody("""{"code":200,"users":[{"screen_name":"NASA","name":"NASA"}]}"""))
            val model = model(server)
            try {
                model.search("https://x.com/jack/status/20", posts = false); await(model)
                model.search("NASA", posts = false); await(model)
                val state = model.state.value
                assertFalse(state.directPost)
                assertTrue(state.posts.isEmpty())
                assertEquals("NASA", state.accounts.single().handle)
                assertNull(state.error)
                server.takeRequest()
                assertEquals("/2/typeahead?q=NASA&result_type=users", server.takeRequest().path)
            } finally { model.viewModelScope.cancel() }
        }
    }
}
