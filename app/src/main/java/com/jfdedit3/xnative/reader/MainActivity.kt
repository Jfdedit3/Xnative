package com.jfdedit3.xnative.reader

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val Ink = Color(0xFF090D13)
internal val Panel = Color(0xFF121923)
internal val Blue = Color(0xFF70B4FF)
internal val Muted = Color(0xFF95A3B5)
internal val Line = Color(0xFF222D3B)

class MainActivity : ComponentActivity() {
    private val model: FeedViewModel by viewModels()
    private val pendingLink = MutableStateFlow<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.rgb(9, 13, 19))
        )
        receive(intent)
        setContent {
            val pending by pendingLink.collectAsStateWithLifecycle()
            val busy by model.busy.collectAsStateWithLifecycle()
            LaunchedEffect(pending, busy) {
                if (pending != null && !busy) {
                    model.importPost(pending!!)
                    pendingLink.value = null
                }
            }
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Ink,
                surfaceVariant = Panel, onSurfaceVariant = Muted, outlineVariant = Line)) {
                XnativeApp(model)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            pendingLink.value = intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XnativeApp(model: FeedViewModel) {
    val library by model.library.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    var tab by rememberSaveableState(0)
    var mediaOnly by rememberSaveableState(false)
    var query by rememberSaveableState("")
    var importDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Post?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val feedScroll = rememberLazyListState()
    val profileScreens = rememberSaveableStateHolder()
    val profiles: ProfileViewModel = viewModel()
    val profileState by profiles.state.collectAsStateWithLifecycle()
    var profileStack by rememberSaveableState(arrayListOf<String>())
    val profileHandle = profileStack.lastOrNull().orEmpty()
    val closeProfile: () -> Unit = { profileStack = ArrayList(profileStack.dropLast(1)) }
    val showProfile: (String) -> Unit = { handle ->
        val canonical = runCatching { Links.handle(handle) }.getOrNull()
        if (canonical != null) {
            selected = null
            if (canonical != profileHandle) profileStack = ArrayList(profileStack + canonical)
        }
    }
    LaunchedEffect(profileHandle, library.rssOnly) {
        if (profileHandle.isNotEmpty()) profiles.load(profileHandle, library) else profiles.close()
    }
    BackHandler(enabled = profileHandle.isNotEmpty() && selected == null) { closeProfile() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    if (profileHandle.isNotEmpty()) {
        profileScreens.SaveableStateProvider(profileHandle) {
            ProfileScreen(if (profileState.account == profileHandle) profileState else ProfileState(profileHandle, loading = true), library, busy, snackbar,
            onBack = { closeProfile() }, onRefresh = { profiles.load(profileHandle, library) },
            onFollow = { if (profileHandle in library.accounts) model.unfollow(profileHandle) else model.follow(profileHandle) },
            onBookmark = model::bookmark, onPost = { selected = it }, onProfile = showProfile)
        }
        selected?.let { post -> PostDialog(post, { selected = null }, showProfile) }
        return
    }
    val titles = listOf("Votre fil", "Explorer", "Favoris", "Abonnements")
    val icons = listOf(Icons.Outlined.Home, Icons.Outlined.Search, Icons.Outlined.BookmarkBorder, Icons.Outlined.PeopleOutline)
    Scaffold(containerColor = Ink, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Blue), contentAlignment = Alignment.Center) {
                        Text("χ", color = Ink, fontWeight = FontWeight.Black, fontSize = 34.sp)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text("Xnative", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp)
                        Text("VOTRE MONDE, SANS LE BRUIT", color = Muted, fontSize = 9.sp, letterSpacing = 1.3.sp)
                    }
                    IconButton(onClick = model::refresh, enabled = !busy && library.accounts.isNotEmpty()) {
                        Icon(Icons.Outlined.Refresh, "Actualiser le fil", tint = Blue)
                    }
                }
                HorizontalDivider(color = Line)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Ink, tonalElevation = 0.dp) {
                titles.forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(icons[index], title) }, label = { Text(title, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Blue, selectedTextColor = Blue, indicatorColor = Panel))
                }
            }
        },
        floatingActionButton = {
            if (tab == 0 || tab == 1) FloatingActionButton(onClick = { importDialog = true }, containerColor = Blue, contentColor = Ink) {
                Icon(Icons.Outlined.AddLink, "Importer une publication")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
            when (tab) {
                3 -> Accounts(library, busy, model, showProfile)
                else -> {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                        Text(titles[tab], fontSize = 29.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp)
                        Spacer(Modifier.height(5.dp))
                        Text(when (tab) {
                            1 -> "Retrouvez une publication ou ajoutez un compte."
                            2 -> "Vos lectures à garder, disponibles hors connexion."
                            else -> "${library.accounts.size} comptes suivis · Propulsé par FxTwitter"
                        }, color = Muted, fontSize = 12.sp)
                        if (tab == 1) {
                            Spacer(Modifier.height(16.dp))
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                                placeholder = { Text("Rechercher dans le fil") }, leadingIcon = { Icon(Icons.Outlined.Search, null) },
                                shape = RoundedCornerShape(16.dp))
                            TextButton(onClick = { tab = 3 }) { Text("Suivre un nouveau compte →") }
                        }
                        if (tab == 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !mediaOnly, onClick = { mediaOnly = false }, label = { Text("Tout le fil") })
                            FilterChip(selected = mediaOnly, onClick = { mediaOnly = true }, label = { Text("Médias") }, leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(16.dp)) })
                        }
                    }
                    val all = if (tab == 2) library.saved else library.timeline()
                    val posts = all.filter { post ->
                        (tab != 0 || !mediaOnly || post.media.isNotEmpty()) &&
                            (tab != 1 || query.isBlank() || "${post.text} ${post.name} ${post.handle}".contains(query.trim(), true))
                    }
                    PullToRefreshBox(isRefreshing = busy, onRefresh = { if (tab != 2) model.refresh() }, modifier = Modifier.weight(1f)) {
                        if (posts.isEmpty()) {
                            LazyColumn(Modifier.fillMaxSize(), state = feedScroll, contentPadding = PaddingValues(24.dp)) {
                                item {
                                    EmptyState(when(tab) { 2 -> "Une bonne lecture se garde"; 1 -> "Trouvez votre prochaine lecture"; else -> if (mediaOnly) "Aucun média pour le moment" else "Votre fil commence ici" },
                                        when(tab) { 2 -> "Touchez le marque-page d’une publication pour la retrouver ici."; 1 -> "La recherche porte sur les publications déjà chargées. Ajoutez des comptes pour enrichir votre fil."; else -> "Suivez vos comptes préférés ou importez un lien X pour commencer." },
                                        if (tab == 2) Icons.Outlined.BookmarkBorder else Icons.Outlined.DynamicFeed)
                                    if (tab != 2) {
                                        Button(onClick = { tab = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Choisir des comptes") }
                                        OutlinedButton(onClick = { importDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Importer un lien X") }
                                    }
                                }
                            }
                        } else LazyColumn(Modifier.fillMaxSize(), state = feedScroll, contentPadding = PaddingValues(bottom = 90.dp)) {
                            items(posts, key = { it.id }) { post ->
                                PostCard(post, library.saved.any { it.id == post.id }, busy, { model.bookmark(post) }, { selected = post }, showProfile)
                                HorizontalDivider(color = Line)
                            }
                            item { Text("Vous êtes à jour · ${posts.size} publications en cache", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(20.dp)) }
                        }
                    }
                }
            }
        }
    }
    if (importDialog) ImportDialog(busy, onDismiss = { importDialog = false }, onImport = { model.importPost(it); importDialog = false; tab = 0 })
    selected?.let { post -> PostDialog(post, { selected = null }, showProfile) }
}

