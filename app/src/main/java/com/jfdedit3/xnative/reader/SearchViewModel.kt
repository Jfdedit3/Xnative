package com.jfdedit3.xnative.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchState(val query: String = "", val accounts: List<Profile> = emptyList(), val posts: List<Post> = emptyList(), val next: String? = null, val loading: Boolean = false, val error: String? = null)
class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(SearchState()); val state = _state.asStateFlow()
    private var request: Job? = null
    private val cursors = mutableSetOf<String>()
    fun search(query: String, posts: Boolean, more: Boolean = false) {
        if (more && (_state.value.loading || _state.value.next == null)) return
        request?.cancel()
        if (!more) cursors.clear()
        val previous = _state.value
        _state.value = if (more) previous.copy(loading = true, error = null) else SearchState(query, loading = true)
        request = viewModelScope.launch {
            try {
                val client = FxClient().apply { retries = SettingsStore.get(getApplication()).state.value.retries }
                if (posts) {
                    val page = withContext(Dispatchers.IO) { client.search(query, if (more) previous.next else null) }
                    _state.value = _state.value.copy(posts = ((if (more) previous.posts else emptyList()) + page.posts).distinctBy { it.id }, next = page.next?.takeIf { cursors.add(it) })
                } else _state.value = _state.value.copy(accounts = withContext(Dispatchers.IO) { client.accounts(query) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                val details = Diagnostics.error(getApplication(), error)
                if (posts && !more) {
                    val local = withContext(Dispatchers.IO) {
                        val library = runCatching { LibraryStorage.read(getApplication()) }.getOrDefault(Library())
                        val all = (library.timeline() + library.saved + ProfileCache.load(getApplication()).values.flatMap { it.posts }).distinctBy { it.id }
                        val author = Regex("from:([A-Za-z0-9_]+)", RegexOption.IGNORE_CASE).find(query)?.groupValues?.get(1)
                        val text = query.replace(Regex("from:[A-Za-z0-9_]+", RegexOption.IGNORE_CASE), "").trim()
                        all.filter { (author == null || it.handle.equals(author, true)) && (text.isBlank() || "${it.text} ${it.handle} ${it.name}".contains(text, true)) }
                    }
                    _state.value = _state.value.copy(posts = local, next = null, error = "Recherche distante indisponible ; résultats du cache local. $details")
                } else _state.value = _state.value.copy(error = details)
            }
            finally { if (isActive) _state.value = _state.value.copy(loading = false) }
        }
    }
}
object NavigationRequests {
    val link = MutableStateFlow<String?>(null)
    val page = MutableStateFlow<String?>(null)
}
