package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.CommonSort
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.MultiSelectFilter
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.selected
import com.nelson.yomukai.data.source.single
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

/** MangaDex public JSON API (https://api.mangadex.org/docs). */
class MangaDexSource(private val network: NetworkHelper) : MangaSource {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val baseUrl = "https://api.mangadex.org"
    override val language = "MULTI"
    override val supportedTypes = setOf(MediaType.MANGA, MediaType.MANHWA, MediaType.MANHUA, MediaType.COMIC)
    override val supportsLatestPaging = true
    override val imageHeaders = mapOf("Referer" to "https://mangadex.org/")

    private val headers = mapOf("User-Agent" to "Yomukai/1.0 (Android)")
    private val languages = listOf("es", "es-la", "en")
    private val pageSize = 24

    private val commonQuery = buildString {
        append("includes[]=cover_art&includes[]=author")
        append("&contentRating[]=safe&contentRating[]=suggestive")
        languages.forEach { append("&availableTranslatedLanguage[]=$it") }
    }

    private suspend fun get(path: String): JsonObject {
        val root = parseJson(network.getString("$baseUrl$path", headers, cloudflareFallback = false)).asJsonObject
        if (root.str("result") == "error") error("MangaDex: ${root.arr("errors").objects().firstOrNull()?.str("detail")}")
        return root
    }

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        get(catalogueRequest(page, values, pageSize)).arr("data").objects().map(::toMediaItem)
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val offset = (page - 1).coerceAtLeast(0) * pageSize
        get("/manga?limit=$pageSize&offset=$offset&order[latestUploadedChapter]=desc&hasAvailableChapters=true&$commonQuery")
            .arr("data").objects().map(::toMediaItem)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        get("/manga?title=${SourceUtils.encode(query)}&limit=$pageSize&order[relevance]=desc&$commonQuery")
            .arr("data").objects().map(::toMediaItem)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val mangaId = media.id.substringAfterLast("/")
        val data = get("/manga/$mangaId?includes[]=cover_art&includes[]=author&includes[]=artist").obj("data")
            ?: error("Manga no encontrado")
        toMediaItem(data)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val mangaId = mediaId.substringAfterLast("/")
        val langQuery = languages.joinToString("&") { "translatedLanguage[]=$it" }
        val chapters = mutableListOf<Chapter>()
        var offset = 0
        var total: Int
        do {
            val root = get(
                "/manga/$mangaId/feed?limit=500&offset=$offset&$langQuery&order[chapter]=desc" +
                    "&includeExternalUrl=0&includeEmptyPages=0&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
            )
            total = root.get("total")?.asInt ?: 0
            root.arr("data").objects().forEach { ch ->
                val attrs = ch.obj("attributes") ?: return@forEach
                if (attrs.str("externalUrl") != null) return@forEach
                val numberRaw = attrs.str("chapter")
                val lang = attrs.str("translatedLanguage").orEmpty().uppercase()
                val title = attrs.str("title").orEmpty()
                val chapterId = ch.str("id") ?: return@forEach
                val label = numberRaw?.let { "Capítulo $it" } ?: "Oneshot"
                chapters += Chapter(
                    id = chapterId,
                    mediaId = mangaId,
                    title = buildString {
                        append(label)
                        if (title.isNotBlank()) append(": ").append(title)
                        append(" [").append(lang).append("]")
                    },
                    chapterNumber = numberRaw?.toFloatOrNull() ?: 0f,
                    url = "https://mangadex.org/chapter/$chapterId",
                    dateUploaded = formatIsoDate(attrs.str("publishAt"))
                )
            }
            offset += 500
        } while (offset < total && offset < 5000)
        // The same chapter is often uploaded by several groups; keep one per number+language.
        chapters.distinctBy { it.chapterNumber to it.title.substringAfterLast("[") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val chapterId = chapterUrl.substringAfterLast("/")
        val root = get("/at-home/server/$chapterId")
        val host = root.str("baseUrl") ?: error("Servidor de imágenes no disponible")
        val chapter = root.obj("chapter") ?: error("Capítulo no disponible")
        val hash = chapter.str("hash")
        chapter.arr("data")?.map { "$host/data/$hash/${it.asString}" }
            ?.ifEmpty { null } ?: error("El capítulo no tiene páginas")
    }

    private fun toMediaItem(manga: JsonObject): MediaItem {
        val mangaId = manga.str("id").orEmpty()
        val attrs = manga.obj("attributes") ?: JsonObject()
        val titles = attrs.obj("title")
        val altTitles = attrs.arr("altTitles").objects()
        val title = titles?.str("es") ?: titles?.str("en") ?: titles?.str("ja-ro")
            ?: altTitles.firstNotNullOfOrNull { it.str("es") ?: it.str("en") }
            ?: titles?.entrySet()?.firstOrNull()?.value?.asString ?: "Sin título"
        val description = attrs.obj("description").let { it?.str("es") ?: it?.str("es-la") ?: it?.str("en") }.orEmpty()

        val relationships = manga.arr("relationships").objects()
        val coverFile = relationships.firstOrNull { it.str("type") == "cover_art" }?.obj("attributes")?.str("fileName")
        val authors = relationships.filter { it.str("type") == "author" || it.str("type") == "artist" }
            .mapNotNull { it.obj("attributes")?.str("name") }.distinct()

        val genres = attrs.arr("tags").objects().mapNotNull { tag ->
            tag.obj("attributes")?.obj("name")?.let { it.str("es") ?: it.str("en") }
        }

        return MediaItem(
            id = mangaId,
            title = title,
            coverUrl = coverFile?.let { "https://uploads.mangadex.org/covers/$mangaId/$it.512.jpg" }.orEmpty(),
            type = when (attrs.str("originalLanguage")) {
                "ko" -> MediaType.MANHWA
                "zh", "zh-hk" -> MediaType.MANHUA
                "en" -> MediaType.COMIC
                else -> MediaType.MANGA
            },
            status = attrs.str("status").orEmpty().replaceFirstChar { it.uppercase() },
            description = description.replace(Regex("""\[(.+?)]\(.+?\)"""), "$1").trim(),
            sourceId = id,
            author = authors.joinToString(", "),
            genres = genres,
            altTitles = altTitles.flatMap { alt -> alt.entrySet().mapNotNull { e -> e.value.takeIf { it.isJsonPrimitive }?.asString } }
        )
    }

