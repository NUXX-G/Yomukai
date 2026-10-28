package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * LectorTMO (lectortmo.vip), a WordPress site ("manganexus" theme) protected by Wordfence and
 * Cloudflare. The home page cards were verified against a recent snapshot; series and reader
 * pages could not be fetched during development, so they are parsed structurally (links nested
 * under the series URL, the biggest group of same-folder images) instead of by exact classes.
 */
class LectorTmoSource(private val network: NetworkHelper) : MangaSource {
    override val id = "lectortmo"
    override val name = "LectorTMO"
    override val baseUrl = "https://lectortmo.vip"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)
    override val supportsLatestPaging = false

    private suspend fun document(url: String): Document =
        Jsoup.parse(network.getString(url, mapOf("Referer" to "$baseUrl/")), url)

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseCards(document("$baseUrl/").outerHtml())
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseCards(document("$baseUrl/?s=${SourceUtils.encode(query)}&post_type=wp-manga").outerHtml())
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val doc = document(media.id)
        fun meta(property: String) = doc.selectFirst("meta[property=$property]")?.attr("content").orEmpty()
        val statusText = doc.select(".post-status, .status, .manga-status, span, div")
            .asSequence().map { it.ownText().trim() }
            .firstOrNull { it.matches(Regex("(?i)(en emisi[oó]n|publicándose|en curso|finalizado|completado|pausado|cancelado)")) }
        media.copy(
            title = doc.selectFirst("h1.font-extrabold, h1")?.text()?.trim()?.ifBlank { null } ?: meta("og:title").substringBefore(" – ").ifBlank { media.title },
            coverUrl = media.coverUrl.ifBlank { meta("og:image") },
            description = doc.selectFirst(".summary__content, .description-summary, .manga-excerpt, [itemprop=description], .entry-content")
                ?.text()?.trim().orEmpty().ifBlank { meta("og:description") },
            genres = doc.select("a.rounded-full[href*=/manga-genre/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = statusText.orEmpty()
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        var chapters = parseChapterLinks(document(mediaId), mediaId)
        if (chapters.isEmpty()) {
            // Madara-derived themes load the list through this endpoint.
            val html = network.postString(mediaId.trimEnd('/') + "/ajax/chapters/", mapOf("Referer" to mediaId))
            chapters = parseChapterLinks(Jsoup.parse(html, mediaId), mediaId)
        }
        chapters.ifEmpty { error("No se encontraron capítulos") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(document(chapterUrl)).ifEmpty { error("No se encontraron imágenes en el capítulo") }
    }

    companion object {
        private val CSS_URL = Regex("""url\(['"]?([^'")]+)['"]?\)""")
        private val SERIES_PATH = Regex("""^https?://[^/]+/manga/[^/]+/?$""")

        fun parseCards(html: String): List<MediaItem> {
            val doc = Jsoup.parse(html, "https://lectortmo.vip/")
            val links = doc.select("a[href*=/manga/]").filter { SERIES_PATH.matches(it.absUrl("href")) }
            return links.mapNotNull { link ->
                val url = link.absUrl("href").let { if (it.endsWith("/")) it else "$it/" }
                val card = link.closest(".manga-card-wrapper, article, .c-tabs-item__content, .page-item-detail") ?: link
                val title = (card.selectFirst(".manga-title, h3, h2, .post-title")?.text() ?: link.text()).trim()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = coverOf(card),
                    type = MediaType.MANGA,
                    status = "",
                    description = "",
                    sourceId = "lectortmo"
                )
            }.distinctBy { it.id }
        }

        private fun coverOf(card: Element): String {
            card.selectFirst("img")?.let { img -> SourceUtils.imageUrl(img).takeIf { it.isNotBlank() }?.let { return it } }
            val styled = (listOf(card) + card.select("[style]")).firstOrNull { "url(" in it.attr("style") }
            return styled?.let { CSS_URL.find(it.attr("style"))?.groupValues?.get(1) }.orEmpty()
        }

        fun parseChapterLinks(doc: Document, seriesUrl: String): List<Chapter> {
            // Current theme: <a class="chapter-card" href=".../manga-chapter/{slug}-cap-X/"> with number and date spans.
            val cards = doc.select("a.chapter-card[href]")
            val total = cards.size
            return cards.mapIndexed { idx, a ->
                val url = a.absUrl("href")
                val title = a.select("span").map { it.text().trim() }
                    .firstOrNull { it.startsWith("Cap", ignoreCase = true) }
                    ?: a.text().trim().ifBlank { url.trimEnd('/').substringAfterLast('/').replace('-', ' ') }
                Chapter(
                    id = url,
                    mediaId = seriesUrl,
                    title = title,
                    chapterNumber = SourceUtils.parseChapterNumber(title, (total - idx).toFloat()),
                    url = url,
                    dateUploaded = a.select("span").map { it.text().trim() }
                        .firstOrNull { Regex("""\d{2}/\d{2}/\d{4}""").matches(it) }.orEmpty()
                )
            }.distinctBy { it.id }
        }

        fun parsePages(doc: Document): List<String> =
            doc.select("#reader-strip img")
                .map { SourceUtils.imageUrl(it) }
                .filter { it.isNotBlank() }
                .distinct()
    }
}
