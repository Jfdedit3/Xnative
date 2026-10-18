package com.jfdedit3.xnative.reader

/** Keeps the upstream cursor after failures so retry resumes the same page. */
internal class ProfileMediaPager(private val client: FxClient) {
    private enum class Source { MEDIA, POSTS, RSS }
    private var source = Source.MEDIA
    private var cursor: String? = null
    private var started = false
    private val seen = mutableSetOf<String>()
    var complete = false
        private set
    var notice: String? = null
        private set

    fun next(account: String): PostPage {
        check(!complete) { "Les médias disponibles ont déjà été chargés." }
        val page = if (!started) {
            try { client.page(account, count = 100, media = true) }
            catch (mediaError: Exception) {
                try {
                    val fallback = client.page(account, count = 100)
                    source = Source.POSTS
                    notice = "Le flux de médias est indisponible. Affichage des médias trouvés dans les publications accessibles."
                    fallback
                } catch (_: Exception) {
                    val fallback = PostPage(client.rssFeed(account))
                    source = Source.RSS
                    notice = "Affichage des médias du flux RSS récent. L’historique complet est indisponible sur FxTwitter."
                    fallback
                }
            }
        } else client.page(account, cursor, 100, media = source == Source.MEDIA)
        // FxTwitter can return empty results with a new cursor indefinitely.
        val next = page.next.takeIf { page.posts.isNotEmpty() && source != Source.RSS }
        check(next == null || next !in seen) { "FxTwitter répète la même page de médias. Réessayez plus tard." }
        next?.let(seen::add)
        cursor = next
        started = true
        complete = next == null
        return page.copy(next = next)
    }
}
