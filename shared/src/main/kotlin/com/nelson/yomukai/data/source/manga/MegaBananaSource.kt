package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.source.NoChaptersException
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup

/**
 * MegaBanana (megabanana.mx): US and European comics translated to Spanish, read online. The site is
 * a WordPress app with its own JSON API: `catalog` lists (paged, by category, with search), and
 * `reader/{post}?chapter=N` gives a chapter's pages. A title is a post; its "chapters" are the
 * collected parts it was split into ("Capítulo 1 al 4"...). The site also posts news and film
 * articles; only posts with a reader (`readerReady`) are comics.
 */
class MegaBananaSource(private val network: HttpFetcher) : MangaSource {
    override val id = "megabanana"
    override val name = "MegaBanana"
    override val baseUrl = "https://megabanana.mx"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.COMIC)
    override val supportsLatestPaging = true
    override val filters: List<SourceFilter> = listOf(PUBLISHER)

    private val api = "$baseUrl/wp-json/megabanana/v1"

    private suspend fun json(url: String): JsonObject =
        JsonParser.parseString(network.getString(url, mapOf("Referer" to "$baseUrl/", "Accept" to "application/json"))).asJsonObject

    private suspend fun catalog(page: Int, category: String, search: String = ""): List<MediaItem> {
        val query = buildString {
            append("$api/catalog?per_page=$PER_PAGE&page=$page&category=").append(SourceUtils.encode(category))
            if (search.isNotBlank()) append("&search=").append(SourceUtils.encode(search))
        }
        return parseCatalog(json(query), id)
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching { catalog(page, "comics") }

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        catalog(page, values.single(PUBLISHER))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching { catalog(1, "comics", query) }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        // The description and publisher come from the post itself; the cover and title from the list.
        val post = json("$baseUrl/wp-json/wp/v2/posts/${media.id}?_fields=title,excerpt,categories")
        val excerpt = post.getAsJsonObject("excerpt")?.get("rendered")?.asString.orEmpty()
        val genres = post.getAsJsonArray("categories")?.mapNotNull { CATEGORY_NAMES[it.asInt] }.orEmpty()
        val title = post.getAsJsonObject("title")?.get("rendered")?.asString?.let { cleanTitle(Jsoup.parse(it).text()) }
        media.copy(
            title = media.title.ifBlank { title.orEmpty() },
            description = Jsoup.parse(excerpt).text().removeSuffix("[…]").removeSuffix("…").trim().ifBlank { media.description },
            genres = genres.ifEmpty { media.genres },
            type = MediaType.COMIC,
            sourceId = id
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseChapters(json("$api/reader/$mediaId"), mediaId, api).ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        json(chapterUrl).getAsJsonArray("pages")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim() }
            ?.filter { it.startsWith("http") }.orEmpty().ifEmpty { error("El capítulo no tiene imágenes") }
    }

    companion object {
        private const val PER_PAGE = 30

        /** Publishers and collections from the site's own menu (`navigation`). */
        private val CATEGORY_NAMES = mapOf(
            3 to "Cómics", 76 to "Marvel", 77 to "DC", 79 to "Dark Horse", 81 to "IDW", 80 to "Image",
            78 to "Independientes", 282 to "Dynamite", 84 to "Vertigo", 29 to "Manga"
        )

        private val PUBLISHER = SelectFilter(
            FilterKeys.GENRE, "Editorial",
            listOf(
                FilterOption("comics", "Todas"), FilterOption("marvel", "Marvel"), FilterOption("dc", "DC"),
                FilterOption("dark-horse", "Dark Horse"), FilterOption("image", "Image"), FilterOption("idw", "IDW"),
                FilterOption("vertigo", "Vertigo"), FilterOption("dynamite", "Dynamite"),
                FilterOption("independientes", "Independientes")
            )
        )

        /** "Leer Providence de Alan Moore Comic Online en Español" -> "Providence de Alan Moore". */
        internal fun cleanTitle(raw: String): String =
            raw.trim()
                .replace(Regex("^Leer\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(C[oó]mic|Manga)?\\s*Online\\s+en\\s+Espa[ñn]ol\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()

        internal fun parseCatalog(root: JsonObject, sourceId: String): List<MediaItem> =
            root.getAsJsonArray("items")?.mapNotNull { e ->
                val o = e.asJsonObject
                // News and film articles have no reader.
                if (o.get("readerReady")?.asBoolean != true) return@mapNotNull null
                val postId = o.get("id")?.asLong ?: return@mapNotNull null
                MediaItem(
                    id = postId.toString(),
                    title = cleanTitle(Jsoup.parse(o.get("title")?.asString.orEmpty()).text()),
                    coverUrl = o.get("thumbnail")?.asString.orEmpty(),
                    type = MediaType.COMIC,
                    status = "",
                    description = Jsoup.parse(o.get("excerpt")?.asString.orEmpty()).text().removeSuffix("…").trim(),
                    sourceId = sourceId
                )
            }.orEmpty()

        internal fun parseChapters(root: JsonObject, mediaId: String, api: String): List<Chapter> =
            root.getAsJsonArray("chapters")?.mapIndexedNotNull { index, e ->
                val o = e.asJsonObject
                val number = o.get("number")?.asString?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val title = o.get("title")?.asString?.trim().orEmpty()
                Chapter(
                    id = "$mediaId:$number",
                    mediaId = mediaId,
                    // Parts are numbered by their first issue (1, 5, 8...): that keeps the reading order.
                    title = title.ifBlank { "Capítulo $number" },
                    chapterNumber = number.toFloatOrNull() ?: (index + 1).toFloat(),
                    url = "$api/reader/$mediaId?chapter=${SourceUtils.encode(number)}",
                    dateUploaded = ""
                )
            }.orEmpty()
    }
}
