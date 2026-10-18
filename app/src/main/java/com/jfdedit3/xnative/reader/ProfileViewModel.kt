package com.jfdedit3.xnative.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ProfileState(
    val account: String = "", val user: Profile? = null, val posts: List<Post> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val postsError: String? = null,
    val next: String? = null, val mediaLoading: Boolean = false, val mediaComplete: Boolean = false
)
class ProfileViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val client = FxClient()
    private val _state = MutableStateFlow(ProfileState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var mediaJob: Job? = null
    private var generation = 0
    fun load(account: String, library: Library) {
        val handle = Links.handle(account); val ticket = ++generation
        request?.cancel(); mediaJob?.cancel()
        val local = (library.feeds[handle].orEmpty() + library.timeline().filter { it.handle.equals(handle, true) } + library.saved.filter { it.handle.equals(handle, true) }).distinctBy { it.id }
        _state.value = ProfileState(handle, posts = local, loading = true)
        request = viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { ProfileCache.load(context)[handle] }
            if (cached != null) _state.value = _state.value.copy(user = cached.user, posts = (cached.posts + local).distinctBy { it.id }, mediaComplete = cached.mediaComplete)
            client.retries = SettingsStore.get(context).state.value.retries
            try {
                try { _state.value = _state.value.copy(user = withContext(Dispatchers.IO) { client.profile(handle) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { _state.value = _state.value.copy(error = Diagnostics.error(context, error)) }
                try {
                    val page = withContext(Dispatchers.IO) {
                        if (library.rssOnly) PostPage(client.timeline(handle, true))
                        else client.page(handle, count = SettingsStore.get(context).state.value.postsPerAccount)
                    }
                    _state.value = _state.value.copy(posts = (page.posts + _state.value.posts).distinctBy { it.id }.sortedByDescending { it.timestamp }, next = page.next, mediaComplete = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { _state.value = _state.value.copy(postsError = Diagnostics.error(context, error)) }
                save()
            } finally { if (generation == ticket) _state.value = _state.value.copy(loading = false) }
        }
    }
    private suspend fun save() {
        val snapshot = _state.value
        try { withContext(Dispatchers.IO) { ProfileCache.put(context, snapshot.account, CachedProfile(snapshot.user, snapshot.posts, snapshot.mediaComplete)) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { Diagnostics.log(context, "Cache profil : ${error.message}") }
    }
    fun loadMore() {
        val current = _state.value; val cursor = current.next ?: return
        if (current.loading) return
        val ticket = generation
        _state.value = current.copy(loading = true)
        request = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { client.page(current.account, cursor, SettingsStore.get(context).state.value.postsPerAccount) }
                _state.value = _state.value.copy(posts = (_state.value.posts + page.posts).distinctBy { it.id }.sortedByDescending { it.timestamp }, next = page.next?.takeIf { it != cursor })
                save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(postsError = Diagnostics.error(context, error)) }
            finally { if (generation == ticket) _state.value = _state.value.copy(loading = false) }
        }
    }
    fun loadAllMedia() {
        if (_state.value.mediaLoading || _state.value.mediaComplete || _state.value.loading) return
        val ticket = generation; val handle = _state.value.account
        _state.value = _state.value.copy(mediaLoading = true)
        mediaJob = viewModelScope.launch {
            try {
                val seen = mutableSetOf<String>(); var cursor: String? = null
                do {
                    ensureActive()
                    val page = withContext(Dispatchers.IO) { client.page(handle, cursor, 100, media = true) }
                    _state.value = _state.value.copy(posts = (_state.value.posts + page.posts).distinctBy { it.id }.sortedByDescending { it.timestamp })
                    cursor = page.next
                    if (cursor != null && !seen.add(cursor)) error("Le serveur répète son curseur. Rechargez les médias pour réessayer.")
                    save()
                } while (cursor != null)
                _state.value = _state.value.copy(mediaComplete = true); save()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { _state.value = _state.value.copy(postsError = Diagnostics.error(context, error)) }
            finally { if (generation == ticket) _state.value = _state.value.copy(mediaLoading = false) }
        }
    }
    fun stopMedia() { mediaJob?.cancel() }
    fun close() { generation++; request?.cancel(); mediaJob?.cancel() }
}
