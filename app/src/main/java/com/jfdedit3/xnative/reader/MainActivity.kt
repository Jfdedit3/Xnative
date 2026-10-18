package com.jfdedit3.xnative.reader

import android.content.Intent
import androidx.core.net.toUri
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
        Notifications.channels(this)
        lifecycleScope.launch(Dispatchers.IO) {
            DownloadHistory.recover(this@MainActivity)
            runCatching { if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) MediaFiles.export(this@MainActivity, "dernier_lancement.log", "Démarrage Xnative 1.3.0 · ${java.time.Instant.now()}", "text/plain") }
            Diagnostics.log(this@MainActivity, "Démarrage Xnative 1.3.0 · Android ${Build.VERSION.SDK_INT}")
            if (SettingsStore.get(this@MainActivity).state.value.startupNotification) Notifications.post(this@MainActivity, 1, "Xnative est prêt", "Consultation, téléchargements et flux RSS", "app")
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Ink, surface = Ink,
                surfaceVariant = Panel, onSurfaceVariant = Muted, outlineVariant = Line)) {
                XnativeApp(model)
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); receive(intent) }
    private fun receive(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            NavigationRequests.link.value = intent.getStringExtra(Intent.EXTRA_TEXT)
            intent.removeExtra(Intent.EXTRA_TEXT)
        }
        intent?.getStringExtra("page")?.let { NavigationRequests.page.value = it; intent.removeExtra("page") }
        intent?.getStringExtra("link")?.let { NavigationRequests.link.value = it; intent.removeExtra("link") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun XnativeApp(model: FeedViewModel) {
    val context = LocalContext.current
    val library by model.library.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val more by model.more.collectAsStateWithLifecycle()
    val preferences by model.preferences.collectAsStateWithLifecycle()
    val mediaRequest by MediaUi.downloads.collectAsStateWithLifecycle()
    val viewer by MediaUi.viewer.collectAsStateWithLifecycle()
    val incoming by NavigationRequests.link.collectAsStateWithLifecycle()
    val requestedPage by NavigationRequests.page.collectAsStateWithLifecycle()
    var tab by rememberSaveableState(1)
    var previousTab by rememberSaveableState(1)
    var settings by rememberSaveableState(false)
    var favoritesPosts by rememberSaveableState(false)
    var query by rememberSaveableState("")
    var searchPosts by rememberSaveableState(false)
    var importDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Post?>(null) }
    var profileStack by rememberSaveableState(arrayListOf<String>())
    val profileHandle = profileStack.lastOrNull().orEmpty()
    val profiles: ProfileViewModel = viewModel()
    val search: SearchViewModel = viewModel()
    val profileState by profiles.state.collectAsStateWithLifecycle()
    val searchState by search.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    val profileScreens = rememberSaveableStateHolder()
    var pendingPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = pendingPermission; pendingPermission = null
        if (granted) action?.invoke() else scope.launch { snackbar.showSnackbar("Autorisation de stockage refusée.") }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) scope.launch { snackbar.showSnackbar("Notifications désactivées. Vous pouvez suivre les transferts dans Galerie.") }
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    fun storageAction(action: () -> Unit) {
        if (Build.VERSION.SDK_INT <= 28 && preferences.folder.isBlank() && ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = action; storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else action()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            SettingsStore.get(context).update { it.copy(folder = uri.toString(), folderLabel = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "Dossier personnalisé") }
        }.onFailure { scope.launch { snackbar.showSnackbar("Ce dossier n’autorise pas l’écriture.") } }
    }
    val export: (String, String) -> Unit = { name, value -> storageAction {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { MediaFiles.export(context, name, value, if (name.endsWith(".log")) "text/plain" else "application/json") } }
                .onSuccess { snackbar.showSnackbar("Export enregistré : $name") }
                .onFailure { snackbar.showSnackbar(Diagnostics.error(context, it)) }
        }
    } }
    fun startDownloads(items: List<DownloadItem>) {
        if (items.isEmpty()) { scope.launch { snackbar.showSnackbar("Aucun média correspondant au filtre.") }; return }
        requestNotifications()
        storageAction { scope.launch { runCatching { withContext(Dispatchers.IO) { Transfers.start(context, items) } }.onSuccess { tab = 2 }.onFailure { snackbar.showSnackbar(Diagnostics.error(context, it)) } } }
    }
    val showProfile: (String) -> Unit = { input ->
        runCatching { Links.profileHandle(input) }.onSuccess { handle ->
            selected = null; settings = false
            if (tab != 4) previousTab = tab
            if (handle != profileHandle) profileStack = ArrayList(profileStack + handle)
            tab = 4
        }.onFailure { scope.launch { snackbar.showSnackbar(it.message ?: "Profil invalide.") } }
    }
    val backProfile: () -> Unit = {
        profileStack = ArrayList(profileStack.dropLast(1)); if (profileStack.isEmpty()) tab = previousTab
    }
    LaunchedEffect(profileHandle, tab == 4, library.rssOnly) { if (tab == 4 && profileHandle.isNotEmpty()) profiles.load(profileHandle, library) else profiles.close() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); model.clearMessage() } }
    LaunchedEffect(requestedPage) { requestedPage?.let { tab = if (it == "downloads") 2 else 1; NavigationRequests.page.value = null } }
    LaunchedEffect(incoming) {
        if (incoming != null) {
            val value = incoming!!; NavigationRequests.link.value = null
            if (runCatching { Links.postId(value) }.isSuccess) {
                query = value; searchPosts = true; settings = false; selected = null
                search.search(value, true); tab = 3
            }
            else showProfile(Regex("https://[^\\s<>]+").find(value)?.value ?: value)
        }
    }
    LaunchedEffect(mediaRequest, preferences.confirmDownloads) {
        if (!preferences.confirmDownloads) mediaRequest?.let { items -> MediaUi.downloads.value = null; startDownloads(if (items.size == 1) items else items.filter { preferences.mediaFilter == "all" || (preferences.mediaFilter == "videos") == it.media.video }) }
    }
    LaunchedEffect(scroll, more, busy, tab) {
        if (tab == 1 && more && !busy) snapshotFlow {
            val info = scroll.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { atEnd -> if (atEnd) model.loadMore() }
    }
    BackHandler(enabled = selected == null && viewer == null && (settings || tab == 4 && profileHandle.isNotEmpty())) { if (settings) settings = false else backProfile() }
    fun submitSearch() {
        if (query.isBlank()) return
        if (query.contains("https://")) NavigationRequests.link.value = query
        else if (tab == 4) showProfile(query)
        else search.search(query, searchPosts)
    }
    val titles = listOf("Favoris", "Actus", "Galerie", "Recherche", "Profil")
    val icons = listOf(Icons.Outlined.StarOutline, Icons.Outlined.Home, Icons.Outlined.PhotoLibrary, Icons.Outlined.Search, Icons.Outlined.PersonOutline)
    Scaffold(containerColor = Ink, snackbarHost = { if (tab != 4 || profileHandle.isBlank() || settings) SnackbarHost(snackbar) }, topBar = {
        if (tab != 4 && !settings) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Xnative", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { importDialog = true }) { Icon(Icons.Outlined.AddLink, "Ouvrir un lien") }
            IconButton(onClick = { if (tab == 0) model.refreshCards() else model.refresh() }, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Actualiser", tint = Blue) }
        }
    }, bottomBar = {
        NavigationBar(containerColor = Ink) { titles.forEachIndexed { index, title ->
            NavigationBarItem(selected = tab == index && !settings, onClick = { settings = false; tab = index }, icon = { Icon(icons[index], title) }, label = { Text(title, fontSize = 10.sp) })
        } }
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue)
            when {
                settings -> SettingsScreen(model, preferences, { settings = false }, { folderPicker.launch(null) }, export, requestNotifications, { settings = false; tab = 2 })
                tab == 2 -> GalleryScreen { settings = true }
                tab == 4 && profileHandle.isNotBlank() -> profileScreens.SaveableStateProvider(profileHandle) {
                    ProfileScreen(if (profileState.account == profileHandle) profileState else ProfileState(profileHandle, loading = true), library, busy, snackbar,
                        onBack = backProfile, onRefresh = { profiles.load(profileHandle, library) },
                        onFollow = { if (profileHandle in library.accounts) model.unfollow(profileHandle) else model.follow(profileHandle) },
                        onBookmark = model::bookmark, onPost = { selected = it }, onProfile = showProfile,
                        onMore = profiles::loadMore, onMedia = profiles::loadAllMedia, onStopMedia = profiles::stopMedia)
                }
                tab == 0 && !favoritesPosts -> {
                    Row(Modifier.padding(horizontal = 18.dp)) { FilterChip(true, {}, label = { Text("Comptes") }); Spacer(Modifier.width(8.dp)); FilterChip(false, { favoritesPosts = true }, label = { Text("Posts enregistrés") }) }
                    PullToRefreshBox(busy, model::refreshCards, Modifier.weight(1f)) { Accounts(library, busy, model, showProfile) }
                }
                tab == 3 || tab == 4 -> {
                    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Pseudo, recherche ou lien X") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submitSearch() }))
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { submitSearch() }, enabled = query.isNotBlank()) { Text(if (tab == 4) "Voir le profil" else "Rechercher") }
                        if (tab == 3) TextButton(onClick = { searchPosts = !searchPosts }) { Text(if (searchPosts || searchState.directPost) "Posts" else "Comptes") }
                    }
                    if (searchState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    searchState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    LazyColumn(Modifier.weight(1f)) {
                        if (!searchPosts && !searchState.directPost) items(searchState.accounts, key = { it.handle }) { user -> ListItem(headlineContent = { Text(user.name) }, supportingContent = { Text("@${user.handle}") }, leadingContent = { InitialAvatar(user.name, url = user.avatar) }, modifier = Modifier.clickable { showProfile(user.handle) }) }
                        else items(searchState.posts, key = { it.id }) { post -> PostCard(post, library.saved.any { it.id == post.id }, busy, { model.bookmark(post) }, { selected = post }, showProfile) }
                        if (searchState.next != null && searchPosts && !searchState.directPost) item { TextButton(onClick = { search.search(query, true, true) }, enabled = !searchState.loading) { Text("Charger la suite") } }
                        if (!searchState.directPost) item { TextButton(onClick = { showProfile(query) }, enabled = query.isNotBlank()) { Text("Ouvrir directement ce pseudo") } }
                    }
                }
                else -> {
                    if (tab == 0) Row(Modifier.padding(horizontal = 18.dp)) { FilterChip(false, { favoritesPosts = false }, label = { Text("Comptes") }); Spacer(Modifier.width(8.dp)); FilterChip(true, {}, label = { Text("Posts enregistrés") }) }
                    val posts = if (tab == 0) library.saved else library.timeline()
                    PullToRefreshBox(busy, { if (tab == 1) model.refresh() }, Modifier.weight(1f)) {
                        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(bottom = 24.dp)) {
                            if (posts.isEmpty()) item { EmptyState("Votre fil commence ici", "Ajoutez un compte dans Favoris ou ouvrez un lien X.", Icons.Outlined.DynamicFeed); Button(onClick = { tab = 0 }) { Text("Choisir des comptes") } }
                            items(posts, key = { it.id }) { post -> PostCard(post, library.saved.any { it.id == post.id }, busy, { model.bookmark(post) }, { selected = post }, showProfile); HorizontalDivider(color = Line) }
                            if (tab == 1 && more) item { TextButton(onClick = model::loadMore, enabled = !busy) { Text("Charger les publications précédentes") } }
                        }
                    }
                }
            }
        }
    }
    if (importDialog) ImportDialog(busy, { importDialog = false }, { NavigationRequests.link.value = it; importDialog = false })
    selected?.let { PostDialog(it, { selected = null }, showProfile) }
    viewer?.let { MediaViewer(it, preferences) { MediaUi.viewer.value = null } }
    if (preferences.confirmDownloads && !mediaRequest.isNullOrEmpty()) {
        var filter by remember(mediaRequest) { mutableStateOf(if (mediaRequest?.size == 1) "all" else preferences.mediaFilter) }
        val filtered = mediaRequest!!.filter { filter == "all" || (filter == "videos") == it.media.video }
        AlertDialog(onDismissRequest = { MediaUi.downloads.value = null }, title = { Text("Télécharger ${filtered.size} médias ?") }, text = {
            Column { Text("Destination : ${if (preferences.folder.isBlank()) "Download/XNative/<pseudo>/" else "${preferences.folderLabel}/XNative/<pseudo>/"}"); listOf("all" to "Tout", "images" to "Images", "videos" to "Vidéos").forEach { (key, name) -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(filter == key, { filter = key }); Text(name) } } }
        }, confirmButton = { TextButton(onClick = { MediaUi.downloads.value = null; startDownloads(filtered) }, enabled = filtered.isNotEmpty()) { Text("Télécharger") } }, dismissButton = { TextButton(onClick = { MediaUi.downloads.value = null }) { Text("Annuler") } })
    }
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
    val cards by ProfileCache.state.collectAsStateWithLifecycle()
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
                InitialAvatar(cards[account]?.user?.name ?: account, url = cards[account]?.user?.avatar.orEmpty())
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("@$account", fontWeight = FontWeight.SemiBold)
                    Text("${cards[account]?.user?.mediaCount ?: cards[account]?.posts?.sumOf { it.media.size } ?: library.feeds[account].orEmpty().sumOf { it.media.size }} médias · ${library.feeds[account].orEmpty().size} posts en cache", color = Muted, fontSize = 11.sp)
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
            Text("Xnative 1.2.0 · Indépendant de X et de FxTwitter", color = Muted, fontSize = 10.sp)
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
    val mediaPreferences by SettingsStore.get(context).state.collectAsStateWithLifecycle()
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
                if (post.media.isNotEmpty()) Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    post.media.indices.toList().chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            row.forEach { index ->
                                val media = post.media[index]
                                Box(Modifier.weight(1f).height(if (post.media.size == 1) 190.dp else 140.dp)
                                    .clip(RoundedCornerShape(10.dp)).background(Panel).clickable { MediaUi.open(post, index) }) {
                                    val image = if (media.video) media.thumbnail else media.url
                                    if (image.isNotBlank()) AsyncImage(image, "Média ${index + 1} sur ${post.media.size}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                    if (media.video) Icon(Icons.Outlined.PlayCircle, "Lire la vidéo ${index + 1}", tint = Color.White, modifier = Modifier.size(40.dp).align(Alignment.Center))
                                    if (mediaPreferences.showDuration && media.duration != null) Text("${media.duration.toInt() / 60}:${(media.duration.toInt() % 60).toString().padStart(2, '0')}", color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Ink).padding(6.dp))
                                }
                            }
                        }
                    }
                }
                if (post.media.isNotEmpty()) TextButton(onClick = { MediaUi.download(post) }) { Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Text(" Télécharger les médias") }
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
            Text("Collez un lien de profil ou de publication X, Twitter ou FxTwitter. Vous pouvez aussi partager un lien vers Xnative depuis une autre application.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(link, { link = it; error = null }, label = { Text("Lien de la publication") }, maxLines = 3, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        } },
        confirmButton = { TextButton(enabled = !busy && link.isNotBlank(), onClick = {
            runCatching { runCatching { Links.postId(link) }.getOrElse { Links.profileHandle(link) } }.onSuccess { onImport(link) }.onFailure { error = it.message }
        }) { Text("Ouvrir") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
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
                    TextButton(onClick = { MediaUi.open(post, post.media.indexOf(media)) }) { Text(if (media.video) "Lire la vidéo" else "Voir l’image") }; TextButton(onClick = { MediaUi.downloads.value = listOf(DownloadItem(post.handle, post.id, media)) }) { Text("Télécharger") }
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
