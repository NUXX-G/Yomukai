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
import java.time.Instant
import java.time.ZoneOffset

/**
 * ManhwaWeb (manhwaweb.com): Spanish manhwa, manga and manhua. A single-page app over its own JSON API
 * (api.manhwaweb.com): the library (paged from 0, with search and order), the newest chapters, a title's page with
 * its chapters, and each chapter's images. The API says which titles are erotic: those count as adult content
 * (hidden unless the user shows it). Images need the site as Referer.
 */
class ManhwaWebSource(private val network: HttpFetcher) : MangaSource {
    override val id = "manhwaweb"
    override val name = "ManhwaWeb"
    override val baseUrl = SITE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.MANHWA, MediaType.MANGA, MediaType.MANHUA)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("api.manhwaweb.com", "img1mw.xyz", "img2mw.xyz")
    override val imageHeaders = mapOf("Referer" to "$SITE/")

    private suspend fun json(path: String): JsonObject =
        JsonParser.parseString(network.getString("$API/$path", mapOf("Referer" to "$SITE/", "Origin" to SITE, "Accept" to "application/json"))).asJsonObject

    /** The library, [page] from 1 (the API counts from 0). */
    private suspend fun library(page: Int, order: String, search: String = ""): List<MediaItem> =
        parseLibrary(
            json(
                "manhwa/library?buscar=${SourceUtils.encode(search)}&estado=&tipo=&erotico=&demografia=" +
                    "&order_item=$order&order_dir=desc&page=${(page - 1).coerceAtLeast(0)}&generes="
            ),
            id
        )

    /** Page 1: the newest chapters (in Spanish); then the library by newest titles, for the index's full crawl. */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseLatest(json("latest/new-manhwa"), id) else library(page - 1, "creacion")
    }

    override suspend fun browse(page: Int, values: com.nelson.yomukai.data.source.FilterValues): Result<List<MediaItem>> =
        runCatching { library(page, "creacion") }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching { library(1, "alfabetico", query) }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(json("manhwa/see/${SourceUtils.encode(media.id)}"), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseChapters(json("manhwa/see/${SourceUtils.encode(mediaId)}"), mediaId).ifEmpty { throw NoChaptersException() }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(json("chapters/see/${SourceUtils.encode(chapterUrl)}")).ifEmpty { error("El capítulo no tiene páginas") }
    }

    companion object {
        private const val SITE = "https://manhwaweb.com"
        private const val API = "https://api.manhwaweb.com"

        private val TYPES = mapOf("manhwa" to MediaType.MANHWA, "manga" to MediaType.MANGA, "manhua" to MediaType.MANHUA)
        private val STATUS = mapOf("publicandose" to "Publicándose", "finalizado" to "Finalizado", "pausado" to "En pausa", "cancelado" to "Cancelado")
        private val GENRES = mapOf(
            3 to "Acción", 29 to "Aventura", 18 to "Comedia", 1 to "Drama", 42 to "Recuentos de la vida", 2 to "Romance", 5 to "Venganza",
            6 to "Harem", 23 to "Fantasía", 31 to "Sobrenatural", 25 to "Tragedia", 43 to "Psicológico", 32 to "Horror", 44 to "Thriller",
            28 to "Historias cortas", 30 to "Ecchi", 34 to "Gore", 27 to "Girls Love", 45 to "Boys Love", 41 to "Reencarnación",
            37 to "Sistema de niveles", 33 to "Ciencia ficción", 38 to "Apocalíptico", 39 to "Artes marciales", 40 to "Superpoderes",
            35 to "Cultivación", 8 to "Milf"
        )

        private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        private fun JsonObject.arr(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        private fun JsonArray.objects() = mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }

        private fun genresOf(o: JsonObject): List<String> {
            // Lists give ids; a title's page gives {"id": "name"} objects.
            val raw = o.arr("_categoris") ?: return emptyList()
            val names = raw.mapNotNull { e ->
                when {
                    e.isJsonPrimitive -> e.asString.toIntOrNull()?.let(GENRES::get)
                    e.isJsonObject -> e.asJsonObject.entrySet().firstOrNull()?.let { (k, v) -> GENRES[k.toIntOrNull()] ?: v.takeIf { it.isJsonPrimitive }?.asString }
                    else -> null
                }
            }
            return (names + if (o.str("_erotico") == "si") listOf("Adulto") else emptyList()).distinct()
        }

        fun item(o: JsonObject, sourceId: String): MediaItem? {
            val id = o.str("real_id").ifBlank { o.str("_id") }.ifBlank { return null }
            val title = o.str("name_esp").ifBlank { o.str("the_real_name") }.trim().ifBlank { return null }
            return MediaItem(
                id = id, title = title, coverUrl = o.str("_imagen"), type = TYPES[o.str("_tipo")] ?: MediaType.MANHWA,
                status = STATUS[o.str("_status")].orEmpty(), description = o.str("_sinopsis").trim(), sourceId = sourceId,
                genres = genresOf(o), altTitles = listOf(o.str("the_real_name"), o.str("name_raw")).filter { it.isNotBlank() && it != title }.distinct()
            )
        }

        fun parseLibrary(root: JsonObject, sourceId: String): List<MediaItem> =
            root.arr("data")?.objects()?.mapNotNull { item(it, sourceId) }.orEmpty()

        /** The newest chapters in Spanish, as titles (one per title). */
        fun parseLatest(root: JsonObject, sourceId: String): List<MediaItem> =
            root.obj("manhwas")?.arr("manhwas_esp")?.objects()?.mapNotNull { o ->
                val id = o.str("id_manhwa").ifBlank { return@mapNotNull null }
                val title = o.str("name_manhwa").trim().ifBlank { return@mapNotNull null }
                MediaItem(id, title, o.str("img"), TYPES[o.str("_tipo")] ?: MediaType.MANHWA, "", "", sourceId)
            }?.distinctBy { it.id }.orEmpty()

        fun parseDetails(o: JsonObject, media: MediaItem): MediaItem {
            val fresh = item(o, media.sourceId) ?: return media
            val authors = (o.arr("autor")?.objects()?.map { it.str("name") }.orEmpty() + o.obj("_extras")?.arr("autores")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty())
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            val others = o.arr("others_name")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString?.trim() }.orEmpty()
            return fresh.copy(
                coverUrl = fresh.coverUrl.ifBlank { media.coverUrl }, author = authors.joinToString(", "),
                altTitles = (fresh.altTitles + others).filter { it.isNotBlank() && it != fresh.title }.distinct()
            )
        }

        /** Every chapter with a reader on the site: its id (the last part of its link) is what the API reads. */
        fun parseChapters(o: JsonObject, mediaId: String): List<Chapter> =
            ((o.arr("chapters_esp")?.objects().orEmpty()) + (o.arr("chapters")?.objects().orEmpty())).mapNotNull { c ->
                // The link is on the chapter, or (several uploads of it) on its first version.
                val link = c.str("link").takeIf { it.contains("/leer/") }
                    ?: c.arr("versions")?.objects()?.firstNotNullOfOrNull { v -> v.str("link").takeIf { it.contains("/leer/") } }
                    ?: return@mapNotNull null
                val chapterId = link.substringAfter("/leer/").substringBefore('?').trimEnd('/').ifBlank { return@mapNotNull null }
                val number = c.str("chapter").toFloatOrNull() ?: -1f
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                val created = c.str("create").ifBlank { c.arr("versions")?.objects()?.firstOrNull()?.str("create").orEmpty() }
                val date = created.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }.orEmpty()
                Chapter(chapterId, mediaId, "Capítulo $label", number, chapterId, date)
            }.distinctBy { it.id }

        fun parsePages(root: JsonObject): List<String> =
            root.obj("chapter")?.arr("img")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.startsWith("https://") } }.orEmpty()
    }
}
