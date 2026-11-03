package com.nelson.yomukai.data.source.manga

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.FilterKeys
import com.nelson.yomukai.data.source.FilterOption
import com.nelson.yomukai.data.source.FilterValues
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.SelectFilter
import com.nelson.yomukai.data.source.SourceFilter
import com.nelson.yomukai.data.source.SourceUtils
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.single
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WEBTOON (m.webtoons.com), one source per language so each shows up under its own filter. Episodes come from the mobile site's JSON API,
 * pages from the `imageList` of the viewer. Search and genre listings are only complete on the
 * desktop site, reached with a desktop User-Agent. Media ids are the absolute mobile list URL,
 * which also tells originals and Canvas apart.
 */
class WebtoonsSource(private val network: HttpFetcher, private val siteLanguage: String) : MangaSource {
    // The Spanish source keeps the original id: titles already in a library stay attached to it.
    override val id = if (siteLanguage == "es") "webtoons" else "webtoons_$siteLanguage"
    override val name = "WEBTOON ${siteLanguage.uppercase()}"
    override val baseUrl = MOBILE
    override val extraHosts = listOf("https://www.webtoons.com", "https://webtoon-phinf.pstatic.net")
    override val language = siteLanguage.uppercase()
    override val supportedTypes = setOf(MediaType.MANHWA)

    private val referer = mapOf("Referer" to "$MOBILE/")

