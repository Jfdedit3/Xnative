package com.jfdedit3.xnative.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.InputSource

@Serializable
data class Media(val url: String, val thumbnail: String = "", val video: Boolean = false, val duration: Double? = null, val mime: String = "")
@Serializable
data class Post(
    val id: String, val url: String, val text: String, val name: String, val handle: String,
    val avatar: String = "", val timestamp: Long = 0, val likes: Long? = null,
    val reposts: Long? = null, val replies: Long? = null, val media: List<Media> = emptyList(),
    val quote: String = "", val repostedBy: String = "", val source: String = "API"
)
@Serializable
data class Profile(
    val handle: String, val name: String, val description: String = "", val avatar: String = "",
    val banner: String = "", val location: String = "", val website: String = "",
    val websiteLabel: String = "", val joined: String = "", val followers: Long? = null,
    val following: Long? = null, val statuses: Long? = null, val verified: Boolean = false,
    val protected: Boolean = false, val mediaCount: Long? = null
)

@Serializable
data class Library(
    val accounts: List<String> = emptyList(), val feeds: Map<String, List<Post>> = emptyMap(),
    val imported: List<Post> = emptyList(), val saved: List<Post> = emptyList(), val rssOnly: Boolean = false
) {
    fun timeline() = (feeds.values.flatten() + imported).distinctBy { it.id }.sortedByDescending { it.timestamp }
}

object Links {
    private val hosts = setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com", "mobile.twitter.com", "fxtwitter.com", "fixupx.com")
    fun handle(value: String): String {
        val input = value.trim().removePrefix("@")
        require(input.matches(Regex("[A-Za-z0-9_]{1,15}"))) { "Saisissez un pseudo X valide (sans espace)." }
        return input.lowercase()
    }
    private val reserved = setOf("home", "explore", "notifications", "messages", "search", "settings", "i", "intent", "login", "signup", "compose", "tos", "privacy")
    fun profileHandle(input: String): String {
        if (!input.trim().startsWith("https://")) return handle(input)
        val uri = URI(input.trim())
        require(uri.host?.lowercase() in hosts) { "Ce lien ne provient pas de X ou FxTwitter." }
        val path = uri.path.trim('/').split('/')
        require(path.size == 1 && path[0].lowercase() !in reserved) { "Ce lien ne désigne pas un profil." }
        return handle(path[0])
    }
    fun postId(input: String): String {
        val candidate = Regex("https://[^\\s<>]+").findAll(input).map { it.value.trimEnd('.', ',', ')') }
            .firstOrNull { runCatching { URI(it).host?.lowercase() in hosts }.getOrDefault(false) }
            ?: throw IllegalArgumentException("Collez un lien https://x.com/pseudo/status/123…")
        val uri = URI(candidate)
        return Regex("/(?:[A-Za-z0-9_]+/status|i/web/status)/(\\d+)(?:/.*)?").matchEntire(uri.path)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Ce lien ne désigne pas une publication X.")
    }
}

