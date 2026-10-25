package com.nelson.yomukai.data.source.novel

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.formatIsoDate
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/** SkyNovels is an Angular SPA backed by a public JSON API at api.skynovels.net. */
class SkyNovelsSource(private val network: NetworkHelper) : NovelSource {
    override val id = "skynovels"
    override val name = "SkyNovels"
    override val baseUrl = "https://www.skynovels.net"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.NOVEL)

    private val api = "https://api.skynovels.net/api"

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
        val body = SourceUtils.htmlToText(chapter.str("chp_content").orEmpty())
        body.ifBlank { error("Este capítulo no tiene contenido disponible (¿VIP?)") }
    }
}
