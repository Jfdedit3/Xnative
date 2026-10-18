package com.jfdedit3.xnative.reader

import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackRulesTest {
    @Test fun fiveSecondSeekingNeverLeavesThePlayableRange() {
        assertEquals(0L, VideoPlaybackRules.seek(2000, -5000, 30000))
        assertEquals(30000L, VideoPlaybackRules.seek(28000, 5000, 30000))
        assertEquals(17000L, VideoPlaybackRules.seek(12000, 5000, 30000))
        assertEquals(0L, VideoPlaybackRules.seek(12000, 5000, -1))
        assertEquals(Long.MAX_VALUE, VideoPlaybackRules.seek(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
    }
    @Test fun holdDragMovesProportionallyAndClampsToDuration() {
        assertEquals(45000L, VideoPlaybackRules.drag(30000, 500f, 1000, 60000))
        assertEquals(15000L, VideoPlaybackRules.drag(30000, -500f, 1000, 60000))
        assertEquals(60000L, VideoPlaybackRules.drag(30000, 5000f, 1000, 60000))
        assertEquals(0L, VideoPlaybackRules.drag(30000, -5000f, 1000, 60000))
    }
    @Test fun brightnessAndVolumeGesturesStayInRange() {
        assertEquals(1f, VideoPlaybackRules.level(0.5f, -2000f, 1000), 0f)
        assertEquals(0f, VideoPlaybackRules.level(0.5f, 2000f, 1000), 0f)
        assertEquals(0.02f, VideoPlaybackRules.level(0.5f, 2000f, 1000, 0.02f), 0f)
        assertEquals(0.75f, VideoPlaybackRules.level(0.5f, -250f, 1000), 0f)
    }
    @Test fun timelineIncludesHoursAndUnknownDurationIsSafe() {
        assertEquals("0:00", VideoPlaybackRules.time(-1))
        assertEquals("1:05", VideoPlaybackRules.time(65000))
        assertEquals("1:01:05", VideoPlaybackRules.time(3665000))
    }
    @Test fun pipRatiosIncludeVerticalVideosWithoutRoundingOutsideAndroidLimits() {
        listOf(0.01f, 1f / 2.39f, 9f / 16f, 16f / 9f, 2.39f, 100f, Float.NaN).forEach { ratio ->
            val (width, height) = VideoPlaybackRules.pipFraction(ratio)
            val actual = width.toDouble() / height
            assertTrue("$ratio -> $actual", actual >= 1.0 / 2.39 && actual <= 2.39)
        }
        assertEquals(9f / 16f, VideoPlaybackRules.ratio(1080, 1920), 0.001f)
        assertEquals(16f / 9f, VideoPlaybackRules.ratio(0, 0), 0.001f)
    }
    @Test fun backgroundReturnResumesOnlyPlaybackThatWasRunning() {
        val session = VideoVisibility()
        assertTrue(session.stop(playing = false, inPip = false))
        assertFalse(session.start())
        assertTrue(session.stop(playing = true, inPip = false))
        assertFalse(session.stop(playing = false, inPip = false))
        assertTrue(session.start())
        assertFalse(session.start())
    }
    @Test fun pipDoesNotPauseOrQueueAnUnwantedResume() {
        val session = VideoVisibility()
        assertFalse(session.stop(playing = true, inPip = true))
        assertFalse(session.pausedForBackground)
        assertFalse(session.start())
    }
}
