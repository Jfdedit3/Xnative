package com.jfdedit3.xnative.reader

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun GalleryScreen(settings: () -> Unit) {
    val context = LocalContext.current
    val records by DownloadHistory.state.collectAsStateWithLifecycle()
    val progress by Transfers.state.collectAsStateWithLifecycle()
    var actual by remember { mutableStateOf<List<DownloadRecord>>(emptyList()) }
    var showFailures by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(records) { actual = withContext(Dispatchers.IO) { records.filter { it.state == "complete" }.mapNotNull { record -> MediaFiles.size(context, record.uri)?.let { record.copy(size = it) } } } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Galerie", fontSize = 28.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = settings) { Icon(Icons.Outlined.Settings, "Réglages") }
            }
            Text("${actual.size} médias · ${actual.sumOf { it.size } / 1024 / 1024} Mo sur le disque", color = Muted, fontSize = 12.sp)
        }
        if (progress.running) item {
            Text("${progress.done + 1}/${progress.total} · ${progress.name}", fontSize = 12.sp)
            Text("${progress.bytes / 1024} Ko · ${progress.speed / 1024} Ko/s", color = Muted, fontSize = 12.sp)
            if (progress.length > 0) LinearProgressIndicator(progress = { progress.bytes.toFloat() / progress.length }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = { Transfers.cancel(context) }) { Text("Annuler le lot") }
        }
        val failed = records.filter { it.state == "failed" || it.state == "cancelled" }
        if (failed.isNotEmpty()) item {
            Text("${failed.size} transferts en échec ou annulés", color = MaterialTheme.colorScheme.error)
            TextButton(enabled = !progress.running, onClick = {
                MediaUi.downloads.value = failed.filter { it.source.isNotBlank() }.map { DownloadItem(it.handle, it.postId, Media(it.source, video = it.video, mime = it.mime)) }
            }) { Text("Réessayer les échecs") }
            TextButton(onClick = { showFailures = !showFailures }) { Text(if (showFailures) "Masquer les échecs" else "Voir tous les échecs") }
        }
        if (showFailures) items(failed, key = { "failure-${it.handle}-${it.id}" }) { Text("${it.name} : ${it.error}", color = Muted, fontSize = 11.sp) }
        if (account == null) {
            items(actual.groupBy { it.handle }.toList(), key = { it.first }) { (handle, files) ->
                ListItem(headlineContent = { Text("@$handle") }, supportingContent = { Text("${files.size} médias · ${files.sumOf { it.size } / 1024 / 1024} Mo") },
                    leadingContent = { AsyncImage(files.first().uri, "Aperçu du compte", Modifier.size(60.dp)) }, modifier = Modifier.clickable { account = handle })
            }
            if (actual.isEmpty()) item { Text("Téléchargez un média depuis une publication ou un profil pour le retrouver ici.", color = Muted) }
        } else {
            item { TextButton(onClick = { account = null }) { Text("← Tous les comptes") }; Text("@$account") }
            val group = actual.filter { it.handle == account }
            items(group, key = { it.id }) { file ->
                ListItem(headlineContent = { Text(file.name, fontSize = 12.sp) }, supportingContent = { Text("${file.size / 1024} Ko") },
                    leadingContent = { AsyncImage(file.uri, "Média téléchargé", Modifier.size(70.dp)) }, modifier = Modifier.clickable {
                        MediaUi.viewer.value = MediaSelection(group.map { DownloadItem(it.handle, it.id, Media(it.uri, video = it.mime.startsWith("video/"))) }, group.indexOf(file))
                    })
            }
        }
    }
}

