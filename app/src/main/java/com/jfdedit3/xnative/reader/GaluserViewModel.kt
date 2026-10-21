package com.jfdedit3.xnative.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal fun galuserMedia(posts: List<Post>, account: String, video: Boolean): List<DownloadItem> =
    profilePostsForTab(posts, account, if (video) 2 else 1)
        .flatMap { post -> post.media.map { DownloadItem(post.handle, post.id, it) } }
        .distinctBy { it.postId to it.media.url }

internal data class GaluserState(
    val account: String = "", val user: Profile? = null, val posts: List<Post> = emptyList(),
    val loading: Boolean = false, val complete: Boolean = false, val error: String? = null,
    val notice: String? = null
)

internal class GaluserViewModel(application: Application, private val client: FxClient) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, FxClient())
    private val context = application.applicationContext
    private val _state = MutableStateFlow(GaluserState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var metadata: Job? = null
    private var pager = ProfileMediaPager(client)
    private var generation = 0
    private var loadId = 0

    fun open(input: String, library: Library) {
        val handle = Links.profileHandle(input)
        val ticket = ++generation
        val load = ++loadId
        request?.cancel(); metadata?.cancel()
        pager = ProfileMediaPager(client)
        client.retries = SettingsStore.get(context).state.value.retries
        SettingsStore.get(context).update { it.copy(galuserAccount = handle) }
        val local = (library.feeds[handle].orEmpty() + library.timeline() + library.saved)
            .filter { it.handle.equals(handle, true) && it.repostedBy.isBlank() }
            .distinctBy { it.id }
        _state.value = GaluserState(handle, posts = local, loading = true)
        request = viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { ProfileCache.load(context)[handle] }
                ensureActive()
                _state.value = _state.value.copy(user = _state.value.user ?: cached?.user, posts = ownPosts(cached?.posts.orEmpty() + local, handle))
                fetch(ticket, handle)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == generation && load == loadId) fail(error) }
            finally { if (ticket == generation && load == loadId) _state.value = _state.value.copy(loading = false) }
        }
        // A metadata failure must not prevent retrieval of an accessible media feed.
        metadata = viewModelScope.launch {
            try {
                val user = withContext(Dispatchers.IO) { client.profile(handle) }
                ensureActive()
                if (ticket == generation) { _state.value = _state.value.copy(user = user); persist() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Diagnostics.log(context, "Fiche Galuser @$handle : ${error.message}") }
        }
    }
    private fun ownPosts(posts: List<Post>, account: String) = posts
        .filter { it.handle.equals(account, true) && it.repostedBy.isBlank() }
        .distinctBy { it.id }.sortedByDescending { it.timestamp }

    private suspend fun fetch(ticket: Int, handle: String) {
        while (!pager.complete) {
            currentCoroutineContext().ensureActive()
            val candidate = pager.fork()
            val page = withContext(Dispatchers.IO) { candidate.next(handle) }
            currentCoroutineContext().ensureActive()
            if (ticket != generation) return
            pager = candidate
            _state.value = _state.value.copy(posts = ownPosts(page.posts + _state.value.posts, handle),
                notice = candidate.notice, complete = candidate.complete, error = null)
            persist()
        }
        if (ticket == generation) _state.value = _state.value.copy(complete = true)
    }
    private suspend fun persist() {
        val snapshot = _state.value
        try {
            withContext(Dispatchers.IO) { ProfileCache.put(context, snapshot.account, CachedProfile(snapshot.user, snapshot.posts, snapshot.complete)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Diagnostics.log(context, "Cache Galuser : ${error.message}") }
    }
    private fun fail(error: Exception) {
        Diagnostics.error(context, error)
        _state.value = _state.value.copy(error = "Les médias n’ont pas pu être entièrement récupérés. Réessayez pour reprendre ; les médias chargés restent disponibles.")
    }
    fun resume() {
        if (_state.value.loading || _state.value.complete || _state.value.account.isBlank()) return
        val ticket = generation; val handle = _state.value.account; val load = ++loadId
        _state.value = _state.value.copy(loading = true, error = null)
        request = viewModelScope.launch {
            try { fetch(ticket, handle) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (ticket == generation && load == loadId) fail(error) }
            finally { if (ticket == generation && load == loadId) _state.value = _state.value.copy(loading = false) }
        }
    }
    fun stop() { loadId++; request?.cancel(); _state.value = _state.value.copy(loading = false) }
}
