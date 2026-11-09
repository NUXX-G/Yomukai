package com.nelson.yomukai.data.source.anime

import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.search.SmartSearch
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.jsoup.Jsoup

/**
 * VerAnimeOnline (veranimeonline.co): Spanish-subtitled anime on a WordPress DooPlay site, plain HTML. The anime list
 * is paged; a title's page lists its episodes; an episode has its player (often Blogger, played directly). Its own
 * search only works from the browser, so searching reads the list of the query's first letter. A title is its slug.
 */
class VerAnimeOnlineSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "veranimeonline"
    override val name = "VerAnimeOnline"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    /** Page 1: the newest episodes; then the anime list (what the index walks the first time). */
    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        if (page <= 1) parseLatestEpisodes(html("$BASE/"), id) else parseCards(html(listPage(page - 1)), id)
    }

    override suspend fun browse(page: Int, values: com.nelson.yomukai.data.source.FilterValues): Result<List<MediaItem>> =
        runCatching { parseCards(html(listPage(page)), id) }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val letter = SmartSearch.normalize(query).firstOrNull { it.isLetterOrDigit() } ?: return@runCatching emptyList()
        val path = if (letter.isLetter()) "letra/$letter" else "letra/0-9"
        val words = SmartSearch.normalize(query).split(' ').filter { it.isNotBlank() }
        val found = mutableListOf<MediaItem>()
        for (page in 1..SEARCH_PAGES) {
            val cards = parseCards(html(if (page == 1) "$BASE/$path/" else "$BASE/$path/page/$page/"), id)
            if (cards.isEmpty()) break
            found += cards.filter { card -> SmartSearch.normalize(card.title).let { t -> words.all { it in t } } }
        }
        found.distinctBy { it.id }
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching { parseDetails(html(animePage(media.id)), media) }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html(animePage(mediaId)), mediaId).ifEmpty { throw NoChaptersException() }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        extractors.resolveAll(parsePlayers(html(episodeUrl)))
    }

    companion object {
        private const val BASE = "https://veranimeonline.co"
        private const val SEARCH_PAGES = 6
        private val ANIME = Regex("""^https://veranimeonline\.co/anime/([a-z0-9-]+)/?$""")
        private val EPISODE = Regex("""^https://veranimeonline\.co/episodio/([a-z0-9-]+?)-episodio-([0-9.]+)/?$""")
        private val NUMBER = Regex("""(\d+(?:\.\d+)?)""")
        private val DATE = Regex("""(\d{2})/(\d{2})/(\d{4})""")

        fun animePage(slug: String) = "$BASE/anime/$slug/"
        private fun listPage(page: Int) = if (page <= 1) "$BASE/anime/" else "$BASE/anime/page/$page/"

        /** Title cards of the anime list, a letter or the home page. */
        fun parseCards(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select("article.item.tvshows").mapNotNull { card ->
                val link = card.selectFirst(".data h3 a[href]") ?: card.selectFirst(".poster a[href]") ?: return@mapNotNull null
                val slug = ANIME.find(link.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val title = link.text().trim().removeSuffix(" Todos los Episodios").ifBlank { null } ?: return@mapNotNull null
                MediaItem(slug, title, card.selectFirst(".poster img")?.absUrl("src").orEmpty(), MediaType.ANIME, "", "", sourceId)
            }.distinctBy { it.id }

        /** The newest episodes on the home page, as titles (one per title). */
        fun parseLatestEpisodes(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select("article.item.episodes").mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val slug = EPISODE.find(link.absUrl("href"))?.groupValues?.get(1) ?: return@mapNotNull null
                val title = card.selectFirst("h3")?.text()?.trim()?.replace(Regex("""\s+Episodio\s+[0-9.]+$""", RegexOption.IGNORE_CASE), "")
                    ?.ifBlank { null } ?: return@mapNotNull null
                MediaItem(slug, title, card.selectFirst("img")?.absUrl("src").orEmpty(), MediaType.ANIME, "En emisión", "", sourceId)
            }.distinctBy { it.id }

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, animePage(media.id))
            return media.copy(
                title = doc.selectFirst(".sheader .data h1, h1")?.text()?.trim()?.removeSuffix(" Todos los Episodios")?.ifBlank { null } ?: media.title,
                coverUrl = doc.selectFirst(".sheader .poster img")?.absUrl("src")?.ifBlank { null } ?: media.coverUrl,
                description = doc.selectFirst("#info .wp-content p, .wp-content p")?.text()?.trim()?.ifBlank { null } ?: media.description,
                genres = doc.select(".sgeneros a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct().ifEmpty { media.genres }
            )
        }

        fun parseEpisodes(html: String, slug: String): List<Chapter> =
            Jsoup.parse(html, animePage(slug)).select("ul.episodios li").mapNotNull { li ->
                val link = li.selectFirst(".episodiotitle a[href]") ?: li.selectFirst("a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").takeIf { it.startsWith("$BASE/episodio/") } ?: return@mapNotNull null
                val number = (EPISODE.find(url)?.groupValues?.get(2) ?: NUMBER.find(link.ownText())?.value)?.toFloatOrNull() ?: return@mapNotNull null
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                val date = DATE.find(li.selectFirst(".date")?.text().orEmpty())?.destructured?.let { (d, m, y) -> "$y-$m-$d" }.orEmpty()
                Chapter(url, slug, "Episodio $label", number, url, date)
            }.distinctBy { it.id }

        /** The players of an episode page (Blogger, other hosts...). */
        fun parsePlayers(html: String): List<VideoSource> =
            Jsoup.parse(html, "$BASE/").select("iframe.metaframe[src], #dooplay_player_response iframe[src]").mapNotNull { frame ->
                val url = frame.absUrl("src").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                val name = when {
                    "blogger.com" in url -> "Blogger"
                    else -> url.substringAfter("://").substringBefore('/').removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
                }
                VideoSource(name, url, name)
            }.distinctBy { it.videoUrl }
    }
}
