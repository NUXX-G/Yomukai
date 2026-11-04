package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.MultiSelectFilter
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.selected
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * ZonaTMO (zonatmo.org), server-rendered. Catalogue and search share `/biblioteca`; a title page
 * holds every chapter; each chapter may have several uploads, of which the first is used.
 * Media ids are the absolute `/library/{type}/{id}/{slug}` URL.
 */
class ZonaTmoSource(private val network: HttpFetcher) : MangaSource {
    override val id = "zonatmo"
    override val name = "ZonaTMO"
    override val baseUrl = BASE
    override val extraHosts = listOf("https://storage.zonatmo.org", "https://storage2.zonatmo.org")
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)
    override val supportsLatestPaging = true

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        parseItems(html(catalogueUrl(page, values)))
    }

    /** The site's "latest uploads" page lists chapters without their series, so new titles are the closest listing. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseItems(html(catalogueUrl(page, mapOf(FilterKeys.SORT to setOf("creation")))))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseItems(html("$BASE/biblioteca?title=${SourceUtils.encode(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(media.id), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseChapters(html(mediaId), mediaId).ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(html(chapterUrl)).ifEmpty { error("El capítulo no tiene imágenes") }
    }

    companion object {
        private const val BASE = "https://zonatmo.org"
        private val TYPE_IN_PATH = Regex("""/library/([a-z_]+)/\d+""")
        private val DATE = Regex("""\d{2}/\d{2}/\d{4}""")

        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(
                FilterOption("likes_count", "Populares"), FilterOption("creation", "Recientes"),
                FilterOption("score", "Mejor valorados"), FilterOption("num_chapters", "Más capítulos"),
                FilterOption("alphabetically", "A-Z")
            )
        )
        private val TYPE = SelectFilter(
            FilterKeys.TYPE, "Tipo",
            listOf(FilterOption("", "Todos"), FilterOption("manga", "Manga"), FilterOption("manhwa", "Manhwa"), FilterOption("manhua", "Manhua"))
        )
        private val STATUS = SelectFilter(
            FilterKeys.STATUS, "Estado",
            listOf(
                FilterOption("", "Todos"), FilterOption("ongoing", "En emisión"), FilterOption("completed", "Completado"),
                FilterOption("hiatus", "En pausa"), FilterOption("cancelled", "Cancelado")
            )
        )
        private val DEMOGRAPHY = SelectFilter(
            "demography", "Demografía",
            listOf(
                FilterOption("", "Todas"), FilterOption("seinen", "Seinen"), FilterOption("shoujo", "Shoujo"),
                FilterOption("shounen", "Shounen"), FilterOption("josei", "Josei"), FilterOption("kodomo", "Kodomo")
            )
        )
        private val GENRE_OPTIONS = listOf(
            "1" to "Acción", "2" to "Aventura", "41" to "Artes marciales", "9" to "Ciencia ficción", "3" to "Comedia", "11" to "Deportes",
            "4" to "Drama", "19" to "Ecchi", "5" to "Fantasía", "44" to "Gore", "20" to "Harem", "16" to "Isekai", "7" to "Misterio",
            "15" to "Psicológico", "22" to "Recuentos de la vida", "36" to "Reencarnación", "8" to "Romance", "12" to "Sobrenatural",
            "37" to "Tragedia", "28" to "Vida escolar", "30" to "Boys Love", "31" to "Yaoi", "35" to "Girls Love", "52" to "Yuri"
        ).map { FilterOption(it.first, it.second) }
        private val GENRES = MultiSelectFilter(FilterKeys.GENRE, "Género", GENRE_OPTIONS)
        private val WITHOUT = MultiSelectFilter("without", "Excluir géneros", GENRE_OPTIONS)
        val FILTERS: List<SourceFilter> = listOf(SORT, TYPE, STATUS, DEMOGRAPHY, GENRES, WITHOUT)

        fun catalogueUrl(page: Int, values: FilterValues): String = buildString {
            val sort = values.single(SORT)
            append("$BASE/biblioteca?order_item=$sort&order_dir=${if (sort == "alphabetically") "asc" else "desc"}&page=${page.coerceAtLeast(1)}")
            values.single(TYPE).takeIf { it.isNotEmpty() }?.let { append("&type=$it") }
            values.single(STATUS).takeIf { it.isNotEmpty() }?.let { append("&status=$it") }
            values.single(DEMOGRAPHY).takeIf { it.isNotEmpty() }?.let { append("&demography=$it") }
            values.selected(FilterKeys.GENRE).forEach { append("&genders%5B%5D=$it") }
            values.selected(WITHOUT.key).forEach { append("&exclude_genders%5B%5D=$it") }
        }

        private fun typeOf(text: String?) = when (text?.lowercase()?.trim()) {
            "manhwa" -> MediaType.MANHWA
            "manhua" -> MediaType.MANHUA
            else -> MediaType.MANGA
        }

        private fun typeFromUrl(url: String) = typeOf(TYPE_IN_PATH.find(url)?.groupValues?.get(1))

        private fun cover(card: Element): String =
            card.selectFirst("img.cover-bg-img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("[data-bg]")?.attr("data-bg").orEmpty()

        fun parseItems(html: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select(".element[data-identifier]").mapNotNull { card ->
                val url = card.selectFirst("a[href*=/library/]")?.absUrl("href")?.ifBlank { null } ?: return@mapNotNull null
                val title = card.selectFirst(".thumbnail-title h4")?.let { it.attr("title").ifBlank { it.text() } }?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = cover(card),
                    type = typeFromUrl(url),
                    status = "",
                    description = "",
                    sourceId = "zonatmo"
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, media.id)
            return media.copy(
                title = doc.selectFirst("h1.element-title")?.ownText()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = media.coverUrl.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() },
                type = typeFromUrl(media.id),
                description = doc.selectFirst("#manga-synopsis")?.wholeText()?.let(SourceUtils::normalizeParagraphs).orEmpty(),
                author = doc.select("a[href*=filter_by=author]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().joinToString(", "),
                genres = doc.select("a.badge-primary[href*=/tag/]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
                status = doc.selectFirst(".book-status")?.text()?.trim().orEmpty().ifBlank { media.status },
                altTitles = doc.select(".syn-badge").map { it.text().trim() }.filter { it.isNotEmpty() }
            )
        }

        fun parseChapters(html: String, mediaId: String): List<Chapter> {
            val rows = Jsoup.parse(html, mediaId).select("li.upload-link[data-chapter-number]")
            val total = rows.size
            return rows.mapIndexedNotNull { index, row ->
                val url = row.selectFirst("a[href*=/view_uploads/]")?.absUrl("href")?.ifBlank { null } ?: return@mapIndexedNotNull null
                val title = row.selectFirst(".chapter-number")?.text()?.trim().orEmpty()
                val number = row.attr("data-chapter-number").toFloatOrNull()
                    ?: SourceUtils.parseChapterNumber(title, (total - index).toFloat())
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = title.ifBlank { "Capítulo $number" },
                    chapterNumber = number,
                    url = url,
                    dateUploaded = DATE.find(row.selectFirst(".chapter-detail")?.text().orEmpty())?.value.orEmpty()
                )
            }.distinctBy { it.id }
        }

        fun parsePages(html: String): List<String> =
            Jsoup.parse(html, BASE).select(".reader-img-wrap img")
                .map { SourceUtils.imageUrl(it) }
                .filter { it.startsWith("http") }
                .distinct()
    }
}
