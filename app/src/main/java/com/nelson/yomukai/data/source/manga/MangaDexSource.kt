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
            genres = genres
        )
    }
}
