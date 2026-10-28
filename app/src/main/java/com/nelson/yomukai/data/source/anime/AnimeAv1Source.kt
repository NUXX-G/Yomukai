package com.nelson.yomukai.data.source.anime

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.AnimeSource
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
class AnimeAv1Source(private val network: NetworkHelper) : AnimeSource {
    override val id = "animeav1"
    override val name = "AnimeAV1"
    override val baseUrl = "https://animeav1.com"
    override val extraHosts = listOf("https://player.zilla-networks.com")
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(path: String) = network.getString("$baseUrl$path", mapOf("Referer" to "$baseUrl/"))

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
        parseEpisodes(html("/media/$mediaId"), mediaId).ifEmpty { error("Sin episodios disponibles todavía") }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parseVideoSources(network.getString(episodeUrl, mapOf("Referer" to "$baseUrl/"))))
            .ifEmpty { error("No se encontraron servidores para este episodio") }
    }

    companion object {
        private const val BASE = "https://animeav1.com"
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
                    coverUrl = card.selectFirst("img")?.attr("src").orEmpty(),
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
                    coverUrl = card.selectFirst("img")?.attr("src").orEmpty(),
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

        /** Embeds are grouped by language (SUB/DUB); only the "embeds" block is used, not downloads. */
        fun parseVideoSources(html: String): List<VideoSource> {
            val start = html.indexOf("embeds:{")
            if (start < 0) return emptyList()
            val end = html.indexOf("downloads:", start).takeIf { it > 0 } ?: (start + 8000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val sources = mutableListOf<VideoSource>()
            Regex("""(SUB|DUB|LAT):\[(.*?)]""").findAll(block).forEach { lang ->
                val label = if (lang.groupValues[1] == "SUB") "Sub español" else "Doblado"
                EMBED.findAll(lang.groupValues[2]).forEach { m ->
                    val server = m.groupValues[1]
                    val url = m.groupValues[2]
                    if ("zilla-networks.com/play/" in url) {
                        // The HLS player page exposes the playlist directly at /m3u8/{id}.
                        sources += VideoSource(
                            quality = "$label • HLS",
                            videoUrl = url.replace("/play/", "/m3u8/"),
                            serverName = server,
                            isDirect = true,
                            headers = mapOf("Referer" to "https://player.zilla-networks.com/")
                        )
                    } else {
                        sources += VideoSource(quality = label, videoUrl = url, serverName = server)
                    }
                }
            }
            return sources.sortedByDescending { it.isDirect }
        }
    }
}