// saveable state keeps navigation and search intact during configuration changes.
@Composable
private fun <T : Any> rememberSaveableState(initial: T) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial) }

@Composable
private fun EmptyState(title: String, description: String, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(24.dp)).background(Panel), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Blue, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(description, fontSize = 14.sp, color = Muted, lineHeight = 22.sp)
    }
}

@Composable
private fun Accounts(library: Library, busy: Boolean, model: FeedViewModel, onProfile: (String) -> Unit) {
    var handle by rememberSaveableState("")
    var remove by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    var accountError by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("À votre rythme.", fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-1).sp)
            Text("Choisissez qui vous voulez lire.", color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        item {
            OutlinedTextField(handle, { handle = it; accountError = null }, label = { Text("Pseudo X") }, prefix = { Text("@") },
                singleLine = true, isError = accountError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            accountError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            TextButton(onClick = {
                runCatching { Links.handle(handle) }.onSuccess(onProfile).onFailure { accountError = it.message }
            }, enabled = handle.isNotBlank()) { Text("Voir le profil →") }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { model.follow(handle); handle = "" }, enabled = !busy && handle.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Suivre ce compte")
            }
        }
        if (library.accounts.isEmpty()) item {
            Text("QUELQUES IDÉES POUR COMMENCER", color = Muted, fontSize = 10.sp, letterSpacing = 1.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("NASA", "SpaceX", "github").forEach { account ->
                    SuggestionChip(onClick = { model.follow(account) }, enabled = !busy, label = { Text("@$account") })
                }
            }
        }
        item { Text("VOS ABONNEMENTS · ${library.accounts.size}", color = Muted, fontSize = 11.sp, letterSpacing = 1.sp) }
        items(library.accounts, key = { it }) { account ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).clickable { onProfile(account) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                InitialAvatar(account)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("@$account", fontWeight = FontWeight.SemiBold)
                    Text("${library.feeds[account].orEmpty().size} publications en cache", color = Muted, fontSize = 11.sp)
                }
                IconButton(onClick = { remove = account }, enabled = !busy) { Icon(Icons.Outlined.PersonRemove, "Ne plus suivre @$account", tint = Muted) }
            }
        }
        item {
            HorizontalDivider(color = Line)
            Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Publications via RSS uniquement", fontSize = 14.sp)
                    Text("Sinon : API, puis RSS en cas d’échec.", color = Muted, fontSize = 11.sp)
                }
                Switch(checked = library.rssOnly, onCheckedChange = model::rssOnly, enabled = !busy)
            }
        }
        item {
            Text("Un lecteur libre et personnel", color = Blue, fontWeight = FontWeight.SemiBold)
            Text("Sans connexion X. Abonnements, favoris et cache sont enregistrés sur cet appareil. Les compteurs proviennent de FxTwitter et ne sont pas mis à jour en temps réel. Les vidéos et les interactions X s’ouvrent dans une application externe.",
                color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = { clear = true }, enabled = !busy && library.imported.isNotEmpty()) { Text("Effacer les publications importées") }
            Text("Xnative 1.1.0 · Indépendant de X et de FxTwitter", color = Muted, fontSize = 10.sp)
        }
    }
    remove?.let { account ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("Ne plus suivre @$account ?") },
            text = { Text("Ses publications seront retirées du fil. Vos favoris seront conservés.") },
            confirmButton = { TextButton(onClick = { model.unfollow(account); remove = null }) { Text("Retirer") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("Annuler") } })
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Effacer les imports ?") }, text = { Text("Vos favoris seront conservés.") },
        confirmButton = { TextButton(onClick = { model.clearImports(); clear = false }) { Text("Effacer") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Annuler") } })
}

@Composable
internal fun InitialAvatar(name: String, modifier: Modifier = Modifier, url: String = "") {
    Box(modifier.size(42.dp).clip(CircleShape).background(Color(0xFF23374D)), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Blue)
        if (url.startsWith("https://")) AsyncImage(url, "Avatar de $name", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun PostCard(post: Post, saved: Boolean, busy: Boolean, bookmark: () -> Unit, open: () -> Unit, onProfile: (String) -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 18.dp, vertical = 16.dp)) {
        if (post.repostedBy.isNotEmpty()) Text("↻ @${post.repostedBy} a republié", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(start = 54.dp, bottom = 8.dp).clickable { onProfile(post.repostedBy) })
        Row {
            InitialAvatar(post.name.ifEmpty { post.handle }, Modifier.clickable(onClickLabel = "Voir le profil de @${post.handle}") { onProfile(post.handle) }, post.avatar)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(post.name, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).clickable { onProfile(post.handle) })
                    Text(dateLabel(post.timestamp), color = Muted, fontSize = 10.sp)
                }
                Text("@${post.handle} · ${post.source}", color = Muted, fontSize = 11.sp, modifier = Modifier.clickable { onProfile(post.handle) })
                if (post.text.isNotBlank()) Text(post.text, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 12, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                if (post.quote.isNotBlank()) Surface(shape = RoundedCornerShape(12.dp), color = Panel, modifier = Modifier.padding(top = 10.dp)) {
                    Text(post.quote, fontSize = 12.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(12.dp))
                }
                post.media.firstOrNull()?.let { media ->
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).background(Panel)) {
                        val image = if (media.video) media.thumbnail else media.url
                        if (image.isNotBlank()) AsyncImage(image, "Média de la publication", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (media.video) Icon(Icons.Outlined.PlayCircle, "Vidéo — ouvrir la publication pour lire", tint = Color.White, modifier = Modifier.size(44.dp).align(Alignment.Center))
                        if (post.media.size > 1) Text("+${post.media.size - 1}", color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).background(Ink).padding(6.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Metric(Icons.Outlined.ChatBubbleOutline, post.replies, "Réponses")
                    Metric(Icons.Outlined.Repeat, post.reposts, "Republications")
                    Metric(Icons.Outlined.FavoriteBorder, post.likes, "J’aime")
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = bookmark, enabled = !busy, modifier = Modifier.size(42.dp)) {
                        Icon(if (saved) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder, if (saved) "Retirer des favoris" else "Enregistrer dans les favoris", tint = if (saved) Blue else Muted, modifier = Modifier.size(19.dp))
                    }
                    IconButton(onClick = { share(context, post.url) }, modifier = Modifier.size(42.dp)) { Icon(Icons.Outlined.Share, "Partager", tint = Muted, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, count: Long?, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
        Icon(icon, label, Modifier.size(14.dp), tint = Muted)
        if (count != null) Text(" ${numberLabel(count)}", fontSize = 10.sp, color = Muted)
    }
}
internal fun numberLabel(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.FRANCE, "%.1f M", value / 1_000_000.0)
    value >= 1_000 -> String.format(Locale.FRANCE, "%.1f k", value / 1_000.0)
    else -> value.toString()
}
private fun dateLabel(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val date = Instant.ofEpochSecond(timestamp).atZone(ZoneId.systemDefault())
    val pattern = if (date.year == java.time.Year.now().value) "d MMM" else "d MMM yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.FRENCH).format(date)
}

@Composable
private fun ImportDialog(busy: Boolean, onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var link by rememberSaveableState("")
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Outlined.AddLink, null, tint = Blue) }, title = { Text("Une publication à lire ?") },
        text = { Column {
            Text("Collez un lien X, Twitter ou FxTwitter. Vous pouvez aussi partager un lien vers Xnative depuis une autre application.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(link, { link = it; error = null }, label = { Text("Lien de la publication") }, maxLines = 3, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(enabled = !busy && link.isNotBlank(), onClick = {
            runCatching { Links.postId(link) }.onSuccess { onImport(link) }.onFailure { error = it.message }
        }) { Text("Ajouter au fil") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}

@Composable
private fun PostDialog(post: Post, dismiss: () -> Unit, onProfile: (String) -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = dismiss, title = { Column(Modifier.clickable { onProfile(post.handle) }) { Text(post.name); Text("@${post.handle}", color = Muted, fontSize = 13.sp) } },
        text = { LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SelectionContainer { Text(post.text, lineHeight = 23.sp) } }
            if (post.quote.isNotEmpty()) item { Text(post.quote, color = Muted) }
            items(post.media) { media ->
                Column {
                    val image = if (media.video) media.thumbnail else media.url
                    if (image.isNotBlank()) AsyncImage(image, "Média de la publication", Modifier.fillMaxWidth().heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                    TextButton(onClick = { browse(context, media.url) }) { Text(if (media.video) "Lire la vidéo ↗" else "Ouvrir l’image ↗") }
                }
            }
            item { Text("Pour répondre, aimer ou republier, ouvrez la publication sur X.", color = Muted, fontSize = 12.sp) }
        } }, confirmButton = { TextButton(onClick = { browse(context, post.url) }) { Text("Ouvrir sur X ↗") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Fermer") } })
}
internal fun browse(context: android.content.Context, url: String) {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { android.widget.Toast.makeText(context, "Aucune application pour ouvrir ce lien.", android.widget.Toast.LENGTH_SHORT).show() }
}
internal fun share(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, "Partager la publication")) }
}
