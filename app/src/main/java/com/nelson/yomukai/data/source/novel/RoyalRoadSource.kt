package com.nelson.yomukai.data.source.novel

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.formatEpochSeconds
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class RoyalRoadSource(private val network: NetworkHelper) : NovelSource {
    override val id = "royalroad"
    override val name = "Royal Road"
    override val baseUrl = "https://www.royalroad.com"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.NOVEL)
    override val supportsLatestPaging = true

    private suspend fun document(url: String): Document = Jsoup.parse(network.getString(url), url)

    private fun parseList(doc: Document): List<MediaItem> =
        doc.select(".fiction-list-item").mapNotNull { elem ->
            val link = elem.selectFirst("h2.fiction-title a, .fiction-title a") ?: return@mapNotNull null
            MediaItem(
                id = link.absUrl("href"),
                title = link.text().trim(),
                coverUrl = SourceUtils.imageUrl(elem.selectFirst("img")),
                type = MediaType.NOVEL,
                status = "",
                description = elem.selectFirst("[id^=description]")?.text().orEmpty(),
                sourceId = id,
                genres = elem.select(".tags a").map { it.text() }
            )
        }.distinctBy { it.id }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseList(document("$baseUrl/fictions/latest-updates?page=$page"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseList(document("$baseUrl/fictions/search?title=${SourceUtils.encode(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val doc = document(media.id)
        val statusWords = setOf("ONGOING", "COMPLETED", "HIATUS", "STUB", "DROPPED", "INACTIVE")
        media.copy(
            title = doc.selectFirst(".fic-title h1, h1")?.text()?.trim() ?: media.title,
            coverUrl = doc.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null } ?: media.coverUrl,
            description = SourceUtils.htmlToText(doc.selectFirst(".description")?.html().orEmpty()),
            author = doc.selectFirst(".fic-title h4 a, h4 a[href*=/profile/]")?.text().orEmpty(),
            genres = doc.select(".tags a").map { it.text().trim() }.distinct(),
            status = doc.select(".fiction-info span.label").map { it.text().trim() }
                .firstOrNull { it.uppercase() in statusWords }.orEmpty()
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        document(mediaId).select("tr.chapter-row").mapIndexedNotNull { idx, row ->
            val link = row.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val url = link.absUrl("href")
            val title = link.text().trim()
            Chapter(
                id = url,
                mediaId = mediaId,
                title = title,
                // Royal Road lists oldest first; titles are free-form so position is the reliable order.
                chapterNumber = (idx + 1).toFloat(),
                url = url,
                dateUploaded = formatEpochSeconds(row.selectFirst("time[unixtime]")?.attr("unixtime")?.toLongOrNull())
            )
        }
    }

    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching {
        val doc = document(chapterUrl)
        // Royal Road injects hidden "stolen content" paragraphs styled via random class names.
        val hiddenClasses = Regex("""\.([A-Za-z0-9_-]+)\s*\{[^}]*display:\s*none""")
            .findAll(doc.select("style").joinToString("\n") { it.data() })
            .map { it.groupValues[1] }
            .toList()
        val content = doc.selectFirst(".chapter-content") ?: error("Contenido del capítulo no encontrado")
        hiddenClasses.forEach { content.select(".$it").remove() }
        SourceUtils.htmlToText(content.html()).ifBlank { error("El capítulo está vacío") }
    }
}
