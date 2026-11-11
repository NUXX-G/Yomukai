package com.nelson.yomukai.data.discovery

import com.google.gson.JsonObject
import com.nelson.yomukai.data.network.HttpFetcher
import com.nelson.yomukai.data.source.arr
import com.nelson.yomukai.data.source.obj
import com.nelson.yomukai.data.source.objects
import com.nelson.yomukai.data.source.parseJson
import com.nelson.yomukai.data.source.str
import com.nelson.yomukai.data.source.strings
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.i18n.tr
import java.time.LocalDate

/** One title as AniList describes it, for the home rows, the hero and the explorer. */
data class DiscoverEntry(
    val anilistId: Int,
    val malId: Int?,
    val title: String,
    val names: List<String>,
    val cover: String,
    val banner: String,
    val color: String,
    val genres: List<String>,
    /** 0..100 */
    val score: Int?,
    val format: String,
    val type: MediaType,
    val synopsis: String,
    val status: String,
    val season: String,
    val year: Int?,
    val episodes: Int?,
    val chapters: Int?,
    val nextEpisode: Int?,
    val nextAiringAt: Long?,
    /** How many AniList users have it on a list (a measure of how many people follow it). */
    val popularity: Int? = null
)

enum class DiscoverKind(val label: String) { ANIME(tr("Anime")), MANGA(tr("Manga y manhwa")), NOVEL(tr("Novelas")) }

/** A filter set for the explorer (every field optional). */
data class DiscoverFilter(
    val genre: String? = null,
    val year: Int? = null,
    /** RELEASING, FINISHED, NOT_YET_RELEASED, CANCELLED, HIATUS */
    val status: String? = null,
    /** TV, MOVIE, OVA, ONA, MANGA, ONE_SHOT, NOVEL... */
    val format: String? = null,
    /** KR = manhwa, CN = manhua, JP = manga */
    val country: String? = null,
    val minScore: Int? = null,
    /** TRENDING_DESC, POPULARITY_DESC, SCORE_DESC, START_DATE_DESC, UPDATED_AT_DESC */
    val sort: String = "TRENDING_DESC",
    val page: Int = 1
)

/**
 * AniList lists for discovering titles. Each home section (trending, popular, airing this season, best rated, by genre)
 * comes in ONE request with GraphQL aliases, so a whole page costs a single call of the ~30 a minute AniList allows.
 * Adult titles are always left out.
 */
class AniListDiscover(private val network: HttpFetcher) {

    suspend fun rows(kind: DiscoverKind, today: LocalDate = LocalDate.now()): Map<String, List<DiscoverEntry>> {
        val (type, extra) = typeFilter(kind)
        val season = seasonOf(today)
        val parts = buildList {
            add("trending" to "sort:TRENDING_DESC")
            if (kind == DiscoverKind.ANIME) add("season" to "season:${season.first},seasonYear:${season.second},sort:POPULARITY_DESC")
            if (kind != DiscoverKind.ANIME) add("releasing" to "status:RELEASING,sort:TRENDING_DESC")
            add("popular" to "sort:POPULARITY_DESC")
            add("top" to "sort:SCORE_DESC")
            GENRE_ROWS.forEach { (alias, genre) -> add(alias to "genre:\"$genre\",sort:TRENDING_DESC") }
        }
        val query = "query{" + parts.joinToString(" ") { (alias, args) ->
            "$alias:Page(perPage:$PER_ROW){media(type:$type,isAdult:false$extra,$args){$FIELDS}}"
        } + "}"
        val data = parseJson(post(query)).asJsonObject.obj("data") ?: return emptyMap()
        return parts.associate { (alias, _) -> alias to parse(data.obj(alias)) }
    }

