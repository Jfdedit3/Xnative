package com.jfdedit3.xnative.reader

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.OutputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class DownloadItem(val handle: String, val postId: String, val media: Media) {
    val id: String get() = stableName(postId, media)
}
fun stableName(postId: String, media: Media): String {
    val uri = java.net.URI(media.url)
    val format = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("format=") }?.substringAfter('=')
    val suffix = uri.path.orEmpty().substringAfterLast('.').lowercase()
    val extension = when {
        media.mime.contains("gif", true) || suffix == "gif" -> "gif"
        suffix in setOf("jpg", "jpeg", "png", "webp", "mp4", "webm") -> suffix
        format in setOf("jpg", "jpeg", "png", "gif", "webp") -> format!!
        media.mime.contains("png", true) -> "png"
        media.mime.contains("webp", true) -> "webp"
        media.mime.contains("webm", true) -> "webm"
        media.video -> "mp4"
        else -> "jpg"
    }
    val query = if (uri.host.orEmpty().endsWith("twimg.com")) "" else uri.rawQuery.orEmpty().split('&').filter { part ->
        val key = part.substringBefore('=').lowercase()
        key !in setOf("format", "name", "token", "sig", "signature", "expires", "exp") && !key.startsWith("x-amz-")
    }.filter { it.isNotBlank() }.sorted().joinToString("&")
    val canonical = "${uri.host}:${uri.port}${uri.path}?$query:$extension"
    val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    return "${postId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_$hash.$extension"
}
fun mediaMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"
    "mp4" -> "video/mp4"; "webm" -> "video/webm"; else -> "image/jpeg"
}
@Serializable
data class DownloadRecord(val id: String, val handle: String, val name: String, val uri: String = "", val mime: String = "", val size: Long = 0,
    val state: String = "complete", val error: String = "", val source: String = "", val postId: String = "", val video: Boolean = false, val session: String = "", val time: Long = System.currentTimeMillis())
object DownloadSession { val id: String = java.util.UUID.randomUUID().toString() }
object DownloadHistory {
    private val _state = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val state = _state.asStateFlow()
    private var loaded = false
    @Synchronized fun load(context: Context): List<DownloadRecord> {
        if (!loaded) {
            val base = runCatching { PostParser.json.decodeFromString<List<DownloadRecord>>(atomicRead(android.util.AtomicFile(File(context.filesDir, "downloads.json")))) }.getOrDefault(emptyList())
            val values = base.associateBy { "${it.handle}/${it.id}" }.toMutableMap()
            val journal = File(context.filesDir, "downloads.events")
            if (journal.exists()) journal.forEachLine { line ->
                // Only the final, interrupted line may be incomplete after a process death.
                runCatching { PostParser.json.decodeFromString<DownloadRecord>(line) }.getOrNull()?.let { values["${it.handle}/${it.id}"] = it }
            }
            if (journal.length() > 0) {
                val terminated = java.io.RandomAccessFile(journal, "r").use { it.seek(it.length() - 1); it.read() == 10 }
                if (!terminated) journal.appendText("\n")
            }
            _state.value = values.values.sortedByDescending { it.time }
            loaded = true
        }
        return _state.value
    }
    @Synchronized fun put(context: Context, record: DownloadRecord) {
        val next = listOf(record) + load(context).filterNot { it.id == record.id && it.handle == record.handle }
        val journal = File(context.filesDir, "downloads.events")
        FileOutputStream(journal, true).use { output ->
            output.write((PostParser.json.encodeToString(record) + "\n").toByteArray()); output.fd.sync()
        }
        if (journal.length() > 5_000_000) {
            atomicWrite(android.util.AtomicFile(File(context.filesDir, "downloads.json")), PostParser.json.encodeToString(next))
            FileOutputStream(journal).use { it.fd.sync() }
        }
        _state.value = next
    }
    @Synchronized fun recover(context: Context) {
        load(context).filter { it.state == "pending" && it.session != DownloadSession.id }.forEach {
            runCatching { context.contentResolver.delete(Uri.parse(it.uri), null, null) }
            put(context, it.copy(state = "cancelled", error = "Transfert interrompu avant sa fin."))
        }
    }
}

data class TransferState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val name: String = "", val bytes: Long = 0, val length: Long = -1, val speed: Long = 0, val cancelled: Boolean = false)
object Transfers {
    val state = MutableStateFlow(TransferState())
    fun start(context: Context, items: List<DownloadItem>) {
        require(items.isNotEmpty()) { "Aucun média à télécharger." }
        val before = state.value
        check(!before.running && state.compareAndSet(before, TransferState(running = true, total = items.size))) { "Un lot est déjà en cours. Annulez-le ou attendez sa fin." }
        try {
        val file = File(context.filesDir, "download-queue.json")
        file.writeText(PostParser.json.encodeToString(items.distinctBy { "${it.handle}/${it.id}" }))
        if (state.value.cancelled) { file.delete(); state.value = state.value.copy(running = false); return }
        androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, MediaDownloadService::class.java))
        } catch (error: Exception) { state.value = TransferState(); throw error }
    }
    fun cancel(context: Context) {
        state.value = state.value.copy(cancelled = true)
        MediaDownloadService.cancelActive()
        context.startService(Intent(context, MediaDownloadService::class.java).setAction("cancel"))
    }
}

