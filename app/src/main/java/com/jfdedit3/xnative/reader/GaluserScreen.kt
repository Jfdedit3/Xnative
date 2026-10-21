package com.jfdedit3.xnative.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
internal fun GaluserScreen(model: GaluserViewModel, library: Library, allowBack: Boolean) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val state by model.state.collectAsStateWithLifecycle()
    val cached by ProfileCache.state.collectAsStateWithLifecycle()
    var picking by rememberSaveable { mutableStateOf(true) }
    var input by rememberSaveable { mutableStateOf(SettingsStore.get(context).state.value.galuserAccount) }
    var inputError by remember { mutableStateOf<String?>(null) }
    var video by rememberSaveable(state.account) { mutableStateOf(false) }
    val media = remember(state.posts, state.account, video) { galuserMedia(state.posts, state.account, video) }
    fun select(value: String) {
        runCatching { Links.profileHandle(value) }.onSuccess { handle ->
            input = handle; inputError = null; keyboard?.hide()
            model.open(handle, library); picking = false
        }.onFailure { inputError = it.message ?: "Saisissez un pseudo X valide." }
    }
    fun back() { picking = true; model.stop() }
    BackHandler(enabled = !picking && state.account.isNotBlank() && allowBack) { back() }
    Column(Modifier.fillMaxSize()) {
        if (picking || state.account.isBlank()) {
            Text("Galuser", fontSize = 28.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            Text("La galerie d’un utilisateur", color = Muted, modifier = Modifier.padding(horizontal = 18.dp))
            OutlinedTextField(input, { input = it; inputError = null }, Modifier.fillMaxWidth().padding(16.dp),
                label = { Text("Pseudo ou lien du profil") }, placeholder = { Text("@utilisateur") }, singleLine = true,
                isError = inputError != null, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { select(input) }))
            inputError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 18.dp)) }
            Button(onClick = { select(input) }, enabled = input.isNotBlank(), modifier = Modifier.padding(horizontal = 18.dp)) { Text("Ouvrir la galerie") }
            val accounts = remember(library.accounts, cached) { (library.accounts + cached.keys).distinct().sorted() }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(18.dp)) {
                if (accounts.isNotEmpty()) item { Text("Vos utilisateurs", color = Muted, modifier = Modifier.padding(vertical = 12.dp)) }
                items(accounts, key = { it }) { handle ->
                    val user = cached[handle]?.user
                    ListItem(headlineContent = { Text(user?.name ?: "@$handle") }, supportingContent = { Text("@$handle") },
                        leadingContent = { InitialAvatar(user?.name ?: handle, url = user?.avatar.orEmpty()) },
                        modifier = Modifier.clickable { select(handle) })
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) { Icon(Icons.Outlined.ArrowBack, "Retour à la saisie de l’utilisateur") }
                Column(Modifier.weight(1f)) {
                    Text(state.user?.name ?: "@${state.account}", fontSize = 20.sp, maxLines = 1)
                    Text("@${state.account} · Galuser", color = Muted, fontSize = 12.sp)
                }
                IconButton(onClick = { model.open(state.account, library) }) { Icon(Icons.Outlined.Refresh, "Actualiser la galerie de cet utilisateur", tint = Blue) }
            }
            TabRow(selectedTabIndex = if (video) 1 else 0, containerColor = Ink, contentColor = Blue) {
                Tab(selected = !video, onClick = { video = false }, text = { Text("Images") })
                Tab(selected = video, onClick = { video = true }, text = { Text("Vidéos") })
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${media.size} ${if (video) "vidéos" else "images"}", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { MediaUi.downloads.value = media }, enabled = media.isNotEmpty()) { Text("Télécharger") }
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Récupération des médias…", color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = model::stop) { Text("Arrêter") }
                }
            } else if (!state.complete) TextButton(onClick = model::resume, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Reprendre le chargement") }
            state.notice?.let { Text(it, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            if (media.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (state.loading) "Chargement…" else "Aucune ${if (video) "vidéo" else "image"} disponible pour ce compte.", color = Muted, modifier = Modifier.padding(24.dp))
            } else LazyVerticalGrid(columns = GridCells.Adaptive(108.dp), modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(media, key = { _, item -> "${item.postId}|${item.media.url}" }) { index, item ->
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Panel).clickable {
                        MediaUi.viewer.value = MediaSelection(media, index)
                    }) {
                        AsyncImage(if (item.media.video) item.media.thumbnail.ifBlank { item.media.url } else item.media.url,
                            "${if (video) "Vidéo" else "Image"} ${index + 1} de @${state.account}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (video) Icon(Icons.Outlined.PlayCircle, "Lire la vidéo", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(36.dp))
                    }
                }
            }
        }
    }
}
