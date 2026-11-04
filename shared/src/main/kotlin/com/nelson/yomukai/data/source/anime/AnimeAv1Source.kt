package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.source.NoChaptersException
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.jsoup.Jsoup

/**
 * AnimeAV1 (SvelteKit). Pages are server-rendered and also embed their data as a JS object
 * literal in the kit.start(...) call, which is where episode lists and video embeds live.
 * Media ids are the anime slug; episode URLs are /media/{slug}/{number}.
 */
class AnimeAv1Source(private val network: HttpFetcher) : AnimeSource {
    override val id = "animeav1"
    override val name = "AnimeAV1"
    override val baseUrl = "https://animeav1.com"
    override val extraHosts = listOf("https://player.zilla-networks.com")
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(path: String) = network.getString("$baseUrl$path", mapOf("Referer" to "$baseUrl/"))

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        parseCatalog(html(catalogueRequest(page, values)))
    }

    /** Page 1 = latest episodes on the home page; later pages browse the catalogue by release. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseLatestEpisodes(html("/")).ifEmpty { parseCatalog(html("/catalogo?order=latest_released")) }
        else parseCatalog(html("/catalogo?order=latest_released&page=$page"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseCatalog(html("/catalogo?search=${SourceUtils.encode(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html("/media/${media.id}"), media.id)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html("/media/$mediaId"), mediaId).ifEmpty { throw NoChaptersException("Sin episodios disponibles todavía") }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parseVideoSources(network.getString(episodeUrl, mapOf("Referer" to "$baseUrl/"))))
            .ifEmpty { error("No se encontraron servidores para este episodio") }
    }

    companion object {
        private const val BASE = "https://animeav1.com"
        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(FilterOption("latest_released", "Recientes"), FilterOption("score", "Mejor valorados"), FilterOption("popular", "Populares"), FilterOption("title", "A-Z"))
        )
        private val GENRE = SelectFilter(
            FilterKeys.GENRE, "Género",
            listOf(
                "" to "Todos", "accion" to "Acción", "aventura" to "Aventura", "ciencia-ficcion" to "Ciencia ficción", "comedia" to "Comedia",
                "deportes" to "Deportes", "drama" to "Drama", "ecchi" to "Ecchi", "fantasia" to "Fantasía", "mecha" to "Mecha",
                "misterio" to "Misterio", "psicologico" to "Psicológico", "romance" to "Romance", "seinen" to "Seinen",
                "shounen" to "Shounen", "sobrenatural" to "Sobrenatural", "suspenso" to "Suspenso", "terror" to "Terror"
            ).map { FilterOption(it.first, it.second) }
        )
        val FILTERS: List<SourceFilter> = listOf(SORT, GENRE)

        fun catalogueRequest(page: Int, values: FilterValues): String = buildString {
            append("/catalogo?order=${values.single(SORT)}&page=${page.coerceAtLeast(1)}")
            values.single(GENRE).takeIf { it.isNotEmpty() }?.let { append("&genre=$it") }
        }

        private val EPISODE_PATH = Regex("""^/media/([^/]+)/(\d+(?:\.\d+)?)$""")
        private val SERIES_PATH = Regex("""^/media/([^/#?]+)$""")
        private val EMBED = Regex("""\{server:"([^"]+)",url:"([^"]+)"\}""")

        fun parseCatalog(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("article").mapNotNull { card ->
                val href = card.selectFirst("a[href^=/media/]")?.attr("href") ?: return@mapNotNull null
                val slug = SERIES_PATH.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                MediaItem(
                    id = slug,
                    title = card.selectFirst("h3")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = card.selectFirst("img")?.attr("src").orEmpty().replace("/thumbnails/", "/covers/"),
                    type = MediaType.ANIME,
                    status = card.selectFirst(".font-bold.text-xs, .text-xs")?.text().orEmpty(),
                    description = "",
                    sourceId = "animeav1"
                )
            }.distinctBy { it.id }

        fun parseLatestEpisodes(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("article").mapNotNull { card ->
                val href = card.selectFirst("a[href^=/media/]")?.attr("href") ?: return@mapNotNull null
                val match = EPISODE_PATH.find(href) ?: return@mapNotNull null
                val title = card.selectFirst("header")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = match.groupValues[1],
                    title = title,
                    coverUrl = card.selectFirst("img")?.attr("src").orEmpty().replace("/thumbnails/", "/covers/"),
                    type = MediaType.ANIME,
                    status = "Episodio ${match.groupValues[2]}",
                    description = "",
                    sourceId = "animeav1"
                )
            }.distinctBy { it.id }

        /** Pulls a quoted JS string value (`key:"..."`) out of the embedded page data. */
        private fun jsString(html: String, key: String): String? =
            Regex("""\b$key:("(?:[^"\\]|\\.)*")""").find(html)?.groupValues?.get(1)
                ?.let { runCatching { JsonParser.parseString(it).asString }.getOrNull() }

        fun parseDetails(html: String, slug: String): MediaItem {
            val doc = Jsoup.parse(html, BASE)
            val mediaId = Regex("""media:\{id:(\d+)""").find(html)?.groupValues?.get(1)
            val statusText = Regex("""En emisión|Finalizado|Próximamente""").find(doc.text())?.value.orEmpty()
            return MediaItem(
                id = slug,
                title = doc.selectFirst("h1")?.text()?.trim() ?: jsString(html, "title").orEmpty(),
                coverUrl = mediaId?.let { "https://cdn.animeav1.com/covers/$it.jpg" }
                    ?: doc.selectFirst("img[src*=/covers/]")?.attr("src").orEmpty(),
                type = MediaType.ANIME,
                status = statusText,
                description = jsString(html, "synopsis")?.let(SourceUtils::normalizeParagraphs)
                    ?: doc.selectFirst(".entry")?.text().orEmpty(),
                sourceId = "animeav1",
                genres = doc.select("a[href^=/catalogo?genre=]").map { it.text().trim() }.distinct()
            )
        }

        fun parseEpisodes(html: String, slug: String): List<Chapter> {
            val list = Regex("""episodes:\[(.*?)]""").find(html)?.groupValues?.get(1).orEmpty()
            val numbers = Regex("""number:(\d+(?:\.\d+)?)""").findAll(list).map { it.groupValues[1] }.toList()
            return numbers.map { n ->
                val url = "$BASE/media/$slug/$n"
                Chapter(
                    id = url,
                    mediaId = slug,
                    title = "Episodio $n",
                    chapterNumber = n.toFloatOrNull() ?: 0f,
                    url = url,
                    dateUploaded = ""
                )
            }
        }

        /** One server of an episode; the zilla-networks player page exposes its playlist directly at /m3u8/{id}. */
        fun embedSource(server: String, url: String, label: String): VideoSource =
            if ("zilla-networks.com/play/" in url) {
                VideoSource(
                    quality = "$label • HLS",
                    videoUrl = url.replace("/play/", "/m3u8/"),
                    serverName = server,
                    isDirect = true,
                    headers = mapOf("Referer" to "https://player.zilla-networks.com/")
                )
            } else {
                VideoSource(quality = label, videoUrl = url, serverName = server)
            }

        /** Embeds are grouped by language (SUB/DUB); only the "embeds" block is used, not downloads. */
        fun parseVideoSources(html: String): List<VideoSource> {
            val start = html.indexOf("embeds:{")
            if (start < 0) return emptyList()
            val end = html.indexOf("downloads:", start).takeIf { it > 0 } ?: (start + 8000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val sources = mutableListOf<VideoSource>()
            Regex("""(SUB|DUB|LAT):\[(.*?)]""").findAll(block).forEach { lang ->
                val label = if (lang.groupValues[1] == "SUB") "Sub español" else "Doblado"
                EMBED.findAll(lang.groupValues[2]).forEach { m -> sources += embedSource(m.groupValues[1], m.groupValues[2], label) }
            }
            return sources.sortedByDescending { it.isDirect }
        }
    }
}
