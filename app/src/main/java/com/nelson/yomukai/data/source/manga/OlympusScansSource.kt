package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.MangaSource
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.text.Normalizer

/**
 * Olympus Scanlation (Nuxt). Uses the site's own JSON routes:
 *  - /api/series/list            full catalogue (searched locally)
 *  - /api/series/{slug}          details
 *  - /api/series/{slug}/chapters paginated chapter list
 * Chapter pages are server-rendered, so images are read from the reader HTML.
 * Media ids are the series slug; chapter URLs use the chapter *number*.
 */
class OlympusScansSource(private val network: NetworkHelper) : MangaSource {
    override val id = "olympus"
    override val name = "Olympus Scans"
    override val baseUrl = "https://olympusxyz.com"
    override val extraHosts = listOf("https://media.imagesolymp.xyz")
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANHWA, MediaType.MANHUA, MediaType.MANGA)

    private val catalogLock = Mutex()
    private var catalogCache: List<MediaItem>? = null

    private suspend fun api(path: String): JsonObject =
        parseJson(network.getString("$baseUrl/api$path", mapOf("Accept" to "application/json", "Referer" to "$baseUrl/")))
            .asJsonObject

    private fun item(s: JsonObject): MediaItem? {
        val slug = s.str("slug") ?: return null
        return MediaItem(
            id = slug,
            title = s.str("name")?.trim() ?: return null,
            coverUrl = s.str("cover").orEmpty(),
            type = MediaType.MANHWA,
            status = s.obj("status")?.str("name").orEmpty(),
            description = s.str("summary").orEmpty(),
            sourceId = id
        )
    }

    /** Home page cards: newest updates first. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseHome(network.getString(baseUrl), catalog = allSeries().associateBy { it.id })
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val needle = normalize(query)
        allSeries().filter { normalize(it.title).contains(needle) || it.id.contains(needle.replace(' ', '-')) }
    }

    private suspend fun allSeries(): List<MediaItem> = catalogLock.withLock {
        catalogCache ?: api("/series/list").arr("data").objects()
            .filter { it.str("type") == "comic" }
            .mapNotNull(::item)
            .also { catalogCache = it }
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val data = api("/series/${media.id}").obj("data") ?: error("Serie no encontrada")
        (item(data) ?: media).copy(
            description = SourceUtils.normalizeParagraphs(data.str("summary").orEmpty()),
            genres = data.arr("genres").objects().mapNotNull { it.str("name")?.trim() },
            author = data.obj("team")?.str("name")?.trim().orEmpty()
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val chapters = mutableListOf<Chapter>()
        var page = 1
        do {
            val root = api("/series/$mediaId/chapters?page=$page&direction=desc&type=comic")
            root.arr("data").objects().forEach { c ->
                val number = c.str("name") ?: return@forEach
                val url = chapterUrl(mediaId, number)
                chapters += Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = "Capítulo $number",
                    chapterNumber = number.toFloatOrNull() ?: 0f,
                    url = url,
                    dateUploaded = formatIsoDate(c.str("published_at"))
                )
            }
            val lastPage = root.obj("meta")?.str("last_page")?.toIntOrNull() ?: 1
            page++
        } while (page <= lastPage && page <= 50)
        chapters
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(network.getString(chapterUrl, mapOf("Referer" to "$baseUrl/")))
            .ifEmpty { error("No se encontraron imágenes en el capítulo") }
    }

    private fun chapterUrl(slug: String, number: String) = "$baseUrl/series/comic-$slug/capitulo/$number"

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")

    companion object {
        private val SLUG_IN_PATH = Regex("""/series/comic-([^/?#]+)""")

        /** Parses the home page update cards; [catalog] (slug → item) fills in covers when needed. */
        fun parseHome(html: String, catalog: Map<String, MediaItem>): List<MediaItem> {
            val doc = Jsoup.parse(html, "https://olympusxyz.com")
            return doc.select("a.chcard-title[href]").mapNotNull { link ->
                val slug = SLUG_IN_PATH.find(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val card = link.parents().firstOrNull { it.selectFirst("img") != null }
                val known = catalog[slug]
                MediaItem(
                    id = slug,
                    title = link.text().trim().ifBlank { known?.title.orEmpty() },
                    coverUrl = SourceUtils.imageUrl(card?.selectFirst("img")).ifBlank { known?.coverUrl.orEmpty() },
                    type = MediaType.MANHWA,
                    status = "",
                    description = "",
                    sourceId = "olympus"
                )
            }.filter { it.title.isNotBlank() }.distinctBy { it.id }
        }

        /** Reader pages are rendered as <img> tags pointing at /comics/{seriesId}/{chapterId}/. */
        fun parsePages(html: String): List<String> =
            Jsoup.parse(html).select("img[src]")
                .map { it.attr("src") }
                .filter { Regex("""/comics/\d+/\d+/""").containsMatchIn(it) }
                .distinct()
    }
}
