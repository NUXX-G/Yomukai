package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * NovelCool (novelcool.com): manga, manhwa and web novels, one site per language. The same site mixes
 * comics and novels, so each language is split by kind: [NovelCoolSource] lists only the comics and
 * [NovelCoolNovelSource] only the novels.
 *
 * The pages are plain HTML. To clients that present themselves as a desktop browser the site hands
 * out advert redirects instead of chapter links, so these requests go out under the app's own name.
 */
internal class NovelCoolSite(private val network: HttpFetcher, val baseUrl: String) {
    private val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$baseUrl/")

    suspend fun document(url: String): Document = Jsoup.parse(network.getString(url, headers, cloudflareFallback = false), url)

    /** Cards of a listing or a search, keeping only novels or only comics. */
    fun cards(doc: Document, novels: Boolean, sourceId: String): List<MediaItem> =
        doc.select(".book-list .book-item, .book-item").mapNotNull { card ->
            val isNovel = card.selectFirst(".book-type-novel") != null
            if (isNovel != novels) return@mapNotNull null
            val link = card.selectFirst(".book-pic a[href], .book-info a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").ifBlank { return@mapNotNull null }
            val title = card.selectFirst(".book-pic")?.attr("title")?.trim()?.ifBlank { null }
                ?: card.selectFirst(".book-name")?.text()?.trim() ?: return@mapNotNull null
            val img = card.selectFirst("img")
            val cover = img?.absUrl("lazy_url")?.ifBlank { null } ?: SourceUtils.imageUrl(img)
            val tags = card.select(".book-tag").map { it.text().trim() }.filter { it.isNotEmpty() }
            MediaItem(
                id = url, title = title, coverUrl = cover.takeUnless { "default_pic" in it }.orEmpty(),
                type = if (novels) MediaType.NOVEL else typeOf(tags),
                status = "", description = card.selectFirst(".book-summary-content")?.text()?.trim().orEmpty(),
                sourceId = sourceId, genres = tags.filterNot { it.equals("Webcomic", true) || it.equals("Manhwa", true) || it.equals("Manhua", true) }
            )
        }.distinctBy { it.id }

