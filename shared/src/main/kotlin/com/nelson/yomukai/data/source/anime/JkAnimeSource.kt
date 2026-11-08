package com.nelson.yomukai.data.source.anime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.util.Base64

/**
 * JKAnime (jkanime.net): a big Spanish anime catalogue. The home page lists the newest episodes; the directory
 * (169 pages) carries its titles as JSON in the page; a title's episodes come from a POST that needs the page's
 * session cookie and CSRF token. An episode has the site's own player (a direct HLS stream) and the usual hosts
 * (VOE, Mp4upload, Streamwish...), whose addresses are Base64 in the page. A title is its slug.
 */
class JkAnimeSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "jkanime"
    override val name = "JKAnime"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("cdn.jkdesa.com")

    private val extractors = VideoExtractors(network)

    /** The token belongs to the session cookie of the page it came from: one title's episodes at a time. */
    private val session = Mutex()

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    /** Page 1: the newest episodes; then the directory, page by page (what the index walks the first time). */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseHome(html("$BASE/"), id) else parseDirectory(html("$BASE/directorio?p=${page - 1}"), id)
    }

    override suspend fun browse(page: Int, values: com.nelson.yomukai.data.source.FilterValues): Result<List<MediaItem>> =
        runCatching { parseDirectory(html("$BASE/directorio?p=${page.coerceAtLeast(1)}"), id) }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseSearch(html("$BASE/buscar/${SourceUtils.encode(query).replace("+", "%20")}/"), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(pageOf(media.id)), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        session.withLock {
            val page = html(pageOf(mediaId))
            val animeId = ANIME_ID.find(page)?.groupValues?.get(1) ?: throw NoChaptersException()
            val token = TOKEN.find(page)?.groupValues?.get(1).orEmpty()
            val headers = mapOf("X-CSRF-TOKEN" to token, "X-Requested-With" to "XMLHttpRequest", "Referer" to pageOf(mediaId))
            val all = mutableListOf<Chapter>()
            var n = 1
            do {
                val json = JsonParser.parseString(network.postString("$BASE/ajax/episodes/$animeId/$n", headers)).asJsonObject
                all += parseEpisodes(json, mediaId)
                val last = json.get("last_page")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
                n++
            } while (n <= last && n <= MAX_EPISODE_PAGES)
            all.ifEmpty { throw NoChaptersException() }.distinctBy { it.id }
        }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        val page = html(episodeUrl)
        // The site's own player first (a direct HLS stream), then every host it lists.
        val own = parsePlayers(page).mapNotNull { player ->
            runCatching { network.getString(player, mapOf("Referer" to episodeUrl)) }.getOrNull()?.let(::streamIn)
                ?.let { VideoSource("JKAnime • HLS", it, "JKAnime", isDirect = true, headers = mapOf("Referer" to "$BASE/")) }
        }.distinctBy { it.videoUrl }.take(1)
        own + extractors.resolveAll(parseServers(page))
    }

    companion object {
        private const val BASE = "https://jkanime.net"
        private const val MAX_EPISODE_PAGES = 120
        private const val MIN_VIDEO_ID = 4
        private val EMPTY_PATHS = setOf("embed", "e", "v", "video", "player")
        private val ANIME_ID = Regex("""data-anime="(\d+)"""")
        private val TOKEN = Regex("""name="csrf-token"\s+content="([^"]+)"""")
        private val DIRECTORY = Regex("""var animes = (\{.*?\});\s*\n""", RegexOption.DOT_MATCHES_ALL)
        private val SERVERS = Regex("""var servers = (\[.*?\]);""")
        private val PLAYER = Regex("""video\[\d+] = '<iframe[^>]*src="(https://jkanime\.net/jkplayer/[^"]+)"""")
        private val STREAM = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val EPISODE_LINK = Regex("""^https://jkanime\.net/([a-z0-9-]+)/(\d+)/?$""")
        private val SLUG = Regex("""^[a-z0-9-]+$""")

        fun pageOf(slug: String) = "$BASE/$slug/"

        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        private fun status(estado: String) = when (estado.lowercase()) {
            "en emision", "en emisión", "currently" -> "En emisión"
            "concluido", "finished" -> "Finalizado"
            "en espera", "standby" -> "En espera"
            else -> estado
        }

        /** The newest episodes on the home page, as titles (one per title). */
        fun parseHome(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select("div.card > a[href]").mapNotNull { a ->
                val slug = EPISODE_LINK.find(a.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val title = a.selectFirst(".card-title")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val cover = a.selectFirst("img")?.let { it.attr("data-animepic").ifBlank { it.attr("src") } }.orEmpty()
                MediaItem(slug, title, cover, MediaType.ANIME, "En emisión", "", sourceId)
            }.distinctBy { it.id }

        /** A directory page: its titles come as JSON inside the page. */
        fun parseDirectory(html: String, sourceId: String): List<MediaItem> {
            val json = DIRECTORY.find(html)?.groupValues?.get(1) ?: return emptyList()
            val data = JsonParser.parseString(json).asJsonObject.getAsJsonArray("data") ?: return emptyList()
            return data.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val slug = o.str("slug").takeIf { SLUG.matches(it) } ?: return@mapNotNull null
                MediaItem(
                    slug, o.str("title").trim().ifBlank { return@mapNotNull null }, o.str("image"), MediaType.ANIME,
                    status(o.str("estado")), o.str("synopsis").trim(), sourceId
                )
            }
        }

        fun parseSearch(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select(".anime__item").mapNotNull { card ->
                val link = card.selectFirst("h5 a[href]") ?: return@mapNotNull null
                val slug = link.absUrl("href").removePrefix("$BASE/").trimEnd('/').takeIf { SLUG.matches(it) } ?: return@mapNotNull null
                MediaItem(
                    slug, link.text().trim().ifBlank { return@mapNotNull null }, card.selectFirst("[data-setbg]")?.attr("data-setbg").orEmpty(),
                    MediaType.ANIME, status(card.selectFirst(".anime__item__text li")?.text()?.trim().orEmpty()), "", sourceId
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, pageOf(media.id))
            val title = doc.selectFirst(".anime__details__title h3, .anime_info h3")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()?.ifBlank { null } ?: media.title
            val synopsis = doc.selectFirst("p.scroll, .sinopsis, .anime__details__text p")?.text()?.trim()?.ifBlank { null }
                ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty()
            val genres = doc.select("a[href*=/genero/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
            return media.copy(
                title = title, description = synopsis.ifBlank { media.description },
                coverUrl = media.coverUrl.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() },
                genres = genres.ifEmpty { media.genres }
            )
        }

        fun parseEpisodes(json: JsonObject, slug: String): List<Chapter> =
            json.getAsJsonArray("data")?.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val number = o.str("number").toFloatOrNull() ?: return@mapNotNull null
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                val url = "$BASE/$slug/$label/"
                Chapter(url, slug, "Episodio $label", number, url, o.str("timestamp").take(10))
            }.orEmpty()

        /** The site's own players of an episode page. */
        fun parsePlayers(html: String): List<String> = PLAYER.findAll(html).map { it.groupValues[1] }.toList()

        /** The direct stream inside one of the site's players. */
        fun streamIn(playerHtml: String): String? = STREAM.find(playerHtml)?.value

        /** Every host listed for the episode (their addresses are Base64), for the extractors or to play embedded. */
        fun parseServers(html: String): List<VideoSource> {
            val json = SERVERS.find(html)?.groupValues?.get(1) ?: return emptyList()
            return JsonParser.parseString(json).asJsonArray.mapNotNull { e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val url = runCatching { String(Base64.getMimeDecoder().decode(o.str("remote"))).trim() }.getOrNull()
                    // Some entries have no video id ("https://host/embed/"): nothing to play.
                    ?.takeIf { url -> url.startsWith("https://") && url.trimEnd('/').substringAfterLast('/').let { it.length >= MIN_VIDEO_ID && it !in EMPTY_PATHS } }
                    ?: return@mapNotNull null
                val name = o.str("server").ifBlank { "Servidor" }
                VideoSource(name, url, name)
            }
        }
    }
}
