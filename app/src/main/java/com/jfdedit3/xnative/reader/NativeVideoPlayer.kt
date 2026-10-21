package com.jfdedit3.xnative.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

internal fun Context.playerActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.playerActivity()
    else -> null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayer(
    url: String, modifier: Modifier = Modifier, fullscreen: Boolean = false, inPip: Boolean = false,
    initialPosition: Long = 0, initialPlaying: Boolean = true, initialSpeed: Float = 1f,
    onSession: ((Boolean, Float, () -> Unit) -> Unit)? = null,
    onExit: ((Long, Boolean, Float) -> Unit)? = null
) {
    val context = LocalContext.current
    val activity = context.playerActivity()
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: activity?.window
    val lifecycle = LocalLifecycleOwner.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var position by rememberSaveable(url) { mutableLongStateOf(initialPosition.coerceAtLeast(0)) }
    var wantedPlaying by rememberSaveable(url) { mutableStateOf(initialPlaying) }
    var speed by rememberSaveable(url) { mutableFloatStateOf(initialSpeed.coerceIn(0.25f, 2f)) }
    var repeating by rememberSaveable(url) { mutableStateOf(true) }
    var playing by remember(url) { mutableStateOf(false) }
    var buffering by remember(url) { mutableStateOf(true) }
    var duration by remember(url) { mutableLongStateOf(0) }
    var buffered by remember(url) { mutableLongStateOf(0) }
    var aspectRatio by remember(url) { mutableFloatStateOf(16f / 9f) }
    var error by remember(url) { mutableStateOf<String?>(null) }
    val visibility = remember(url) { VideoVisibility() }
    var controls by remember(url) { mutableStateOf(true) }
    var locked by rememberSaveable(url) { mutableStateOf(false) }
    var menu by remember(url) { mutableStateOf(false) }
    var resize by rememberSaveable(url) { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var interaction by remember { mutableIntStateOf(0) }
    var seeking by remember { mutableStateOf(false) }
    var seekPreview by remember { mutableLongStateOf(0) }
    var hud by remember { mutableStateOf<String?>(null) }
    var hudTick by remember { mutableIntStateOf(0) }
    fun message(value: String) { hud = value; hudTick++ }
    val player = remember(url) {
        ExoPlayer.Builder(context.applicationContext).setSeekBackIncrementMs(5000).setSeekForwardIncrementMs(5000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { if (!visibility.pausedForBackground) wantedPlaying = playWhenReady }
                override fun onVideoSizeChanged(videoSize: VideoSize) { aspectRatio = VideoPlaybackRules.ratio((videoSize.width * videoSize.pixelWidthHeightRatio).toInt(), videoSize.height) }
                override fun onPlayerError(value: PlaybackException) { buffering = false; error = "Lecture impossible (${value.errorCodeName})."; controls = true }
            })
            repeatMode = if (repeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            setPlaybackSpeed(speed)
            setMediaItem(MediaItem.fromUri(url), position)
            playWhenReady = wantedPlaying
            prepare()
        }
    }
    val currentPip by rememberUpdatedState(inPip)
    val fullScreenLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            val target = data.getLongExtra("position", player.currentPosition)
            player.seekTo(target); position = target
            speed = data.getFloatExtra("speed", speed).coerceIn(0.25f, 2f); player.setPlaybackSpeed(speed)
            wantedPlaying = data.getBooleanExtra("playing", false); player.playWhenReady = wantedPlaying
        }
        controls = true
    }
    fun exit() { onExit?.invoke(player.currentPosition, player.playWhenReady, speed) }
    fun touch() { interaction++; controls = true }
    fun seek(delta: Long) {
        if (duration <= 0 || !player.isCurrentMediaItemSeekable) return
        val before = player.currentPosition
        val target = VideoPlaybackRules.seek(before, delta, duration)
        player.seekTo(target); position = target
        message("${if (target >= before) "+" else "−"}${kotlin.math.abs(target - before) / 1000} s")
        interaction++
    }
    fun togglePlayback() {
        val ended = player.playbackState == Player.STATE_ENDED
        wantedPlaying = VideoPlaybackRules.playAfterToggle(player.playWhenReady, ended)
        if (ended) { player.seekTo(0); position = 0 }
        player.playWhenReady = wantedPlaying; touch()
    }
    DisposableEffect(player, lifecycle) {
        val originalBrightness = window?.attributes?.screenBrightness
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && visibility.stop(player.playWhenReady, currentPip)) player.pause()
            if (event == Lifecycle.Event.ON_START && visibility.start()) player.play()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            runCatching { if (originalBrightness != null) window?.let { it.attributes = it.attributes.apply { screenBrightness = originalBrightness } } }
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (isActive) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            buffered = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }
    LaunchedEffect(playing, controls, interaction, seeking, menu, locked) {
        if (playing && controls && !seeking && !menu && !locked) { delay(3000); controls = false }
    }
    LaunchedEffect(hudTick) { if (hud != null) { delay(1000); hud = null } }
    LaunchedEffect(playing, aspectRatio, error) { onSession?.invoke(playing && error == null, aspectRatio, { exit() }) }
    var startLevel by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var brightnessSide by remember { mutableStateOf(false) }
    var dragStartPosition by remember { mutableLongStateOf(0) }
    var dragX by remember { mutableFloatStateOf(0f) }
    val touchLayer = if (locked) Modifier.pointerInput(locked) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } } while (event.changes.any { it.pressed })
        }
    } else Modifier
        .pointerInput(player) {
            detectTapGestures(onTap = { controls = !controls; interaction++ }, onDoubleTap = { point ->
                when { point.x < size.width * 0.35f -> seek(-5000); point.x > size.width * 0.65f -> seek(5000); else -> togglePlayback() }
            })
        }
        .pointerInput(player) {
            detectVerticalDragGestures(onDragStart = { point ->
                brightnessSide = point.x < size.width / 2
                dragY = 0f
                startLevel = if (brightnessSide) window?.attributes?.screenBrightness?.takeIf { it >= 0 } ?: (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f)
                else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            }, onVerticalDrag = { change, amount ->
                change.consume(); dragY += amount
                val value = VideoPlaybackRules.level(startLevel, dragY, size.height, if (brightnessSide) 0.02f else 0f)
                if (brightnessSide) window?.let { it.attributes = it.attributes.apply { screenBrightness = value } }
                else runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (value * audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).roundToInt(), 0) }
                message("${if (brightnessSide) "Luminosité" else "Volume"} ${(value * 100).roundToInt()} %")
                interaction++
            })
        }
        .pointerInput(player) {
            detectDragGesturesAfterLongPress(onDragStart = {
                seeking = duration > 0 && player.isCurrentMediaItemSeekable
                dragStartPosition = player.currentPosition; dragX = 0f; seekPreview = dragStartPosition
            }, onDrag = { change, amount ->
                if (seeking) {
                    change.consume(); dragX += amount.x
                    seekPreview = VideoPlaybackRules.drag(dragStartPosition, dragX, size.width, duration)
                    message("${VideoPlaybackRules.time(seekPreview)} / ${VideoPlaybackRules.time(duration)}")
                }
            }, onDragEnd = { if (seeking) { player.seekTo(seekPreview); position = seekPreview }; seeking = false; interaction++ }, onDragCancel = { seeking = false })
        }
    Box(modifier.background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false; setKeepContentOnPlayerReset(true) } },
            update = { it.player = player; it.resizeMode = resize; it.keepScreenOn = playing },
            onRelease = { it.player = null; it.keepScreenOn = false }, modifier = Modifier.fillMaxSize())
        if (!inPip) {
            Box(Modifier.fillMaxSize().then(touchLayer))
            if (buffering && error == null) CircularProgressIndicator(Modifier.size(42.dp).align(Alignment.Center), color = Color.White)
            hud?.let { Text(it, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(top = 72.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp)).padding(18.dp)) }
            if (locked) IconButton(onClick = { locked = false; touch() }, modifier = Modifier.align(Alignment.CenterEnd).padding(16.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape)) { Icon(Icons.Outlined.LockOpen, "Déverrouiller le lecteur", tint = Color.White) }
            else if (controls) {
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent))).padding(horizontal = 8.dp).statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    if (fullscreen) IconButton(onClick = { exit() }) { Icon(Icons.Outlined.ArrowBack, "Quitter le plein écran", tint = Color.White) }
                    Text("Vidéo", color = Color.White, modifier = Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = { locked = true; menu = false }) { Icon(Icons.Outlined.Lock, "Verrouiller les commandes", tint = Color.White) }
                    if (fullscreen && (activity as? VideoPlayerActivity)?.canPip() == true) IconButton(onClick = { if (!(activity as VideoPlayerActivity).enterPip()) message("Image dans l’image indisponible") }) { Icon(Icons.Outlined.PictureInPictureAlt, "Image dans l’image", tint = Color.White) }
                    Box {
                        TextButton(onClick = { menu = !menu; touch() }) { Text("${speed}×", color = Color.White) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; touch() }) {
                            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { rate -> DropdownMenuItem(text = { Text("${rate}×") }, onClick = { speed = rate; player.setPlaybackSpeed(rate); menu = false; touch() }) }
                        }
                    }
                }
                if (error == null && !buffering) Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                    IconButton(onClick = { seek(-5000); touch() }, enabled = duration > 0, modifier = Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Outlined.Replay5, "Reculer de 5 secondes", tint = Color.White, modifier = Modifier.size(32.dp)) }
                    IconButton(onClick = { togglePlayback() }, modifier = Modifier.size(70.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape)) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (playing) "Mettre en pause" else "Lire", tint = Color.White, modifier = Modifier.size(46.dp)) }
                    IconButton(onClick = { seek(5000); touch() }, enabled = duration > 0, modifier = Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)) { Icon(Icons.Outlined.Forward5, "Avancer de 5 secondes", tint = Color.White, modifier = Modifier.size(32.dp)) }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)))).padding(12.dp).navigationBarsPadding()) {
                    Slider(value = (if (seeking) seekPreview else position).coerceIn(0, duration.coerceAtLeast(1)).toFloat(), onValueChange = { seeking = true; seekPreview = it.toLong(); interaction++ }, onValueChangeFinished = { if (seeking) { player.seekTo(seekPreview); position = seekPreview }; seeking = false; touch() }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), enabled = duration > 0 && player.isCurrentMediaItemSeekable)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${VideoPlaybackRules.time(if (seeking) seekPreview else position)} / ${VideoPlaybackRules.time(duration)}", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { repeating = !repeating; player.repeatMode = if (repeating) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF; touch() }) { Icon(Icons.Outlined.Repeat, "Lecture en boucle ${if (repeating) "activée" else "désactivée"}", tint = if (repeating) Blue else Color.White) }
                        IconButton(onClick = { resize = if (resize == AspectRatioFrameLayout.RESIZE_MODE_FIT) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT; message(if (resize == AspectRatioFrameLayout.RESIZE_MODE_FIT) "Adapter à l’écran" else "Remplir l’écran"); touch() }) { Icon(Icons.Outlined.AspectRatio, "Adapter ou remplir l’écran", tint = Color.White) }
                        IconButton(onClick = {
                            if (fullscreen) {
                                activity?.requestedOrientation = if (activity?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                            } else {
                                val playingBefore = player.playWhenReady
                                val intent = Intent(context, VideoPlayerActivity::class.java).putExtra("url", url).putExtra("position", player.currentPosition).putExtra("playing", playingBefore).putExtra("speed", speed)
                                player.pause(); wantedPlaying = false; fullScreenLauncher.launch(intent)
                            }
                            touch()
                        }) { Icon(if (fullscreen) Icons.Outlined.ScreenRotation else Icons.Outlined.Fullscreen, if (fullscreen) "Changer l’orientation" else "Ouvrir le plein écran", tint = Color.White) }
                    }
                    if (buffered > position && duration > 0) LinearProgressIndicator(progress = { (buffered.toFloat() / duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp), color = Color.White.copy(alpha = 0.35f))
                }
            }
            error?.let {
                Column(Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(16.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, color = Color.White)
                    TextButton(onClick = { error = null; player.prepare(); player.play(); touch() }) { Text("Réessayer") }
                }
            }
        }
    }
}
