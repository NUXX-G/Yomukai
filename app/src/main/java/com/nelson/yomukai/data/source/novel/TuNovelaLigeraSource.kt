package com.nelson.yomukai.data.source.novel

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup

/**
 * WordPress "Madara" site. Chapter lists come from the `/ajax/chapters/` endpoint and many
 * old chapters are genuinely empty on the site itself. Links carry a `?lcp_page0=1` suffix
 * which must be stripped, otherwise the AJAX URL built from the media id is invalid.
 * The WebView fallback in [NetworkHelper] handles Cloudflare if it ever kicks in.
 */
class TuNovelaLigeraSource(private val network: NetworkHelper) : NovelSource {
    override val id = "tunovelaligera"
    override val name = "TuNovelaLigera"
    override val baseUrl = "https://tunovelaligera.com"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.NOVEL)
    override val supportsLatestPaging = true

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$baseUrl/"))

    override val filters: List<SourceFilter> = listOf(SORT)

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        parseItems(html(catalogueUrl(page, values)), ".page-item-detail")
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val url = if (page <= 1) baseUrl else "$baseUrl/novelas/page/$page/?m_orderby=latest"
        parseItems(html(url), ".page-item-detail")
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseItems(html("$baseUrl/?s=${SourceUtils.encode(query)}&post_type=wp-manga"), ".c-tabs-item__content")
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(media.id), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val html = network.postString(mediaId.trimEnd('/') + "/ajax/chapters/", mapOf("Referer" to mediaId))
        parseChapters(html, mediaId).ifEmpty { error("Sin capítulos disponibles") }
    }

    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching {
        parseChapterText(html(chapterUrl))
    }

    companion object {
        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(FilterOption("latest", "Recientes"), FilterOption("views", "Más vistas"), FilterOption("alphabet", "A-Z"))
        )

        fun catalogueUrl(page: Int, values: FilterValues): String {
            val path = if (page <= 1) "/novelas/" else "/novelas/page/$page/"
            return "https://tunovelaligera.com$path?m_orderby=${values.single(SORT)}"
        }

        private fun clean(url: String) = url.substringBefore('?')

        fun parseItems(html: String, selector: String): List<MediaItem> =
            Jsoup.parse(html, "https://tunovelaligera.com").select(selector).mapNotNull { elem ->
                val link = elem.selectFirst(".post-title a, h3 a, h4 a, a[href]") ?: return@mapNotNull null
                val title = link.text().trim().ifBlank { link.attr("title") }
                val url = clean(link.absUrl("href"))
                if (title.isBlank() || url.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = SourceUtils.imageUrl(elem.selectFirst("img")),
                    type = MediaType.NOVEL,
                    status = "",
                    description = "",
                    sourceId = "tunovelaligera"
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, media.id)
            return media.copy(
                title = doc.selectFirst(".post-title h1, h1")?.text()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = media.coverUrl.ifBlank { SourceUtils.imageUrl(doc.selectFirst(".summary_image img")) },
                description = SourceUtils.htmlToText(doc.selectFirst(".summary__content, .description-summary")?.html().orEmpty()),
                author = doc.select(".author-content a").joinToString(", ") { it.text() },
                genres = doc.select(".genres-content a").map { it.text() },
                status = doc.selectFirst(".post-status .summary-content")?.text().orEmpty()
            )
        }

        fun parseChapters(html: String, mediaId: String): List<Chapter> {
            val links = Jsoup.parse(html, mediaId).select(".wp-manga-chapter a[href]")
                .filter { it.absUrl("href").startsWith("http") }
            val total = links.size
            return links.mapIndexed { idx, a ->
                val url = clean(a.absUrl("href"))
                val title = a.text().trim()
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = title.ifBlank { "Capítulo ${total - idx}" },
                    chapterNumber = SourceUtils.parseChapterNumber(title, (total - idx).toFloat()),
                    url = url,
                    dateUploaded = ""
                )
            }.distinctBy { it.id }
        }

        fun parseChapterText(html: String): String {
            val content = Jsoup.parse(html).selectFirst(".reading-content .text-left, .reading-content")
                ?: error("Contenido del capítulo no encontrado")
            return SourceUtils.htmlToText(content.html()).ifBlank { error("El capítulo está vacío en el sitio") }
        }
    }
}
