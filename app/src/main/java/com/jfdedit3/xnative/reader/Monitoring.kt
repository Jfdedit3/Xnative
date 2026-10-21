package com.jfdedit3.xnative.reader

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.util.concurrent.TimeUnit

object Notifications {
    fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel("downloads", "Téléchargements", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel("rss", "Nouvelles publications", NotificationManager.IMPORTANCE_DEFAULT))
            manager.createNotificationChannel(NotificationChannel("app", "État de l’application", NotificationManager.IMPORTANCE_LOW))
        }
    }
    fun open(context: Context, page: String, link: String? = null): PendingIntent = PendingIntent.getActivity(context, (page + link).hashCode(),
        Intent(context, MainActivity::class.java).putExtra("page", page).putExtra("link", link).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun post(context: Context, id: Int, title: String, text: String, channel: String, link: String? = null) {
        channels(context)
        if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { NotificationManagerCompat.from(context).notify(id, NotificationCompat.Builder(context, channel).setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setAutoCancel(true)
            .setContentIntent(open(context, if (channel == "downloads") "downloads" else "actus", link)).build()) }
    }
}
object RssMonitoring {
    fun schedule(context: Context) {
        val options = SettingsStore.get(context).state.value
        val manager = WorkManager.getInstance(context)
        if (!options.rssEnabled || options.monitored.isEmpty()) { manager.cancelUniqueWork("rss-periodic"); manager.cancelUniqueWork("rss-now"); return }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(options.batterySaver).build()
        manager.enqueueUniquePeriodicWork("rss-periodic", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RssWorker>(if (options.batterySaver) 60 else 15, TimeUnit.MINUTES).setConstraints(constraints).build())
        manager.enqueueUniqueWork("rss-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RssWorker>().setConstraints(constraints).build())
    }
}
class RssWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext; val options = SettingsStore.get(context).state.value
        if (!options.rssEnabled) return@withContext Result.success()
        val baseline = context.getSharedPreferences("rss-seen", Context.MODE_PRIVATE)
        var failed = false
        for (account in options.monitored) {
            if (isStopped) return@withContext Result.success()
            try {
                val posts = FxClient().apply { retries = options.retries }.rssFeed(account)
                if (isStopped) return@withContext Result.success()
                val previous = baseline.getStringSet(account, null)
                Diagnostics.log(context, "RSS @$account : ${posts.size} publications, ${if (previous == null) 0 else posts.count { it.id !in previous }} nouvelles")
                // First activation establishes a baseline, rather than notifying for the entire archive.
                if (previous != null) for (post in posts.filterNot { it.id in previous }) {
                    Notifications.post(context, post.id.hashCode(), "Nouvelle publication de @${post.handle}", post.text.ifBlank { "Un nouveau média est disponible." }, "rss", post.url)
                }
                baseline.edit().putStringSet(account, (posts.map { it.id } + previous.orEmpty()).distinct().take(2000).toSet()).apply()
            } catch (error: Exception) { failed = true; Diagnostics.log(context, "RSS @$account : ${error.message}") }
        }
        if (failed && runAttemptCount < 2) Result.retry() else Result.success()
    }
}
object FavoritesExport {
    // Explicit versioned UTF-8 schema, usable by Windows consumers without Android-specific URIs.
    fun json(library: Library, profiles: Map<String, CachedProfile>): String = PostParser.json.encodeToString(FavoritesDocument(accounts = library.accounts, posts = library.saved, profiles = profiles))
}
@kotlinx.serialization.Serializable
data class FavoritesDocument(val format: String = "XNative.favorites", val version: Int = 1,
    val accounts: List<String>, val posts: List<Post>, val profiles: Map<String, CachedProfile>)
