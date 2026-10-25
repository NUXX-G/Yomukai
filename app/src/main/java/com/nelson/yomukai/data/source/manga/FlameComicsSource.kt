package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.formatEpochSeconds
import com.nelson.yomukai.data.source.nextPageProps
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

/**
 * Flame Comics is a Next.js site: every page embeds its data as JSON in __NEXT_DATA__,
 * which is far more stable than scraping the rendered markup.
 */
class FlameComicsSource(private val network: NetworkHelper) : MangaSource {
    override val id = "flame"
    override val name = "Flame Comics"
    override val baseUrl = "https://flamecomics.xyz"
    override val language = "EN"
    override val supportedTypes = setOf(MediaType.MANHWA, MediaType.MANHUA, MediaType.MANGA)

    private val cdn = "https://cdn.flamecomics.xyz/uploads/images/series"
    private val catalogLock = Mutex()
    private var catalogCache: List<MediaItem>? = null

    private fun mediaId(seriesId: String) = "$baseUrl/series/$seriesId"
    private fun seriesIdOf(mediaId: String) = mediaId.trimEnd('/').substringAfterLast("/")

    private fun typeOf(raw: String?) = when (raw?.lowercase()) {
        "manhua" -> MediaType.MANHUA
        "manga" -> MediaType.MANGA
        else -> MediaType.MANHWA
    }

    private fun seriesToItem(s: JsonObject): MediaItem? {
        val seriesId = s.str("series_id") ?: return null
        val title = s.str("title") ?: return null
        return MediaItem(
            id = mediaId(seriesId),
            title = title.trim(),
            coverUrl = s.str("cover")?.let { "$cdn/$seriesId/$it" }.orEmpty(),
            type = typeOf(s.str("type")),
            status = s.str("status").orEmpty(),
            description = "",
            sourceId = id,
            genres = s.arr("tags").strings()
        )
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val props = nextPageProps(network.getString(baseUrl))
        listOf("latestEntries", "popularEntries", "staffPicks")
            .flatMap { key -> props.obj(key)?.arr("blocks").objects() }
            .flatMap { block -> block.arr("series").objects() }
            .mapNotNull(::seriesToItem)
            .distinctBy { it.id }
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val needle = normalize(query)
        allSeries().filter { normalize(it.title).contains(needle) }
    }

    /** /api/series returns the full (small) catalogue; cache it for the session. */
    private suspend fun allSeries(): List<MediaItem> = catalogLock.withLock {
        catalogCache ?: parseJson(network.getString("$baseUrl/api/series")).asJsonArray.objects().mapNotNull { s ->
            val sid = s.str("id") ?: return@mapNotNull null
            MediaItem(
                id = mediaId(sid),
                title = s.str("label").orEmpty().trim(),
                coverUrl = s.str("image")?.let { "$cdn/$sid/$it" }.orEmpty(),
                type = MediaType.MANHWA,
                status = s.str("status").orEmpty(),
                description = "",
                sourceId = id
            )
        }.also { catalogCache = it }
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        val series = nextPageProps(network.getString(media.id)).obj("series") ?: error("Serie no encontrada")
        val base = seriesToItem(series) ?: media
        base.copy(
            description = SourceUtils.htmlToText(series.str("description").orEmpty()),
            author = (series.arr("author").strings() + series.arr("artist").strings()).distinct().joinToString(", ")
        )
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val seriesId = seriesIdOf(mediaId)
        val props = nextPageProps(network.getString(mediaId(seriesId)))
        props.arr("chapters").objects().mapNotNull { c ->
            val token = c.str("token") ?: return@mapNotNull null
            val numberRaw = c.str("chapter").orEmpty()
            val number = numberRaw.toFloatOrNull() ?: 0f
            val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
            val extra = c.str("title").orEmpty().trim()
            val url = "${mediaId(seriesId)}/$token"
            Chapter(
                id = url,
                mediaId = mediaId,
                title = if (extra.isNotEmpty()) "Chapter $label - $extra" else "Chapter $label",
                chapterNumber = number,
                url = url,
                dateUploaded = formatEpochSeconds(c.str("release_date")?.toLongOrNull())
            )
        }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        val chapter = nextPageProps(network.getString(chapterUrl)).obj("chapter") ?: error("Capítulo no encontrado")
        val seriesId = chapter.str("series_id") ?: seriesIdOf(chapterUrl.substringBeforeLast("/"))
        val token = chapter.str("token") ?: chapterUrl.substringAfterLast("/")
        val images = chapter.obj("images") ?: error("El capítulo no tiene imágenes")
        images.entrySet()
            .sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { (_, value) -> value.takeIf { it.isJsonObject }?.asJsonObject?.str("name") }
            .map { "$cdn/$seriesId/$token/$it" }
            .ifEmpty { error("El capítulo no tiene imágenes") }
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
}