object PostParser {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun JsonObject.str(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    private fun post(obj: JsonObject): Post? {
        val id = obj.str("id")
        val author = obj["author"] as? JsonObject ?: return null
        if (id.isEmpty()) return null
        val media = (obj["media"] as? JsonObject)?.get("all") as? JsonArray
        val quote = obj["quote"] as? JsonObject
        return Post(id, obj.str("url").ifEmpty { "https://x.com/${author.str("screen_name")}/status/$id" },
            obj.str("text"), author.str("name"), author.str("screen_name"), author.str("avatar_url"),
            obj.number("created_timestamp") ?: 0, obj.number("likes"), obj.number("reposts") ?: obj.number("retweets"), obj.number("replies"),
            media.orEmpty().mapNotNull { element ->
                val m = element as? JsonObject ?: return@mapNotNull null
                if (m.str("url").isBlank()) null else Media(m.str("url"), m.str("thumbnail_url"), m.str("type") in setOf("video", "gif") && !m.str("url").substringBefore('?').endsWith(".gif", true),
                    (m["duration"] as? JsonPrimitive)?.doubleOrNull, m.str("format"))
            }, quote?.let { "@${(it["author"] as? JsonObject)?.str("screen_name").orEmpty()}\n${it.str("text")}" }.orEmpty(),
            (obj["reposted_by"] as? JsonObject)?.str("screen_name").orEmpty())
    }
    fun api(body: String): List<Post> {
        val root = json.parseToJsonElement(body).jsonObject
        val code = root.number("code") ?: 200
        require(code == 200L) { "FxTwitter : ${root.str("message").ifBlank { "erreur $code" }}" }
        (root["tweet"] as? JsonObject)?.let { return listOfNotNull(post(it)) }
        (root["status"] as? JsonObject)?.let { return listOfNotNull(post(it)) }
        val rows = root["results"] as? JsonArray ?: throw IllegalArgumentException("Réponse API FxTwitter non reconnue.")
        fun flatten(value: JsonElement): List<Post> = when (value) {
            is JsonObject -> post(value)?.let(::listOf) ?: listOf("statuses", "items", "results").flatMap { key ->
                (value[key] as? JsonArray).orEmpty().flatMap(::flatten)
            }
            else -> emptyList()
        }
        return rows.flatMap(::flatten).distinctBy { it.id }
    }
    fun profile(body: String): Profile {
        val root = json.parseToJsonElement(body).jsonObject
        val code = root.number("code") ?: 200
        require(code == 200L) { "FxTwitter : ${root.str("message").ifBlank { "profil inaccessible ($code)" }}" }
        val user = root["user"] as? JsonObject ?: error("Réponse de profil FxTwitter non reconnue.")
        val handle = user.str("screen_name")
        Links.handle(handle)
        val website = user["website"] as? JsonObject
        return Profile(handle, user.str("name").ifBlank { handle }, user.str("description"),
            user.str("avatar_url"), user.str("banner_url"), user.str("location"),
            website?.str("url").orEmpty(), website?.str("display_url").orEmpty(), user.str("joined"),
            user.number("followers"), user.number("following"), user.number("statuses"),
            ((user["verification"] as? JsonObject)?.get("verified") as? JsonPrimitive)?.booleanOrNull ?: false,
            (user["protected"] as? JsonPrimitive)?.booleanOrNull ?: false, user.number("media_count"))
    }
    fun page(body: String): PostPage {
        val root = json.parseToJsonElement(body).jsonObject
        val cursor = (root["cursor"] as? JsonObject)?.str("bottom")?.takeIf { it.isNotBlank() }
        return PostPage(api(body), cursor)
    }
    fun accounts(body: String): List<Profile> {
        val root = json.parseToJsonElement(body).jsonObject
        require(root.number("code") == null || root.number("code") == 200L) { "Recherche FxTwitter indisponible." }
        return (root["users"] as? JsonArray).orEmpty().mapNotNull { user ->
            runCatching { profile(buildJsonObject { put("code", 200); put("user", user) }.toString()) }.getOrNull()
        }
    }
    private fun plain(html: String) = html.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")
        .replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
    fun rss(body: String, account: String): List<Post> {
        require(!body.contains("<!DOCTYPE", ignoreCase = true)) { "Flux XML non autorisé." }
        val posts = mutableListOf<Post>()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        var inItem = false
        var recognized = false
        var key = ""
        var value = StringBuilder()
        var fields = mutableMapOf<String, String>()
        var attachment: Media? = null
        val handler = object : DefaultHandler() {
            override fun startElement(uri: String?, local: String, qName: String?, attrs: Attributes) {
                if (local == "rss") recognized = true
                if (local == "item") { inItem = true; fields = mutableMapOf(); attachment = null }
                if (!inItem) return
                key = local; value = StringBuilder()
                if (local == "enclosure") attachment = Media(attrs.getValue("url").orEmpty(), video = attrs.getValue("type").orEmpty().startsWith("video"))
                if (local == "thumbnail") fields["thumbnail"] = attrs.getValue("url").orEmpty()
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { if (inItem) value.append(ch, start, length) }
            override fun endElement(uri: String?, local: String, qName: String?) {
                if (!inItem) return
                if (local == key && local in setOf("title", "link", "pubDate", "description")) fields[local] = value.toString().trim()
                if (local != "item") return
                inItem = false
                val url = fields["link"].orEmpty()
                val id = runCatching { Links.postId(url) }.getOrNull() ?: return
                val handle = runCatching { URI(url).path.split('/')[1] }.getOrDefault(account)
                val date = runCatching { ZonedDateTime.parse(fields["pubDate"], DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() }.getOrDefault(0)
                posts += Post(id, url, plain(fields["description"] ?: fields["title"].orEmpty()), handle, handle,
                    timestamp = date, media = listOfNotNull(attachment?.copy(thumbnail = fields["thumbnail"].orEmpty())), source = "RSS")
            }
        }
        factory.newSAXParser().parse(InputSource(StringReader(body)), handler)
        require(recognized) { "FxTwitter n’a pas renvoyé un flux RSS valide." }
        return posts.distinctBy { it.id }
    }
}

class FxClient(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.fxtwitter.com",
    private val rssBase: String = "https://fxtwitter.com"
) {
    private fun fetch(url: String): String = client.newCall(Request.Builder().url(url)
        .header("User-Agent", "Xnative/1.2 (Android RSS reader)").build()).execute().use { response ->
        when (response.code) {
            204 -> return "{\"code\":200,\"results\":[]}"
            429 -> error("FxTwitter limite les requêtes. Réessayez dans quelques minutes.")
            404 -> error("Compte ou publication introuvable sur FxTwitter.")
        }
        check(response.isSuccessful) { "FxTwitter indisponible (HTTP ${response.code})." }
        val body = response.body ?: error("Réponse FxTwitter vide.")
        check(body.contentLength() <= 5_000_000) { "Réponse trop volumineuse." }
        val bytes = body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                check(output.size() + count <= 5_000_000) { "Réponse trop volumineuse." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        check(bytes.size <= 5_000_000) { "Réponse trop volumineuse." }
        bytes.toString(Charsets.UTF_8)
    }
    var retries: Boolean = false
    private fun get(url: String): String {
        var last: Exception? = null
        repeat(if (retries) 3 else 1) { attempt ->
            try { return fetch(url) } catch (error: Exception) {
                last = error
                // Invalid inputs and 404s will never improve by retrying.
                if (error.message.orEmpty().contains("introuvable")) throw error
                if (attempt < 2 && retries) Thread.sleep(250L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("Connexion impossible.")
    }
    fun page(account: String, cursor: String? = null, count: Int = 30, media: Boolean = false): PostPage {
        val url = "$apiBase/2/profile/${Links.handle(account)}/${if (media) "media" else "statuses"}".toHttpUrl().newBuilder()
            .addQueryParameter("count", count.coerceIn(1, 100).toString()).addQueryParameter("safe", "1")
        cursor?.let { url.addQueryParameter("cursor", it) }
        return PostParser.page(get(url.build().toString()))
    }
    fun search(query: String, cursor: String? = null): PostPage {
        require(query.isNotBlank()) { "Saisissez une recherche." }
        val url = "$apiBase/2/search".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).addQueryParameter("count", "30")
        cursor?.let { url.addQueryParameter("cursor", it) }
        return PostParser.page(get(url.build().toString()))
    }
    fun accounts(query: String): List<Profile> {
        val url = "$apiBase/2/typeahead".toHttpUrl().newBuilder().addQueryParameter("q", query.trim()).addQueryParameter("result_type", "users").build()
        return PostParser.accounts(get(url.toString()))
    }
    fun timeline(account: String, rssOnly: Boolean): List<Post> {
        val handle = Links.handle(account)
        if (rssOnly) return PostParser.rss(get("$rssBase/$handle/feed.xml?count=30&safe=1"), handle)
        return try { PostParser.api(get("$apiBase/2/profile/$handle/statuses?count=30&safe=1")) }
        catch (apiError: Exception) {
            try { PostParser.rss(get("$rssBase/$handle/feed.xml?count=30&safe=1"), handle) }
            catch (_: Exception) { throw apiError }
        }
    }
    fun rssFeed(account: String): List<Post> = PostParser.rss(get("$rssBase/${Links.handle(account)}/feed.xml?count=100&safe=1"), account)
    fun profile(account: String): Profile = PostParser.profile(get("$apiBase/2/profile/${Links.handle(account)}"))
    fun status(link: String): Post = PostParser.api(get("$apiBase/status/${Links.postId(link)}")).firstOrNull()
        ?: error("Publication inaccessible.")
}

data class PostPage(val posts: List<Post>, val next: String? = null)
