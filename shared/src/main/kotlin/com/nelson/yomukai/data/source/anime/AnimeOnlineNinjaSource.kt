package com.nelson.yomukai.data.source.anime

import com.google.gson.JsonParser
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NoChaptersException
import com.nelson.yomukai.domain.model.AudioTrack
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import org.jsoup.Jsoup

/**
 * AnimeOnline.Ninja (ww4.animeonline.ninja): anime in Japanese with Spanish subtitles, Latin American and Spain's
 * Spanish dubs (F3.2). A WordPress DooPlay site behind Cloudflare's check (the server passes it with FlareSolverr, the
 * app with its browser). The latest and the list walked by the index are its «anime castellano» section, so those
 * titles are marked as dubbed into Spain's Spanish. An episode's «MULTISERVER» player page groups its servers by
 * audio (SUB, LAT, ES), so each server says what it is heard in. A title is its slug.
 */
class AnimeOnlineNinjaSource(private val network: HttpFetcher) : AnimeSource {
    override val id = "animeonlineninja"
    override val name = "AnimeOnline.Ninja"
    override val baseUrl = BASE
    override val language = "ES"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true

    private val extractors = VideoExtractors(network)

    private suspend fun html(url: String) = network.getString(url, mapOf("Referer" to "$BASE/"))

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching { parseCards(html(castellanoPage(page)), id, AudioTrack.CASTELLANO) }