    private suspend fun desktop(url: String) = network.getString(url, mapOf("User-Agent" to DESKTOP_AGENT))

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        val genre = values.single(GENRE)
        if (genre.isEmpty()) parseCards(network.getString("$MOBILE/$siteLanguage/originals", referer), id)
        else parseCards(desktop("$DESKTOP/$siteLanguage/genres/$genre?webtoon-platform-redirect=true"), id)
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseCards(network.getString("$MOBILE/$siteLanguage/originals", referer), id)
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseCards(desktop("$DESKTOP/$siteLanguage/search?keyword=${SourceUtils.encode(query)}&webtoon-platform-redirect=true"), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(network.getString(media.id, referer), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val titleNo = titleNo(mediaId) ?: error("Título no válido")
        val kind = if ("/canvas/" in mediaId) "canvas" else "webtoon"
        suspend fun page(cursor: Int?): JsonObject {
            val path = "$MOBILE/api/v1/$kind/$titleNo/episodes?pageSize=$PAGE_SIZE" + (cursor?.let { "&cursor=$it" } ?: "")
            return parseJson(
                network.getString(path, mapOf("Accept" to "application/json", "Referer" to mediaId), cloudflareFallback = false)
            ).asJsonObject.obj("result") ?: error("WEBTOON no devolvió episodios")
        }
        // Each page takes about a second, so the rest are asked for together. The cursor is the last
        // episode number, which the title page says how far it goes.
        val first = page(null)
        val chapters = parseEpisodes(first, mediaId).toMutableList()
        var next = first.str("nextCursor")?.toIntOrNull()?.takeIf { it > 0 }
        if (next != null) {
            val total = TOTAL_EPISODES.find(network.getString(mediaId, referer))?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val limiter = Semaphore(PARALLEL_PAGES)
            chapters += coroutineScope {
                (next!! until minOf(total, MAX_EPISODES) step PAGE_SIZE).map { cursor ->
                    async { limiter.withPermit { page(cursor) } }
                }.awaitAll()
            }.flatMap { parseEpisodes(it, mediaId) }
            // Episode numbers can have gaps: whatever is left is walked one page at a time.
            next = chapters.maxOfOrNull { it.chapterNumber.toInt() }?.takeIf { chapters.size < total }
            while (next != null && chapters.size < MAX_EPISODES) {
                val result = page(next)
                val fresh = parseEpisodes(result, mediaId)
                chapters += fresh
                next = result.str("nextCursor")?.toIntOrNull()?.takeIf { it > 0 && fresh.isNotEmpty() }
            }
        }
        chapters.distinctBy { it.id }.ifEmpty { error("Sin episodios disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(network.getString(chapterUrl, referer))
            .ifEmpty { error("Episodio sin imágenes (¿de pago o solo para la app?)") }
    }

    companion object {
        private const val MOBILE = "https://m.webtoons.com"
        private const val DESKTOP = "https://www.webtoons.com"
        private const val DESKTOP_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        private const val PAGE_SIZE = 30
        private const val PARALLEL_PAGES = 5
        private val TOTAL_EPISODES = Regex(""""totalEpisodeCount":(\d+)""")
        private const val MAX_EPISODES = 3000

        private val TITLE_NO = Regex("""title_no=(\d+)""")
        // Android's regex engine rejects an unescaped closing brace, unlike the JVM's.
        private val DTO = Regex("""dto:\s*(\{.*\})\s*,?\s*$""", RegexOption.MULTILINE)
        private val IMAGE_LIST = Regex("""var\s+imageList\s*=\s*\[(.*?)];""", RegexOption.DOT_MATCHES_ALL)
        private val IMAGE_URL = Regex("""url:\s*"([^"]+)"""")

        private val GENRE = SelectFilter(
            FilterKeys.GENRE, "Género",
            listOf(
                "" to "Destacados", "action" to "Acción", "comedy" to "Comedia", "drama" to "Drama", "fantasy" to "Fantasía",
                "heartwarming" to "Reconfortante", "historical" to "Histórico", "horror" to "Terror", "mystery" to "Misterio",
                "romance" to "Romance", "sf" to "Ciencia ficción", "slice_of_life" to "Recuentos de la vida", "sports" to "Deportes",
                "super_hero" to "Superhéroes", "supernatural" to "Sobrenatural", "thriller" to "Suspenso"
            ).map { FilterOption(it.first, it.second) }
        )
        val FILTERS: List<SourceFilter> = listOf(GENRE)

        private fun titleNo(url: String) = TITLE_NO.find(url)?.groupValues?.get(1)

        /** URLs from the site may hold raw spaces, which are not valid for downloads. */
        private fun clean(url: String) = url.trim().replace(" ", "%20")

        private fun mobile(url: String) = url.replace("://www.webtoons.com", "://m.webtoons.com")

        fun parseCards(html: String, sourceId: String = "webtoons"): List<MediaItem> =
            Jsoup.parse(html, MOBILE).select("a[data-title-no][href*=title_no=]").mapNotNull { card ->
                val url = mobile(card.absUrl("href")).ifBlank { return@mapNotNull null }
                val title = card.selectFirst(".title, .subj")?.text()?.trim().orEmpty()
                if (title.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = clean(card.selectFirst("img")?.attr("src").orEmpty()),
                    type = MediaType.MANHWA,
                    status = "",
                    description = "",
                    sourceId = sourceId,
                    author = card.selectFirst(".author")?.text()?.trim().orEmpty(),
                    genres = listOfNotNull(card.selectFirst(".genre")?.text()?.trim()?.ifBlank { null })
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, media.id)
            val dto = DTO.find(html)?.groupValues?.get(1)?.let { runCatching { parseJson(it).asJsonObject }.getOrNull() }
            val title = dto?.obj("title")
            return media.copy(
                title = title?.str("title") ?: doc.selectFirst("strong.subject")?.text()?.trim() ?: media.title,
                coverUrl = media.coverUrl.ifBlank { clean(doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()) },
                description = title?.str("synopsis")?.trim() ?: doc.selectFirst(".summary")?.text()?.trim().orEmpty(),
                author = doc.selectFirst(".info_area .author")?.text()?.replace(Regex("\\s*,\\s*"), ", ")?.trim().orEmpty(),
                genres = listOfNotNull(title?.obj("representGenre")?.str("displayName")).ifEmpty {
                    dto?.obj("tag")?.arr("tagList")?.map { it.asString }.orEmpty()
                },
                status = title?.str("titleStatusString").orEmpty().ifBlank { media.status }
            )
        }

        private fun dateOf(millis: Long?): String =
            if (millis == null || millis <= 0) "" else SimpleDateFormat("dd/MM/yyyy", Locale.ROOT).format(Date(millis))

        fun parseEpisodes(result: JsonObject, mediaId: String): List<Chapter> =
            result.arr("episodeList").objects().mapNotNull { episode ->
                val number = episode.str("episodeNo")?.toFloatOrNull() ?: return@mapNotNull null
                val link = episode.str("viewerLink") ?: return@mapNotNull null
                val url = if (link.startsWith("http")) link else MOBILE + link
                val title = episode.str("episodeTitle").orEmpty().trim()
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = title.ifBlank { "Episodio ${number.toInt()}" },
                    chapterNumber = number,
                    url = url,
                    dateUploaded = dateOf(episode.str("exposureDateMillis")?.toLongOrNull())
                )
            }

        fun parsePages(html: String): List<String> {
            val list = IMAGE_LIST.find(html)?.groupValues?.get(1) ?: return emptyList()
            return IMAGE_URL.findAll(list).map { clean(it.groupValues[1]) }
                .filterNot { "_warning." in it } // the site's copyright notice, not a page
                .distinct()
                .toList()
        }
    }
}
