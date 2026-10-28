package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.CommonSort
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.formatIsoDate
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.parser.Parser

/**
 * Asura Scans (asurascans.com, Astro front-end + api.asurascans.com JSON backend).
 * Media ids are the bare series slug; the site's public URLs append a rotating hash
 * suffix, but /comics/{slug} without it keeps working, so we never store the suffix.
 */
class AsuraScansSource(private val network: NetworkHelper) : MangaSource {
    override val id = "asura"
    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.MANHWA, MediaType.MANHUA, MediaType.MANGA)
    override val supportsLatestPaging = true

    private val apiUrl = "https://api.asurascans.com/api"
    private val pageSize = 24

    private suspend fun api(path: String): JsonObject =
        parseJson(network.getString("$apiUrl$path", mapOf("Accept" to "application/json", "Referer" to "$baseUrl/")))
            .asJsonObject

    private fun typeOf(raw: String?) = when (raw?.lowercase()) {
        "manhua" -> MediaType.MANHUA
        "manga" -> MediaType.MANGA
        else -> MediaType.MANHWA
    }

    private fun item(s: JsonObject): MediaItem? {
        val slug = s.str("slug") ?: return null
        return MediaItem(
            id = slug,
            title = s.str("title")?.trim() ?: return null,
            coverUrl = (s.str("cover") ?: s.str("cover_url")).orEmpty(),
            type = typeOf(s.str("type")),
            status = s.str("status").orEmpty().replaceFirstChar { it.uppercase() },
            description = SourceUtils.htmlToText(s.str("description").orEmpty()),
            sourceId = id,
            author = listOfNotNull(s.str("author"), s.str("artist")).filter { it.isNotBlank() }.distinct().joinToString(", "),
            genres = s.arr("genres").objects().mapNotNull { it.str("name") },
            altTitles = s.arr("alt_titles").strings()
        )
    }

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        api(catalogueRequest(page, values, pageSize)).arr("data").objects().mapNotNull(::item)
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        api("/series?sort=latest&limit=$pageSize&page=$page").arr("data").objects().mapNotNull(::item)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        api("/search?q=${SourceUtils.encode(query)}").arr("data").objects().mapNotNull(::item)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val series = api("/series/${media.id}").obj("series") ?: error("Serie no encontrada")
        item(series) ?: media
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        api("/series/$mediaId/chapters").arr("data").objects().mapNotNull { c ->
            val number = c.str("number") ?: return@mapNotNull null
            val label = number.toFloatOrNull()?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: number
            val premium = c.str("is_premium") == "true"
            val url = "$baseUrl/comics/$mediaId/chapter/$label"
            Chapter(
                id = url,
                mediaId = mediaId,
                title = "Chapter $label" + if (premium) " (Premium)" else "",
                chapterNumber = number.toFloatOrNull() ?: 0f,
                url = url,
                dateUploaded = formatIsoDate(c.str("published_at"))
            )
        }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(network.getString(chapterUrl, mapOf("Referer" to "$baseUrl/")))
            .ifEmpty { error("No se encontraron páginas (¿capítulo premium o de acceso anticipado?)") }
    }

    companion object {
        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(
                FilterOption(CommonSort.LATEST, "Recientes"),
                FilterOption(CommonSort.POPULAR, "Populares"),
                FilterOption("title", "A-Z")
            )
        )
        private val STATUS = SelectFilter(
            FilterKeys.STATUS, "Estado",
            listOf(FilterOption("", "Todos"), FilterOption("ongoing", "En emisión"), FilterOption("completed", "Completado"))
        )
        private val TYPE = SelectFilter(
            FilterKeys.TYPE, "Tipo",
            listOf(FilterOption("", "Todos"), FilterOption("manhwa", "Manhwa"), FilterOption("manhua", "Manhua"), FilterOption("manga", "Manga"))
        )
        val FILTERS: List<SourceFilter> = listOf(SORT, STATUS, TYPE)

        fun catalogueRequest(page: Int, values: FilterValues, pageSize: Int): String = buildString {
            append("/series?sort=${values.single(SORT)}&limit=$pageSize&page=$page")
            values.single(STATUS).takeIf { it.isNotEmpty() }?.let { append("&status=$it") }
            values.single(TYPE).takeIf { it.isNotEmpty() }?.let { append("&type=$it") }
        }

        private val PAGE_URL = Regex(""""url":\[0,"(https://cdn\.asurascans\.com/asura-images/chapters/[^"]+)"""")

        /**
         * The reader is an Astro island whose props (HTML-escaped JSON) contain a "pages" array in
         * reading order. DOM <img> order is not reliable because pages are lazy-rendered.
         */
        fun parsePages(html: String): List<String> {
            val props = Parser.unescapeEntities(html, false)
            val start = props.indexOf("\"pages\":")
            if (start < 0) return emptyList()
            return PAGE_URL.findAll(props, start).map { it.groupValues[1] }.distinct().toList()
        }
    }
}
