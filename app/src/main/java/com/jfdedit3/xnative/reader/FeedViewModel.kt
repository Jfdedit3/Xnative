package com.jfdedit3.xnative.reader

import android.app.Application
import android.util.AtomicFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File

class FeedViewModel(application: Application) : AndroidViewModel(application) {
    private val client = FxClient()
    private val context = application.applicationContext
    val preferences = SettingsStore.get(context).state
    private val cursorHistory = mutableMapOf<String, MutableSet<String>>()
    private val cursors = mutableMapOf<String, String?>()
    private val _more = MutableStateFlow(false)
    val more = _more.asStateFlow()
    private val _library = MutableStateFlow(Library())
    val library = _library.asStateFlow()
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    init {
        viewModelScope.launch {
            _library.value = withContext(Dispatchers.IO) {
                try { ProfileCache.load(context); LibraryStorage.read(context) }
                catch (_: java.io.FileNotFoundException) { Library() }
                catch (_: Exception) { _message.value = "Le cache local est illisible. Vous pouvez recharger vos comptes."; Library() }
            }
            _busy.value = false
        }
    }
    fun clearMessage() { _message.value = null }
    private suspend fun persist() {
        val snapshot = _library.value
        try {
            withContext(Dispatchers.IO) {
                LibraryStorage.write(context, snapshot)
            }
        } catch (_: Exception) { _message.value = "Impossible de sauvegarder les données sur cet appareil." }
    }
    private fun work(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { client.retries = preferences.value.retries; action() }
            catch (error: Exception) { _message.value = Diagnostics.error(context, error) }
            finally { _busy.value = false }
        }
    }
    fun follow(input: String) = work {
        val account = Links.handle(input)
        require(account !in _library.value.accounts) { "Vous suivez déjà @$account." }
        // Keep the subscription even when the upstream is temporarily unavailable.
        _library.value = _library.value.copy(accounts = _library.value.accounts + account)
        persist()
        val posts = withContext(Dispatchers.IO) { firstPage(account) }
        _library.value = _library.value.copy(feeds = _library.value.feeds + (account to posts))
        persist()
        _message.value = "@$account ajouté · ${posts.size} publications"
    }
    fun unfollow(account: String) = work {
        cursors.remove(account); cursorHistory.remove(account); _more.value = cursors.values.any { it != null }
        _library.value = _library.value.copy(accounts = _library.value.accounts - account, feeds = _library.value.feeds - account)
        persist()
    }
    fun refresh() = work {
        val errors = mutableListOf<String>()
        for (account in _library.value.accounts) {
            try {
                val posts = withContext(Dispatchers.IO) { firstPage(account) }
                val previous = _library.value.feeds[account].orEmpty()
                val combined = (posts + previous).distinctBy { it.id }.sortedByDescending { it.timestamp }
                _library.value = _library.value.copy(feeds = _library.value.feeds + (account to combined))
            } catch (_: Exception) { errors += "@$account" }
        }
        persist()
        _message.value = if (errors.isEmpty()) "Votre fil est à jour" else "Actualisation impossible pour ${errors.joinToString()}. Le cache reste disponible."
    }
    fun importPost(link: String) = work {
        val post = withContext(Dispatchers.IO) { client.status(link) }
        _library.value = _library.value.copy(imported = (listOf(post) + _library.value.imported).distinctBy { it.id }.take(300))
        persist()
        _message.value = "Publication ajoutée au fil"
    }
    fun bookmark(post: Post) = work {
        val exists = _library.value.saved.any { it.id == post.id }
        _library.value = _library.value.copy(saved = if (exists) _library.value.saved.filterNot { it.id == post.id } else listOf(post) + _library.value.saved)
        persist()
    }
    fun rssOnly(enabled: Boolean) = work {
        cursors.clear(); cursorHistory.clear(); _more.value = false
        _library.value = _library.value.copy(rssOnly = enabled)
        persist()
        _message.value = "Source modifiée. Actualisez le fil pour l’utiliser."
    }
    private fun firstPage(account: String): List<Post> {
        if (_library.value.rssOnly) { cursors[account] = null; return client.timeline(account, true) }
        return try {
            val page = client.page(account, count = preferences.value.postsPerAccount)
            cursors[account] = page.next
            cursorHistory[account] = mutableSetOf<String>().apply { page.next?.let { add(it) } }
            _more.value = cursors.values.any { it != null }
            page.posts
        } catch (_: Exception) { cursors[account] = null; client.timeline(account, true) }
    }
    fun loadMore() = work {
        for ((account, cursor) in cursors.toMap()) {
            if (cursor == null || account !in _library.value.accounts) continue
            val page = withContext(Dispatchers.IO) { client.page(account, cursor, preferences.value.postsPerAccount) }
            cursors[account] = page.next?.takeIf { cursorHistory.getOrPut(account) { mutableSetOf(cursor) }.add(it) }
            _library.value = _library.value.copy(feeds = _library.value.feeds + (account to
                (_library.value.feeds[account].orEmpty() + page.posts).distinctBy { it.id }.sortedByDescending { it.timestamp }))
        }
        _more.value = cursors.values.any { it != null }; persist()
    }
    fun refreshCards() = work {
        for (account in _library.value.accounts) {
            try {
                withContext(Dispatchers.IO) {
                    val previous = ProfileCache.load(context)[account] ?: CachedProfile(posts = _library.value.feeds[account].orEmpty())
                    ProfileCache.put(context, account, previous.copy(user = client.profile(account)))
                }
            } catch (error: Exception) { Diagnostics.log(context, "Profil @$account : ${error.message}") }
        }
        _message.value = "Fiches mises à jour"
    }
    fun clearCards() = work { withContext(Dispatchers.IO) { ProfileCache.clear(context) }; _message.value = "Cache des fiches vidé" }
    fun clearImports() = work { _library.value = _library.value.copy(imported = emptyList()); persist() }
}
