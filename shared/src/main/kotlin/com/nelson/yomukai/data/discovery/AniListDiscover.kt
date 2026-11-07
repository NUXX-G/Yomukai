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
    val nextAiringAt: Long?
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
        private val SAFE = Regex("[A-Za-z' -]{2,30}")
        private val STATUSES = setOf("RELEASING", "FINISHED", "NOT_YET_RELEASED", "CANCELLED", "HIATUS")
        private val FORMATS = setOf("TV", "TV_SHORT", "MOVIE", "SPECIAL", "OVA", "ONA", "MANGA", "ONE_SHOT", "NOVEL")
        private val SORTS = setOf("TRENDING_DESC", "POPULARITY_DESC", "SCORE_DESC", "START_DATE_DESC", "UPDATED_AT_DESC", "FAVOURITES_DESC")

        /** Home rows by genre: alias → AniList genre. */
        val GENRE_ROWS = listOf("action" to "Action", "romance" to "Romance", "fantasy" to "Fantasy", "comedy" to "Comedy")

        private const val FIELDS = "id idMal title{romaji english native} synonyms coverImage{extraLarge color} bannerImage genres averageScore " +
            "format type status season seasonYear episodes chapters description(asHtml:false) countryOfOrigin nextAiringEpisode{episode airingAt}"

        fun seasonOf(day: LocalDate): Pair<String, Int> = when (day.monthValue) {
            12 -> "WINTER" to day.year + 1
            1, 2 -> "WINTER" to day.year
            in 3..5 -> "SPRING" to day.year
            in 6..8 -> "SUMMER" to day.year
            else -> "FALL" to day.year
        }

        fun parse(page: JsonObject?): List<DiscoverEntry> = page?.arr("media").objects().mapNotNull { m ->
            val id = m.str("id")?.toIntOrNull() ?: return@mapNotNull null
            val t = m.obj("title")
            val names = (listOfNotNull(t?.str("english"), t?.str("romaji"), t?.str("native")) + m.arr("synonyms").strings()).filter { it.isNotBlank() }.distinct()
            val type = when {
                m.str("type") == "ANIME" -> MediaType.ANIME
                m.str("format") == "NOVEL" -> MediaType.NOVEL
                m.str("countryOfOrigin") == "KR" -> MediaType.MANHWA
                m.str("countryOfOrigin") == "CN" -> MediaType.MANHUA
                else -> MediaType.MANGA
            }
            DiscoverEntry(
                anilistId = id, malId = m.str("idMal")?.toIntOrNull(),
                title = t?.str("english")?.ifBlank { null } ?: t?.str("romaji") ?: names.firstOrNull() ?: return@mapNotNull null,
                names = names, cover = m.obj("coverImage")?.str("extraLarge").orEmpty(), banner = m.str("bannerImage").orEmpty(),
                color = m.obj("coverImage")?.str("color").orEmpty(), genres = m.arr("genres").strings(),
                score = m.str("averageScore")?.toIntOrNull(), format = m.str("format").orEmpty(), type = type,
                synopsis = AniListClient.cleanDescription(m.str("description")), status = m.str("status").orEmpty(),
                season = m.str("season").orEmpty(), year = m.str("seasonYear")?.toIntOrNull(),
                episodes = m.str("episodes")?.toIntOrNull(), chapters = m.str("chapters")?.toIntOrNull(),
                nextEpisode = m.obj("nextAiringEpisode")?.str("episode")?.toIntOrNull(), nextAiringAt = m.obj("nextAiringEpisode")?.str("airingAt")?.toLongOrNull()
            )
        }.orEmpty()
    }
}
