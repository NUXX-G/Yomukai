package com.nelson.yomukai.data.source.anime

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Base64

/**
 * Anitaku (anitaku.io): anime with English subtitles, a WordPress site on the "AnimeStream" theme.
 * The catalogue is paged HTML (`/series/?order=update`), search is the theme's live-search call,
 * a series page lists its episodes, and an episode page offers its players as Base64-encoded iframes
 * (Blogger video, turned into a direct MP4 by [VideoExtractors]). Ids are absolute URLs.
 */
class AnitakuSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "anitaku"
    override val name = "Anitaku"
    override val baseUrl = BASE
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseCatalog(html("$BASE/series/?order=update&page=$page"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val body = network.postString(
            "$BASE/wp-admin/admin-ajax.php?action=ts_ac_do_search&ts_ac_query=${URLEncoder.encode(query.trim(), "UTF-8")}",
            mapOf("Referer" to "$BASE/", "X-Requested-With" to "XMLHttpRequest")
        )
        parseSearch(body)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(media.id), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html(mediaId), mediaId).ifEmpty { throw NoChaptersException("Sin episodios disponibles todavía") }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parseServers(html(episodeUrl))).ifEmpty { error("No se encontraron servidores para este episodio") }
    }

    companion object {
        private const val BASE = "https://anitaku.io"

        fun parseCatalog(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select("article.bs a[href*=/series/]").mapNotNull { a ->
                val url = a.absUrl("href").ifBlank { return@mapNotNull null }
                val title = a.attr("title").ifBlank { a.selectFirst("h2")?.text().orEmpty() }.trim()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(url, title, AnimeFlvSource.fullCover(a.selectFirst("img")?.absUrl("src").orEmpty()), MediaType.ANIME, "", "", "anitaku")
            }.distinctBy { it.id }

        /** The live search answers JSON: `{"series":[{"all":[{post_title, post_link, post_image...}]}]}`. */
        fun parseSearch(json: String): List<MediaItem> =
            runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("series") }.getOrNull()
                ?.flatMap { group -> group.asJsonObject.getAsJsonArray("all")?.toList().orEmpty() }
                ?.mapNotNull { e ->
                    val o = e.asJsonObject
                    val url = o.get("post_link")?.asString?.takeIf { it.startsWith(BASE) } ?: return@mapNotNull null
                    MediaItem(
                        url, Jsoup.parse(o.get("post_title")?.asString.orEmpty()).text(), o.get("post_image")?.asString.orEmpty(),
                        MediaType.ANIME, "", "", "anitaku",
                        genres = o.get("post_genres")?.asString?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    )
                }.orEmpty()

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, BASE)
            val info = doc.select(".spe span").associate { span ->
                span.selectFirst("b")?.text()?.trim()?.removeSuffix(":").orEmpty() to span.ownText().trim().ifBlank { span.select("a").text() }
            }
            val description = doc.select(".entry-content p").map { it.text() }
                .filterNot { it.startsWith("(Source") }.joinToString("\n\n")
            return media.copy(
                title = doc.selectFirst("h1.entry-title")?.text()?.trim().orEmpty().ifBlank { media.title },
                coverUrl = doc.selectFirst(".thumb img, .bigcontent img")?.absUrl("src").orEmpty().ifBlank { media.coverUrl },
                description = description.ifBlank { media.description },
                status = when (info["Status"]?.lowercase()) {
                    "ongoing" -> "En emisión"; "completed" -> "Finalizado"; else -> info["Status"].orEmpty()
                },
                author = info["Studio"].orEmpty(),
                genres = doc.select(".genxed a").map { it.text().trim() }.filter { it.isNotEmpty() }.ifEmpty { media.genres },
                type = MediaType.ANIME,
                sourceId = "anitaku"
            )
        }

        fun parseEpisodes(html: String, mediaId: String): List<Chapter> =
            Jsoup.parse(html, BASE).select(".eplister li a").mapNotNull { a ->
                val url = a.absUrl("href").ifBlank { return@mapNotNull null }
                val number = a.selectFirst(".epl-num")?.text()?.trim()?.toFloatOrNull() ?: return@mapNotNull null
                Chapter(url, mediaId, "Episodio ${number.toString().removeSuffix(".0")}", number, url, a.selectFirst(".epl-date")?.text()?.trim().orEmpty())
            }.distinctBy { it.id }

        /** Each `<option value>` is an iframe in Base64; the page's own iframe is the default player. */
        fun parseServers(html: String): List<VideoSource> {
            val doc = Jsoup.parse(html, BASE)
            val fromOptions = doc.select("select.mirror option[value]").mapNotNull { option ->
                val decoded = runCatching { String(Base64.getDecoder().decode(option.attr("value").trim())) }.getOrNull() ?: return@mapNotNull null
                val src = Jsoup.parse(decoded).selectFirst("iframe")?.attr("src")?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
                src to option.text().trim()
            }
            val embedded = doc.select(".player-embed iframe[src], #pembed iframe[src], .video-content iframe[src]")
                .map { it.attr("src") to "" }.filter { it.first.startsWith("http") }
            return (fromOptions + embedded).distinctBy { it.first }.mapIndexed { i, (src, label) ->
                val host = runCatching { java.net.URI(src).host.removePrefix("www.") }.getOrDefault("")
                val server = label.ifBlank { if ("blogger.com" in host) "Blogger" else host.substringBefore('.').replaceFirstChar { it.uppercase() } }
                VideoSource(quality = if (i == 0) "Principal" else "Servidor ${i + 1}", videoUrl = src, serverName = server, isDirect = false)
            }
        }
    }
}