    suspend fun details(media: MediaItem): MediaItem {
        val doc = document(media.id)
        val genres = doc.select(".bk-cates .bk-cate-item a[title]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val status = doc.selectFirst(".bk-cate-type1 a, .bk-status-item a")?.attr("href").orEmpty()
        return media.copy(
            title = doc.selectFirst("h1.bookinfo-title")?.text()?.trim()?.ifBlank { null } ?: media.title,
            coverUrl = doc.selectFirst(".bookinfo-pic-img")?.absUrl("src")?.takeUnless { it.isBlank() || "default_pic" in it } ?: media.coverUrl,
            description = doc.selectFirst(".bk-summary-txt")?.text()?.trim()
                ?.takeUnless { it.isBlank() || it.startsWith("Información no completada") || it.startsWith("Information is not complete") }
                ?: media.description,
            author = doc.selectFirst(".bookinfo-author > a")?.attr("title")?.trim()?.ifBlank { null } ?: media.author,
            genres = genres.ifEmpty { media.genres },
            status = when {
                "completed" in status -> "Finalizado"
                "updated" in status -> "En emisión"
                else -> media.status
            },
            type = if (media.type == MediaType.NOVEL || doc.selectFirst(".bookinfo-pic .book-type-novel") != null) MediaType.NOVEL else typeOf(genres)
        )
    }

    suspend fun chapters(mediaId: String): List<Chapter> {
        val doc = document(mediaId)
        val links = doc.select(".chapter-item-list .chp-item a[href]")
        val count = links.size
        return links.mapIndexedNotNull { i, a ->
            val url = a.absUrl("href")
            if ("/chapter/" !in url) return@mapIndexedNotNull null
            val title = a.attr("title").ifBlank { a.selectFirst(".chapter-item-headtitle")?.text().orEmpty() }.trim()
            Chapter(
                id = url, mediaId = mediaId, title = title,
                // Newest first on the page: the position is the fallback number.
                chapterNumber = SourceUtils.parseChapterNumber(title, (count - i).toFloat()),
                url = url,
                dateUploaded = a.selectFirst(".chapter-item-time")?.text()?.trim().orEmpty()
            )
        }.distinctBy { it.url }.ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    /**
     * Comic pages: the site shows ten per page in its "-10-N" layout, so the first one tells how many
     * there are and the rest are fetched at the same time.
     */
    suspend fun pages(chapterUrl: String): List<String> = coroutineScope {
        val base = chapterUrl.trimEnd('/').removeSuffix(".html")
        val first = document("$base-10-1.html")
        val total = first.select("select.sl-page option").size.coerceIn(1, MAX_SUBPAGES)
        val rest = (2..total).map { n -> async { runCatching { pageImages(document("$base-10-$n.html")) }.getOrDefault(emptyList()) } }
        (pageImages(first) + rest.awaitAll().flatten()).distinct().ifEmpty { error("El capítulo no tiene imágenes") }
    }

    private fun pageImages(doc: Document): List<String> =
        doc.select("img.mangaread-manga-pic").map { it.absUrl("src") }.filter { it.startsWith("http") }

    /** Novel text: what lies between the start and end marks, line breaks kept as paragraphs. */
    suspend fun text(chapterUrl: String): String {
        val html = network.getString(chapterUrl, headers, cloudflareFallback = false)
        val start = html.indexOf("chapter-start-mark")
        val end = html.indexOf("chapter-end-mark", start.coerceAtLeast(0))
        if (start < 0 || end < 0) error("No se encontró el texto del capítulo")
        val body = html.substring(html.indexOf('>', start) + 1, html.lastIndexOf('<', end))
        val fragment = Jsoup.parseBodyFragment(body.replace(Regex("(?i)<br\\s*/?>"), "\n"))
        fragment.select("script, style, ins, iframe, .sharethis-inline-share-buttons, [class*=share]").remove()
        return SourceUtils.normalizeParagraphs(fragment.body().wholeText()).ifBlank { error("El capítulo está vacío") }
    }

    companion object {
        const val USER_AGENT = "Yomukai/1.13 (open source reader; +https://github.com/NUXX-G/Yomukai)"
        private const val MAX_SUBPAGES = 40

        fun typeOf(tags: List<String>): MediaType = when {
            tags.any { it.equals("Manhua", true) } -> MediaType.MANHUA
            tags.any { it.equals("Manhwa", true) || it.equals("Webtoon", true) || it.equals("Webtoons", true) || it.equals("Webcomic", true) } -> MediaType.MANHWA
            else -> MediaType.MANGA
        }
    }
}

private val SORT = SelectFilter(
    FilterKeys.SORT, "Orden",
    listOf(FilterOption("latest", "Actualizados"), FilterOption("popular", "Populares"), FilterOption("new_list", "Nuevos"), FilterOption("completed", "Finalizados"))
)

/** Comics of one NovelCool language site (es.novelcool.com, www.novelcool.com). */
class NovelCoolSource(network: HttpFetcher, private val lang: String) : MangaSource {
    override val id = if (lang == "es") "novelcool_es" else "novelcool_en"
    override val name = if (lang == "es") "NovelCool (ES)" else "NovelCool (EN)"
    override val baseUrl = if (lang == "es") "https://es.novelcool.com" else "https://www.novelcool.com"
    override val language = lang.uppercase()
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("img.novelcool.com", "movietop.cc")
    override val imageHeaders: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")
    override val filters: List<SourceFilter> = listOf(SORT)

    private val site = NovelCoolSite(network, baseUrl)

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = browse(page, emptyMap())

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        val listing = values.single(SORT)
        val path = if (page <= 1) "/category/$listing.html" else "/category/${listing}_$page.html"
        site.cards(site.document(baseUrl + path), novels = false, sourceId = id)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        site.cards(site.document("$baseUrl/search/?name=${SourceUtils.encode(query)}"), novels = false, sourceId = id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching { site.details(media) }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching { site.chapters(mediaId) }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching { site.pages(chapterUrl) }
}

/** Web novels of NovelCool in English: the "Web Novel" category of www.novelcool.com. */
class NovelCoolNovelSource(network: HttpFetcher) : NovelSource {
    override val id = "novelcool_novels_en"
    override val name = "NovelCool Novels (EN)"
    override val baseUrl = "https://www.novelcool.com"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.NOVEL)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("img.novelcool.com")

    private val site = NovelCoolSite(network, baseUrl)

    override val filters: List<SourceFilter> = listOf(
        SelectFilter(FilterKeys.SORT, "Orden", listOf(FilterOption("latest", "Actualizadas"), FilterOption("popular", "Populares")))
    )

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = browse(page, emptyMap())

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        val listing = values[FilterKeys.SORT]?.firstOrNull() ?: "latest"
        // The "Web Novel" category only has one page per order; past it, the site's general listing
        // (which mixes in comics) is read for the novels in it.
        val url = when {
            page <= 1 -> "$baseUrl/category/$listing/category_id-$WEB_NOVEL.html"
            page == 2 -> "$baseUrl/category/${if (listing == "latest") "popular" else "latest"}/category_id-$WEB_NOVEL.html"
            else -> "$baseUrl/category/${listing}_${page - 1}.html"
        }
        site.cards(site.document(url), novels = true, sourceId = id)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        site.cards(site.document("$baseUrl/search/?name=${SourceUtils.encode(query)}"), novels = true, sourceId = id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching { site.details(media).copy(type = MediaType.NOVEL) }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching { site.chapters(mediaId) }

    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching { site.text(chapterUrl) }

    private companion object {
        const val WEB_NOVEL = 1090
    }
}
