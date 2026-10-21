package com.jfdedit3.xnative.reader

internal object VideoPlaybackRules {
    fun playAfterToggle(playWhenReady: Boolean, ended: Boolean): Boolean = ended || !playWhenReady
    fun seek(position: Long, delta: Long, duration: Long): Long =
        (position.toDouble() + delta.toDouble()).coerceIn(0.0, duration.coerceAtLeast(0).toDouble()).toLong()
    fun drag(position: Long, deltaX: Float, width: Int, duration: Long): Long =
        seek(position, (duration.coerceAtLeast(0) * deltaX / width.coerceAtLeast(1) * 0.5).toLong(), duration)
    fun level(start: Float, deltaY: Float, height: Int, minimum: Float = 0f): Float =
        (start - deltaY / height.coerceAtLeast(1)).coerceIn(minimum, 1f)
    fun time(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
        else "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
    }
    fun pipFraction(ratio: Float): Pair<Int, Int> {
        val numerator = (ratio.takeIf { it.isFinite() && it > 0 } ?: (16f / 9f)).times(10000).toInt()
        val roundedRatio = numerator / 10000.0
        return when {
            roundedRatio >= 2.39 -> 239 to 100
            roundedRatio <= 1.0 / 2.39 -> 100 to 239
            else -> numerator to 10000
        }
    }
    fun ratio(width: Int, height: Int): Float =
        if (width <= 0 || height <= 0) 16f / 9f else (width.toFloat() / height).coerceIn(1f / 2.39f, 2.39f)
}

internal class VideoVisibility {
    var pausedForBackground = false
        private set
    private var resumePlayback = false
    fun stop(playing: Boolean, inPip: Boolean): Boolean {
        if (inPip || pausedForBackground) return false
        pausedForBackground = true
        resumePlayback = playing
        return true
    }
    fun start(): Boolean {
        val resume = pausedForBackground && resumePlayback
        pausedForBackground = false
        resumePlayback = false
        return resume
    }
}
