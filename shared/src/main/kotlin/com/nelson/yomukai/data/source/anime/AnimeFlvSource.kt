package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.jsoup.Jsoup
import java.util.Base64

/**
 * AnimeFLV (animeflv.or.at), a WordPress theme. Each title page embeds its episodes as JSON, and an
 * episode page lists its players as Base64 in `data-src`. Media ids are the absolute `/anime/{slug}/`
 * URL; episode pages live under dated URLs, so they can only be reached through that JSON.
 */
class AnimeFlvSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "animeflv"
    override val name = "AnimeFLV"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    /** Page 1 = latest episodes on the home page; later pages browse the catalogue. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseLatestEpisodes(html("$BASE/")).ifEmpty { parseCatalog(html("$BASE/anime/")) }
        else parseCatalog(html("$BASE/anime/page/$page/"))
    }

    override suspend fun browse(page: Int, values: Map<String, Set<String>>): Result<List<MediaItem>> = runCatching {
        parseCatalog(html(if (page <= 1) "$BASE/anime/" else "$BASE/anime/page/$page/"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseCatalog(html("$BASE/?s=${SourceUtils.encode(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(media.id), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html(mediaId), mediaId).ifEmpty { error("Sin episodios disponibles todavía") }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parseVideoSources(html(episodeUrl)))
            .ifEmpty { error("No se encontraron servidores para este episodio") }
    }

    companion object {
        private const val BASE = "https://animeflv.or.at"
        private val EPISODE_PATH = Regex("""/\d{4}/\d{2}/\d{2}/(.+)-episodio-(\d+(?:-\d+)?)/?$""")
        private val TRAILING_EPISODE = Regex("""\s+Episodio\s+\d+(?:[.-]\d+)?\s*$""", RegexOption.IGNORE_CASE)

        /** Lists show WordPress's cropped copies ("4482-260x200.jpg", landscape); the poster is the file without the size. */
        fun fullCover(url: String): String = url.replace(SIZED_COPY, "$1")
        private val SIZED_COPY = Regex("""-\d{2,4}x\d{2,4}(\.(?:jpe?g|png|webp))$""")

        fun parseCatalog(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("a.thumbnail-link[href*=/anime/]").mapNotNull { card ->
                val url = card.absUrl("href").ifBlank { return@mapNotNull null }
                val title = card.selectFirst(".entry-title")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = fullCover(card.selectFirst("img")?.absUrl("src").orEmpty()),
                    type = MediaType.ANIME,
                    status = "",
                    description = "",
                    sourceId = "animeflv"
                )
            }.distinctBy { it.id }

        /** Home cards link to an episode; the title page is the same slug under /anime/. */
        fun parseLatestEpisodes(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select(".List-Episodes .Episode a[href]").mapNotNull { card ->
                val match = EPISODE_PATH.find(card.absUrl("href")) ?: return@mapNotNull null
                val title = card.selectFirst(".Title")?.text()?.replace(TRAILING_EPISODE, "")?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = "$BASE/anime/${match.groupValues[1]}/",
                    title = title,
                    coverUrl = fullCover(card.selectFirst("img")?.absUrl("src").orEmpty()),
                    type = MediaType.ANIME,
                    status = "Episodio ${match.groupValues[2].replace('-', '.')}",
                    description = "",
                    sourceId = "animeflv"
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, media.id)
            return media.copy(
                title = doc.selectFirst("h1.anime-title")?.text()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = doc.selectFirst(".anime-poster img")?.absUrl("src")?.ifBlank { null } ?: media.coverUrl,
                description = doc.selectFirst(".anime-synopsis")?.let { box ->
                    box.select("h3").remove()
                    SourceUtils.htmlToText(box.html())
                }.orEmpty(),
                genres = doc.select(".anime-genres .genre-tag").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
                status = ""
            )
        }

        fun parseEpisodes(html: String, mediaId: String): List<Chapter> {
            val json = Jsoup.parse(html).selectFirst("script.animeflv-episodes-data")?.data().orEmpty()
            if (json.isBlank()) return emptyList()
            return parseJson(json).asJsonArray.objects().mapNotNull { episode ->
                val url = episode.str("permalink") ?: return@mapNotNull null
                val number = episode.str("number").orEmpty()
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = "Episodio $number",
                    chapterNumber = number.toFloatOrNull() ?: 0f,
                    url = url,
                    dateUploaded = ""
                )
            }
        }

        fun parseVideoSources(html: String): List<VideoSource> =
            Jsoup.parse(html).select("button.server_btn[data-src]").mapNotNull { button ->
                val url = runCatching { String(Base64.getDecoder().decode(button.attr("data-src").trim())) }.getOrNull()
                    ?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                val server = button.parent()?.selectFirst(".tooltip-text")?.text()?.trim().orEmpty().ifBlank { "Servidor" }
                AnimeAv1Source.embedSource(server, url, "Sub español")
            }.distinctBy { it.videoUrl }
    }
}
