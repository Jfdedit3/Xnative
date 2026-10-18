package com.jfdedit3.xnative.reader

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File

@Serializable
data class Preferences(
    val animations: Boolean = true, val showDuration: Boolean = true,
    val confirmDownloads: Boolean = true, val folder: String = "", val folderLabel: String = "Dossier personnalisé", val postsPerAccount: Int = 30,
    val retries: Boolean = true, val detailedErrors: Boolean = false, val mediaFilter: String = "all",
    val monitored: List<String> = emptyList(), val rssEnabled: Boolean = false,
    val batterySaver: Boolean = true, val startupNotification: Boolean = true
)

class SettingsStore private constructor(context: Context) {
    private val disk = context.applicationContext.getSharedPreferences("preferences", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(runCatching { PostParser.json.decodeFromString<Preferences>(disk.getString("json", "{}")!!) }.getOrDefault(Preferences()))
    val state = _state.asStateFlow()
    @Synchronized fun update(transform: (Preferences) -> Preferences) {
        val next = transform(_state.value)
        disk.edit().putString("json", PostParser.json.encodeToString(next)).apply()
        _state.value = next
    }
    companion object {
        @Volatile private var instance: SettingsStore? = null
        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context).also { instance = it }
        }
    }
}

internal fun atomicRead(file: AtomicFile): String = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
internal fun atomicWrite(file: AtomicFile, value: String) {
    val stream = file.startWrite()
    try { stream.write(value.toByteArray()); file.finishWrite(stream) }
    catch (error: Exception) { file.failWrite(stream); throw error }
}
object LibraryStorage {
    @Synchronized fun read(context: Context): Library {
        val file = AtomicFile(File(context.filesDir, "library.json"))
        return try { PostParser.json.decodeFromString(atomicRead(file)) }
        catch (_: java.io.FileNotFoundException) { Library() }
    }
    @Synchronized fun write(context: Context, value: Library) {
        atomicWrite(AtomicFile(File(context.filesDir, "library.json")), PostParser.json.encodeToString(value))
    }
}
@Serializable
data class CachedProfile(val user: Profile? = null, val posts: List<Post> = emptyList(), val mediaComplete: Boolean = false)
object ProfileCache {
    private var loaded = false
    private val _state = MutableStateFlow<Map<String, CachedProfile>>(emptyMap())
    val state = _state.asStateFlow()
    @Synchronized fun load(context: Context): Map<String, CachedProfile> {
        if (!loaded) {
            _state.value = runCatching { PostParser.json.decodeFromString<Map<String, CachedProfile>>(atomicRead(AtomicFile(File(context.filesDir, "profiles.json")))) }.getOrDefault(emptyMap())
            loaded = true
        }
        return _state.value
    }
    @Synchronized fun put(context: Context, handle: String, value: CachedProfile) {
        val previous = load(context)
        val merged = value.copy(user = value.user ?: previous[handle]?.user, posts = (value.posts + previous[handle]?.posts.orEmpty()).distinctBy { it.id })
        val next = previous + (Links.handle(handle) to merged)
        atomicWrite(AtomicFile(File(context.filesDir, "profiles.json")), PostParser.json.encodeToString(next))
        _state.value = next
    }
    @Synchronized fun clear(context: Context) {
        atomicWrite(AtomicFile(File(context.filesDir, "profiles.json")), "{}")
        loaded = true; _state.value = emptyMap()
    }
}
object Diagnostics {
    @Synchronized fun log(context: Context, message: String) { runCatching {
        val file = File(context.filesDir, "debug.log")
        if (file.length() > 512_000) file.writeText(file.readText().takeLast(256_000))
        file.appendText("${java.time.Instant.now()} $message\n")
    } }
    fun error(context: Context, error: Throwable): String {
        log(context, error.stackTraceToString())
        return if (SettingsStore.get(context).state.value.detailedErrors) error.stackTraceToString()
        else error.message ?: "Connexion impossible. Réessayez."
    }
    fun read(context: Context) = runCatching { File(context.filesDir, "debug.log").readText() }.getOrDefault("Aucun journal disponible.")
}
