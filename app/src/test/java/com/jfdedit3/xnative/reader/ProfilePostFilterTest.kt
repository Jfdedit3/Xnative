package com.jfdedit3.xnative.reader

import org.junit.Assert.*
import org.junit.Test

class ProfilePostFilterTest {
    private val image = Media("https://example.org/image.jpg")
    private val video = Media("https://example.org/video.mp4", video = true)
    private val own = Post("1", "https://x.com/NASA/status/1", "Mixed post", "NASA", "NASA", media = listOf(image, video))
    private val posts = listOf(own, own.copy(id = "2", handle = "other", repostedBy = "nasa"), own.copy(id = "3", handle = "other"), own.copy(id = "4", repostedBy = "NASA"), own.copy(id = "5", media = emptyList()))

    @Test fun imagesIncludeOnlyTheAccountsOwnImagesWithoutReposts() {
        val selected = profilePostsForTab(posts, "nasa", 1)
        assertEquals(listOf("1"), selected.map { it.id })
        assertEquals(listOf(image), selected.single().media)
    }
    @Test fun videosIncludeOnlyTheAccountsOwnVideosWithoutReposts() {
        val selected = profilePostsForTab(posts, "nasa", 2)
        assertEquals(listOf("1"), selected.map { it.id })
        assertEquals(listOf(video), selected.single().media)
    }
    @Test fun publicationsKeepRepostsAndMixedMediaWithoutMutatingTheSource() {
        assertEquals(posts, profilePostsForTab(posts, "nasa", 0))
        profilePostsForTab(posts, "nasa", 1)
        assertEquals(listOf(image, video), posts.first().media)
    }
}
