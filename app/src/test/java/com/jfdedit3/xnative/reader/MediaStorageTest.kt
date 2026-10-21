package com.jfdedit3.xnative.reader

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MediaStorageTest {
    private lateinit var context: Context
    private lateinit var provider: DiskMediaProvider
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        provider = DiskMediaProvider()
        provider.attachInfo(context, ProviderInfo().apply { authority = "media" })
        ShadowContentResolver.registerProviderInternal("media", provider)
        SettingsStore.get(context).update { it.copy(folder = "") }
    }
    @Test fun interruptedFilesAreDeletedAndNeverPublished() {
        val target = MediaFiles.begin(context, "interrupted.jpg", "image/jpeg", "nasa")
        target.output.write(byteArrayOf(1, 2, 3))
        assertEquals(1, provider.rows[target.uri]!!.first.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        target.abort()
        assertFalse(provider.rows.containsKey(target.uri))
        assertEquals(0, provider.published)
    }
    @Test fun completedFileIsPublishedAfterTheStreamClosesAndHasActualSize() {
        val target = MediaFiles.begin(context, "complete.jpg", "image/jpeg", "nasa")
        target.output.write(byteArrayOf(1, 2, 3, 4, 5))
        assertEquals(0, provider.published)
        val uri = target.finish()
        assertEquals(1, provider.published)
        assertEquals(0, provider.rows[uri]!!.first.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        assertEquals("Download/XNative/nasa/", provider.rows[uri]!!.first.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals(5L, MediaFiles.size(context, uri.toString()))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), provider.rows[uri]!!.second.readBytes())
    }
    @Test @Config(sdk = [24]) fun legacyDownloadsNeverOverwriteAnExistingFile() {
        org.robolectric.shadows.ShadowEnvironment.setExternalStorageState(android.os.Environment.MEDIA_MOUNTED)
        val name = "legacy-${System.nanoTime()}.jpg"
        val target = MediaFiles.begin(context, name, "image/jpeg", "nasa")
        target.output.write(byteArrayOf(9, 8, 7))
        val uri = target.finish()
        assertEquals(3L, MediaFiles.size(context, uri.toString()))
        assertTrue(runCatching { MediaFiles.begin(context, name, "image/jpeg", "nasa") }.isFailure)
        assertEquals(3L, MediaFiles.size(context, uri.toString()))
        context.contentResolver.delete(uri, null, null)
    }
    @Test fun queueRejectsASecondBatch() {
        Transfers.state.value = TransferState()
        try {
            Transfers.start(context, listOf(DownloadItem("nasa", "1", Media("https://example.org/a.jpg"))))
            assertTrue(Transfers.state.value.running)
            assertTrue(runCatching { Transfers.start(context, listOf(DownloadItem("nasa", "2", Media("https://example.org/b.jpg")))) }.isFailure)
        } finally { Transfers.state.value = TransferState() }
    }
    @Test fun completedServiceTransferAppearsInPersistentHistory() {
        val previous = MediaDownloadService.httpClient
        MediaDownloadService.httpClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body(byteArrayOf(1, 2, 3, 4).toResponseBody("image/jpeg".toMediaType())).build()
        }.build()
        val item = DownloadItem("nasa", "success-test", Media("https://example.org/success-test.jpg"))
        File(context.filesDir, "download-queue.json").writeText(PostParser.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DownloadItem.serializer()), listOf(item)))
        val controller = Robolectric.buildService(MediaDownloadService::class.java).create()
        try {
            controller.get().onStartCommand(android.content.Intent(context, MediaDownloadService::class.java), 0, 1)
            val deadline = System.currentTimeMillis() + 5000
            while (Transfers.state.value.running && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertFalse(Transfers.state.value.running)
            val record = DownloadHistory.state.value.first { it.id == item.id }
            assertEquals("complete", record.state)
            assertEquals(4L, MediaFiles.size(context, record.uri))
            assertEquals(1, provider.published)
            val persisted = File(context.filesDir, "downloads.events").readLines().map { PostParser.json.decodeFromString<DownloadRecord>(it) }
            assertEquals("complete", persisted.last { it.id == item.id }.state)
        } finally { controller.destroy(); MediaDownloadService.httpClient = previous; Transfers.state.value = TransferState() }
    }
    @Test fun cancellationInterruptsTheActiveStreamAndRemovesItsPendingFile() {
        val previous = MediaDownloadService.httpClient
        val reading = java.util.concurrent.CountDownLatch(1)
        MediaDownloadService.httpClient = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            val body = object : okhttp3.ResponseBody() {
                override fun contentType() = "image/jpeg".toMediaType()
                override fun contentLength() = 10_000_000L
                override fun source(): okio.BufferedSource = object : okio.Source {
                    override fun timeout() = okio.Timeout.NONE
                    override fun close() {}
                    override fun read(sink: okio.Buffer, byteCount: Long): Long {
                        reading.countDown()
                        if (chain.call().isCanceled()) throw java.io.IOException("Cancelled")
                        Thread.sleep(20)
                        sink.write(ByteArray(1024)); return 1024
                    }
                }.buffer()
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        val item = DownloadItem("nasa", "cancel-test", Media("https://example.org/cancel-test.jpg"))
        File(context.filesDir, "download-queue.json").writeText(PostParser.json.encodeToString(kotlinx.serialization.builtins.ListSerializer(DownloadItem.serializer()), listOf(item)))
        val controller = Robolectric.buildService(MediaDownloadService::class.java).create()
        try {
            controller.get().onStartCommand(android.content.Intent(context, MediaDownloadService::class.java), 0, 1)
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            MediaDownloadService.cancelActive()
            val deadline = System.currentTimeMillis() + 5000
            while (Transfers.state.value.running && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertFalse(Transfers.state.value.running)
            assertEquals(0, provider.published)
            assertTrue(provider.rows.isEmpty())
            assertEquals("cancelled", DownloadHistory.state.value.first { it.id == item.id }.state)
        } finally { controller.destroy(); MediaDownloadService.httpClient = previous; Transfers.state.value = TransferState() }
    }

}
class DiskMediaProvider : ContentProvider() {
    val rows = mutableMapOf<Uri, Pair<ContentValues, File>>()
    var published = 0
    override fun onCreate() = true
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val result = Uri.parse("content://media/external/downloads/${rows.size + 1}")
        rows[result] = ContentValues(values) to File.createTempFile("media", ".part", context!!.cacheDir)
        return result
    }
    override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(rows[uri]!!.second, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        rows[uri]!!.first.putAll(values)
        if (values?.getAsInteger(MediaStore.MediaColumns.IS_PENDING) == 0) published++
        return 1
    }
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int { rows.remove(uri)?.second?.delete(); return 1 }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = MatrixCursor(arrayOf(android.provider.OpenableColumns.SIZE)).apply { rows[uri]?.let { addRow(arrayOf(it.second.length())) } }
    override fun getType(uri: Uri) = "image/jpeg"
}