@Composable
fun SettingsScreen(model: FeedViewModel, preferences: Preferences, onBack: () -> Unit, chooseFolder: () -> Unit, export: (String, String) -> Unit, permission: () -> Unit, gallery: () -> Unit) {
    val context = LocalContext.current; val store = SettingsStore.get(context)
    val library by model.library.collectAsStateWithLifecycle()
    var log by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun update(transform: (Preferences) -> Preferences) { store.update(transform) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Retour") }; Text("Réglages", fontSize = 25.sp) } }
        item { Text("Réseau et médias", color = Blue) }
        item { Option("Animations des médias", preferences.animations) { value -> update { it.copy(animations = value) } } }
        item { Option("Afficher la durée des vidéos", preferences.showDuration) { value -> update { it.copy(showDuration = value) } } }
        item { Option("Confirmer les téléchargements", preferences.confirmDownloads) { value -> update { it.copy(confirmDownloads = value) } } }
        item { Option("Réessayer les requêtes (3 essais)", preferences.retries) { value -> update { it.copy(retries = value) } } }
        item { Option("Afficher les erreurs détaillées", preferences.detailedErrors) { value -> update { it.copy(detailedErrors = value) } } }
        item { Text("Posts par page : ${preferences.postsPerAccount}"); Slider(preferences.postsPerAccount.toFloat(), { value -> update { it.copy(postsPerAccount = value.toInt()) } }, valueRange = 10f..100f, steps = 8) }
        item { Text("Contenu des lots"); Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("all" to "Tout", "images" to "Images", "videos" to "Vidéos").forEach { (key, label) -> FilterChip(preferences.mediaFilter == key, { update { it.copy(mediaFilter = key) } }, label = { Text(label) }) } } }
        item { Text("Dossier : ${if (preferences.folder.isBlank()) "Download/XNative/<pseudo>/" else "${preferences.folderLabel}/XNative/<pseudo>/"}", fontSize = 12.sp); TextButton(onClick = chooseFolder) { Text("Choisir un dossier") }; TextButton(onClick = { update { it.copy(folder = "") } }) { Text("Utiliser Download/XNative") } }
        item { HorizontalDivider(); Text("Favoris et fiches", color = Blue) }
        item { TextButton(onClick = model::refreshCards) { Text("Rafraîchir toutes les fiches") }; TextButton(onClick = model::clearCards) { Text("Vider le cache des fiches") } }
        item { TextButton(onClick = { scope.launch { val value = withContext(Dispatchers.IO) { FavoritesExport.json(library, ProfileCache.load(context)) }; export("favoris_export.json", value) } }) { Text("Exporter les favoris (JSON)") } }
        item { HorizontalDivider(); Text("Surveillance RSS", color = Blue); Text("Android planifie les analyses environ toutes les 15 minutes, ou 1 heure en mode économie. La première analyse établit le point de départ des notifications.", fontSize = 12.sp, color = Muted) }
        item { Option("Surveiller les comptes sélectionnés", preferences.rssEnabled) { value -> update { it.copy(rssEnabled = value) }; if (value) permission(); RssMonitoring.schedule(context) } }
        item { Option("Économiser la batterie", preferences.batterySaver) { value -> update { it.copy(batterySaver = value) }; RssMonitoring.schedule(context) } }
        items((library.accounts + preferences.monitored).distinct(), key = { "rss-$it" }) { account ->
            Option("@$account", account in preferences.monitored) { checked -> update { it.copy(monitored = if (checked) (it.monitored + account).distinct() else it.monitored - account) }; RssMonitoring.schedule(context) }
        }
        item { TextButton(onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) { Text("Paramètres batterie Android") } }
        item { TextButton(onClick = { RssMonitoring.schedule(context) }) { Text("Analyser maintenant / replanifier") } }
        item { HorizontalDivider(); Text("Notifications et diagnostic", color = Blue) }
        item { TextButton(onClick = permission) { Text("Autoriser les notifications") }; Option("Notification au démarrage", preferences.startupNotification) { value -> update { it.copy(startupNotification = value) } } }
        item { TextButton(onClick = { log = Diagnostics.read(context) }) { Text("Voir le dernier journal") }; TextButton(onClick = { export("dernier_lancement.log", Diagnostics.read(context)) }) { Text("Exporter le journal") }; TextButton(onClick = model::refresh) { Text("Relancer les requêtes du fil") } }
        item { HorizontalDivider(); TextButton(onClick = gallery) { Text("Ouvrir les téléchargements") }; Text("Xnative 1.2.3 · Android natif · FxEmbed", color = Muted); Text("Paquet : ${context.packageName}", color = Muted, fontSize = 11.sp) }
    }
    log?.let { AlertDialog(onDismissRequest = { log = null }, title = { Text("Journal") }, text = { LazyColumn { item { Text(it, fontSize = 10.sp) } } }, confirmButton = { TextButton(onClick = { log = null }) { Text("Fermer") } }) }
}
@Composable
private fun Option(label: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f)); Switch(checked, change) }
}
