package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.social.ActivityStats
import com.nelson.yomukai.data.social.ChatGuard
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.work.WorkKey
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Friends on the same server: profiles (avatar, banner, bio, accent, favourite, shown title), stats, libraries with
 * privacy (whole library hidden, or some categories), presence ("watching now"), the activity feed, who has a work,
 * ratings and recommendations. Everyone with an account (not guests) is a friend: the server is a private group.
 */
class Social(
    private val db: Database,
    private val events: Events,
    private val dataDir: File,
    private val isAnime: (String) -> Boolean,
    private val workKeyOf: (String, String) -> String?,
    private val notify: suspend (userId: Long, kind: String, text: String, link: String) -> Unit = { _, _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val listType = object : TypeToken<List<String>>() {}.type
    private val dir = File(dataDir, "profiles").apply { mkdirs() }
    private val zone = ZoneId.systemDefault()

    // ---------- Profile ----------

    data class Favorite(val sourceId: String, val mediaId: String, val title: String, val cover: String)

    data class Profile(
        val name: String, val bio: String, val accent: String, val favorite: Favorite?, val shownTitle: String,
        val hidden: Boolean, val privateCategories: List<String>, val avatar: String?, val banner: String?, val admin: Boolean, val since: Long
    )

    suspend fun profile(userId: Long): Profile? = db.tx {
        queryOne("SELECT * FROM users WHERE id = ? AND is_guest = 0", userId) { profileRow() }
    }

    suspend fun profileByName(name: String): Pair<Long, Profile>? = db.tx {
        queryOne("SELECT * FROM users WHERE name = ? AND is_guest = 0", name.take(60)) { getLong("id") to profileRow() }
    }

    private fun java.sql.ResultSet.profileRow(): Profile {
        val name = getString("name")
        val avatarAt = getLong("avatar_at")
        val bannerAt = getLong("banner_at")
        return Profile(
            name, getString("bio"), getString("accent"),
            getString("favorite").takeIf { it.isNotBlank() }?.let { runCatching { gson.fromJson(it, Favorite::class.java) }.getOrNull() },
            getString("shown_title"), getInt("hidden") == 1,
            runCatching { gson.fromJson<List<String>>(getString("private_categories"), listType) }.getOrDefault(emptyList()),
            avatarAt.takeIf { it > 0 }?.let { "/api/users/${enc(name)}/avatar?v=$it" },
            bannerAt.takeIf { it > 0 }?.let { "/api/users/${enc(name)}/banner?v=$it" },
            getInt("is_admin") == 1, getLong("created_at")
        )
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    suspend fun update(user: User, body: JsonObject) {
        require(!user.isGuest) { "Los invitados no tienen perfil" }
        val bio = ChatGuard.clean(body.text("bio", 600)).take(MAX_BIO)
        val accent = body.text("accent", 10).lowercase().also { require(it.isEmpty() || ACCENT.matches(it)) { "Color no válido" } }
        val favorite = body.getAsJsonObject("favorite")?.let {
            Favorite(it.text("sourceId", 60), it.text("mediaId", 2_000), it.text("title", 200), it.text("cover", 2_000).takeIf { c -> c.startsWith("/api/img?") }.orEmpty())
        }?.takeIf { it.sourceId.isNotBlank() && it.mediaId.isNotBlank() }
        val private = body.getAsJsonArray("privateCategories")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.trim()?.take(100) }
            ?.filter { it.isNotEmpty() }?.distinct()?.take(50).orEmpty()
        val shownTitle = body.text("shownTitle", 60)
        db.tx {
            update(
                "UPDATE users SET bio = ?, accent = ?, favorite = ?, hidden = ?, private_categories = ?, shown_title = ? WHERE id = ?",
                bio, accent, favorite?.let(gson::toJson).orEmpty(), body.get("hidden")?.asBoolean == true, gson.toJson(private), shownTitle, user.id
            )
        }
    }

    /** Avatar (square, 256 px) or banner (3:1, 1500 px), always re-drawn as JPEG; the original file is never kept. */
    suspend fun saveImage(user: User, kind: String, bytes: ByteArray) {
        require(!user.isGuest) { "Los invitados no tienen perfil" }
        require(kind == "avatar" || kind == "banner")
        val jpeg = if (kind == "avatar") ImageShrinker.reencode(bytes, 256, 1.0) else ImageShrinker.reencode(bytes, 1500, 3.0)
        requireNotNull(jpeg) { "No es una imagen válida" }
        File(dir, "${user.id}-$kind.jpg").writeBytes(jpeg)
        db.tx { update("UPDATE users SET ${kind}_at = ? WHERE id = ?", clock(), user.id) }
    }

    suspend fun removeImage(user: User, kind: String) {
        require(kind == "avatar" || kind == "banner")
        File(dir, "${user.id}-$kind.jpg").delete()
        db.tx { update("UPDATE users SET ${kind}_at = 0 WHERE id = ?", user.id) }
    }

    fun imageFile(userId: Long, kind: String): File? = File(dir, "$userId-$kind.jpg").takeIf { it.isFile }

    // ---------- Stats and library ----------

    data class Stats(val episodes: Int, val chapters: Int, val hours: Int, val streak: Int, val longestStreak: Int, val topGenre: String?, val inLibrary: Int)

    suspend fun stats(userId: Long): Stats {
        val read = db.tx { query("SELECT source_id, updated_at FROM progress WHERE user_id = ? AND read = 1", userId) { getString(1) to getLong(2) } }
        val episodes = read.count { isAnime(it.first) }
        val chapters = read.size - episodes
        val days = read.map { Instant.ofEpochMilli(it.second).atZone(zone).toLocalDate() }
        val streak = ActivityStats.streak(days, LocalDate.now(zone))
        val library = db.library(userId)
        val genres = library.map { row -> row.genres.split('|', ',').map { it.trim() }.filter { it.isNotEmpty() } }
        return Stats(episodes, chapters, ActivityStats.hours(episodes, chapters), streak.current, streak.longest, ActivityStats.topGenre(genres), library.size)
    }

    /** What [viewer] may see of [owner]'s library: nothing when hidden, never the private categories. */
    suspend fun visibleLibrary(owner: Long, profile: Profile, viewer: User): List<LibraryRow> {
        if (viewer.id != owner && profile.hidden) return emptyList()
        val all = db.library(owner)
        if (viewer.id == owner) return all
        return all.filter { row -> row.categories.orEmpty().none { it in profile.privateCategories } }
    }

    /** Members of the group for the friends list: name, avatar, accent and what they are doing now (unless hidden). */
    suspend fun members(viewer: User): List<Map<String, Any?>> = db.tx {
        query("SELECT * FROM users WHERE is_guest = 0 AND suspended_at IS NULL ORDER BY name COLLATE NOCASE") { getLong("id") to profileRow() }
    }.map { (id, p) ->
        mapOf("name" to p.name, "avatar" to p.avatar, "accent" to p.accent, "me" to (id == viewer.id), "now" to if (p.hidden) null else presenceOf(id))
    }

    // ---------- Presence ----------

    data class Presence(val kind: String, val title: String, val label: String, val sourceId: String, val mediaId: String, val at: Long)

    private val presence = ConcurrentHashMap<Long, Presence>()

    suspend fun setPresence(user: User, body: JsonObject) {
        if (user.isGuest) return
        val kind = body.text("kind", 10).takeIf { it == "watching" || it == "reading" } ?: return presence.remove(user.id).let { }
        val sourceId = body.text("sourceId", 60)
        val mediaId = body.text("mediaId", 2_000)
        // Something kept in a private category is not announced as "watching now" either.
        if (isPrivate(user.id, sourceId, mediaId)) return presence.remove(user.id).let { }
        presence[user.id] = Presence(kind, body.text("title", 200), body.text("label", 100), sourceId, mediaId, clock())
    }

    /** True when [userId] keeps this title in one of their private categories. */
    suspend fun isPrivate(userId: Long, sourceId: String, mediaId: String): Boolean {
        if (sourceId.isBlank() || mediaId.isBlank()) return false
        // The same work read from another source counts too: match the page and every page of its work.
        val key = workKeyOf(sourceId, mediaId)
        return db.tx {
            query(
                """SELECT u.private_categories, l.categories FROM library l JOIN users u ON u.id = l.user_id
                   WHERE l.user_id = ? AND l.deleted = 0 AND ((l.source_id = ? AND l.media_id = ?) OR (? IS NOT NULL AND l.work_key = ?))""",
                userId, sourceId, mediaId, key, key
            ) {
                val private = runCatching { gson.fromJson<List<String>>(getString(1), listType) }.getOrDefault(emptyList())
                val cats = runCatching { gson.fromJson<List<String>>(getString(2), listType) }.getOrDefault(emptyList())
                cats.any { it in private }
            }
        }.any { it }
    }

    fun presenceOf(userId: Long): Presence? = presence[userId]?.takeIf { clock() - it.at < PRESENCE_MS }

    // ---------- Activity ----------

    /**
     * Called before progress is stored: chapters that just became read become "done" events for the feed and the
     * achievements. Bulk changes ("mark everything as read") are not activity and are skipped.
     */
    suspend fun onProgress(user: User, rows: List<ProgressRow>, history: HistoryRow?) {
        if (user.isGuest || rows.isEmpty() || rows.size > LIVE_ROWS) return
        for (row in rows.filter { it.read }) {
            val was = db.tx {
                queryOne("SELECT read FROM progress WHERE user_id = ? AND source_id = ? AND chapter_id = ?", user.id, row.sourceId, row.chapterId) { getInt(1) == 1 }
            } == true
            if (was) continue
            val h = history?.takeIf { it.sourceId == row.sourceId && it.mediaId == row.mediaId }
            val title = h?.title ?: db.libraryRow(user.id, row.sourceId, row.mediaId)?.title ?: continue
            val anime = isAnime(row.sourceId)
            events.record(
                user.id, if (anime) "episode" else "chapter",
                mapOf(
                    "s" to row.sourceId, "m" to row.mediaId, "c" to row.chapterId, "title" to title.take(200),
                    "label" to (if (h?.chapterId == row.chapterId) h.chapterTitle else "").take(100),
                    "cover" to h?.cover.orEmpty().take(2_000), "work" to (workKeyOf(row.sourceId, row.mediaId) ?: WorkKey.itemKey(row.sourceId, row.mediaId))
                )
            )
        }
    }

    data class FeedEntry(
        val user: String, val avatar: String?, val kind: String, val title: String, val count: Int, val first: String, val last: String,
        val marathon: Boolean, val sourceId: String, val mediaId: String, val cover: String, val at: Long, val text: String = ""
    )

    /** The group's recent activity, bursts merged ("12 episodes in a row" is one entry), hidden people left out. */
    suspend fun feed(viewer: User, days: Int = 7): List<FeedEntry> {
        val since = clock() - days * DAY_MS
        data class Row(val userId: Long, val user: String, val hidden: Boolean, val kind: String, val data: JsonObject, val at: Long)
        val rows = db.tx {
            query(
                """SELECT e.user_id, u.name, u.hidden, e.kind, e.data, e.created_at FROM events e JOIN users u ON u.id = e.user_id
                   WHERE e.created_at > ? AND e.kind IN ('episode','chapter','rated','achievement') ORDER BY e.created_at DESC LIMIT 2000""",
                since
            ) { Row(getLong(1), getString(2), getInt(3) == 1, getString(4), runCatching { JsonParser.parseString(getString(5)).asJsonObject }.getOrDefault(JsonObject()), getLong(6)) }
        }.filter { !it.hidden || it.userId == viewer.id }.let { list ->
            // Titles in someone's private categories never reach the others' feed.
            val cache = HashMap<Triple<Long, String, String>, Boolean>()
            list.filter { r ->
                r.userId == viewer.id || r.kind == "achievement" ||
                    !cache.getOrPut(Triple(r.userId, r.data.str("s"), r.data.str("m"))) { isPrivate(r.userId, r.data.str("s"), r.data.str("m")) }
            }
        }
        val avatars = HashMap<Long, String?>()
        suspend fun avatar(id: Long) = avatars.getOrPut(id) { profile(id)?.avatar }
        val out = mutableListOf<FeedEntry>()
        rows.filter { it.kind == "episode" || it.kind == "chapter" }.groupBy { it.userId to it.kind }.forEach { (key, list) ->
            val byWork = list.associateBy { it.data.str("work") + "|" + it.at }
            val bursts = ActivityStats.bursts(list.map { ActivityStats.Done(it.data.str("work"), it.data.str("title"), it.data.str("label"), it.at) })
            bursts.forEach { b ->
                val sample = byWork[b.work + "|" + b.to]?.data ?: JsonObject()
                out += FeedEntry(
                    list.first().user, avatar(key.first), key.second, b.title, b.count, b.firstLabel, b.lastLabel, b.marathon,
                    sample.str("s"), sample.str("m"), sample.str("cover"), b.to
                )
            }
        }
        rows.filter { it.kind == "rated" || it.kind == "achievement" }.forEach {
            out += FeedEntry(it.user, avatar(it.userId), it.kind, it.data.str("title"), 1, "", "", false, it.data.str("s"), it.data.str("m"), it.data.str("cover"), it.at, it.data.str("text"))
        }
        return out.sortedByDescending { it.at }.take(60)
    }

    private fun JsonObject.str(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    /** Friends who have this work (any of its source pages) and how far they are, unless they hide their library. */
    suspend fun friendsOn(viewer: User, sourceId: String, mediaId: String): List<Map<String, Any?>> {
        val key = workKeyOf(sourceId, mediaId)
        val rows = db.tx {
            query(
                """SELECT u.id, u.name, u.hidden, u.private_categories, l.reading_status, l.categories, l.source_id, l.media_id,
                   (SELECT h.chapter_title FROM history h WHERE h.user_id = l.user_id AND h.source_id = l.source_id AND h.media_id = l.media_id) AS last
                   FROM library l JOIN users u ON u.id = l.user_id
                   WHERE l.deleted = 0 AND u.id <> ? AND ((l.source_id = ? AND l.media_id = ?) OR (? IS NOT NULL AND l.work_key = ?))""",
                viewer.id, sourceId, mediaId, key, key
            ) {
                val private = runCatching { gson.fromJson<List<String>>(getString("private_categories"), listType) }.getOrDefault(emptyList())
                val cats = runCatching { gson.fromJson<List<String>>(getString("categories"), listType) }.getOrDefault(emptyList())
                if (getInt("hidden") == 1 || cats.any { it in private }) null
                else mapOf("name" to getString("name"), "status" to getString("reading_status"), "last" to getString("last"))
            }
        }
        return rows.filterNotNull().distinctBy { it["name"] }
    }

    // ---------- Ratings and recommendations ----------

    suspend fun rate(user: User, sourceId: String, mediaId: String, title: String, cover: String, score: Int?, review: String) {
        require(!user.isGuest) { "Los invitados no pueden valorar" }
        val key = workKeyOf(sourceId, mediaId) ?: WorkKey.itemKey(sourceId, mediaId)
        if (score == null) {
            db.tx { update("DELETE FROM ratings WHERE user_id = ? AND work_key = ?", user.id, key) }
            return
        }
        require(score in 1..10) { "La nota va del 1 al 10" }
        val text = ChatGuard.clean(review).take(MAX_REVIEW)
        db.tx {
            update(
                """INSERT INTO ratings(user_id, work_key, score, review, updated_at) VALUES(?,?,?,?,?)
                   ON CONFLICT(user_id, work_key) DO UPDATE SET score = excluded.score, review = excluded.review, updated_at = excluded.updated_at""",
                user.id, key, score, text, clock()
            )
        }
        if (score == 10 && (groupAverage(key, user.id) ?: 10.0) < 5.0) events.record(user.id, "questionable_taste", mapOf("work" to key))
        events.record(user.id, "rated", mapOf("s" to sourceId, "m" to mediaId, "title" to title.take(200), "cover" to cover.take(2_000), "text" to "$score/10", "score" to score, "work" to key))
    }

    suspend fun ratings(viewer: User, sourceId: String, mediaId: String): Map<String, Any?> {
        val key = workKeyOf(sourceId, mediaId) ?: WorkKey.itemKey(sourceId, mediaId)
        val list = db.tx {
            query(
                "SELECT u.name, r.score, r.review, r.updated_at, u.id FROM ratings r JOIN users u ON u.id = r.user_id WHERE r.work_key = ? ORDER BY r.updated_at DESC",
                key
            ) { mapOf("name" to getString(1), "score" to getInt(2), "review" to getString(3), "at" to getLong(4), "me" to (getLong(5) == viewer.id)) }
        }
        val avg = list.map { it["score"] as Int }.takeIf { it.isNotEmpty() }?.average()
        return mapOf("average" to avg, "count" to list.size, "ratings" to list)
    }

    /** Group average of a work (for the "questionable taste" achievement and the title page). */
    suspend fun groupAverage(key: String, exceptUser: Long): Double? =
        db.tx { queryOne("SELECT AVG(score), COUNT(*) FROM ratings WHERE work_key = ? AND user_id <> ?", key, exceptUser) { if (getInt(2) == 0) null else getDouble(1) } }

    suspend fun recommend(from: User, toName: String, body: JsonObject) {
        require(!from.isGuest) { "Los invitados no pueden recomendar" }
        val to = db.tx { queryOne("SELECT id FROM users WHERE name = ? AND is_guest = 0", toName.take(60)) { getLong(1) } } ?: throw NoSuchElementException("No existe ese usuario")
        require(to != from.id) { "No hace falta recomendártelo a ti" }
        val sourceId = body.text("sourceId", 60)
        val mediaId = body.text("mediaId", 2_000)
        require(sourceId.isNotBlank() && mediaId.isNotBlank()) { "Falta la obra" }
        val recent = db.tx { queryOne("SELECT COUNT(*) FROM recommendations WHERE from_id = ? AND created_at > ?", from.id, clock() - DAY_MS) { getInt(1) } } ?: 0
        require(recent < MAX_RECOMMENDATIONS_PER_DAY) { "Ya has recomendado mucho hoy" }
        db.tx {
            update(
                "INSERT INTO recommendations(from_id, to_id, source_id, media_id, work_key, title, cover, message, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                from.id, to, sourceId, mediaId, workKeyOf(sourceId, mediaId) ?: WorkKey.itemKey(sourceId, mediaId), body.text("title", 200),
                body.text("cover", 2_000).takeIf { it.startsWith("/api/img?") }.orEmpty(), ChatGuard.clean(body.text("message", 400)).take(MAX_MESSAGE), clock()
            )
        }
        events.record(from.id, "recommended", mapOf("to" to toName.take(60)))
        notify(to, "recommendation", "${from.name} te recomienda «${body.text("title", 200)}»", "/obra/${enc(sourceId)}/${enc(mediaId)}")
    }

    suspend fun inbox(user: User): List<Map<String, Any?>> = db.tx {
        query(
            """SELECT r.*, u.name AS from_name FROM recommendations r JOIN users u ON u.id = r.from_id WHERE r.to_id = ? ORDER BY r.created_at DESC LIMIT 50""",
            user.id
        ) {
            mapOf(
                "id" to getLong("id"), "from" to getString("from_name"), "sourceId" to getString("source_id"), "mediaId" to getString("media_id"),
                "title" to getString("title"), "cover" to getString("cover"), "message" to getString("message"), "at" to getLong("created_at"), "seen" to (getInt("seen") == 1)
            )
        }
    }

    suspend fun markSeen(user: User) = db.tx { update("UPDATE recommendations SET seen = 1 WHERE to_id = ?", user.id) }

    /** When someone adds a work to their library, recommendations of it count as followed (for "Influencer"). */
    suspend fun onLibraryAdd(user: User, sourceId: String, mediaId: String) {
        val key = workKeyOf(sourceId, mediaId) ?: WorkKey.itemKey(sourceId, mediaId)
        val followed = db.tx {
            val senders = query(
                "SELECT DISTINCT from_id FROM recommendations WHERE to_id = ? AND added = 0 AND (work_key = ? OR (source_id = ? AND media_id = ?))",
                user.id, key, sourceId, mediaId
            ) { getLong(1) }
            update("UPDATE recommendations SET added = 1 WHERE to_id = ? AND (work_key = ? OR (source_id = ? AND media_id = ?))", user.id, key, sourceId, mediaId)
            senders
        }
        followed.forEach { events.record(it, "recommendation_followed", mapOf("by" to user.name)) }
    }

    companion object {
        const val MAX_BIO = 300
        const val MAX_REVIEW = 500
        const val MAX_MESSAGE = 280
        const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
        const val PRESENCE_MS = 3 * 60_000L
        const val LIVE_ROWS = 3
        const val DAY_MS = 86_400_000L
        const val MAX_RECOMMENDATIONS_PER_DAY = 30
        private val ACCENT = Regex("^#[0-9a-f]{6}$")
    }
}

private fun io.ktor.server.application.ApplicationCall.q(name: String, max: Int): String =
    request.queryParameters[name]?.take(max) ?: throw IllegalArgumentException("Falta $name")

fun Route.socialRoutes(s: Services) {
    get("/profile") { call.respond(s.social.profile(call.user.id) ?: throw NoSuchElementException("Los invitados no tienen perfil")) }
    put("/profile") {
        val body = call.jsonBody(8_192)
        // The title under the name must be an achievement the user has.
        body.text("shownTitle", 60).takeIf { it.isNotEmpty() }?.let { require(s.achievements.has(call.user.id, it)) { "Ese logro aún no es tuyo" } }
        s.social.update(call.user, body)
        call.respond(mapOf("ok" to true))
    }
    for (kind in listOf("avatar", "banner")) {
        post("/profile/$kind") {
            val length = call.request.contentLength()
            require(length == null || length <= Social.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 4 MB)" }
            val bytes = call.receiveChannel().readRemaining(Social.MAX_IMAGE_BYTES + 1L).readByteArray()
            require(bytes.size <= Social.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 4 MB)" }
            s.social.saveImage(call.user, kind, bytes)
            call.respond(mapOf("ok" to true))
        }
        delete("/profile/$kind") {
            s.social.removeImage(call.user, kind)
            call.respond(mapOf("ok" to true))
        }
        get("/users/{name}/$kind") {
            val (id, _) = s.social.profileByName(call.parameters["name"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe"))
            val file = s.social.imageFile(id, kind) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sin imagen"))
            call.response.header(HttpHeaders.CacheControl, "private, max-age=604800")
            call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
        }
    }
    get("/users") { call.respond(s.social.members(call.user)) }
    get("/users/{name}") {
        val (id, p) = s.social.profileByName(call.parameters["name"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe ese usuario"))
        val mine = id == call.user.id
        val library = s.social.visibleLibrary(id, p, call.user)
        call.respond(
            mapOf(
                "profile" to if (mine) p else p.copy(privateCategories = emptyList()),
                "stats" to s.social.stats(id), "library" to library, "libraryHidden" to (!mine && p.hidden),
                "now" to if (p.hidden && !mine) null else s.social.presenceOf(id),
                "achievements" to s.achievements.of(id, call.user.id)
            )
        )
    }
    post("/presence") {
        s.social.setPresence(call.user, call.jsonBody(4_096))
        call.respond(mapOf("ok" to true))
    }
    get("/feed") { call.respond(s.social.feed(call.user)) }
    get("/friends-on") { call.respond(s.social.friendsOn(call.user, call.q("s", 60), call.q("id", 2_000))) }
    get("/ratings") { call.respond(s.social.ratings(call.user, call.q("s", 60), call.q("id", 2_000))) }
    put("/ratings") {
        val b = call.jsonBody(4_096)
        s.social.rate(
            call.user, b.text("sourceId", 60), b.text("mediaId", 2_000), b.text("title", 200), b.text("cover", 2_000),
            b.get("score")?.takeIf { it.isJsonPrimitive }?.asInt, b.text("review", 600)
        )
        call.respond(mapOf("ok" to true))
    }
    post("/recommend/{name}") {
        s.social.recommend(call.user, call.parameters["name"].orEmpty(), call.jsonBody(8_192))
        call.respond(mapOf("ok" to true))
    }
    get("/recommendations") { call.respond(s.social.inbox(call.user)) }
    post("/recommendations/seen") {
        s.social.markSeen(call.user)
        call.respond(mapOf("ok" to true))
    }
}

/** True when a source page belongs to an anime source (episodes, not chapters). */
fun animeSourceCheck(services: () -> com.nelson.yomukai.data.source.SourceManager): (String) -> Boolean = { id -> services().get(id) is AnimeSource }
