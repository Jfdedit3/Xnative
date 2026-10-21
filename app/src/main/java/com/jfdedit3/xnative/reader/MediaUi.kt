package com.jfdedit3.xnative.reader

import android.app.Application
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class XnativeApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this).components {
        add(VideoFrameDecoder.Factory())
        if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
    }.build()
}
data class MediaSelection(val items: List<DownloadItem>, val index: Int = 0)
object MediaUi {
    val viewer = MutableStateFlow<MediaSelection?>(null)
    val downloads = MutableStateFlow<List<DownloadItem>?>(null)
    fun open(post: Post, index: Int = 0) { if (post.media.isNotEmpty()) viewer.value = MediaSelection(post.media.map { DownloadItem(post.handle, post.id, it) }, index) }
    fun download(post: Post) { downloads.value = post.media.map { DownloadItem(post.handle, post.id, it) }.takeIf { it.isNotEmpty() } }
}
@Composable
fun MediaViewer(selection: MediaSelection, preferences: Preferences, close: () -> Unit) {
    val pager = rememberPagerState(initialPage = selection.index.coerceIn(0, selection.items.lastIndex)) { selection.items.size }
    var zoomed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = close) { Icon(Icons.Outlined.Close, "Fermer la visionneuse", tint = Color.White) }
                    Text("${pager.currentPage + 1} / ${selection.items.size}", color = Color.White, modifier = Modifier.weight(1f))
                    val current = selection.items[pager.currentPage]
                    if (current.media.url.startsWith("https://")) IconButton(onClick = { MediaUi.downloads.value = listOf(current) }) { Icon(Icons.Outlined.Download, "Télécharger ce média", tint = Color.White) }
                }
                HorizontalPager(pager, userScrollEnabled = !zoomed, modifier = Modifier.weight(1f)) { index ->
                    val item = selection.items[index]
                    if (item.media.video) {
                        if (pager.currentPage == index) VideoPlayer(item.media.url, Modifier.fillMaxSize())
                    } else ZoomImage(item.media.url, preferences.animations, pager.currentPage == index, { zoomed = it })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = pager.currentPage > 0, onClick = { scope.launch { zoomed = false; if (preferences.animations) pager.animateScrollToPage(pager.currentPage - 1) else pager.scrollToPage(pager.currentPage - 1) } }) { Text("Précédent") }
                    Text(if (selection.items[pager.currentPage].media.video) "Double appui : ±5 s · glissez verticalement : luminosité / volume" else "Pincez ou double-touchez pour zoomer", color = Muted, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified, modifier = Modifier.weight(1f).padding(8.dp))
                    TextButton(enabled = pager.currentPage < selection.items.lastIndex, onClick = { scope.launch { zoomed = false; if (preferences.animations) pager.animateScrollToPage(pager.currentPage + 1) else pager.scrollToPage(pager.currentPage + 1) } }) { Text("Suivant") }
                }
            }
        }
    }
}
@Composable
private fun ZoomImage(url: String, animations: Boolean, active: Boolean, onZoom: (Boolean) -> Unit) {
    var zoom by remember(url) { mutableFloatStateOf(1f) }
    var pan by remember(url) { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (active) { zoom = 1f; pan = Offset.Zero; onZoom(false) } }
    AsyncImage(ImageRequest.Builder(LocalContext.current).data(url).crossfade(animations).build(), "Image — pincer pour zoomer, double toucher pour réinitialiser", contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize().pointerInput(url) {
            detectTapGestures(onDoubleTap = { zoom = if (zoom > 1f) 1f else 2.5f; pan = Offset.Zero; onZoom(zoom > 1f) })
        }.pointerInput(url) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val event = awaitPointerEvent()
                    if (zoom > 1f || event.changes.count { it.pressed } > 1) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 6f)
                        val moved = pan + event.calculatePan()
                        pan = Offset(moved.x.coerceIn(-size.width * (zoom - 1) / 2, size.width * (zoom - 1) / 2), moved.y.coerceIn(-size.height * (zoom - 1) / 2, size.height * (zoom - 1) / 2))
                        event.changes.forEach { it.consume() }
                        onZoom(zoom > 1f)
                    }
                } while (event.changes.any { it.pressed })
            }
        }.graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y })
}
