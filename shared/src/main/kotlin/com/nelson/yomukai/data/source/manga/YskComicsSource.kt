package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.source.NoChaptersException
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup

/**
 * YSK Comics (ysk-comics.com): US comics in English (Marvel, DC, Image...), read online. The site is a
 * Nuxt app over a JSON API at `api.ysk-comics.com/api/v1`: latest series, search, a series with its
 * paged issue list, and each issue's page images on their CDN. Titles and issues are addressed by slug.
 */
class YskComicsSource(private val network: HttpFetcher) : MangaSource {
    override val id = "yskcomics"
    override val name = "YSK Comics"
    override val baseUrl = "https://www.ysk-comics.com"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.COMIC)
    override val supportsLatestPaging = true
    override val extraHosts = listOf("api.ysk-comics.com", "cdn.ysk-comics.com")

    private val api = "https://api.ysk-comics.com/api/v1"

    private suspend fun json(url: String): JsonObject = JsonParser.parseString(
        network.getString(url, mapOf("Accept" to "application/json", "X-Localization" to "en", "Referer" to "$baseUrl/"))
    ).asJsonObject

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseList(json("$api/home/latest-comics?page=$page").getAsJsonObject("data")?.get("data_messages"), id)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseList(json("$api/search-comics-home?name=${SourceUtils.encode(query)}").get("data"), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val d = json("$api/comics/${SourceUtils.encode(media.id)}").getAsJsonObject("data")
        val genres = d.getAsJsonArray("genres")?.mapNotNull { it.asJsonObject.str("name").takeIf { n -> n.isNotBlank() } }.orEmpty()
        media.copy(
            title = d.str("full_name").ifBlank { media.title },
            coverUrl = d.str("image").ifBlank { media.coverUrl },
            description = Jsoup.parse(d.str("description")).text().ifBlank { media.description },
            author = d.get("writer")?.takeIf { it.isJsonObject }?.asJsonObject?.str("name").orEmpty().ifBlank { media.author },
            status = when (d.str("status").lowercase()) { "ongoing" -> "En emisión"; "completed" -> "Finalizado"; else -> d.str("status") },
            genres = (genres + listOfNotNull(d.get("publisher")?.takeIf { it.isJsonObject }?.asJsonObject?.str("name")?.takeIf { it.isNotBlank() })).distinct(),
            type = MediaType.COMIC,
            sourceId = id
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val all = mutableListOf<Chapter>()
        var page = 1
        // Issues come twelve to a page; long runs have many pages.
        while (page <= MAX_CHAPTER_PAGES) {
            val data = json("$api/comics/${SourceUtils.encode(mediaId)}/chapters?page=$page").getAsJsonObject("data") ?: break
            all += parseChapters(data.get("data_messages"), mediaId, api)
            val total = data.getAsJsonObject("meta")?.get("total_pages")?.asInt ?: 1
            if (page >= total) break
            page++
        }
        all.ifEmpty { throw NoChaptersException("Sin capítulos disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        json(chapterUrl).getAsJsonArray("data")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }
            ?.filter { it.startsWith("https://") }.orEmpty().ifEmpty { error("El capítulo no tiene imágenes") }
    }

    companion object {
        private const val MAX_CHAPTER_PAGES = 60

        private fun JsonObject.str(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        internal fun parseList(items: JsonElement?, sourceId: String): List<MediaItem> =
            items?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.asJsonObject
                val slug = o.str("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                MediaItem(
                    id = slug, title = o.str("full_name"), coverUrl = o.str("image"), type = MediaType.COMIC,
                    status = "", description = "", sourceId = sourceId, author = o.str("writer"),
                    genres = o.getAsJsonArray("genres")?.mapNotNull { g -> g.takeIf { it.isJsonObject }?.asJsonObject?.str("name") }.orEmpty()
                )
            }.orEmpty()

        internal fun parseChapters(items: JsonElement?, mediaId: String, api: String): List<Chapter> =
            items?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { e ->
                val o = e.asJsonObject
                val slug = o.str("slug").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val number = o.str("rank").toFloatOrNull() ?: Regex("(\\d+(?:\\.\\d+)?)#?\\s*$").find(o.str("name"))?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                Chapter(
                    id = slug, mediaId = mediaId,
                    // "Batman (2025) 1#" -> "#1": the series name is already on the title page.
                    title = "#" + number.toString().removeSuffix(".0"),
                    chapterNumber = number,
                    url = "$api/chapters/${SourceUtils.encode(slug)}/images",
                    dateUploaded = ""
                )
            }.orEmpty()
    }
}
