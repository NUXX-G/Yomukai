package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit

/**
 * ZonaTMO (zonatmo.to): a big Spanish catalogue of manga, manhwa and manhua (some 30 000 titles). A different site
 * from the old ZonaTMO: a WordPress app that loads everything from its own JSON API (`/wp-api/api`), steadier than
 * its markup. A title is its slug; a chapter is "slug/chapter-slug". Pages live on a CDN under the chapter's token.
 * Text novels and English uploads are left out (this is a Spanish image source); doujinshi count as adult content
 * (hidden unless the user shows it).
 */
class ZonaTmoToSource(private val network: HttpFetcher) : MangaSource {
    override val id = "zonatmo_to"
    override val name = "ZonaTMO"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("cdn.zonatmo.to")

    private suspend fun json(path: String): JsonObject =
        JsonParser.parseString(network.getString("$API/$path", mapOf("Referer" to "$BASE/", "Accept" to "application/json"))).asJsonObject

    private suspend fun listing(page: Int, order: String, search: String = ""): List<MediaItem> {
        val query = buildString {
            append("listing/manga?page=${page.coerceAtLeast(1)}&postsPerPage=$PER_PAGE&orderBy=$order")
            if (search.isNotBlank()) append("&search=").append(SourceUtils.encode(search))
        }
        return parseListing(json(query), id)
    }

    /** Newest chapters first: what the index reads every couple of hours. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching { listing(page, "last_chapter_date") }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching { listing(1, "vote_count", query) }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val data = json("single/manga/${SourceUtils.encode(media.id)}").obj("data") ?: return@runCatching media
        item(data, id)?.let { fresh -> fresh.copy(coverUrl = fresh.coverUrl.ifBlank { media.coverUrl }) } ?: media
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        suspend fun page(n: Int) = json("single/manga/${SourceUtils.encode(mediaId)}/chapters?page=$n&order=desc").obj("data")
        // The first page says how many there are; the rest come at the same time (a few at once), so a long series
        // takes about as long as a short one.
        val first = page(1) ?: throw NoChaptersException()
        val total = first.obj("pagination")?.get("total_pages")?.takeIf { it.isJsonPrimitive }?.asInt?.coerceIn(1, MAX_CHAPTER_PAGES) ?: 1
        val limiter = kotlinx.coroutines.sync.Semaphore(PARALLEL_PAGES)
        val rest = kotlinx.coroutines.coroutineScope {
            (2..total).map { n -> async { limiter.withPermit { page(n)?.let { parseChapters(it, mediaId) }.orEmpty() } } }.awaitAll()
        }
        val all = parseChapters(first, mediaId) + rest.flatten()
        if (all.isEmpty()) throw NoChaptersException()
        all.distinctBy { it.id }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val (manga, chapter) = chapterUrl.split('/', limit = 2).takeIf { it.size == 2 } ?: error("Capítulo no válido")
        parsePages(json("single/manga/${SourceUtils.encode(manga)}/${SourceUtils.encode(chapter)}")).ifEmpty { error("El capítulo no tiene páginas") }
    }

    companion object {
        private const val BASE = "https://zonatmo.to"
        private const val API = "$BASE/wp-api/api"
        private const val UPLOADS = "$BASE/wp-content/uploads"
        private const val CDN = "https://cdn.zonatmo.to"
        private const val PER_PAGE = 30
        private const val MAX_CHAPTER_PAGES = 80
        private const val PARALLEL_PAGES = 6

        // The site's own ids (its siteConfig): types, statuses and genres.
        private val TYPES = mapOf(14 to MediaType.MANGA, 87 to MediaType.MANHWA, 31 to MediaType.MANHUA, 976 to MediaType.MANGA, 12919 to MediaType.MANGA, 12312 to MediaType.MANGA)
        private const val DOUJINSHI = 207
        private val NOVELS = setOf(214, 12920)
        private val STATUS = mapOf(
            12 to "Publicándose", 12856 to "Publicándose", 12866 to "Publicándose", 12869 to "Publicándose",
            19 to "Finalizado", 12874 to "Finalizado", 198 to "Cancelado", 174 to "En pausa", 12922 to "En pausa"
        )
        private val GENRES = mapOf(
            16 to "Romance", 4 to "Comedia", 15 to "Drama", 5 to "Fantasía", 2 to "Acción", 103 to "Boys Love", 33 to "Recuentos de la vida",
            23 to "Vida escolar", 3 to "Aventura", 32 to "Ecchi", 7 to "Sobrenatural", 60 to "Reencarnación", 8 to "Harem", 6 to "Magia",
            36 to "Psicológico", 40 to "Misterio", 26 to "Artes marciales", 21 to "Ciencia ficción", 22 to "Girls Love", 46 to "Tragedia",
            81 to "Historia", 88 to "Demonios", 116 to "Superpoderes", 82 to "Horror", 112 to "Supervivencia", 181 to "Gore", 1027 to "Familia",
            183 to "Género bender", 861 to "Apocalíptico", 41 to "Crimen", 49 to "Thriller", 37 to "Deporte", 147 to "Realidad", 342 to "Militar",
            1109 to "Guerra", 820 to "Parodia", 345 to "Vampiros", 403 to "Música", 219 to "Niños", 27 to "Realidad virtual", 144 to "Mecha",
            111 to "Policiaco", 470 to "Telenovela", 12868 to "Sistema de niveles", 99 to "Samurái", 6198 to "Animación", 356 to "Ciberpunk",
            1168 to "Extranjero", 12915 to "Academia", 12891 to "Venganza"
        )

        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        private fun JsonObject.ints(key: String): List<Int> = arr(key)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull() }.orEmpty()
        private fun JsonObject.strings(key: String): List<String> =
            arr(key)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() } }.orEmpty()

        /** One title of the API, or null when it is not a Spanish image work (a text novel, an English upload). */
        fun item(o: JsonObject, sourceId: String): MediaItem? {
            val slug = o.str("slug").ifBlank { return null }
            val title = o.str("title").trim().ifBlank { return null }
            if (title.startsWith("[English]", ignoreCase = true) || title.startsWith("[EN]", ignoreCase = true)) return null
            val types = o.ints("types")
            if (types.any { it in NOVELS }) return null
            val type = types.firstNotNullOfOrNull { TYPES[it] } ?: MediaType.MANGA
            val genres = o.ints("genres").mapNotNull { GENRES[it] } + (if (DOUJINSHI in types) listOf("Adulto", "Doujinshi") else emptyList())
            val cover = o.str("cover").let { if (it.startsWith("http")) it else if (it.isNotBlank()) UPLOADS + it else "" }
            return MediaItem(
                id = slug, title = title, coverUrl = cover, type = type,
                status = o.ints("status").firstNotNullOfOrNull { STATUS[it] }.orEmpty(),
                description = o.str("overview").trim(), sourceId = sourceId, genres = genres.distinct(),
                altTitles = (o.strings("alt_titles") + o.strings("synonyms") + o.str("subtitle")).filter { it.isNotBlank() && it != title }.distinct()
            )
        }

