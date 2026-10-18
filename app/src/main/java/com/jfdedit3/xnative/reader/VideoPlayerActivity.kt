package com.jfdedit3.xnative.reader

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class VideoPlayerActivity : ComponentActivity() {
    private var pip by mutableStateOf(false)
    private var wantsPlayback = false
    private var ratio = 16f / 9f
    private var finishPlayback: (() -> Unit)? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        volumeControlStream = AudioManager.STREAM_MUSIC
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        val url = intent.getStringExtra("url").orEmpty()
        if (!(url.startsWith("https://") || url.startsWith("content://"))) { finish(); return }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue)) {
                VideoPlayer(url, Modifier.fillMaxSize(), fullscreen = true, inPip = pip,
                    initialPosition = intent.getLongExtra("position", 0), initialPlaying = intent.getBooleanExtra("playing", true),
                    initialSpeed = intent.getFloatExtra("speed", 1f), onSession = { playing, videoRatio, exit ->
                        wantsPlayback = playing; ratio = videoRatio; finishPlayback = exit; updatePip()
                    }, onExit = { position, playing, speed ->
                        setResult(RESULT_OK, Intent().putExtra("position", position).putExtra("playing", playing).putExtra("speed", speed))
                        finish()
                    })
            }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finishPlayback?.invoke() ?: finish() }
        })
    }
    @androidx.annotation.ChecksSdkIntAtLeast(api = 26)
    fun canPip() = Build.VERSION.SDK_INT >= 26 && packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    @androidx.annotation.RequiresApi(26)
    private fun params(): PictureInPictureParams {
        val (width, height) = VideoPlaybackRules.pipFraction(ratio)
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(width, height))
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(wantsPlayback).setSeamlessResizeEnabled(true)
        return builder.build()
    }
    private fun updatePip() {
        if (canPip() && Build.VERSION.SDK_INT >= 31) runCatching { setPictureInPictureParams(params()) }
    }
    fun enterPip(): Boolean = if (canPip()) runCatching { enterPictureInPictureMode(params()) }.getOrDefault(false) else false
    @Deprecated("Android lifecycle callback")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && wantsPlayback) enterPip()
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }
}
