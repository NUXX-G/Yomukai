package com.nelson.yomukai.data.source

import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Selector-driven scraper for classic server-rendered manga sites (MangaThemesia, Madara...).
 * Subclasses only describe where things live in the DOM.
 */
abstract class HtmlMangaSource(protected val network: NetworkHelper) : MangaSource {

    override val isExperimental: Boolean = true

    protected abstract val defaultType: MediaType
    protected abstract val latestPath: String
    protected abstract fun searchUrl(query: String): String

    protected abstract val itemSelector: String
    protected open val itemTitleSelector: String = ".title, .tt, h3, h2, h4"
    protected abstract val chapterSelector: String
    protected open val chapterTitleSelector: String? = null
    protected abstract val pageImageSelector: String

    protected open val descriptionSelector: String =
        ".entry-content[itemprop=description], .summary__content, .description, .synopsis, [itemprop=description]"
    protected open val authorSelector: String = ".author-content a, .imptdt:contains(Autor) i, .imptdt:contains(Author) i"
    protected open val genreSelector: String = ".genres-content a, .mgen a, .genres a"
    protected open val statusSelector: String =
        ".post-status .summary-content, .imptdt:contains(Status) i, .imptdt:contains(Estado) i"

    /** Most sites list newest chapters first. */
    protected open val chaptersNewestFirst: Boolean = true

    protected suspend fun document(url: String): Document =
        Jsoup.parse(network.getString(url, headers = mapOf("Referer" to "$baseUrl/")), url)

    protected fun parseItems(doc: Document): List<MediaItem> =
        doc.select(itemSelector).mapNotNull { elem ->
            val link = elem.selectFirst("a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = (elem.selectFirst(itemTitleSelector)?.text()
                ?: link.attr("title").ifBlank { link.text() }).trim()
            if (title.isBlank()) return@mapNotNull null
            MediaItem(
                id = url,
                title = title,
                coverUrl = SourceUtils.imageUrl(elem.selectFirst("img")),
                type = defaultType,
                status = "",
                description = "",
                sourceId = id
            )
        }.distinctBy { it.id }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseItems(document("$baseUrl$latestPath"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseItems(document(searchUrl(query)))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val doc = document(media.id)
        media.copy(
            title = doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null } ?: media.title,
            coverUrl = media.coverUrl.ifBlank {
                doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
            },
            description = doc.selectFirst(descriptionSelector)?.text()?.trim().orEmpty()
                .ifBlank { doc.selectFirst("meta[property=og:description]")?.attr("content").orEmpty() },
            author = doc.select(authorSelector).joinToString(", ") { it.text().trim() },
            genres = doc.select(genreSelector).map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            status = doc.selectFirst(statusSelector)?.text()?.trim().orEmpty().ifBlank { media.status }
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val elements = document(mediaId).select(chapterSelector)
        val total = elements.size
        elements.mapIndexedNotNull { idx, elem ->
            val link = if (elem.tagName() == "a") elem else elem.selectFirst("a[href]")
            val url = link?.absUrl("href").orEmpty()
            if (url.isBlank()) return@mapIndexedNotNull null
            val title = (chapterTitleSelector?.let { elem.selectFirst(it)?.text() } ?: link!!.text()).trim()
            val fallback = if (chaptersNewestFirst) (total - idx).toFloat() else (idx + 1).toFloat()
            Chapter(
                id = url,
                mediaId = mediaId,
                title = title.ifBlank { "Capítulo ${fallback.toInt()}" },
                chapterNumber = SourceUtils.parseChapterNumber(title, fallback),
                url = url,
                dateUploaded = elem.selectFirst(".chapterdate, .chapter-release-date, time")?.text().orEmpty()
            )
        }.distinctBy { it.id }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val pages = document(chapterUrl).select(pageImageSelector)
            .map { SourceUtils.imageUrl(it) }
            .filter { it.isNotBlank() }
            .distinct()
        if (pages.isEmpty()) error("No se encontraron imágenes en el capítulo")
        pages
    }
}
