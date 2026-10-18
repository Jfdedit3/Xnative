package com.xnative.app

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
    private val disk = AtomicFile(File(application.filesDir, "library.json"))
    private val client = FxClient()
    private val _library = MutableStateFlow(Library())
    val library = _library.asStateFlow()
    private val _busy = MutableStateFlow(true)
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    init {
        viewModelScope.launch {
            _library.value = withContext(Dispatchers.IO) {
                try { disk.openRead().use { PostParser.json.decodeFromString<Library>(it.readBytes().toString(Charsets.UTF_8)) } }
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
                val stream = disk.startWrite()
                try { stream.write(PostParser.json.encodeToString(snapshot).toByteArray()); disk.finishWrite(stream) }
                catch (error: Exception) { disk.failWrite(stream); throw error }
            }
        } catch (_: Exception) { _message.value = "Impossible de sauvegarder les données sur cet appareil." }
    }
    private fun work(action: suspend () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try { action() }
            catch (error: Exception) { _message.value = error.message ?: "Connexion impossible. Vérifiez votre accès Internet." }
            finally { _busy.value = false }
        }
    }
    fun follow(input: String) = work {
        val account = Links.handle(input)
        require(account !in _library.value.accounts) { "Vous suivez déjà @$account." }
        require(_library.value.accounts.size < 30) { "Vous pouvez suivre jusqu’à 30 comptes." }
        // Keep the subscription even when the upstream is temporarily unavailable.
        _library.value = _library.value.copy(accounts = _library.value.accounts + account)
        persist()
        val posts = withContext(Dispatchers.IO) { client.timeline(account, _library.value.rssOnly) }
        _library.value = _library.value.copy(feeds = _library.value.feeds + (account to posts))
        persist()
        _message.value = "@$account ajouté · ${posts.size} publications"
    }
    fun unfollow(account: String) = work {
        _library.value = _library.value.copy(accounts = _library.value.accounts - account, feeds = _library.value.feeds - account)
        persist()
    }
    fun refresh() = work {
        val errors = mutableListOf<String>()
        for (account in _library.value.accounts) {
            try {
                val posts = withContext(Dispatchers.IO) { client.timeline(account, _library.value.rssOnly) }
                val previous = _library.value.feeds[account].orEmpty()
                val combined = (posts + previous).distinctBy { it.id }.sortedByDescending { it.timestamp }.take(150)
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
        _library.value = _library.value.copy(rssOnly = enabled)
        persist()
        _message.value = "Source modifiée. Actualisez le fil pour l’utiliser."
    }
    fun clearImports() = work { _library.value = _library.value.copy(imported = emptyList()); persist() }
}
