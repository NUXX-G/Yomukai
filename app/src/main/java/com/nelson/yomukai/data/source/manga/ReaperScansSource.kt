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
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType

/**
 * Reaper Scans runs on HeanCMS, which exposes a JSON API at api.reaperscans.com.
 * Experimental: the API sits behind Cloudflare bot protection that rejects datacenter IPs,
 * so it could not be verified during development; residential connections usually pass.
 * Media ids are series slugs; chapter URLs are "{seriesSlug}/{chapterSlug}" on the API.
 */
class ReaperScansSource(private val network: NetworkHelper) : MangaSource {
    override val id = "reaper"
    override val name = "Reaper Scans"
    override val baseUrl = "https://reaperscans.com"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.MANHWA)
    override val isExperimental = true
    override val supportsLatestPaging = true

    private val apiUrl = "https://api.reaperscans.com"
    private val mediaUrl = "https://media.reaperscans.com/file/4SRBHm"

    private suspend fun api(path: String): JsonObject = parseJson(
        network.getString(
            "$apiUrl$path",
            mapOf("Accept" to "application/json", "Origin" to baseUrl, "Referer" to "$baseUrl/"),
            cloudflareFallback = false
        )
    ).asJsonObject

    private fun absolute(path: String?): String = when {
        path.isNullOrBlank() -> ""
        path.startsWith("http") -> path
        else -> "$mediaUrl/${path.trimStart('/')}"
    }

    private fun item(s: JsonObject): MediaItem? {
        val slug = s.str("series_slug") ?: return null
        return MediaItem(
            id = slug,
            title = s.str("title")?.trim() ?: return null,
            coverUrl = absolute(s.str("thumbnail")),
            type = MediaType.MANHWA,
            status = s.str("status").orEmpty(),
            description = SourceUtils.htmlToText(s.str("description").orEmpty()),
            sourceId = id,
            author = listOfNotNull(s.str("author"), s.str("studio")).filter { it.isNotBlank() }.joinToString(", "),
            genres = s.arr("tags").objects().mapNotNull { it.str("name") }
        )
    }

    private suspend fun query(page: Int, search: String = ""): List<MediaItem> =
        api(
            "/query?query_string=${SourceUtils.encode(search)}&series_status=All&order=desc&orderBy=updated_at" +
                "&series_type=Comic&page=$page&perPage=20&tags_ids=%5B%5D&adult=true"
        ).arr("data").objects().mapNotNull(::item)

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching { query(page) }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching { query(1, query) }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        item(api("/series/${media.id}")) ?: media
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val seriesId = api("/series/$mediaId").str("id") ?: error("Serie no encontrada")
        api("/chapter/query?page=1&perPage=1000&series_id=$seriesId").arr("data").objects().mapNotNull { c ->
            val chapterSlug = c.str("chapter_slug") ?: return@mapNotNull null
            val name = c.str("chapter_name").orEmpty()
            val paid = (c.str("price")?.toIntOrNull() ?: 0) > 0
            val url = "$apiUrl/chapter/$mediaId/$chapterSlug"
            Chapter(
                id = url,
                mediaId = mediaId,
                title = listOfNotNull(name.ifBlank { null }, c.str("chapter_title")?.ifBlank { null }).joinToString(" - ") +
                    if (paid) " (de pago)" else "",
                chapterNumber = SourceUtils.parseChapterNumber(name, 0f),
                url = url,
                dateUploaded = formatIsoDate(c.str("created_at"))
            )
        }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val root = parseJson(network.getString(chapterUrl, mapOf("Accept" to "application/json"), cloudflareFallback = false))
            .asJsonObject
        if (root.str("paywall") == "true") error("Capítulo de pago: no disponible")
        val data = root.obj("chapter")?.obj("chapter_data") ?: error("Capítulo no disponible")
        val images = data.arr("images").strings().ifEmpty { data.arr("files").objects().mapNotNull { it.str("url") } }
        images.map(::absolute).ifEmpty { error("El capítulo no tiene imágenes") }
    }
}
