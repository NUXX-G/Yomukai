package com.nelson.yomukai.data.source.novel

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.formatIsoDate
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/** SkyNovels is an Angular SPA backed by a public JSON API at api.skynovels.net. */
class SkyNovelsSource(private val network: HttpFetcher) : NovelSource {
    override val id = "skynovels"
    override val name = "SkyNovels"
    override val baseUrl = "https://www.skynovels.net"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.NOVEL)
    override val supportsLatestPaging = true

    private val api = "https://api.skynovels.net/api"
    private val STATUS = SelectFilter(FilterKeys.STATUS, "Estado", listOf(FilterOption("", "Todos"), FilterOption("Active", "En emisión"), FilterOption("Finished", "Finalizado")))
    private val GENRE = SelectFilter(
        FilterKeys.GENRE, "Género",
        listOf(
            "" to "Todos", "9" to "Acción", "3" to "Artes marciales", "2" to "Aventura", "40" to "BL", "7" to "Comedia", "19" to "Cultivación",
            "8" to "Drama", "21" to "Ecchi", "4" to "Fantasía", "41" to "GL", "12" to "Harem", "39" to "Horror", "16" to "Magia", "22" to "Misterio",
            "27" to "Psicológico", "23" to "Reencarnación", "5" to "Romance", "17" to "Ciencia ficción", "20" to "Sobrenatural", "24" to "Transmigración", "6" to "Xianxia"
        ).map { FilterOption(it.first, it.second) }
    )

    private suspend fun getJson(path: String) =
        parseJson(network.getString("$api$path", cloudflareFallback = false)).asJsonObject

    private fun coverUrl(image: String?) = image?.takeIf { it.isNotBlank() }
        ?.let { "$api/get-image/$it/novels/false" }.orEmpty()

    private fun toItem(n: JsonObject): MediaItem? {
        val novelId = n.str("id") ?: n.str("novel_id") ?: return null
        return MediaItem(
            id = novelId,
            title = (n.str("nvl_title") ?: n.str("novel_title") ?: return null).trim(),
            coverUrl = coverUrl(n.str("image") ?: n.str("novel_image")),
            type = MediaType.NOVEL,
            status = n.str("nvl_status").orEmpty(),
            description = n.str("nvl_content").orEmpty().trim(),
            sourceId = id,
            author = n.str("nvl_writer").orEmpty()
        )
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val home = getJson("/home")
        listOf("updatedNovels", "recentNovels", "topNovels", "viewsNovels", "finishedNovels")
            .flatMap { home.arr(it).objects() }
            .mapNotNull(::toItem)
            .distinctBy { it.id }
    }

    override val filters: List<SourceFilter> = listOf(STATUS, GENRE)

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        val query = buildString {
            append("/novels?page=${page.coerceAtLeast(1)}")
            values.single(STATUS).takeIf { it.isNotEmpty() }?.let { append("&status=$it") }
            values.single(GENRE).takeIf { it.isNotEmpty() }?.let { append("&genres=$it") }
        }
        getJson(query).arr("novels").objects().mapNotNull(::toItem)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        getJson("/novels?q=${SourceUtils.encode(query)}").arr("novels").objects().mapNotNull(::toItem)
    }

    private suspend fun readingInfo(novelId: String): JsonObject =
        getJson("/novel/$novelId/reading").arr("novel").objects().firstOrNull() ?: error("Novela no encontrada")

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val novel = readingInfo(media.id)
        val base = toItem(novel) ?: media
        base.copy(
            description = SourceUtils.normalizeParagraphs(novel.str("nvl_content").orEmpty()),
            genres = novel.arr("genres").objects().mapNotNull { it.str("genre_name") },
            status = when (novel.str("nvl_status")) {
                "Active" -> "En emisión"
                "Finished" -> "Finalizado"
                else -> novel.str("nvl_status").orEmpty()
            }
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        readingInfo(mediaId).arr("volumes").objects()
            .flatMap { it.arr("chapters").objects() }
            .filter { it.str("chp_status") != "Inactive" }
            .mapNotNull { c ->
                val chapterId = c.str("id") ?: return@mapNotNull null
                val number = c.str("chp_number")?.toFloatOrNull() ?: 0f
                val vip = c.str("isVip") == "1" && c.str("vipUnlocked") != "1"
                val url = "$api/novel-chapter/$chapterId"
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = (c.str("chp_index_title") ?: "Capítulo ${number.toInt()}") + if (vip) " (VIP)" else "",
                    chapterNumber = number,
                    url = url,
                    dateUploaded = formatIsoDate(c.str("createdAt"))
                )
            }
    }

    override suspend fun fetchChapterText(chapterUrl: String): Result<String> = runCatching {
        val chapter = getJson(chapterUrl.removePrefix(api)).arr("chapter").objects().firstOrNull()
            ?: error("Capítulo no encontrado")
        val body = SourceUtils.markdownToText(SourceUtils.htmlToText(chapter.str("chp_content").orEmpty()))
        body.ifBlank { error("Este capítulo no tiene contenido disponible (¿VIP?)") }
    }
}
