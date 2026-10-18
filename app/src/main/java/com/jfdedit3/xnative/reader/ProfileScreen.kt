package com.jfdedit3.xnative.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreen(
    state: ProfileState, library: Library, busy: Boolean, snackbar: SnackbarHostState,
    onBack: () -> Unit, onRefresh: () -> Unit, onFollow: () -> Unit,
    onBookmark: (Post) -> Unit, onPost: (Post) -> Unit, onProfile: (String) -> Unit,
    onMore: () -> Unit, onMedia: () -> Unit, onStopMedia: () -> Unit
) {
    val context = LocalContext.current
    var mediaTab by rememberSaveable(state.account) { mutableIntStateOf(0) }
    LaunchedEffect(mediaTab, state.loading) { if (mediaTab > 0 && !state.loading && !state.mediaComplete) onMedia() }
    val user = state.user ?: Profile(state.account, state.account)
    val posts = state.posts.filter { mediaTab == 0 || it.media.any { media -> media.video == (mediaTab == 2) } }
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        TopAppBar(title = {
            Column {
                Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                user.statuses?.let { Text("${numberLabel(it)} publications", color = Muted, fontSize = 11.sp) }
            }
        }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Revenir à l’écran précédent") } },
            actions = {
                IconButton(onClick = { share(context, "https://x.com/${state.account}") }) { Icon(Icons.Outlined.Share, "Partager ce profil") }
                IconButton(onClick = onRefresh, enabled = !state.loading) { Icon(Icons.Outlined.Refresh, "Actualiser le profil", tint = Blue) }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Ink))
    }) { padding ->
        PullToRefreshBox(state.loading, onRefresh, Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
                item(key = "header") {
                    ProfileHeader(user, state.account in library.accounts, busy, onFollow)
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
                    state.error?.let { ProfileError(it, !state.loading, onRefresh) }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { MediaUi.downloads.value = state.posts.flatMap { post -> post.media.map { DownloadItem(post.handle, post.id, it) } }.distinctBy { it.id } }, enabled = !state.mediaLoading && state.posts.any { it.media.isNotEmpty() }) { Text("Télécharger les médias chargés") }
                        TextButton(onClick = { browse(context, "https://fxtwitter.com/${state.account}/feed.xml") }) { Text("Flux RSS ↗") }
                    }
                    if (state.mediaLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("Chargement de tous les médias : ${state.posts.sumOf { it.media.size }} médias", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onClick = onStopMedia) { Text("Arrêter le chargement") }
                    } else if (!state.mediaComplete) TextButton(onClick = onMedia) { Text("Charger tous les médias / réessayer") }
                    TabRow(selectedTabIndex = mediaTab, containerColor = Ink, contentColor = Blue) {
                        listOf("Publications", "Images", "Vidéos").forEachIndexed { index, name -> Tab(selected = mediaTab == index, onClick = { mediaTab = index }, text = { Text(name) }) }
                    }
                    state.postsError?.let { ProfileError(it, !state.loading, onRefresh) }
                }
                if (posts.isEmpty()) item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (user.protected) Icons.Outlined.Lock else Icons.Outlined.DynamicFeed, null, tint = Muted, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(14.dp))
                        Text(when {
                            state.loading -> "Chargement des publications…"
                            user.protected -> "Ce compte est protégé"
                            state.postsError != null -> "Aucune publication disponible hors connexion"
                            mediaTab > 0 -> "Aucun média dans les publications chargées"
                            else -> "Aucune publication disponible"
                        }, color = Muted)
                        if (user.protected) Text("FxTwitter peut uniquement fournir les publications accessibles publiquement.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                items(posts, key = { it.id }) { post ->
                    PostCard(if (mediaTab == 0) post else post.copy(media = post.media.filter { it.video == (mediaTab == 2) }), library.saved.any { it.id == post.id }, busy, { onBookmark(post) }, { onPost(post) }, onProfile)
                    HorizontalDivider(color = Line)
                }
                if (mediaTab == 0 && state.next != null) item { TextButton(onClick = onMore, enabled = !state.loading) { Text("Charger les publications précédentes") } }
                if (posts.isNotEmpty()) item(key = "footer") {
                    Text("${posts.size} publications chargées · FxTwitter", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(20.dp))
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(user: Profile, followed: Boolean, busy: Boolean, onFollow: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().height(150.dp).background(Brush.linearGradient(listOf(Color(0xFF163451), Color(0xFF253454), Color(0xFF14212E))))) {
        if (user.banner.startsWith("https://")) AsyncImage(user.banner, "Bannière de ${user.name}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
    Row(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.offset(y = (-24).dp).requiredSize(100.dp).border(4.dp, Ink, CircleShape).padding(4.dp).clip(CircleShape)) {
            InitialAvatar(user.name, Modifier.fillMaxSize(), user.avatar)
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { browse(context, "https://x.com/${user.handle}") }, contentPadding = PaddingValues(horizontal = 12.dp)) {
            Icon(Icons.Outlined.OpenInNew, "Voir le profil sur X", Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onFollow, enabled = !busy, contentPadding = PaddingValues(horizontal = 18.dp),
            colors = if (followed) ButtonDefaults.buttonColors(containerColor = Panel, contentColor = Blue) else ButtonDefaults.buttonColors()) {
            Text(if (followed) "Abonné" else "Suivre")
        }
    }
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(user.name, fontSize = 25.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f, fill = false))
            if (user.verified) Icon(Icons.Outlined.Verified, "Compte vérifié sur X", tint = Blue, modifier = Modifier.padding(start = 6.dp).size(21.dp))
            if (user.protected) Icon(Icons.Outlined.Lock, "Compte protégé", tint = Muted, modifier = Modifier.padding(start = 6.dp).size(19.dp))
        }
        Text("@${user.handle}", color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 3.dp))
        if (user.description.isNotBlank()) SelectionContainer {
            Text(user.description, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 14.dp))
        }
        if (user.location.isNotBlank()) ProfileMetadata(Icons.Outlined.LocationOn, user.location)
        if (user.website.startsWith("https://") || user.website.startsWith("http://")) {
            TextButton(onClick = { browse(context, user.website) }, contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Outlined.Link, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                Text(user.websiteLabel.ifBlank { user.website }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        joinedLabel(user.joined)?.let { ProfileMetadata(Icons.Outlined.CalendarMonth, it) }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ProfileCount(user.following, "Abonnements")
            ProfileCount(user.followers, "Abonnés")
        }
        Text("Suivi enregistré uniquement sur cet appareil", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 14.dp, bottom = 16.dp))
    }
}

@Composable
private fun ProfileMetadata(icon: ImageVector, value: String) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Muted)
        Text(value, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun ProfileCount(count: Long?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(count?.let(::numberLabel) ?: "—", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(" $label", color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun ProfileError(message: String, enabled: Boolean, retry: () -> Unit) {
    Surface(color = Panel, modifier = Modifier.fillMaxWidth().padding(14.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, color = Muted, fontSize = 12.sp)
            TextButton(onClick = retry, enabled = enabled) { Text("Réessayer") }
        }
    }
}

private fun joinedLabel(value: String): String? = runCatching {
    val date = ZonedDateTime.parse(value, DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss Z yyyy", Locale.ENGLISH))
    "A rejoint X en ${date.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRENCH))}"
}.getOrNull()
