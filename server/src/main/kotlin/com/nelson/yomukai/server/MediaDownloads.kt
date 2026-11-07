package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

/**
 * Downloads to the home NAS, done by the worker: anime episodes into the Jellyfin library and manga/comic chapters as
 * CBZ for Kavita/Komga. Admins pick them by hand, or follow a work so that new episodes or chapters are queued on
 * their own (like Sonarr), with a few rules per work. Everyone involved hears about it in the bell (and on the phone
 * through ntfy if they turned it on): what was queued, what arrived and what failed.
 */
class MediaDownloads(
    private val db: Database,
    private val catalog: Catalog,
    private val works: Works,
    private val integrations: Integrations,
    private val jobs: JobQueue,
    private val notifications: Notifications,
    private val events: Events,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    /** Per work: which server to prefer (name contains it) and how many new ones to queue in one go. */
    data class Rules(val preferredServer: String = "", val maxPerRun: Int = 5) {
        fun clean() = Rules(preferredServer.trim().take(40), maxPerRun.coerceIn(1, MAX_PER_RUN))
    }

    data class Follow(
        val id: Long, val userId: Long, val sourceId: String, val mediaId: String, val title: String, val rules: Rules,
        val lastNumber: Double, val createdAt: Long
    )

    // ---------- Queueing ----------

    /** Episodes of one anime to Jellyfin. Returns how many were queued. */
    suspend fun queueEpisodes(userId: Long, sourceId: String, mediaId: String, chapters: List<Chapter>, rules: Rules = Rules(), auto: Boolean = false): Int {
        val cfg = integrations.get()
        require(cfg.animePath.isNotBlank()) { "Configura antes la carpeta de anime en Integraciones" }
        val work = runCatching { works.resolve(sourceId, mediaId) }.getOrNull()
        val item = catalog.media(sourceId, mediaId).getOrThrow()
        val config = integrations.forWorker()
        chapters.forEach { ch ->
            val payload = JsonObject().apply {
                addProperty("sourceId", sourceId); addProperty("episodeUrl", ch.url); addProperty("title", work?.title ?: item.title)
                addProperty("number", ch.chapterNumber); addProperty("season", 1); addProperty("episodeTitle", ch.title)
                addProperty("synopsis", (work?.synopsis ?: item.description).take(2_000)); addProperty("cover", work?.cover ?: item.coverUrl)
                addProperty("preferredServer", rules.preferredServer); addProperty("auto", auto); addProperty("mediaId", mediaId)
                work?.anilistId?.let { addProperty("anilistId", it) }; work?.malId?.let { addProperty("malId", it) }
                add("genres", JsonArray().apply { (work?.genres ?: item.genres).take(10).forEach(::add) })
                add("config", config)
            }
            jobs.enqueue(
                JobQueue.Request(
                    JobTypes.JELLYFIN_DOWNLOAD, payload, JobPriority.BACKGROUND, dedupKey = "jf:$sourceId:${ch.url}", requestedBy = userId,
                    ttlMs = 30L * 86_400_000, maxAttempts = 5
                )
            )
        }
        return chapters.size
    }

    /** Chapters of one manga/comic as CBZ files for Kavita/Komga. Returns how many were queued. */
    suspend fun queueChapters(userId: Long, sourceId: String, mediaId: String, chapters: List<Chapter>, auto: Boolean = false): Int {
        val cfg = integrations.get()
        require(cfg.comicsPath.isNotBlank()) { "Configura antes la carpeta de cómics en Integraciones" }
        val item = catalog.media(sourceId, mediaId).getOrThrow()
        val work = runCatching { works.resolve(sourceId, mediaId) }.getOrNull()
        val config = integrations.forWorker()
        var queued = 0
        for (ch in chapters) {
            val (pages, headers) = catalog.rawPages(sourceId, ch.url).getOrNull() ?: continue
            val payload = JsonObject().apply {
                addProperty("series", work?.title ?: item.title); addProperty("number", ch.chapterNumber); addProperty("title", ch.title)
                addProperty("summary", item.description.take(2_000)); addProperty("type", item.type.name); addProperty("auto", auto)
                add("authors", JsonArray().apply { item.author.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add) })
                add("genres", JsonArray().apply { (work?.genres ?: item.genres).take(10).forEach(::add) })
                add("pages", JsonArray().apply { pages.forEach(::add) })
                add("headers", gson.toJsonTree(headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
                work?.anilistId?.let { addProperty("anilistId", it) }; work?.malId?.let { addProperty("malId", it) }
                add("config", config)
            }
            jobs.enqueue(
                JobQueue.Request(JobTypes.CBZ_EXPORT, payload, JobPriority.BACKGROUND, dedupKey = "cbz:$sourceId:${ch.url}", requestedBy = userId, ttlMs = 30L * 86_400_000)
            )
            queued++
        }
        return queued
    }

    // ---------- Follows ----------

    suspend fun follows(): List<Follow> = db.tx { query("SELECT * FROM follows ORDER BY title COLLATE NOCASE") { follow() } }

    suspend fun followOf(sourceId: String, mediaId: String): Follow? =
        db.tx { queryOne("SELECT * FROM follows WHERE source_id = ? AND media_id = ?", sourceId, mediaId) { follow() } }

    /**
     * Starts following a work from what exists today: only what comes out after this moment is downloaded on its own
     * (what is already out can be sent by hand). Following it again only changes the rules.
     */
    suspend fun follow(userId: Long, sourceId: String, mediaId: String, rules: Rules): Follow {
        val item = catalog.media(sourceId, mediaId).getOrThrow()
        val latest = catalog.chapterList(sourceId, mediaId).getOrNull().orEmpty().maxOfOrNull { it.chapterNumber.toDouble() } ?: 0.0
        val r = rules.clean()
        db.tx {
            update(
                """INSERT INTO follows(user_id, source_id, media_id, title, rules, last_number, created_at) VALUES(?,?,?,?,?,?,?)
                   ON CONFLICT(source_id, media_id) DO UPDATE SET rules = excluded.rules""",
                userId, sourceId, mediaId, item.title.take(300), gson.toJson(r), latest, clock()
            )
        }
        return followOf(sourceId, mediaId)!!
    }

    suspend fun unfollow(id: Long): Boolean = db.tx { update("DELETE FROM follows WHERE id = ?", id) } == 1

    /**
     * Looks at every followed work for episodes or chapters newer than the last one seen and queues them. Spaced out
     * so the sources are not hammered. A work whose source fails is simply tried again next time.
     */
    suspend fun checkAll(pauseMs: Long = 5_000) {
        if (!integrations.get().let { it.animePath.isNotBlank() || it.comicsPath.isNotBlank() }) return
        for (f in follows()) {
            runCatching { check(f) }.onFailure { log.info("Seguida {} sin revisar: {}", f.title, it.message) }
            if (pauseMs > 0) delay(pauseMs)
        }
    }

    suspend fun check(f: Follow): Int {
        val chapters = catalog.chapterList(f.sourceId, f.mediaId).getOrThrow()
        val fresh = newOnes(chapters, f.lastNumber, f.rules.maxPerRun)
        if (fresh.isEmpty()) return 0
        val type = catalog.media(f.sourceId, f.mediaId).getOrNull()?.type
        val queued = if (type == MediaType.ANIME) queueEpisodes(f.userId, f.sourceId, f.mediaId, fresh, f.rules, auto = true)
        else queueChapters(f.userId, f.sourceId, f.mediaId, fresh, auto = true)
        db.tx { update("UPDATE follows SET last_number = ? WHERE id = ?", fresh.maxOf { it.chapterNumber.toDouble() }, f.id) }
        if (queued > 0) {
            val what = if (type == MediaType.ANIME) "episodio" else "capítulo"
            val where = if (type == MediaType.ANIME) "al Jellyfin" else "a tu biblioteca de cómics"
            val plural = if (queued == 1) "" else "s"
            val text = "${f.title}: $queued $what$plural nuevo$plural en camino $where"
            notifications.send(f.userId, "download", text, "/obra/${enc(f.sourceId)}/${enc(f.mediaId)}")
        }
        return queued
    }

    // ---------- Results from the worker ----------

    /** A download finished: counts for the achievement and, if it came from a follow, says it arrived. */
    suspend fun finished(job: Job) {
        val user = job.requestedBy ?: return
        when (job.type) {
            JobTypes.JELLYFIN_DOWNLOAD -> {
                events.record(user, "jellyfin_download", mapOf("title" to job.payload.str("title"), "number" to job.payload.str("number")))
                if (job.payload.bool("auto")) {
                    notifications.send(user, "download", "Ya está en tu Jellyfin: ${job.payload.str("title")} ep. ${number(job.payload)}", "")
                }
            }
            JobTypes.CBZ_EXPORT -> if (job.payload.bool("auto")) {
                notifications.send(user, "download", "Ya está en tu biblioteca: ${job.payload.str("series")} cap. ${number(job.payload)}", "")
            }
        }
    }

    /** A download gave up for good (not retried any more): the person who asked for it is told why. */
    suspend fun failed(job: Job) {
        val user = job.requestedBy ?: return
        if (job.status != JobStatus.FAILED || job.type !in setOf(JobTypes.JELLYFIN_DOWNLOAD, JobTypes.CBZ_EXPORT)) return
        val name = job.payload.str("title").ifBlank { job.payload.str("series") }
        notifications.send(user, "download", "No se pudo descargar $name (${number(job.payload)}): ${job.error.orEmpty().take(120)}", "")
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")

    private fun number(p: JsonObject) = p.get("number")?.asDouble?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }.orEmpty()

    private fun java.sql.ResultSet.follow() = Follow(
        getLong("id"), getLong("user_id"), getString("source_id"), getString("media_id"), getString("title"),
        runCatching { gson.fromJson(getString("rules"), Rules::class.java) }.getOrNull()?.clean() ?: Rules(),
        getDouble("last_number"), getLong("created_at")
    )

    companion object {
        private val log = LoggerFactory.getLogger(MediaDownloads::class.java)
        const val MAX_PER_RUN = 20

        /**
         * What came out after [lastNumber], oldest first, at most [max] (a source that suddenly lists hundreds of
         * "new" chapters, or renumbers, does not fill the NAS in one go). Unnumbered entries are never auto-picked.
         */
        fun newOnes(chapters: List<Chapter>, lastNumber: Double, max: Int): List<Chapter> =
            chapters.filter { it.chapterNumber > 0f && it.chapterNumber.toDouble() > lastNumber + 1e-6 }
                .distinctBy { it.chapterNumber }
                .sortedBy { it.chapterNumber }
                .take(max.coerceIn(1, MAX_PER_RUN))

        private fun JsonObject.str(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        private fun JsonObject.bool(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    }
}

/** Admin only: download by hand, and follow works so new episodes/chapters download on their own. */
private suspend fun io.ktor.server.application.ApplicationCall.selection(): Triple<String, String, List<String>> {
    val b = jsonBody(32_000)
    val ids = b.getAsJsonArray("chapterIds")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.take(2_000) }?.take(500).orEmpty()
    require(ids.isNotEmpty()) { "Elige al menos un episodio o capítulo" }
    return Triple(b.text("sourceId", 60), b.text("mediaId", 2_000), ids)
}

fun Route.mediaLibraryAdminRoutes(s: Services) {
    post("/jellyfin/download") {
        require(s.features.isOn("jellyfin")) { "Jellyfin está desactivado" }
        val (sourceId, mediaId, ids) = call.selection()
        require(s.integrations.get().animePath.isNotBlank()) { "Configura antes la carpeta de anime en Integraciones" }
        val chapters = s.catalog.chapterList(sourceId, mediaId).getOrThrow().filter { it.id in ids || it.url in ids }
        call.respond(mapOf("queued" to s.library.queueEpisodes(call.user.id, sourceId, mediaId, chapters)))
    }
    post("/cbz/export") {
        require(s.features.isOn("jellyfin")) { "Desactivado" }
        val (sourceId, mediaId, ids) = call.selection()
        require(s.integrations.get().comicsPath.isNotBlank()) { "Configura antes la carpeta de cómics en Integraciones" }
        val chapters = s.catalog.chapterList(sourceId, mediaId).getOrThrow().filter { it.id in ids || it.url in ids }
        call.respond(mapOf("queued" to s.library.queueChapters(call.user.id, sourceId, mediaId, chapters)))
    }
    get("/follows") {
        call.respond(
            s.library.follows().map {
                mapOf(
                    "id" to it.id, "sourceId" to it.sourceId, "mediaId" to it.mediaId, "title" to it.title, "rules" to it.rules,
                    "lastNumber" to it.lastNumber, "createdAt" to it.createdAt
                )
            }
        )
    }
    get("/follows/of") {
        val f = s.library.followOf(call.request.queryParameters["s"].orEmpty().take(60), call.request.queryParameters["id"].orEmpty().take(2_000))
        call.respond(mapOf("follow" to f?.let { mapOf("id" to it.id, "rules" to it.rules, "lastNumber" to it.lastNumber) }))
    }
    post("/follows") {
        require(s.features.isOn("jellyfin")) { "Jellyfin está desactivado" }
        val b = call.jsonBody(4_096)
        val sourceId = b.text("sourceId", 60)
        require(s.sources.get(sourceId) != null) { "Fuente desconocida" }
        val rules = b.getAsJsonObject("rules")?.let { r ->
            MediaDownloads.Rules(
                r.get("preferredServer")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                r.get("maxPerRun")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: 5
            )
        } ?: MediaDownloads.Rules()
        val f = s.library.follow(call.user.id, sourceId, b.text("mediaId", 2_000), rules)
        call.respond(mapOf("id" to f.id, "rules" to f.rules, "lastNumber" to f.lastNumber))
    }
    delete("/follows/{id}") {
        val ok = s.library.unfollow(call.parameters["id"]?.toLongOrNull() ?: 0)
        call.respond(if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound, mapOf("ok" to ok))
    }
    post("/follows/check") {
        require(s.features.isOn("jellyfin")) { "Jellyfin está desactivado" }
        val queued = s.library.follows().sumOf { f -> runCatching { s.library.check(f) }.getOrDefault(0) }
        call.respond(mapOf("queued" to queued))
    }
}

/** Which of [names] (a work's servers, in the source's order) to use: the first that contains [preferred], else the first. */
fun preferServer(names: List<String>, preferred: String): Int =
    if (preferred.isBlank()) 0 else names.indexOfFirst { it.contains(preferred.trim(), ignoreCase = true) }.coerceAtLeast(0)
