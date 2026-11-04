package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.source.NoChaptersException
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.formatIsoDate
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup

/**
 * LectorXD (lectorxd.com): manga, manhwa and manhua in Spanish. An Astro site with a public JSON API
 * for the catalogue, the search and each title's card, which answer at once. The chapter list and the
 * pages only come in the HTML pages, which sit behind a Cloudflare check: those go through the
 * platform's browser fallback. Titles are addressed as "type/slug" (the path of their page).
 */
class LectorXdSource(private val network: HttpFetcher) : MangaSource {
    override val id = "lectorxd"
    override val name = "LectorXD"
    override val baseUrl = "https://lectorxd.com"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("cdnlxd.xyz")
    // Its title pages sit behind Cloudflare; the list already leaves out titles without chapters.
    override val cheapChapterList = false

    private val sort = SelectFilter(
        FilterKeys.SORT, "Orden",
        listOf(
            FilterOption("recent", "Recientes"), FilterOption("views", "Más vistos"), FilterOption("rating", "Mejor valorados"),
            FilterOption("leyendo", "Más leídos ahora"), FilterOption("chapters", "Más capítulos")
        )
    )
    private val type = SelectFilter(
        FilterKeys.TYPE, "Tipo",
        listOf(FilterOption("", "Todos"), FilterOption("manga", "Manga"), FilterOption("manhwa", "Manhwa"), FilterOption("manhua", "Manhua"), FilterOption("one_shot", "One shot"))
    )
    private val status = SelectFilter(
        FilterKeys.STATUS, "Estado",
        listOf(FilterOption("", "Todos"), FilterOption("en_emision", "En emisión"), FilterOption("completado", "Finalizado"))
    )
    private val adult = SelectFilter(
        "adult", "Contenido +18",
        listOf(FilterOption("safe", "Sin +18"), FilterOption("all", "Todo"), FilterOption("adult", "Solo +18"))
    )
    override val filters: List<SourceFilter> = listOf(sort, type, status, adult)