    override suspend fun browse(page: Int, values: com.nelson.yomukai.data.source.FilterValues): Result<List<MediaItem>> = fetchLatestUpdates(page)

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        parseSearch(html("$BASE/?s=" + java.net.URLEncoder.encode(query.trim().take(MAX_QUERY), Charsets.UTF_8)), id)
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching { parseDetails(html(animePage(media.id)), media) }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        parseEpisodes(html(animePage(mediaId)), mediaId).ifEmpty { throw NoChaptersException() }
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        val servers = parsePlayerOptions(html(episodeUrl)).flatMap { option ->
            val answer = runCatching { network.getString(playerApi(option), mapOf("Referer" to episodeUrl)) }.getOrNull() ?: return@flatMap emptyList()
            val embed = embedUrl(answer) ?: return@flatMap emptyList()
            if (MULTI in embed) parseMultiserver(network.getString(embed, mapOf("Referer" to "$BASE/"), cloudflareFallback = false))
            else listOf(VideoSource(option.name, embed, option.name))
        }
        extractors.resolveAll(servers)
    }

    /** One player of an episode page: what the site's player API needs to give its address. */
    data class PlayerOption(val post: String, val nume: String, val type: String, val name: String)

    companion object {
        private const val BASE = "https://ww4.animeonline.ninja"
        private const val MAX_QUERY = 100
        private const val MULTI = "embed.php"
        private val ANIME = Regex("""^https://ww\d*\.animeonline\.ninja/(online|pelicula)/([a-z0-9-]+)/?""")

        /** A series is its slug; a film «pelicula/slug» (its own page, no list of episodes). */
        private fun idOf(url: String): String? = ANIME.find(url)?.let { m -> if (m.groupValues[1] == "pelicula") "pelicula/" + m.groupValues[2] else m.groupValues[2] }
        private val EPISODE_NUMBER = Regex("""-cap-([0-9]+(?:\.[0-9]+)?)/?$""")
        private val NUMERANDO = Regex("""\d+\s*-\s*([0-9.]+)""")
        private val DATE = Regex("""([A-Za-z]{3})\.?\s+(\d{1,2}),\s+(\d{4})""")
        private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        private val GO_TO = Regex("""go_to_player\('([^']+)'\)""")
        private val SAFE_ID = Regex("""^[0-9]{1,12}$""")

        fun animePage(id: String) = if (id.startsWith("pelicula/")) "$BASE/$id/" else "$BASE/online/$id/"
        private fun castellanoPage(page: Int) = if (page <= 1) "$BASE/genero/anime-castellano/" else "$BASE/genero/anime-castellano/page/$page/"

        fun playerApi(o: PlayerOption) = "$BASE/wp-json/dooplayer/v1/post/${o.post}?type=${o.type}&source=${o.nume}"

        /** The title cards of a section (its anime, all with [audio]). */
        fun parseCards(html: String, sourceId: String, audio: AudioTrack? = null): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select("article.item").mapNotNull { card ->
                val link = card.selectFirst(".data h3 a[href]") ?: card.selectFirst(".poster a[href]") ?: return@mapNotNull null
                val slug = idOf(link.absUrl("href")) ?: return@mapNotNull null
                val title = link.text().trim().ifBlank { card.selectFirst(".poster img")?.attr("alt")?.trim().orEmpty() }.ifBlank { null } ?: return@mapNotNull null
                MediaItem(slug, title, cover(card.selectFirst(".poster img")), MediaType.ANIME, "", "", sourceId, audio = audio)
            }.distinctBy { it.id }

        /** The site's search results. */
        fun parseSearch(html: String, sourceId: String): List<MediaItem> =
            Jsoup.parse(html, "$BASE/").select(".result-item article").mapNotNull { r ->
                val link = r.selectFirst(".details .title a[href]") ?: return@mapNotNull null
                val slug = idOf(link.absUrl("href")) ?: return@mapNotNull null
                MediaItem(slug, link.text().trim().ifBlank { null } ?: return@mapNotNull null, cover(r.selectFirst(".image img")), MediaType.ANIME, "", "", sourceId)
            }.distinctBy { it.id }

        /** Lazy images keep the real address in data-src (src is an empty SVG). */
        private fun cover(img: org.jsoup.nodes.Element?): String =
            img?.absUrl("data-src")?.ifBlank { null } ?: img?.absUrl("src")?.takeIf { it.startsWith("http") }.orEmpty()

        fun parseDetails(html: String, media: MediaItem): MediaItem {
            val doc = Jsoup.parse(html, animePage(media.id))
            return media.copy(
                title = doc.selectFirst(".sheader .data h1")?.text()?.trim()?.ifBlank { null } ?: media.title,
                coverUrl = cover(doc.selectFirst(".sheader .poster img")).ifBlank { media.coverUrl },
                // The first paragraph after the site's list of links is the synopsis; its «genres» are SEO words, skipped.
                description = doc.select("#info .wp-content p").map { it.text().trim() }.firstOrNull { it.length > MIN_SYNOPSIS } ?: media.description
            )
        }

        fun parseEpisodes(html: String, slug: String): List<Chapter> {
            // A film is its own page: one «episode» with its players.
            if (slug.startsWith("pelicula/")) {
                val doc = Jsoup.parse(html, animePage(slug))
                return if (doc.selectFirst("li.dooplay_player_option") != null) listOf(Chapter(animePage(slug), slug, "Película", 1f, animePage(slug), "")) else emptyList()
            }
            return Jsoup.parse(html, animePage(slug)).select("ul.episodios li").mapNotNull { li ->
                val link = li.selectFirst(".episodiotitle a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").takeIf { it.startsWith("$BASE/episodio/") } ?: return@mapNotNull null
                val number = (EPISODE_NUMBER.find(url)?.groupValues?.get(1) ?: NUMERANDO.find(li.selectFirst(".numerando")?.text().orEmpty())?.groupValues?.get(1))
                    ?.toFloatOrNull() ?: return@mapNotNull null
                val label = if (number % 1f == 0f) number.toInt().toString() else number.toString()
                Chapter(url, slug, "Episodio $label", number, url, date(li.selectFirst(".date")?.text().orEmpty()))
            }.distinctBy { it.id }
        }

        /** «Nov. 05, 2026» → «2026-11-05»; empty when it is not a date. */
        fun date(text: String): String = DATE.find(text)?.destructured?.let { (m, d, y) ->
            val month = MONTHS.indexOf(m.lowercase()).takeIf { it >= 0 } ?: return@let ""
            "%s-%02d-%02d".format(y, month + 1, d.toInt())
        }.orEmpty()

        /** The players of an episode page (only well-formed ids reach the site's API). */
        fun parsePlayerOptions(html: String): List<PlayerOption> =
            Jsoup.parse(html, "$BASE/").select("li.dooplay_player_option[data-post][data-nume]").mapNotNull { li ->
                val post = li.attr("data-post").takeIf { SAFE_ID.matches(it) } ?: return@mapNotNull null
                val nume = li.attr("data-nume").takeIf { SAFE_ID.matches(it) } ?: return@mapNotNull null
                val type = li.attr("data-type").takeIf { it == "tv" || it == "movie" } ?: "tv"
                PlayerOption(post, nume, type, li.selectFirst(".title")?.text()?.trim()?.ifBlank { null } ?: "Servidor")
            }

        /** The player's address in the API's answer ({"embed_url": …}); only https. */
        fun embedUrl(json: String): String? =
            runCatching { JsonParser.parseString(json).asJsonObject.get("embed_url")?.asString }.getOrNull()?.takeIf { it.startsWith("https://") }

        /** The «MULTISERVER» page: its servers grouped by audio (SUB = Japanese with subtitles, LAT, ES = Spain's). */
        fun parseMultiserver(html: String): List<VideoSource> =
            Jsoup.parse(html).select(".OptionsLangDisp .OD").flatMap { group ->
                val audio = when {
                    group.hasClass("OD_LAT") -> AudioTrack.LATINO
                    group.hasClass("OD_ES") -> AudioTrack.CASTELLANO
                    else -> AudioTrack.ORIGINAL
                }
                group.select("li[onclick]").mapNotNull { li ->
                    val url = GO_TO.find(li.attr("onclick"))?.groupValues?.get(1)?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                    val server = li.selectFirst("span")?.text()?.trim()?.ifBlank { null } ?: "Servidor"
                    VideoSource(audio.label, url, server, audio = audio)
                }
            }.distinctBy { it.videoUrl }

        private const val MIN_SYNOPSIS = 40
    }
}