    suspend fun browse(kind: DiscoverKind, f: DiscoverFilter): List<DiscoverEntry> {
        val (type, extra) = typeFilter(kind)
        val args = buildList {
            f.genre?.takeIf { SAFE.matches(it) }?.let { add("genre:\"$it\"") }
            f.year?.takeIf { it in 1940..2100 }?.let { add(if (kind == DiscoverKind.ANIME) "seasonYear:$it" else "startDate_greater:${it}0000,startDate_lesser:${it + 1}0000") }
            f.status?.takeIf { it in STATUSES }?.let { add("status:$it") }
            f.format?.takeIf { it in FORMATS }?.let { add("format:$it") }
            f.country?.takeIf { it in setOf("JP", "KR", "CN") }?.let { add("countryOfOrigin:$it") }
            f.minScore?.takeIf { it in 1..100 }?.let { add("averageScore_greater:$it") }
            add("sort:${f.sort.takeIf { it in SORTS } ?: "TRENDING_DESC"}")
        }.joinToString(",")
        val query = "query{list:Page(page:${f.page.coerceIn(1, 50)},perPage:$PER_PAGE){media(type:$type,isAdult:false$extra,$args){$FIELDS}}}"
        return parse(parseJson(post(query)).asJsonObject.obj("data")?.obj("list"))
    }

    /**
     * Every episode that airs between [from] and [to] (epoch seconds), one entry per episode with its own number and
     * time: up to [AIRING_PAGES] pages of 50 in ONE request (aliases). Adult titles and the ones almost nobody follows
     * (Chinese shorts, kids' shows with no English name…) are left out so the week stays readable.
     */
    suspend fun airing(from: Long, to: Long): List<DiscoverEntry> {
        require(to > from)
        val query = "query{" + (1..AIRING_PAGES).joinToString(" ") { page ->
            "p$page:Page(page:$page,perPage:50){pageInfo{hasNextPage} airingSchedules(airingAt_greater:$from,airingAt_lesser:$to,sort:TIME){episode airingAt media{$FIELDS}}}"
        } + "}"
        return parseAiring(parseJson(post(query)).asJsonObject.obj("data"))
    }

    /**
     * What people in general think of this season's anime, from AniList's community: the best rated (with enough
     * people behind the score) and what is trending now. One request with two aliases.
     */
    suspend fun seasonCommunity(today: LocalDate = LocalDate.now()): Map<String, List<DiscoverEntry>> {
        val (season, year) = seasonOf(today)
        val base = "type:ANIME,isAdult:false,season:$season,seasonYear:$year"
        val parts = listOf("top" to "$base,sort:SCORE_DESC,popularity_greater:$MIN_RATED_POPULARITY", "trending" to "$base,sort:TRENDING_DESC")
        val query = "query{" + parts.joinToString(" ") { (alias, args) -> "$alias:Page(perPage:$COMMUNITY_SIZE){media($args){$FIELDS}}" } + "}"
        val data = parseJson(post(query)).asJsonObject.obj("data") ?: return emptyMap()
        return parts.associate { (alias, _) -> alias to parse(data.obj(alias)) }
    }

    /** Titles by AniList id (at most 50), in one request; for the group's picks. */
    suspend fun byIds(ids: Collection<Int>): List<DiscoverEntry> {
        val list = ids.filter { it > 0 }.distinct().take(50)
        if (list.isEmpty()) return emptyList()
        return parse(parseJson(post("query{list:Page(perPage:50){media(id_in:[${list.joinToString(",")}]){$FIELDS}}}")).asJsonObject.obj("data")?.obj("list"))
    }

    private fun typeFilter(kind: DiscoverKind): Pair<String, String> = when (kind) {
        DiscoverKind.ANIME -> "ANIME" to ""
        DiscoverKind.MANGA -> "MANGA" to ",format_not:NOVEL"
        DiscoverKind.NOVEL -> "MANGA" to ",format:NOVEL"
    }

    private suspend fun post(query: String): String {
        val body = JsonObject().apply { addProperty("query", query) }
        return network.postJson("https://graphql.anilist.co", body.toString())
    }

    companion object {
        const val PER_ROW = 18
        const val PER_PAGE = 30
        const val AIRING_PAGES = 4
        const val COMMUNITY_SIZE = 12

        /** Below this many followers an airing title is noise in a weekly calendar. */
        const val MIN_AIRING_POPULARITY = 800

        /** A season's best score only counts with this many people on AniList behind it. */
        const val MIN_RATED_POPULARITY = 3_000
        private val SAFE = Regex("[A-Za-z' -]{2,30}")
        private val STATUSES = setOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "CANCELLED", "HIATUS")
        private val FORMATS = setOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MANGA", "ONE_SHOT", "NOVEL")
        private val SORTS = setOf("TRENDING_DESC", "POPULARITY_DESC", "SCORE_DESC", "START_DATE_DESC", "UPDATED_AT_DESC", "FAVOURITES_DESC")

