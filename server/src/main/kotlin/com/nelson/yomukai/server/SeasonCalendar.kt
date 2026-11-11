package com.nelson.yomukai.server

import com.nelson.yomukai.data.discovery.AniListDiscover
import com.nelson.yomukai.data.discovery.DiscoverEntry
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * The calendar beyond the user's library (bloque 5):
 * - every anime episode that airs this week, from AniList's airing schedule (one request, cached [AIRING_TTL_MS]);
 * - what people in general rate this season, from AniList's community (cached [COMMUNITY_TTL_MS]). AniList's API is
 *   free for non-commercial use with a rate limit (90 a minute, 30 when degraded): a few requests a day is far below;
 * - the group's recommendations and comments on this season's anime (table season_picks, migration 28).
 * Nothing here is heavy: plain HTTP to AniList now and then, and small queries.
 */
class SeasonCalendar(
    private val api: AniListDiscover,
    private val db: Database,
    private val decorate: suspend (List<DiscoverEntry>, String) -> List<DiscoverItemDto>,
    /** AniList covers through our image proxy (the pages only load images from their own site). */
    private val image: (String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now
) {
    private class Cached(val at: Long, val entries: Map<String, List<DiscoverEntry>>)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val fetching = Mutex()
    private val writes = ConcurrentHashMap<Long, ArrayDeque<Long>>()

    /** This week's episodes, from yesterday (time zones) to eight days ahead. */
    suspend fun airing(lang: String): List<DiscoverItemDto> {
        val entries = cached("airing", AIRING_TTL_MS) {
            val now = clock() / 1000
            mapOf("airing" to api.airing(now - DAY_S, now + 8 * DAY_S))
        }["airing"].orEmpty()
        return decorate(entries, lang)
    }

    /** AniList's community on this season: the best rated (with enough people behind it) and what is trending. */
    suspend fun community(lang: String): Map<String, Any> {
        val rows = cached("community", COMMUNITY_TTL_MS) { api.seasonCommunity(today()) }
        val (season, year) = AniListDiscover.seasonOf(today())
        return mapOf(
            "season" to season, "year" to year, "source" to "AniList", "sourceUrl" to "https://anilist.co/search/anime/this-season",
            "top" to decorate(rows["top"].orEmpty(), lang), "trending" to decorate(rows["trending"].orEmpty(), lang)
        )
    }

    /** Kept on failure: an old list is better than an empty calendar when AniList is slow or down. */
    private suspend fun cached(key: String, ttl: Long, load: suspend () -> Map<String, List<DiscoverEntry>>): Map<String, List<DiscoverEntry>> {
        cache[key]?.takeIf { clock() - it.at < ttl }?.let { return it.entries }
        return fetching.withLock {
            cache[key]?.takeIf { clock() - it.at < ttl }?.let { return@withLock it.entries }
            val fresh = withTimeoutOrNull(FETCH_WAIT_MS) { runCatching { load() }.getOrNull() }?.takeIf { m -> m.values.any { it.isNotEmpty() } }
            if (fresh != null) cache[key] = Cached(clock(), fresh)
            fresh ?: cache[key]?.entries.orEmpty()
        }
    }

    // ---------- The group's picks ----------

    data class PickComment(val name: String, val recommend: Boolean, val comment: String, val at: Long, val mine: Boolean)
    data class SeasonPick(val anilistId: Int, val title: String, val cover: String, val recommends: Int, val comments: List<PickComment>, val mine: PickComment?)
    data class SeasonPicks(val season: String, val year: Int, val items: List<SeasonPick>)

    fun seasonKey(): String = AniListDiscover.seasonOf(today()).let { (s, y) -> "$s-$y" }

    suspend fun picks(viewer: User): SeasonPicks {
        val season = seasonKey()
        val rows = db.tx {
            query(
                """SELECT p.anilist_id, p.title, p.cover, p.recommend, p.comment, p.updated_at, p.user_id, u.name
                   FROM season_picks p JOIN users u ON u.id = p.user_id WHERE p.season = ? ORDER BY p.updated_at DESC LIMIT 1000""",
                season
            ) {
                val c = PickComment(getString(8), getInt(4) == 1, getString(5), getLong(6), getLong(7) == viewer.id)
                Triple(getInt(1), getString(2) to getString(3), c)
            }
        }
        val items = rows.groupBy { it.first }.map { (id, list) ->
            val (title, cover) = list.first().second
            val comments = list.map { it.third }
            SeasonPick(id, title, image(cover), comments.count { it.recommend }, comments.filter { it.comment.isNotBlank() }, comments.firstOrNull { it.mine })
        }.sortedWith(compareByDescending<SeasonPick> { it.recommends }.thenByDescending { p -> p.comments.maxOfOrNull { it.at } ?: 0 })
        val (s, y) = AniListDiscover.seasonOf(today())
        return SeasonPicks(s, y, items.take(MAX_ITEMS))
    }

    /** Recommends [anilistId] this season and/or comments on it (one entry per person and title, replaced each time). */
    suspend fun pick(user: User, anilistId: Int, recommend: Boolean, rawComment: String) {
        require(!user.isGuest) { "Los invitados no pueden recomendar" }
        require(anilistId in 1..MAX_ANILIST_ID) { "Anime no válido" }
        val comment = cleanComment(rawComment)
        require(recommend || comment.isNotEmpty()) { "Escribe un comentario o recomiéndalo" }
        throttle(user.id)
        val season = seasonKey()
        val known = db.tx { queryOne("SELECT title, cover FROM season_picks WHERE anilist_id = ? AND season = ? LIMIT 1", anilistId, season) { getString(1) to getString(2) } }
        val (title, cover) = known ?: lookUp(anilistId)
        val count = db.tx { queryOne("SELECT COUNT(*) FROM season_picks WHERE user_id = ? AND season = ?", user.id, season) { getInt(1) } } ?: 0
        val now = clock()
        db.tx {
            val exists = queryOne("SELECT 1 FROM season_picks WHERE user_id = ? AND anilist_id = ? AND season = ?", user.id, anilistId, season) { 1 } != null
            require(exists || count < MAX_PER_USER) { "Ya tienes $MAX_PER_USER animes comentados esta temporada" }
            update(
                """INSERT INTO season_picks(user_id, anilist_id, season, title, cover, recommend, comment, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(user_id, anilist_id, season) DO UPDATE SET recommend = excluded.recommend, comment = excluded.comment, updated_at = excluded.updated_at""",
                user.id, anilistId, season, title, cover, if (recommend) 1 else 0, comment, now, now
            )
        }
    }

    suspend fun unpick(user: User, anilistId: Int) {
        require(!user.isGuest) { "Los invitados no pueden recomendar" }
        db.tx { update("DELETE FROM season_picks WHERE user_id = ? AND anilist_id = ? AND season = ?", user.id, anilistId, seasonKey()) }
    }

    /** Title and cover from what the calendar already has; AniList only for a title it has not seen. */
    private suspend fun lookUp(id: Int): Pair<String, String> {
        val seen = cache.values.asSequence().flatMap { it.entries.values.asSequence().flatten() }.firstOrNull { it.anilistId == id }
        val entry = seen ?: withTimeoutOrNull(FETCH_WAIT_MS) { runCatching { api.byIds(listOf(id)) }.getOrNull() }?.firstOrNull { it.anilistId == id }
        requireNotNull(entry) { "No encuentro ese anime en AniList" }
        val cover = entry.cover.takeIf { it.startsWith("https://") && AniListHosts.matches(it) }.orEmpty()
        return entry.title.take(200) to cover
    }

    private fun throttle(userId: Long) {
        val now = clock()
        val times = writes.getOrPut(userId) { ArrayDeque() }
        synchronized(times) {
            while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
            require(times.size < MAX_WRITES_PER_MINUTE) { "Demasiados cambios seguidos; espera un momento" }
            times.addLast(now)
        }
    }

    private object AniListHosts {
        fun matches(url: String): Boolean = runCatching { java.net.URI(url).host?.lowercase() }.getOrNull()?.let { it == "anilist.co" || it.endsWith(".anilist.co") } == true
    }

    companion object {
        const val AIRING_TTL_MS = 3 * 3_600_000L
        const val COMMUNITY_TTL_MS = 6 * 3_600_000L
        const val MAX_COMMENT = 500
        const val MAX_PER_USER = 60
        const val MAX_ITEMS = 60
        const val MAX_WRITES_PER_MINUTE = 12
        private const val MAX_ANILIST_ID = 10_000_000
        private const val FETCH_WAIT_MS = 15_000L
        private const val DAY_S = 86_400L

        /** A comment as plain text: no control characters (line breaks kept, at most two in a row), trimmed, short. */
        fun cleanComment(raw: String): String =
            raw.replace(Regex("[\\p{Cntrl}&&[^\\n]]"), "").replace(Regex("\\n{3,}"), "\n\n").trim().take(MAX_COMMENT)
    }
}

private fun ApplicationCall.anilistParam(): Int = parameters["id"]?.toIntOrNull() ?: throw IllegalArgumentException("Anime no válido")

private suspend fun ApplicationCall.seasonOn(s: Services): Boolean {
    if (s.features.isOn("season_social")) return true
    respond(HttpStatusCode.NotFound, mapOf("error" to "Las recomendaciones de temporada están desactivadas"))
    return false
}

private fun ApplicationCall.lang() = request.queryParameters["lang"]?.lowercase()?.takeIf { it.matches(Regex("[a-z]{2}")) } ?: "es"

fun Route.seasonCalendarRoutes(s: Services) {
    get("/calendar/airing") { call.respond(mapOf("items" to s.seasonCalendar.airing(call.lang()))) }
    get("/calendar/community") { if (call.seasonOn(s)) call.respond(s.seasonCalendar.community(call.lang())) }
    get("/calendar/picks") { if (call.seasonOn(s)) call.respond(s.seasonCalendar.picks(call.user)) }
    put("/calendar/picks/{id}") {
        if (!call.seasonOn(s)) return@put
        val b = call.jsonBody(4_096)
        val recommend = b.get("recommend")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true
        s.seasonCalendar.pick(call.user, call.anilistParam(), recommend, b.text("comment", SeasonCalendar.MAX_COMMENT * 2))
        call.respond(s.seasonCalendar.picks(call.user))
    }
    delete("/calendar/picks/{id}") {
        if (!call.seasonOn(s)) return@delete
        s.seasonCalendar.unpick(call.user, call.anilistParam())
        call.respond(s.seasonCalendar.picks(call.user))
    }
}
