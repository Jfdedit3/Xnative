package com.jfdedit3.xnative.reader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GalleryScannerTest {
    private lateinit var context: Context
    private lateinit var provider: GalleryDocumentsProvider
    private val tree = "content://gallery.test/tree/root"
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = GalleryDocumentsProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "gallery.test" })
        ShadowContentResolver.registerProviderInternal("gallery.test", provider)
    }
    @Test fun findsExistingMediaRecursivelyWithoutDownloadHistory() = runBlocking {
        provider.rows["root"] = listOf(Doc("folder", "NASA", DocumentsContract.Document.MIME_TYPE_DIR), Doc("photo", "photo.jpg", "image/jpeg", 450))
        provider.rows["folder"] = listOf(Doc("video", "clip.mp4", "video/mp4", 1500), Doc("gif", "animation.gif", "image/gif", 900))
        val scan = GalleryScanner.scan(context, tree)
        assertEquals(3, scan.files.size)
        assertEquals(2850L, scan.files.sumOf { it.size })
        assertEquals("NASA", scan.files.single { it.name == "clip.mp4" }.handle)
        assertTrue(scan.files.single { it.name == "clip.mp4" }.video)
        assertFalse(scan.files.single { it.name == "animation.gif" }.video)
        assertTrue(scan.files.all { it.uri.startsWith("content://gallery.test/") })
        assertTrue(scan.warnings.isEmpty())
        assertEquals(0, provider.mutations)
    }
    @Test fun skipsPartialHiddenAndNonMediaFilesButAcceptsMediaExtensions() = runBlocking {
        provider.rows["root"] = listOf(Doc("1", ".image.jpg.part", "image/jpeg"), Doc("2", "image.tmp", "image/jpeg"), Doc("3", "log.txt", "text/plain"), Doc("4", "movie.MP4", "application/octet-stream"), Doc("5", "picture.avif", ""))
        val files = GalleryScanner.scan(context, tree).files
        assertEquals(setOf("movie.MP4", "picture.avif"), files.map { it.name }.toSet())
        assertEquals("video/mp4", files.single { it.video }.mime)
        assertEquals("image/avif", files.single { !it.video }.mime)
    }
    @Test fun revokedFolderAccessProducesAnErrorRatherThanAnEmptySuccess() = runBlocking {
        provider.blocked += "root"
        val error = runCatching { GalleryScanner.scan(context, tree) }.exceptionOrNull()
        assertTrue(error is SecurityException)
    }
    @Test fun inaccessibleSubfolderKeepsReadableFilesAndReportsTheLimit() = runBlocking {
        provider.rows["root"] = listOf(Doc("locked", "Locked", DocumentsContract.Document.MIME_TYPE_DIR), Doc("photo", "ok.jpg", "image/jpeg", 50))
        provider.blocked += "locked"
        val scan = GalleryScanner.scan(context, tree)
        assertEquals("ok.jpg", scan.files.single().name)
        assertTrue(scan.warnings.single().contains("Locked"))
    }
    @Test fun sameDocumentInHistoryAndFolderIsDisplayedOnceWithHistoryMetadata() {
        val own = DownloadRecord("download", "nasa", "photo.jpg", uri = "content://gallery.test/document/photo", size = 10, postId = "123")
        val external = own.copy(id = "external", handle = "Dossier choisi", postId = "", uri = "content://gallery.test/tree/root/document/photo")
        val merged = GalleryScanner.merge(context, listOf(own), listOf(external))
        assertEquals(listOf(own), merged)
    }
    @Test fun equalNamesInDifferentFoldersAreNotMistakenForTheSameFile() {
        val own = DownloadRecord("first", "nasa", "photo.jpg", uri = "content://gallery.test/document/first")
        val external = own.copy(id = "second", uri = "content://gallery.test/tree/root/document/second")
        assertEquals(2, GalleryScanner.merge(context, listOf(own), listOf(external)).size)
    }
}

private data class Doc(val id: String, val name: String, val mime: String, val size: Long = 0)
private class GalleryDocumentsProvider : ContentProvider() {
    val rows = mutableMapOf<String, List<Doc>>()
    val blocked = mutableSetOf<String>()
    var mutations = 0
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val id = DocumentsContract.getDocumentId(uri)
        if (id in blocked) throw SecurityException("Access revoked")
        val columns = projection ?: error("Missing projection")
        return MatrixCursor(columns).apply {
            rows[id].orEmpty().forEach { doc ->
                val values = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to doc.id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to doc.name,
                    DocumentsContract.Document.COLUMN_MIME_TYPE to doc.mime, DocumentsContract.Document.COLUMN_SIZE to doc.size, DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1000L)
                addRow(columns.map { values[it] }.toTypedArray())
            }
        }
    }
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? { mutations++; return null }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; return 0 }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int { mutations++; return 0 }
}
