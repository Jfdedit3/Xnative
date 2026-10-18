package com.jfdedit3.xnative.reader

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.ArrayDeque

internal data class GalleryScan(val files: List<DownloadRecord>, val warnings: List<String> = emptyList())

internal object GalleryScanner {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif")
    private val videoExtensions = setOf("mp4", "webm", "m4v", "mov", "mkv", "3gp", "avi", "mpeg", "mpg")
    internal fun mime(name: String, declared: String): String? {
        if (name.startsWith('.') || name.substringAfterLast('.').lowercase() in setOf("part", "tmp", "partial", "crdownload")) return null
        if (declared.startsWith("image/") || declared.startsWith("video/")) return declared
        val extension = name.substringAfterLast('.').lowercase()
        return when (extension) {
            in imageExtensions -> "image/${if (extension in setOf("jpg", "jpeg")) "jpeg" else extension}"
            in videoExtensions -> "video/${if (extension in setOf("mp4", "m4v")) "mp4" else if (extension == "mov") "quicktime" else extension}"
            else -> null
        }
    }
    suspend fun scan(context: Context, folder: String, label: String = "Dossier choisi"): GalleryScan {
        val tree = Uri.parse(folder)
        val queue = ArrayDeque<Pair<String, String>>()
        val visited = mutableSetOf<String>()
        val files = mutableListOf<DownloadRecord>()
        val warnings = mutableListOf<String>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to label)
        while (queue.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (parent, group) = queue.removeFirst()
            if (!visited.add(parent)) continue
            try {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val cursor = context.contentResolver.query(children, projection, null, null, null) ?: error("Le dossier n’est plus accessible.")
                cursor.use {
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = it.getString(0); val name = it.getString(1).orEmpty(); val declared = it.getString(2).orEmpty()
                        if (declared == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith('.')) queue.add(id to name)
                        } else {
                            val mime = mime(name, declared) ?: continue
                            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()
                            files += DownloadRecord(uri, group, name, uri = uri, mime = mime, size = it.getLong(3),
                                video = mime.startsWith("video/"), time = it.getLong(4))
                        }
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (visited.size == 1) throw error
                warnings += "Le sous-dossier $group n’a pas pu être lu."
            }
        }
        return GalleryScan(files.sortedByDescending { it.time }, warnings)
    }
    private fun uriKey(uri: String): String = runCatching {
        val value = Uri.parse(uri)
        "${value.authority}:${DocumentsContract.getDocumentId(value)}"
    }.getOrDefault(uri)
    private fun inode(context: Context, uri: String): String? = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use {
            val stat = Os.fstat(it.fileDescriptor)
            if (stat.st_ino > 0) "${stat.st_dev}:${stat.st_ino}" else null
        }
    }.getOrNull()
    fun merge(context: Context, history: List<DownloadRecord>, scanned: List<DownloadRecord>): List<DownloadRecord> {
        val known = history.map { uriKey(it.uri) }.toMutableSet()
        val byName = history.groupBy { it.name }
        val physical = mutableMapOf<String, String?>()
        fun physicalKey(uri: String) = physical.getOrPut(uri) { inode(context, uri) }
        val additions = scanned.filter { candidate ->
            if (!known.add(uriKey(candidate.uri))) false
            else {
                val matching = byName[candidate.name].orEmpty()
                val candidateKey = if (matching.isNotEmpty()) physicalKey(candidate.uri) else null
                candidateKey == null || matching.none { physicalKey(it.uri) == candidateKey }
            }
        }
        return (history + additions).sortedByDescending { it.time }
    }
}
