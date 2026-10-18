package com.jfdedit3.xnative.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Profile requests have their own lifecycle so browsing never blocks local bookmarks or follows.
data class ProfileState(
    val account: String = "", val user: Profile? = null, val posts: List<Post> = emptyList(),
    val loading: Boolean = false, val error: String? = null, val postsError: String? = null
)

class ProfileViewModel : ViewModel() {
    private val client = FxClient()
    private val cache = mutableMapOf<String, ProfileState>()
    private val _state = MutableStateFlow(ProfileState())
    val state = _state.asStateFlow()
    private var request: Job? = null
    private var generation = 0

    fun load(account: String, library: Library) {
        val handle = Links.handle(account)
        val ticket = ++generation
        request?.cancel()
        val previous = cache[handle]
        val local = (library.timeline() + library.saved).distinctBy { it.id }.filter { it.handle.equals(handle, ignoreCase = true) }
        val author = local.firstOrNull()
        _state.value = ProfileState(handle,
            user = previous?.user ?: author?.let { Profile(it.handle, it.name, avatar = it.avatar) },
            posts = previous?.posts ?: local, loading = true)
        request = viewModelScope.launch {
            try {
                try {
                    val user = withContext(Dispatchers.IO) { client.profile(handle) }
                    _state.value = _state.value.copy(user = user)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    _state.value = _state.value.copy(error = error.message ?: "Informations du profil indisponibles.")
                }
                try {
                    val posts = withContext(Dispatchers.IO) { client.timeline(handle, library.rssOnly) }
                    _state.value = _state.value.copy(posts = posts.sortedByDescending { it.timestamp })
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    _state.value = _state.value.copy(postsError = "Publications indisponibles. Les publications déjà chargées restent affichées.")
                }
            } finally {
                // A cancelled, older request must never replace the next profile's state.
                if (generation == ticket) {
                    _state.value = _state.value.copy(loading = false)
                    cache.remove(handle)
                    cache[handle] = _state.value
                    if (cache.size > 30) cache.remove(cache.keys.first())
                }
            }
        }
    }

    fun close() { generation++; request?.cancel() }
}
