package com.nelson.yomukai.data.source.novel

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** WordPress "Madara" theme site; protected by Cloudflare so it relies on the WebView fallback. */
class TuNovelaLigeraSource(private val network: NetworkHelper) : NovelSource {
    override val id = "tunovelaligera"
    override val name = "TuNovelaLigera"
    override val baseUrl = "https://tunovelaligera.com"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.NOVEL)
    override val isExperimental = true

    private suspend fun document(url: String): Document = Jsoup.parse(network.getString(url), url)

    private fun parseItems(doc: Document, selector: String) = doc.select(selector).mapNotNull { elem ->
        val link = elem.selectFirst(".post-title a, h3 a, h4 a, a[href]") ?: return@mapNotNull null
        val title = link.text().trim().ifBlank { link.attr("title") }
        if (title.isBlank()) return@mapNotNull null
        MediaItem(
            id = link.absUrl("href"),
            title = title,
            coverUrl = SourceUtils.imageUrl(elem.selectFirst("img")),
            type = MediaType.NOVEL,
            status = "",
            description = "",
            sourceId = id
        )
    }.distinctBy { it.id }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseItems(document(baseUrl), ".page-item-detail")
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseItems(document("$baseUrl/?s=${SourceUtils.encode(query)}&post_type=wp-manga"), ".c-tabs-item__content")
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val doc = document(media.id)
        media.copy(
            title = doc.selectFirst(".post-title h1, h1")?.text()?.trim() ?: media.title,
            coverUrl = media.coverUrl.ifBlank { SourceUtils.imageUrl(doc.selectFirst(".summary_image img")) },
            description = SourceUtils.htmlToText(doc.selectFirst(".summary__content, .description-summary")?.html().orEmpty()),
            author = doc.select(".author-content a").joinToString(", ") { it.text() },
            genres = doc.select(".genres-content a").map { it.text() },
            status = doc.selectFirst(".post-status .summary-content")?.text().orEmpty()
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        var links = document(mediaId).select(".wp-manga-chapter a")
        if (links.isEmpty()) {
            // Newer Madara versions load the list through an AJAX endpoint.
            val html = network.postString(mediaId.trimEnd('/') + "/ajax/chapters/", mapOf("Referer" to mediaId))
            links = Jsoup.parse(html, mediaId).select(".wp-manga-chapter a")
        }
        val total = links.size
        links.mapIndexedNotNull { idx, a ->
            val url = a.absUrl("href").ifBlank { return@mapIndexedNotNull null }
            val title = a.text().trim()
            Chapter(
                id = url,
                mediaId = mediaId,
                title = title.ifBlank { "Capítulo ${total - idx}" },
                chapterNumber = SourceUtils.parseChapterNumber(title, (total - idx).toFloat()),
                url = url,
                dateUploaded = ""
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching {
        val content = document(chapterUrl).selectFirst(".reading-content .text-left, .reading-content, .entry-content")
            ?: error("Contenido del capítulo no encontrado")
        SourceUtils.htmlToText(content.html()).ifBlank { error("El capítulo está vacío") }
    }
}
