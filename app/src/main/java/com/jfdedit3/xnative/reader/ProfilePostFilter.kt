package com.jfdedit3.xnative.reader

internal fun profilePostsForTab(posts: List<Post>, account: String, tab: Int): List<Post> {
    if (tab == 0) return posts
    return posts.asSequence()
        .filter { it.handle.equals(account, ignoreCase = true) && it.repostedBy.isBlank() }
        .map { post -> post.copy(media = post.media.filter { it.video == (tab == 2) }) }
        .filter { it.media.isNotEmpty() }
        .toList()
}