        fun parseListing(root: JsonObject, sourceId: String): List<MediaItem> =
            root.obj("data")?.arr("items")?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let { item(it, sourceId) } }.orEmpty()

        /** A page of chapters (Spanish only; one per number, the newest upload of it). */
        fun parseChapters(data: JsonObject, mediaId: String): List<Chapter> =
            data.arr("items")?.mapNotNull { e ->
                val c = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                if (c.str("language").let { it.isNotBlank() && it != "es" }) return@mapNotNull null
                val slug = c.str("slug").ifBlank { return@mapNotNull null }
                val number = c.str("chapter_number").toFloatOrNull() ?: -1f
                val url = "$mediaId/$slug"
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                Chapter(url, mediaId, c.str("title").ifBlank { "Capítulo $label" }, number, url, c.str("release_date").take(10))
            }.orEmpty()

        /** The chapter's page images: the CDN, the chapter's token, then each file. */
        fun parsePages(root: JsonObject): List<String> {
            val chapter = root.obj("data")?.obj("chapter") ?: return emptyList()
            val token = chapter.str("jit").ifBlank { return emptyList() }
            return chapter.arr("images")?.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject }
                ?.sortedBy { it.str("page_number").toIntOrNull() ?: Int.MAX_VALUE }
                ?.mapNotNull { img -> img.str("image_url").takeIf { it.isNotBlank() }?.let { file -> if (file.startsWith("http")) file else "$CDN/manga/$token/$file" } }
                .orEmpty()
        }
    }
}
