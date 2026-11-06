package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nelson.yomukai.data.social.ActivityStats
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Achievements: the rules live in one file (resources/achievements.json: id, name, description, emoji, rarity,
 * secret, rule = metric + threshold); the metrics are measured here from what is already stored. Rules are checked
 * only when something that can change them happens (an event of a related kind), and the first time a user is checked
 * everything is worked out from their history quietly: no flood of notifications for what they did long ago.
 */
class Achievements(
    private val db: Database,
    private val events: Events,
    private val isAnime: (String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Rule(val metric: String, val atLeast: Int)
    data class Achievement(val id: String, val name: String, val description: String, val emoji: String, val rarity: String, val secret: Boolean, val rule: Rule)
    private data class File(val achievements: List<Achievement>)

    val all: List<Achievement> = Gson().fromJson(
        javaClass.getResourceAsStream("/achievements.json")!!.bufferedReader().readText(), File::class.java
    ).achievements.also { list ->
        require(list.map { it.id }.toSet().size == list.size) { "ids de logros repetidos" }
        require(list.all { it.rule.metric in METRICS }) { "métrica desconocida en achievements.json: " + list.map { it.rule.metric }.filter { it !in METRICS } }
    }
    private val zone = ZoneId.systemDefault()

    /** Which metrics an event kind can move. */
    private val triggers: Map<String, Set<String>> = mapOf(
        "episode" to setOf("episodes", "episodes_3_to_5", "episodes_in_a_day", "longest_streak", "series_in_a_week", "best_burst", "weekend_episodes"),
        "chapter" to setOf("chapters", "chapters_in_a_day", "longest_streak"),
        "library" to setOf("dropped", "plan_to_read", "completed", "library"),
        "skip_opening" to setOf("opening_skips"),
        "room_hosted" to setOf("rooms_hosted"),
        "room_join_late" to setOf("late_joins"),
        "room_most_pauses" to setOf("most_pauses"),
        "room_buffering" to setOf("room_buffering"),
        "reaction" to setOf("reactions_in_an_episode"),
        "rated" to setOf("ratings"),
        "questionable_taste" to setOf("questionable_taste"),
        "recommendation_followed" to setOf("recommendations_followed"),
        "recommended" to setOf("recommendations_sent"),
        "room_message" to setOf("room_messages", "spoilers_hidden"),
        "forum_spoiler" to setOf("spoilers_hidden"),
        "thread_revived" to setOf("threads_revived"),
        "theme_used" to setOf("theme_users"),
        "word_saved" to setOf("words_saved"),
        "chapter_no_lookup" to setOf("chapters_without_lookups"),
        "jellyfin_download" to setOf("jellyfin_downloads")
    )

    /** Checks the rules [kind] can affect; the first check of a user works everything out quietly. */
    suspend fun check(userId: Long, kind: String) {
        val user = db.userById(userId)?.takeIf { !it.isGuest } ?: return
        val scanned = db.tx { queryOne("SELECT 1 FROM achievement_scans WHERE user_id = ?", user.id) { 1 } } != null
        val metrics = if (scanned) triggers[kind].orEmpty() else METRICS
        if (metrics.isEmpty()) return
        val have = unlockedIds(user.id)
        val values = HashMap<String, Int>()
        for (a in all.filter { it.rule.metric in metrics && it.id !in have }) {
            val value = values.getOrPut(a.rule.metric) { measure(user.id, a.rule.metric) }
            if (value >= a.rule.atLeast) unlock(user, a, quiet = !scanned)
        }
        if (!scanned) db.tx { update("INSERT OR IGNORE INTO achievement_scans(user_id, at) VALUES(?,?)", user.id, clock()) }
    }

    private suspend fun unlock(user: User, a: Achievement, quiet: Boolean) {
        val inserted = db.tx {
            update("INSERT OR IGNORE INTO achievements(user_id, id, unlocked_at, notified) VALUES(?,?,?,?)", user.id, a.id, clock(), quiet)
        } > 0
        // Shown in the feed only when it happened now (not when found in old history).
        if (inserted && !quiet) {
            db.tx {
                update(
                    "INSERT INTO events(user_id, kind, data, created_at) VALUES(?,?,?,?)",
                    user.id, "achievement", Gson().toJson(mapOf("title" to "${a.emoji} ${a.name}", "text" to a.description, "id" to a.id)), clock()
                )
            }
        }
    }

    suspend fun unlockedIds(userId: Long): Set<String> = db.tx { query("SELECT id FROM achievements WHERE user_id = ?", userId) { getString(1) } }.toSet()

    suspend fun has(userId: Long, id: String): Boolean = id in unlockedIds(userId)

    /** All achievements as [viewer] may see them for [owner]: secrets stay ??? unless the viewer has them too. */
    suspend fun of(owner: Long, viewer: Long): List<Map<String, Any?>> {
        val mine = db.tx { query("SELECT id, unlocked_at FROM achievements WHERE user_id = ?", owner) { getString(1) to getLong(2) } }.toMap()
        val viewerHas = if (viewer == owner) mine.keys else unlockedIds(viewer)
        val members = db.tx { queryOne("SELECT COUNT(*) FROM users WHERE is_guest = 0",) { getInt(1) } }?.coerceAtLeast(1) ?: 1
        val counts = db.tx { query("SELECT id, COUNT(*) FROM achievements GROUP BY id") { getString(1) to getInt(2) } }.toMap()
        return all.map { a ->
            val unlocked = mine[a.id]
            val hidden = a.secret && unlocked == null && a.id !in viewerHas
            mapOf(
                "id" to a.id, "name" to if (hidden) "???" else a.name, "description" to if (hidden) "Logro secreto" else a.description,
                "emoji" to if (hidden) "❔" else a.emoji, "rarity" to a.rarity, "secret" to a.secret, "unlockedAt" to unlocked,
                "percent" to (100 * (counts[a.id] ?: 0) / members)
            )
        }
    }

    /** Achievements unlocked since last asked (for the toast), marked as shown. */
    suspend fun fresh(userId: Long): List<Achievement> {
        val ids = db.tx {
            val list = query("SELECT id FROM achievements WHERE user_id = ? AND notified = 0", userId) { getString(1) }
            update("UPDATE achievements SET notified = 1 WHERE user_id = ?", userId)
            list
        }
        return all.filter { it.id in ids }
    }

    // ---------- Metrics ----------

    private suspend fun eventTimes(userId: Long, kind: String): List<Long> =
        db.tx { query("SELECT created_at FROM events WHERE user_id = ? AND kind = ? ORDER BY created_at", userId, kind) { getLong(1) } }

    private suspend fun libraryCount(userId: Long, status: String?): Int = db.tx {
        if (status == null) queryOne("SELECT COUNT(*) FROM library WHERE user_id = ? AND deleted = 0", userId) { getInt(1) }
        else queryOne("SELECT COUNT(*) FROM library WHERE user_id = ? AND deleted = 0 AND reading_status IN (?, ?)", userId, status, if (status == "PLAN_TO_READ") "PLAN" else status) { getInt(1) }
    } ?: 0

    private fun day(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    @Suppress("CyclomaticComplexMethod")
    suspend fun measure(userId: Long, metric: String): Int = when (metric) {
        "episodes", "chapters" -> {
            val read = db.tx { query("SELECT source_id FROM progress WHERE user_id = ? AND read = 1", userId) { getString(1) } }
            read.count { isAnime(it) == (metric == "episodes") }
        }
        "episodes_3_to_5" -> eventTimes(userId, "episode").count { Instant.ofEpochMilli(it).atZone(zone).hour in 3..4 }
        "episodes_in_a_day" -> eventTimes(userId, "episode").groupingBy(::day).eachCount().values.maxOrNull() ?: 0
        "chapters_in_a_day" -> eventTimes(userId, "chapter").groupingBy(::day).eachCount().values.maxOrNull() ?: 0
        "longest_streak" -> {
            val days = db.tx { query("SELECT updated_at FROM progress WHERE user_id = ? AND read = 1", userId) { getLong(1) } }.map(::day)
            ActivityStats.streak(days, LocalDate.now(zone)).longest
        }
        "weekend_episodes" -> eventTimes(userId, "episode").map(::day).filter { it.dayOfWeek == DayOfWeek.SATURDAY || it.dayOfWeek == DayOfWeek.SUNDAY }
            .groupingBy { it.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) }.eachCount().values.maxOrNull() ?: 0
        "series_in_a_week", "best_burst" -> {
            val done = db.tx {
                query("SELECT data, created_at FROM events WHERE user_id = ? AND kind = 'episode' ORDER BY created_at", userId) {
                    val d = runCatching { JsonParser.parseString(getString(1)).asJsonObject }.getOrNull()
                    ActivityStats.Done(d?.get("work")?.asString.orEmpty(), d?.get("title")?.asString.orEmpty(), "", getLong(2))
                }
            }
            if (metric == "best_burst") ActivityStats.bursts(done).maxOfOrNull { it.count } ?: 0
            else done.groupBy { it.work }.values.maxOfOrNull { list -> maxInWindow(list.map { it.at }, WEEK_MS) } ?: 0
        }
        "reactions_in_an_episode" -> db.tx {
            query("SELECT data FROM events WHERE user_id = ? AND kind = 'reaction'", userId) { getString(1) }
        }.groupingBy { runCatching { JsonParser.parseString(it).asJsonObject.get("chapter")?.asString }.getOrNull().orEmpty() }.eachCount()
            .filterKeys { it.isNotEmpty() }.values.maxOrNull() ?: 0
        "recommendations_followed" -> db.tx {
            query("SELECT data FROM events WHERE user_id = ? AND kind = 'recommendation_followed'", userId) { getString(1) }
        }.map { runCatching { JsonParser.parseString(it).asJsonObject.get("by")?.asString }.getOrNull() }.distinct().size
        "dropped" -> libraryCount(userId, "DROPPED")
        "plan_to_read" -> libraryCount(userId, "PLAN_TO_READ")
        "completed" -> libraryCount(userId, "COMPLETED")
        "library" -> libraryCount(userId, null)
        "ratings" -> db.tx { queryOne("SELECT COUNT(*) FROM ratings WHERE user_id = ?", userId) { getInt(1) } } ?: 0
        "room_messages" -> db.tx { queryOne("SELECT COUNT(*) FROM room_messages WHERE user_id = ?", userId) { getInt(1) } } ?: 0
        "recommendations_sent" -> db.tx { queryOne("SELECT COUNT(*) FROM recommendations WHERE from_id = ?", userId) { getInt(1) } } ?: 0
        "spoilers_hidden" -> (db.tx { queryOne("SELECT COUNT(*) FROM room_messages WHERE user_id = ? AND spoiler = 1", userId) { getInt(1) } } ?: 0) +
            events.count(userId, "forum_spoiler")
        else -> EVENT_METRICS[metric]?.let { events.count(userId, it) } ?: 0
    }

    /** Most timestamps inside any window of [windowMs]. */
    private fun maxInWindow(times: List<Long>, windowMs: Long): Int {
        var best = 0
        var start = 0
        for (end in times.indices) {
            while (times[end] - times[start] > windowMs) start++
            best = maxOf(best, end - start + 1)
        }
        return best
    }

    companion object {
        const val WEEK_MS = 7 * 86_400_000L

        /** Metrics that are just "how many events of this kind". */
        private val EVENT_METRICS = mapOf(
            "opening_skips" to "skip_opening", "rooms_hosted" to "room_hosted", "late_joins" to "room_join_late",
            "most_pauses" to "room_most_pauses", "room_buffering" to "room_buffering", "questionable_taste" to "questionable_taste",
            "threads_revived" to "thread_revived", "theme_users" to "theme_used", "words_saved" to "word_saved",
            "chapters_without_lookups" to "chapter_no_lookup", "jellyfin_downloads" to "jellyfin_download"
        )
        val METRICS: Set<String> = EVENT_METRICS.keys + setOf(
            "episodes", "chapters", "episodes_3_to_5", "episodes_in_a_day", "chapters_in_a_day", "longest_streak", "weekend_episodes",
            "series_in_a_week", "best_burst", "reactions_in_an_episode", "recommendations_followed", "dropped", "plan_to_read",
            "completed", "library", "ratings", "room_messages", "recommendations_sent", "spoilers_hidden"
        )

        /** What the apps may report themselves (things only the device sees); limited per day so it cannot be farmed. */
        val CLIENT_EVENTS = setOf("skip_opening", "word_saved", "chapter_no_lookup")
        const val CLIENT_EVENTS_PER_DAY = 300
    }
}

fun Route.achievementRoutes(s: Services) {
    get("/achievements") { call.respond(s.achievements.of(call.user.id, call.user.id)) }
    get("/achievements/new") { call.respond(s.achievements.fresh(call.user.id)) }
    post("/achievements/event") {
        val kind = call.jsonBody(512).text("kind", 40)
        require(kind in Achievements.CLIENT_EVENTS) { "Evento desconocido" }
        if (!call.user.isGuest && s.events.count(call.user.id, kind, System.currentTimeMillis() - 86_400_000L) < Achievements.CLIENT_EVENTS_PER_DAY) {
            s.events.record(call.user.id, kind, emptyMap())
        }
        call.respond(mapOf("ok" to true))
    }
}