    private suspend fun api(path: String): JsonObject =
        JsonParser.parseString(network.getString("$baseUrl$path", mapOf("Accept" to "application/json", "Referer" to "$baseUrl/"), cloudflareFallback = false)).asJsonObject

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = browse(page, emptyMap())

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        val query = buildString {
            append("/api/catalog?orderBy=").append(values.single(sort))
            append("&adult=").append(values.single(adult))
            append("&excludeTypes=novela")
            values.single(type).takeIf { it.isNotEmpty() }?.let { append("&types=").append(it) }
            values.single(status).takeIf { it.isNotEmpty() }?.let { append("&status=").append(it) }
            if (page > 1) append("&page=").append(page)
        }
        parseList(api(query).getAsJsonArray("mangas"), id)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        // The catalogue search also matches descriptions and brings the chapter count; the quick search does not.
        parseList(api("/api/catalog?search=${SourceUtils.encode(query)}&adult=all&excludeTypes=novela").getAsJsonArray("mangas"), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val slug = media.id.substringAfter('/')
        val m = api("/api/mangas/preview?slug=${SourceUtils.encode(slug)}").getAsJsonObject("manga") ?: return@runCatching media
        media.copy(
            title = m.str("title").ifBlank { media.title },
            coverUrl = m.str("coverImage").ifBlank { media.coverUrl },
            description = m.str("description").ifBlank { media.description },
            status = statusOf(m.str("status")).ifBlank { media.status },
            type = typeOf(m.str("type"))
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val html = network.getString("$baseUrl/${pathOf(mediaId)}")
        parseChapters(html, mediaId, baseUrl).ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val doc = Jsoup.parse(network.getString(chapterUrl), chapterUrl)
        doc.select("#reader-content img.page-image, img.page-image").mapNotNull { img ->
            listOf("data-src", "data-lazy-src", "src").map { img.absUrl(it) }.firstOrNull { it.startsWith("http") }
        }.distinct().ifEmpty { error("El capítulo no tiene imágenes") }
    }

    companion object {
        private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        fun typeOf(type: String) = when (type) {
            "manhwa" -> MediaType.MANHWA
            "manhua" -> MediaType.MANHUA
            else -> MediaType.MANGA
        }

        private fun statusOf(status: String) = when (status) {
            "en_emision" -> "En emisión"
            "completado" -> "Finalizado"
            "pausado" -> "En pausa"
            "cancelado" -> "Cancelado"
            else -> ""
        }

        /** The site's path for a title: one-shots live under /manga. */
        fun pathOf(mediaId: String): String {
            val kind = mediaId.substringBefore('/')
            return (if (kind in setOf("manga", "manhwa", "manhua")) kind else "manga") + "/" + mediaId.substringAfter('/')
        }

        internal fun parseList(items: JsonArray?, sourceId: String): List<MediaItem> = items?.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val slug = o.str("slug").ifBlank { return@mapNotNull null }
            val kind = o.str("type")
            if (kind == "novela") return@mapNotNull null
            // A title the site lists with no chapters yet cannot be read.
            if (o.get("totalChapters")?.takeIf { it.isJsonPrimitive }?.asInt == 0) return@mapNotNull null
            MediaItem(
                id = "$kind/$slug", title = o.str("title").trim(), coverUrl = o.str("coverImage"), type = typeOf(kind),
                status = statusOf(o.str("status")), description = o.str("description"), sourceId = sourceId,
                genres = o.getAsJsonArray("tags")?.mapNotNull { t ->
                    t.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonObject("tag")?.str("name")?.takeIf { it.isNotBlank() }
                }.orEmpty().filterNot { it in SCAN_TAGS }
            )
        }.orEmpty()

        /** The chapter list is the "props" of the page's ChapterList Astro island. */
        internal fun parseChapters(html: String, mediaId: String, baseUrl: String): List<Chapter> {
            val doc = Jsoup.parse(html)
            val island = doc.selectFirst("astro-island[component-url*=ChapterList]") ?: return emptyList()
            val props = unwrapAstro(JsonParser.parseString(island.attr("props"))).asJsonObject
            val chapters = props.getAsJsonArray("chapters") ?: return emptyList()
            val path = pathOf(mediaId)
            return chapters.mapNotNull { e ->
                val c = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val number = c.str("chapter").ifBlank { return@mapNotNull null }
                val group = c.get("groupId")?.takeIf { it.isJsonPrimitive }?.asString
                val url = "$baseUrl/$path/leer/$number" + (group?.let { "?g=$it" } ?: "")
                Chapter(
                    id = url, mediaId = mediaId, title = "Capítulo $number",
                    chapterNumber = number.toFloatOrNull() ?: SourceUtils.parseChapterNumber(number, 0f),
                    url = url, dateUploaded = formatIsoDate(c.str("publishAt"))
                )
            }.distinctBy { it.url }
        }

        /** Astro serialises every value as a [type, value] pair. */
        internal fun unwrapAstro(e: JsonElement): JsonElement = when {
            e.isJsonArray -> {
                val a = e.asJsonArray
                if (a.size() == 2 && a[0].isJsonPrimitive && (a[0] as JsonPrimitive).isNumber) unwrapAstro(a[1])
                else JsonArray().apply { a.forEach { add(unwrapAstro(it)) } }
            }
            e.isJsonObject -> JsonObject().apply { e.asJsonObject.entrySet().forEach { (k, v) -> add(k, unwrapAstro(v)) } }
            else -> e
        }

        /** Tags that name the scan group, not a genre. */
        private val SCAN_TAGS = setOf("BegaTranslation", "MangoScan", "PununiScan", "Mangatoon", "honeytoon", "Publicándose", "Finalizado", "Nuevo", "Recomendado", "Exclusivo")
    }
}