        /** Home rows by genre: alias → AniList genre. */
        val GENRE_ROWS = listOf("action" to "Action", "romance" to "Romance", "fantasy" to "Fantasy", "comedy" to "Comedy")

        private const val FIELDS = "id idMal title{romaji english native} synonyms coverImage{extraLarge color} bannerImage genres averageScore " +
            "format type status season seasonYear episodes chapters description(asHtml:false) countryOfOrigin nextAiringEpisode{episode airingAt} popularity isAdult"

        fun seasonOf(day: LocalDate): Pair<String, Int> = when (day.monthValue) {
            12 -> "WINTER" to day.year + 1
            1, 2 -> "WINTER" to day.year
            in 3..5 -> "SPRING" to day.year
            in 6..8 -> "SUMMER" to day.year
            else -> "FALL" to day.year
        }

        /** The airing pages of [airing]: one entry per episode, its number and time instead of the "next" one. */
        fun parseAiring(data: JsonObject?, minPopularity: Int = MIN_AIRING_POPULARITY): List<DiscoverEntry> =
            (1..AIRING_PAGES).flatMap { page ->
                data?.obj("p$page")?.arr("airingSchedules").objects().mapNotNull { s ->
                    val media = s.obj("media") ?: return@mapNotNull null
                    val entry = entry(media) ?: return@mapNotNull null
                    if ((entry.popularity ?: 0) < minPopularity) return@mapNotNull null
                    entry.copy(nextEpisode = s.str("episode")?.toIntOrNull(), nextAiringAt = s.str("airingAt")?.toLongOrNull() ?: return@mapNotNull null)
                }
            }.distinctBy { it.anilistId to it.nextEpisode }

        fun parse(page: JsonObject?): List<DiscoverEntry> = page?.arr("media").objects().mapNotNull(::entry).orEmpty()

        /** One AniList media object; null for adult titles (never shown) or broken ones. */
        private fun entry(m: JsonObject): DiscoverEntry? {
            if (m.str("isAdult") == "true") return null
            val id = m.str("id")?.toIntOrNull() ?: return null
            val t = m.obj("title")
            val names = (listOfNotNull(t?.str("english"), t?.str("romaji"), t?.str("native")) + m.arr("synonyms").strings()).filter { it.isNotBlank() }.distinct()
            val type = when {
                m.str("type") == "ANIME" -> MediaType.ANIME
                m.str("format") == "NOVEL" -> MediaType.NOVEL
                m.str("countryOfOrigin") == "KR" -> MediaType.MANHWA
                m.str("countryOfOrigin") == "CN" -> MediaType.MANHUA
                else -> MediaType.MANGA
            }
            return DiscoverEntry(
                anilistId = id, malId = m.str("idMal")?.toIntOrNull(),
                title = t?.str("english")?.ifBlank { null } ?: t?.str("romaji") ?: names.firstOrNull() ?: return null,
                names = names, cover = m.obj("coverImage")?.str("extraLarge").orEmpty(), banner = m.str("bannerImage").orEmpty(),
                color = m.obj("coverImage")?.str("color").orEmpty(), genres = m.arr("genres").strings(),
                score = m.str("averageScore")?.toIntOrNull(), format = m.str("format").orEmpty(), type = type,
                synopsis = AniListClient.cleanDescription(m.str("description")), status = m.str("status").orEmpty(),
                season = m.str("season").orEmpty(), year = m.str("seasonYear")?.toIntOrNull(),
                episodes = m.str("episodes")?.toIntOrNull(), chapters = m.str("chapters")?.toIntOrNull(),
                nextEpisode = m.obj("nextAiringEpisode")?.str("episode")?.toIntOrNull(), nextAiringAt = m.obj("nextAiringEpisode")?.str("airingAt")?.toLongOrNull(),
                popularity = m.str("popularity")?.toIntOrNull()
            )
        }
    }
}