class PendingFile(val uri: Uri, val output: OutputStream, val finish: () -> Uri, val abort: () -> Unit)
object MediaFiles {
    fun begin(context: Context, name: String, mime: String, handle: String = "", folder: String = SettingsStore.get(context).state.value.folder): PendingFile {
        val path = "XNative" + if (handle.isNotBlank()) "/${Links.handle(handle)}" else ""
        if (folder.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder)) ?: error("Dossier sélectionné inaccessible.")
            val base = tree.findFile("XNative") ?: tree.createDirectory("XNative") ?: error("Création du dossier impossible.")
            val dir = if (handle.isBlank()) base else base.findFile(Links.handle(handle)) ?: base.createDirectory(Links.handle(handle)) ?: error("Dossier du compte inaccessible.")
            check(dir.findFile(name) == null) { "Un fichier portant ce nom existe déjà dans ce dossier." }
            val temp = dir.createFile(mime, ".$name.part") ?: error("Création du fichier impossible.")
            val output = try { context.contentResolver.openOutputStream(temp.uri) ?: error("Écriture impossible.") } catch (error: Exception) { temp.delete(); throw error }
            return PendingFile(temp.uri, output, {
                output.close(); check(temp.renameTo(name)) { "Impossible de publier le fichier terminé." }; temp.uri
            }, { runCatching { output.close() }; temp.delete(); Unit })
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name); put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$path/"); put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Stockage indisponible.")
            val output = try { context.contentResolver.openOutputStream(uri) ?: error("Écriture impossible.") }
            catch (error: Exception) { context.contentResolver.delete(uri, null, null); throw error }
            return PendingFile(uri, output, {
                output.close(); check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) { "Publication MediaStore impossible." }; uri
            }, { runCatching { output.close() }; context.contentResolver.delete(uri, null, null); Unit })
        }
        @Suppress("DEPRECATION") val dir = File(File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOWNLOADS), path)
        check(dir.mkdirs() || dir.isDirectory) { "Impossible de créer le dossier de téléchargement." }
        val finalFile = File(dir, name)
        check(!finalFile.exists()) { "Ce fichier existe déjà. Aucun écrasement n’a été effectué." }
        val temp = File(dir, ".$name.part")
        check(temp.createNewFile()) { "Un transfert incomplet portant ce nom existe déjà." }
        val output = try { temp.outputStream() } catch (error: Exception) { temp.delete(); throw error }
        val uri = try { FileProvider.getUriForFile(context, "${context.packageName}.files", temp) } catch (error: Exception) { output.close(); temp.delete(); throw error }
        return PendingFile(uri, output, {
            output.close(); check(!finalFile.exists() && temp.renameTo(finalFile)) { "Publication impossible." }
            MediaScannerConnection.scanFile(context, arrayOf(finalFile.absolutePath), arrayOf(mime), null)
            FileProvider.getUriForFile(context, "${context.packageName}.files", finalFile)
        }, { runCatching { output.close() }; temp.delete(); Unit })
    }
    fun size(context: Context, uri: String): Long? = runCatching {
        context.contentResolver.query(Uri.parse(uri), arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) it.getLong(0) else null }
    }.getOrNull()
    fun export(context: Context, name: String, value: String, mime: String = "application/json"): Uri {
        val exports = context.getSharedPreferences("exports", Context.MODE_PRIVATE)
        val key = "$name:${SettingsStore.get(context).state.value.folder}"
        exports.getString(key, null)?.let { previous -> runCatching {
            val uri = Uri.parse(previous)
            if (android.provider.DocumentsContract.isDocumentUri(context, uri)) DocumentFile.fromSingleUri(context, uri)?.delete()
            else context.contentResolver.delete(uri, null, null)
        } }
        val target = begin(context, name, mime)
        try { target.output.write(value.toByteArray()); val uri = target.finish(); exports.edit().putString(key, uri.toString()).apply(); return uri }
        catch (error: Exception) { target.abort(); throw error }
    }
}

class MediaDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "cancel") { cancelActive(); task?.cancel(); if (task == null) stopSelf(); return START_NOT_STICKY }
        if (task?.isActive == true) return START_NOT_STICKY
        if (Transfers.state.value.cancelled) { Transfers.state.value = Transfers.state.value.copy(running = false); stopSelf(); return START_NOT_STICKY }
        Notifications.channels(this)
        Transfers.state.value = TransferState(running = true)
        ServiceCompat.startForeground(this, 100, notification(TransferState(running = true)), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        activeCancelled = false
        task = scope.launch {
            var failures = 0; var successes = 0
            try {
                val items = PostParser.json.decodeFromString<List<DownloadItem>>(File(filesDir, "download-queue.json").readText())
                for ((index, item) in items.withIndex()) {
                    ensureActive()
                    val old = DownloadHistory.load(this@MediaDownloadService).firstOrNull { it.id == item.id && it.handle == item.handle && it.state == "complete" }
                    if (old != null && MediaFiles.size(this@MediaDownloadService, old.uri) != null) { successes++; continue }
                    var record = DownloadRecord(item.id, item.handle, item.id, mime = mediaMime(item.id), state = "pending", source = item.media.url, postId = item.postId, video = item.media.video, session = DownloadSession.id)
                    var target: PendingFile? = null
                    try {
                        target = MediaFiles.begin(this@MediaDownloadService, item.id, record.mime, item.handle)
                        record = record.copy(uri = target.uri.toString()); DownloadHistory.put(this@MediaDownloadService, record)
                        transfer(item, target, index, items.size)
                        ensureActive()
                        val uri = target.finish()
                        DownloadHistory.put(this@MediaDownloadService, record.copy(uri = uri.toString(), state = "complete", size = MediaFiles.size(this@MediaDownloadService, uri.toString()) ?: 0))
                        target = null; successes++
                    } catch (error: Exception) {
                        target?.abort()
                        val cancelled = !isActive || activeCancelled
                        DownloadHistory.put(this@MediaDownloadService, record.copy(state = if (cancelled) "cancelled" else "failed", error = error.message ?: "Transfert interrompu."))
                        if (cancelled) throw CancellationException("Annulé")
                        failures++; Diagnostics.log(this@MediaDownloadService, "Téléchargement ${item.id} : ${error.message}")
                    }
                }
                Notifications.post(this@MediaDownloadService, 101, "Téléchargements terminés", "$successes fichiers disponibles · $failures échecs", "downloads")
            } catch (_: CancellationException) {
                Notifications.post(this@MediaDownloadService, 101, "Lot annulé", "$successes fichiers terminés conservés", "downloads")
            } catch (error: Exception) { Diagnostics.log(this@MediaDownloadService, error.stackTraceToString()) }
            finally {
                activeCall = null; activeCancelled = false
                Transfers.state.value = Transfers.state.value.copy(running = false)
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    private suspend fun transfer(item: DownloadItem, target: PendingFile, index: Int, total: Int) {
        val call = httpClient.newCall(Request.Builder().url(item.media.url).build()); activeCall = call
        call.execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body ?: error("Fichier vide.")
            val type = body.contentType()?.toString().orEmpty()
            check(type.isBlank() || type.startsWith("image/") || type.startsWith("video/") || type.startsWith("application/octet-stream")) { "Le serveur n’a pas renvoyé un média ($type)." }
            var bytes = 0L; val start = System.currentTimeMillis(); var last = 0L
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    if (activeCancelled) throw CancellationException("Annulé")
                    val count = input.read(buffer); if (count < 0) break
                    target.output.write(buffer, 0, count); bytes += count
                    val now = System.currentTimeMillis()
                    if (now - last >= 500) {
                        last = now
                        val state = TransferState(true, index, total, item.id, bytes, body.contentLength(), bytes * 1000 / (now - start).coerceAtLeast(1))
                        Transfers.state.value = state
                        if (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(this@MediaDownloadService, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            getSystemService(NotificationManager::class.java).notify(100, notification(state))
                    }
                }
            }
            check(body.contentLength() < 0 || body.contentLength() == bytes) { "Fichier incomplet." }
        }
    }
    private fun notification(state: TransferState): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, MediaDownloadService::class.java).setAction("cancel"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "downloads").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(if (state.total <= 0) "Xnative · Préparation du lot" else "Xnative · ${state.done + 1}/${state.total}")
            .setContentText("${state.name} · ${state.speed / 1024} Ko/s").setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(Notifications.open(this, "downloads"))
            .setProgress(100, if (state.length > 0) (state.bytes * 100 / state.length).toInt() else 0, state.length <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Annuler", cancel).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) { cancelActive(); task?.cancel(); stopSelf() }
    override fun onDestroy() { cancelActive(); scope.cancel(); super.onDestroy() }
    companion object {
        internal var httpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build()
        @Volatile private var activeCall: Call? = null
        @Volatile private var activeCancelled = false
        fun cancelActive() { activeCancelled = true; activeCall?.cancel() }
    }
}
