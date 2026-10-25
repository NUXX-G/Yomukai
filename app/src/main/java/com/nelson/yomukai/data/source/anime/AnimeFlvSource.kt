package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.NetworkHelper
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

/**
 * AnimeFLV. Server-rendered pages; episode numbers and video servers are embedded as JS
 * variables (`var episodes = [[n, id], ...]`, `var videos = {"SUB": [...]}`).
 * The site is behind Cloudflare and was returning origin errors (522) during development,
 * so it is marked experimental. Its servers are web embeds; supported hosts (ok.ru, YourUpload,
 * MP4Upload) are resolved to direct streams by [VideoExtractors], the rest open in the browser.
 * Media ids are the anime slug.
 */
class AnimeFlvSource(private val network: NetworkHelper) : AnimeSource {
    override val id = "animeflv"
    override val name = "AnimeFLV"
    override val baseUrl = "https://www3.animeflv.net"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val isExperimental = true
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(path: String) = network.getString("$baseUrl$path", mapOf("Referer" to "$baseUrl/"))

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseLatestEpisodes(html("/")) else parseBrowse(html("/browse?order=updated&page=$page"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseBrowse(html("/browse?q=${SourceUtils.encode(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val doc = Jsoup.parse(html("/anime/${media.id}"), baseUrl)
        media.copy(
            title = doc.selectFirst("h1.Title")?.text()?.trim()?.ifBlank { null } ?: media.title,
            coverUrl = doc.selectFirst(".AnimeCover .Image img")?.absUrl("src")?.ifBlank { null } ?: media.coverUrl,
            description = doc.selectFirst(".Description p")?.text().orEmpty(),
            status = doc.selectFirst("p.AnmStus")?.text().orEmpty(),
            genres = doc.select("nav.Nvgnrs a").map { it.text().trim() }
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html("/anime/$mediaId"), mediaId).ifEmpty { error("Sin episodios disponibles") }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parseVideoSources(network.getString(episodeUrl, mapOf("Referer" to "$baseUrl/"))))
            .ifEmpty { error("No se encontraron servidores para este episodio") }
    }

    companion object {
        private const val BASE = "https://www3.animeflv.net"

        fun parseLatestEpisodes(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("ul.ListEpisodios > li").mapNotNull { li ->
                val href = li.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                // /ver/{slug}-{episode}
                val slug = href.substringAfterLast("/").substringBeforeLast("-").ifBlank { return@mapNotNull null }
                MediaItem(
                    id = slug,
                    title = li.selectFirst(".Title")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = li.selectFirst(".Image img")?.absUrl("src").orEmpty(),
                    type = MediaType.ANIME,
                    status = li.selectFirst(".Capi")?.text().orEmpty(),
                    description = "",
                    sourceId = "animeflv"
                )
            }.distinctBy { it.id }

        fun parseBrowse(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("ul.ListAnimes article.Anime").mapNotNull { card ->
                val href = card.selectFirst("a[href*=/anime/]")?.attr("href") ?: return@mapNotNull null
                MediaItem(
                    id = href.substringAfterLast("/"),
                    title = card.selectFirst(".Title")?.text()?.trim() ?: return@mapNotNull null,
                    coverUrl = card.selectFirst(".Image img")?.absUrl("src").orEmpty(),
                    type = MediaType.ANIME,
                    status = card.selectFirst(".Type")?.text().orEmpty(),
                    description = card.select(".Description p").text(),
                    sourceId = "animeflv"
                )
            }.distinctBy { it.id }

        fun parseEpisodes(html: String, slug: String): List<Chapter> {
            val raw = Regex("""var episodes = (\[.*?]);""").find(html)?.groupValues?.get(1) ?: return emptyList()
            val entries = runCatching { parseJson(raw).asJsonArray.toList() }.getOrDefault(emptyList())
            return entries.mapNotNull { entry ->
                val number = entry.takeIf { it.isJsonArray }?.asJsonArray?.get(0)?.asDouble ?: return@mapNotNull null
                val label = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
                val url = "$BASE/ver/$slug-$label"
                Chapter(
                    id = url,
                    mediaId = slug,
                    title = "Episodio $label",
                    chapterNumber = number.toFloat(),
                    url = url,
                    dateUploaded = ""
                )
            }
        }

        fun parseVideoSources(html: String): List<VideoSource> {
            val raw = Regex("""var videos = (\{.*?\});""").find(html)?.groupValues?.get(1) ?: return emptyList()
            val root = runCatching { parseJson(raw).asJsonObject }.getOrNull() ?: return emptyList()
            return root.entrySet().flatMap { (lang, servers) ->
                val label = if (lang == "SUB") "Sub español" else "Latino"
                servers.takeIf { it.isJsonArray }?.asJsonArray.objects().mapNotNull { s ->
                    val url = (s.str("code") ?: s.str("url"))?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    VideoSource(quality = label, videoUrl = url, serverName = s.str("title") ?: s.str("server") ?: "Servidor")
                }
            }
        }
    }
}
