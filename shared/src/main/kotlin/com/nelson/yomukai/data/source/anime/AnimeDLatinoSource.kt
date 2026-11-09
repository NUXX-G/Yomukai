package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

/**
 * AnimeDLatino (animedlatino.com): anime dubbed into Latin American Spanish (and subtitled), plain HTML. The search
 * with no words is the whole catalogue, paged; a series page lists its episodes ("1x6": season and episode); an
 * episode lists its servers, each a small page with the host's player. The site's own hentai and adult lists mark
 * that content (hidden unless the user shows it). A title is its slug.
 */
class AnimeDLatinoSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "animedlatino"
    override val name = "AnimeDLatino"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true
    override val adultLists = ADULT.size

    private val extractors = VideoExtractors(network)

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    /** Page 1: the newest episodes; then the whole catalogue (what the index walks the first time). */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseCards(html("$BASE/"), id) else parseCards(html("$BASE/buscar?q=&pagina=${page - 1}"), id)
    }

    override suspend fun browse(page: Int, values: com.nelson.yomukai.data.source.FilterValues): Result<List<MediaItem>> =
        runCatching { parseCards(html("$BASE/buscar?q=&pagina=${page.coerceAtLeast(1)}"), id) }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseCards(html("$BASE/buscar?q=${SourceUtils.encode(query)}"), id)
    }

    override suspend fun adultTitles(list: Int, page: Int): Result<List<MediaItem>> = runCatching {
        val path = ADULT.getOrNull(list) ?: return@runCatching emptyList()
        parseCards(html("$BASE/$path?pagina=${page.coerceAtLeast(1)}"), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html("$BASE/serie/${media.id}"), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html("$BASE/serie/$mediaId"), mediaId).ifEmpty { throw NoChaptersException() }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        val servers = parseServers(html(episodeUrl))
        // Each server is a small page with the host's player inside: read them all at once.
        val hosts = coroutineScope {
            servers.take(MAX_SERVERS).map { (name, url) ->
                async { runCatching { network.getString(url, mapOf("Referer" to episodeUrl)) }.getOrNull()?.let(::playerIn)?.let { VideoSource(name, it, name) } }
            }.awaitAll().filterNotNull()
        }
        extractors.resolveAll(hosts.distinctBy { it.videoUrl })
    }

    companion object {
        private const val BASE = "https://animedlatino.com"
        private const val MAX_SERVERS = 12
        private val ADULT = listOf("hentai", "adultos")
        private val SERIES = Regex("""^https://animedlatino\.com/(?:serie|ver)/([a-z0-9-]+)""")
        private val SEASON_EPISODE = Regex("""(\d+)x(\d+(?:\.\d+)?)""")
        private val IFRAME = Regex("""<iframe[^>]+src="(https://[^"]+)"""", RegexOption.IGNORE_CASE)

        /** Cards of any list (home, catalogue, search, genre): one per series. */
        fun parseCards(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select("article.tarjeta").mapNotNull { card ->
                val link = card.selectFirst("a.tarjeta__enlace") ?: return@mapNotNull null
                val slug = SERIES.find(link.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val title = card.selectFirst(".tarjeta__titulo")?.text()?.trim()?.ifBlank { null } ?: return@mapNotNull null
                val cover = card.selectFirst("img.tarjeta__imagen")?.absUrl("src").orEmpty()
                MediaItem(slug, title, cover, MediaType.ANIME, "", "", sourceId)
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, "$BASE/serie/${media.id}")
            fun meta(label: String) = doc.select(".ficha__meta li").firstOrNull { it.selectFirst("strong")?.text()?.startsWith(label) == true }
                ?.ownText()?.trim().orEmpty()
            return media.copy(
                title = doc.selectFirst(".ficha h1")?.text()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = doc.selectFirst("img.ficha__portada")?.absUrl("src")?.ifBlank { null } ?: media.coverUrl,
                status = meta("Estado").ifBlank { media.status },
                description = doc.selectFirst(".ficha__sinopsis, .sinopsis")?.text()?.trim()?.ifBlank { null }
                    ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim().orEmpty().ifBlank { media.description },
                genres = doc.select("a[href*=/genero/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { media.genres }
            )
        }

        /** Episodes of every season; with more than one season, the season is in the name. */
        fun parseEpisodes(html: String, slug: String): List<Chapter> {
            val doc = Jsoup.parse(html, "$BASE/serie/$slug")
            val rows = doc.select("li[data-capitulo]")
            val seasons = rows.mapNotNull { SEASON_EPISODE.find(it.attr("data-capitulo"))?.groupValues?.get(1) }.distinct().size
            return rows.mapNotNull { li ->
                val url = li.selectFirst("a[href]")?.absUrl("href")?.takeIf { it.startsWith("$BASE/ver/") } ?: return@mapNotNull null
                val (season, episode) = SEASON_EPISODE.find(li.attr("data-capitulo"))?.destructured ?: return@mapNotNull null
                val number = episode.toFloatOrNull() ?: return@mapNotNull null
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                Chapter(url, slug, if (seasons > 1) "T$season · Capítulo $label" else "Capítulo $label", number, url, "")
            }.distinctBy { it.id }
        }

        /** The servers of an episode page: name and the address of its player page. */
        fun parseServers(html: String): List<Pair<String, String>> =
            Jsoup.parse(html, "$BASE/").select("[data-url*=/reproducir]").mapNotNull { el ->
                val url = el.absUrl("data-url").takeIf { it.startsWith("$BASE/reproducir") } ?: return@mapNotNull null
                el.attr("data-servidor").ifBlank { "Servidor" }.replace(Regex("""\s*\(\d+\)$"""), "") to url
            }.distinctBy { it.second }

        /** The host's player inside a server page. */
        fun playerIn(html: String): String? = IFRAME.find(html)?.groupValues?.get(1)
    }
}
