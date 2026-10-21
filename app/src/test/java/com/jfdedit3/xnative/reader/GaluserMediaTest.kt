package com.jfdedit3.xnative.reader

import org.junit.Assert.*
import org.junit.Test

class GaluserMediaTest {
    private val photo = Media("https://example.org/one.jpg")
    private val second = Media("https://example.org/two.jpg")
    private val video = Media("https://example.org/video.mp4", video = true)
    private val own = Post("1", "https://x.com/alice/status/1", "", "Alice", "Alice", media = listOf(photo, second, video))
    @Test fun mixedPostContributesEveryPhotoAndOnlyTheVideoToItsTab() {
        assertEquals(listOf(photo, second), galuserMedia(listOf(own), "alice", false).map { it.media })
        assertEquals(listOf(video), galuserMedia(listOf(own), "alice", true).map { it.media })
    }
    @Test fun otherAuthorsAndSelfRepostsNeverAppearInTheUserGallery() {
        val posts = listOf(own, own.copy(id = "2", handle = "other"), own.copy(id = "3", repostedBy = "alice"))
        assertEquals(setOf("1"), galuserMedia(posts, "ALICE", false).map { it.postId }.toSet())
    }
    @Test fun duplicatePostsAndMediaDoNotDuplicateTilesOrDownloads() {
        val duplicate = own.copy(media = listOf(photo, photo, video))
        assertEquals(1, galuserMedia(listOf(duplicate, duplicate), "alice", false).size)
    }
}
