package com.nelson.yomukai.data.source.manga

import com.nelson.yomukai.data.network.NetworkHelper
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
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Marmota Comics (marmota.me): Spanish translations of US comics on the WordPress "Madara" theme.
 * Chapter lists come from `{series}/ajax/chapters/`; page images are lazy-loaded through
 * `data-src` with stray whitespace and `http://` URLs, which are normalised here.
 */
class MarmotaComicsSource(private val network: NetworkHelper) : MangaSource {
    override val id = "marmota"
    override val name = "Marmota Comics"
    override val baseUrl = "https://marmota.me"
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.COMIC)
    override val supportsLatestPaging = true

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$baseUrl/"))

    override val filters: List<SourceFilter> = FILTERS

    override suspend fun browse(page: Int, values: FilterValues): Result<List<MediaItem>> = runCatching {
        parseItems(html(catalogueUrl(page, values)), ".c-tabs-item__content")
    }

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        parseItems(html(if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"), ".page-item-detail")
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseItems(html("$baseUrl/?s=${SourceUtils.encode(query)}&post_type=wp-manga"), ".c-tabs-item__content")
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        parseDetails(html(media.id), media)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val body = network.postString(mediaId.trimEnd('/') + "/ajax/chapters/", mapOf("Referer" to mediaId))
        parseChapters(body, mediaId).ifEmpty { error("Sin capítulos disponibles") }
    }

    override suspend fun fetchPageList(chapterUrl: String): Result<List<String>> = runCatching {
        parsePages(html(chapterUrl)).ifEmpty { error("El capítulo no tiene imágenes") }
    }

    companion object {
        private val SORT = SelectFilter(
            FilterKeys.SORT, "Orden",
            listOf(
                FilterOption("latest", "Recientes"), FilterOption("trending", "Tendencia"),
                FilterOption("views", "Más vistos"), FilterOption("rating", "Mejor valorados"),
                FilterOption("new-manga", "Nuevos"), FilterOption("alphabet", "A-Z")
            )
        )
        private val STATUS = MultiSelectFilter(
            FilterKeys.STATUS, "Estado",
            listOf(
                FilterOption("on-going", "En emisión"), FilterOption("end", "Completado"),
                FilterOption("canceled", "Cancelado"), FilterOption("on-hold", "En pausa"),
                FilterOption("upcoming", "Próximamente")
            )
        )
        /** "Género" on marmota.me is really the publisher / collection (slugs from the site's own search form). */
        private val GENRES = MultiSelectFilter(
            FilterKeys.GENRE, "Editorial / colección",
            listOf(
                "20th-century-studios" to "20th Century Studios", "aftershock" to "Aftershock", "archie-comics" to "Archie",
                "black-label" to "Black Label", "boom-studios" to "BOOM! Studios", "crossovers" to "Crossovers",
                "dark-horse" to "Dark Horse", "dc-comics" to "DC Comics", "dc-horror" to "DC Horror",
                "dynamite" to "Dynamite", "editorial-awa" to "AWA", "europeo" to "Europeo",
                "eventos-dc" to "Eventos DC", "eventos-marvel" to "Eventos Marvel", "idw" to "IDW",
                "image-comics" to "Image", "indies" to "Indies", "mad-cave" to "Mad Cave", "manga" to "Manga",
                "marvel-comics" to "Marvel", "oni-press" to "Oni Press", "rebellion-developments" to "Rebellion",
                "vertigo" to "Vertigo", "virus" to "Virus", "zenescope" to "Zenescope"
            ).map { FilterOption(it.first, it.second) }
        )
        val FILTERS: List<SourceFilter> = listOf(SORT, STATUS, GENRES)

        fun catalogueUrl(page: Int, values: FilterValues): String = buildString {
            append("$BASE/?s=&post_type=wp-manga&m_orderby=${values.single(SORT)}&paged=${page.coerceAtLeast(1)}")
            values.selected(FilterKeys.STATUS).forEach { append("&status%5B%5D=$it") }
            values.selected(FilterKeys.GENRE).forEach { append("&genre%5B%5D=$it") }
        }

        private const val BASE = "https://marmota.me"

        private fun secure(url: String) = url.trim().replaceFirst("http://", "https://")

        private fun lazyImage(img: Element?): String {
            if (img == null) return ""
            val real = img.attr("data-src").trim().ifBlank { img.attr("src").trim() }
            return if (real.isBlank() || real.contains("dflazy")) "" else secure(real)
        }

        fun parseItems(html: String, selector: String): List<MediaItem> =
            Jsoup.parse(html, BASE).select(selector).mapNotNull { elem ->
                val link = elem.selectFirst(".post-title a") ?: return@mapNotNull null
                val title = link.text().trim()
                val url = link.absUrl("href")
                if (title.isBlank() || url.isBlank()) return@mapNotNull null
                MediaItem(
                    id = url,
                    title = title,
                    coverUrl = lazyImage(elem.selectFirst("img")),
                    type = MediaType.COMIC,
                    status = "",
                    description = "",
                    sourceId = "marmota",
                    altTitles = elem.select(".mg_alternative .summary-content").text()
                        .split(",", ";", "/").map { it.trim() }.filter { it.isNotEmpty() }
                )
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, media.id)
            val status = doc.select(".post-status .post-content_item").firstOrNull {
                it.selectFirst(".summary-heading")?.text()?.contains("Estado", ignoreCase = true) == true
            }?.selectFirst(".summary-content")?.text()?.trim().orEmpty()
            return media.copy(
                title = doc.selectFirst(".post-title h1")?.ownText()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = media.coverUrl.ifBlank { lazyImage(doc.selectFirst(".summary_image img")) },
                description = doc.selectFirst(".manga-excerpt")?.text()?.trim().orEmpty(),
                author = doc.select(".author-content a").joinToString(", ") { it.text().trim() },
                genres = doc.select(".genres-content a").map { it.text().trim() }.filter { it.isNotEmpty() },
                status = status.ifBlank { media.status }
            )
        }

        fun parseChapters(html: String, mediaId: String): List<Chapter> {
            val items = Jsoup.parse(html, mediaId).select("li.wp-manga-chapter")
            val total = items.size
            return items.mapIndexedNotNull { idx, li ->
                val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                val url = a.absUrl("href").takeIf { it.startsWith("http") } ?: return@mapIndexedNotNull null
                val title = a.text().trim()
                Chapter(
                    id = url,
                    mediaId = mediaId,
                    title = title.ifBlank { "Capítulo ${total - idx}" },
                    chapterNumber = SourceUtils.parseChapterNumber(title, (total - idx).toFloat()),
                    url = url,
                    dateUploaded = li.selectFirst(".chapter-release-date")?.text()?.trim().orEmpty()
                )
            }.distinctBy { it.id }
        }

        fun parsePages(html: String): List<String> =
            Jsoup.parse(html, BASE).select(".reading-content img.wp-manga-chapter-img")
                .map { lazyImage(it) }.filter { it.isNotBlank() }
    }
}