    companion object {
        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(
                FilterOption(CommonSort.LATEST, "Recientes"),
                FilterOption(CommonSort.POPULAR, "Populares"),
                FilterOption(CommonSort.RATING, "Mejor valorados"),
                FilterOption(CommonSort.TITLE, "A-Z")
            )
        )
        private val STATUS = MultiSelectFilter(
            FilterKeys.STATUS, "Estado",
            listOf(
                FilterOption("ongoing", "En emisión"), FilterOption("completed", "Completado"),
                FilterOption("hiatus", "En pausa"), FilterOption("cancelled", "Cancelado")
            )
        )
        private val TYPE = MultiSelectFilter(
            FilterKeys.TYPE, "Tipo",
            listOf(
                FilterOption("ja", "Manga"), FilterOption("ko", "Manhwa"),
                FilterOption("zh", "Manhua"), FilterOption("en", "Cómic")
            )
        )
        private val LANGUAGE = SelectFilter(
            FilterKeys.LANGUAGE, "Idioma de capítulos",
            listOf(FilterOption("all", "Español e inglés"), FilterOption("es", "Español"), FilterOption("en", "Inglés"))
        )
        /** Genre tag ids from https://api.mangadex.org/manga/tag. */
        private val GENRES = MultiSelectFilter(
            FilterKeys.GENRE, "Género",
            listOf(
                "391b0423-d847-456f-aff0-8b0cfc03066b" to "Acción", "87cc87cd-a395-47af-b27a-93258283bbc6" to "Aventura",
                "4d32cc48-9f00-4cca-9b5a-a839f0764984" to "Comedia", "b9af3a63-f058-46de-a9a0-e0c13906197a" to "Drama",
                "cdc58593-87dd-415e-bbc0-2ec27bf404cc" to "Fantasía", "33771934-028e-4cb3-8744-691e866a923e" to "Histórico",
                "cdad7e68-1419-41dd-bdce-27753074a640" to "Terror", "ace04997-f6bd-436e-b261-779182193d3d" to "Isekai",
                "50880a9d-5440-4732-9afb-8f457127e836" to "Mecha", "ee968100-4191-4968-93d3-f82d72be7e46" to "Misterio",
                "3b60b75c-a2d7-4860-ab56-05f391bb889c" to "Psicológico", "423e2eae-a7a2-4a8b-ac03-a8351462d71d" to "Romance",
                "256c8bd9-4904-4360-bf4f-508a76d67183" to "Ciencia ficción", "e5301a23-ebd9-49dd-a0cb-2add944c7fe9" to "Recuentos de la vida",
                "69964a64-2f90-4d33-beeb-f3ed2875eb4c" to "Deportes", "7064a261-a137-4d3a-8848-2d385de3a99c" to "Superhéroes",
                "07251805-a27e-4d59-b488-f0bfbec15168" to "Suspenso", "acc803a4-c95a-4c22-86fc-eb6b582d82a2" to "Wuxia"
            ).map { FilterOption(it.first, it.second) }
        )
        val FILTERS: List<SourceFilter> = listOf(SORT, STATUS, TYPE, GENRES, LANGUAGE)

        private val CONTENT_LANGS = listOf("es", "es-la", "en")

        /** Request path (with query) for one catalogue page; pure so it can be unit tested. */
        fun catalogueRequest(page: Int, values: FilterValues, pageSize: Int): String {
            val order = when (values.single(SORT)) {
                CommonSort.POPULAR -> "order[followedCount]=desc"
                CommonSort.RATING -> "order[rating]=desc"
                CommonSort.TITLE -> "order[title]=asc"
                else -> "order[latestUploadedChapter]=desc"
            }
            val langs = when (values.single(LANGUAGE)) {
                "es" -> listOf("es", "es-la")
                "en" -> listOf("en")
                else -> CONTENT_LANGS
            }
            return buildString {
                append("/manga?limit=$pageSize&offset=${(page - 1).coerceAtLeast(0) * pageSize}&$order&hasAvailableChapters=true")
                append("&includes[]=cover_art&includes[]=author&contentRating[]=safe&contentRating[]=suggestive")
                langs.forEach { append("&availableTranslatedLanguage[]=$it") }
                values.selected(FilterKeys.STATUS).forEach { append("&status[]=$it") }
                values.selected(FilterKeys.TYPE).forEach { append("&originalLanguage[]=$it") }
                values.selected(FilterKeys.GENRE).forEach { append("&includedTags[]=$it") }
            }
        }
    }
}
