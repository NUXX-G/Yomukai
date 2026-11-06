package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.work.AdaptationPoint
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

private fun ApplicationCall.param(name: String, max: Int = 2_000): String =
    request.queryParameters[name]?.take(max) ?: throw IllegalArgumentException("Falta el parámetro '$name'")

private fun workKey(raw: String?): String {
    val key = raw.orEmpty()
    require(key.length in 3..400 && Regex("^(al|mal|t):").containsMatchIn(key)) { "Obra no válida" }
    return key
}

/** Reading preferences that steer which source opens each chapter (from the user's settings). */
private suspend fun Services.mergePrefsOf(user: User): Pair<List<String>, List<String>> {
    val settings = runCatching { JsonParser.parseString(db.settings(user.id) ?: "{}").asJsonObject }.getOrElse { JsonObject() }
    fun list(name: String) = settings.getAsJsonArray(name)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.take(40) }.orEmpty()
    return list("chapterLanguages").ifEmpty { listOf("ES", "EN") } to list("sourceOrder")
}

/** The work model over HTTP: one work across sources, its merged chapters, where to continue, calendar, fillers, skips. */
fun Route.workRoutes(s: Services) {
    // The work behind a source page (resolved on first use).
    get("/work") {
        val w = s.works.resolve(call.param("s", 60), call.param("id"))
        call.respond(s.works.dto(w, s.works.sourcesOf(w.key)) { s.proxy.cover(it, emptyMap()) })
    }
    get("/works/{key}") {
        val key = workKey(call.parameters["key"])
        val w = s.works.get(key) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Obra desconocida"))
        call.respond(s.works.dto(w, s.works.sourcesOf(key)) { s.proxy.cover(it, emptyMap()) })
    }
    get("/works/{key}/chapters") {
        val key = workKey(call.parameters["key"])
        val (languages, order) = s.mergePrefsOf(call.user)
        call.respond(s.works.chapters(call.user, key, languages, order))
    }
    // Which source opens one chapter number, chosen by hand (sourceId null = back to automatic).
    put("/works/{key}/choice") {
        val key = workKey(call.parameters["key"])
        val body = call.jsonBody(1_024)
        val number = Works.numberOf(body.text("number", 20)) ?: throw IllegalArgumentException("Número de capítulo no válido")
        val source = body.text("sourceId", 60).ifBlank { null }?.also { require(s.sources.get(it) != null) { "Fuente desconocida" } }
        s.works.choose(call.user, key, number, source)
        call.respond(mapOf("ok" to true))
    }
    // Join another source's page to this work by hand ("it is the same work").
    post("/works/{key}/sources") {
        require(!call.user.isGuest) { "Los invitados no pueden cambiar obras" }
        val key = workKey(call.parameters["key"])
        val body = call.jsonBody(4_096)
        val source = body.text("sourceId", 60).also { require(s.sources.get(it) != null) { "Fuente desconocida" } }
        s.works.link(key, source, body.text("mediaId", 2_000).ifBlank { throw IllegalArgumentException("Falta la obra") }, "manual")
        call.respond(mapOf("ok" to true))
    }
    get("/works/{key}/continue") {
        call.respond(s.works.continuation(workKey(call.parameters["key"]), call.request.queryParameters["episode"]?.toIntOrNull()))
    }
    get("/works/{key}/adaptations/history") { call.respond(s.works.history(workKey(call.parameters["key"]))) }
    // "This anime up to episode N adapts up to chapter C / volume V": anyone (not guests) can correct it, with history.
    put("/adaptations") {
        require(!call.user.isGuest) { "Los invitados no pueden corregir esto" }
        val b = call.jsonBody(2_048)
        s.works.setPoint(
            call.user,
            AdaptationPoint(
                workKey(b.text("animeKey", 400)), b.get("untilEpisode")?.asInt ?: 0, workKey(b.text("targetKey", 400)),
                b.get("chapter")?.takeIf { it.isJsonPrimitive }?.asFloat, b.get("volume")?.takeIf { it.isJsonPrimitive }?.asInt
            )
        )
        call.respond(mapOf("ok" to true))
    }
    get("/calendar") {
        val days = (call.request.queryParameters["days"]?.toIntOrNull() ?: 7).coerceIn(1, 31)
        call.respond(s.works.calendar(call.user, days))
    }
    get("/fillers") {
        call.respond(s.works.fillers(s.http, call.param("title", 200)))
    }
    get("/updates/unified") {
        call.respond(s.catalog.unifiedLatest(s.prefs(call.user)).map { (primary, others) -> mapOf("primary" to primary, "others" to others) })
    }
}

/** Continue rows and skip markers: the parts of the work model the player and the home page use. */
fun Route.playbackRoutes(s: Services) {
    // What to continue: the latest history, each with its progress inside the chapter and the work's banner.
    get("/continue") {
        val rows = s.db.tx {
            query(
                """SELECT h.source_id, h.media_id, h.chapter_id, h.chapter_title, h.title, h.cover, h.type, h.read_at, h.work_key,
                   p.page, p.total, p.fraction, p.read, w.banner, w.cover AS work_cover
                   FROM history h LEFT JOIN progress p ON p.user_id = h.user_id AND p.source_id = h.source_id AND p.chapter_id = h.chapter_id
                   LEFT JOIN works w ON w.key = h.work_key
                   WHERE h.user_id = ? AND h.deleted = 0 ORDER BY h.read_at DESC LIMIT 30""", call.user.id
            ) {
                val total = getInt("total")
                val page = getInt("page")
                val anime = getString("type") == "ANIME"
                mapOf(
                    "sourceId" to getString("source_id"), "mediaId" to getString("media_id"), "chapterId" to getString("chapter_id"),
                    "chapterTitle" to getString("chapter_title"), "title" to getString("title"), "type" to getString("type"),
                    // History covers are already proxied addresses; AniList ones are not.
                    "cover" to (getString("work_cover")?.takeIf { it.startsWith("https://") }?.let { s.proxy.cover(it, emptyMap()) } ?: getString("cover")),
                    "banner" to getString("banner")?.takeIf { it.isNotBlank() }?.let { s.proxy.cover(it, emptyMap()) },
                    "readAt" to getLong("read_at"), "workKey" to getString("work_key"),
                    "fraction" to getDouble("fraction"), "finished" to (getInt("read") == 1),
                    // For video the position and length are in seconds.
                    "remainingMin" to if (anime && total > 0) ((total - page).coerceAtLeast(0) + 59) / 60 else null
                )
            }
        }
        call.respond(rows)
    }
    // Opening/ending markers of an episode from audio fingerprints (computed by the home worker, then cached).
    get("/skip") {
        val sourceId = call.param("s", 60)
        val chapter = call.param("c")
        val next = call.request.queryParameters["other"]?.take(2_000)
        val dedup = "skip:$sourceId:$chapter"
        s.jobs.results.get(dedup)?.let { (file, _) -> return@get call.respond(JsonParser.parseString(file.readText()).asJsonObject) }
        if (!s.features.isOn("worker_jobs") || "audio_fingerprint" !in s.workers.availability().engines || next == null) {
            return@get call.respond(HttpStatusCode.NoContent)
        }
        val episodes = listOf(chapter, next).map { c ->
            s.catalog.rawVideos(sourceId, c).getOrNull()?.firstOrNull { it.isDirect && !ipBound(it.videoUrl) }
        }
        if (episodes.any { it == null }) return@get call.respond(HttpStatusCode.NoContent)
        val payload = JsonObject().apply {
            add("episodes", com.google.gson.JsonArray().also { arr ->
                episodes.filterNotNull().forEach { v -> arr.add(JsonObject().apply { addProperty("url", v.videoUrl); add("headers", com.google.gson.Gson().toJsonTree(v.headers)) }) }
            })
        }
        s.jobs.enqueue(JobQueue.Request(JobTypes.AUDIO_FINGERPRINT, payload, JobPriority.BACKGROUND, dedupKey = dedup, requestedBy = call.user.id, ttlMs = Auth.DAY_MS))
        call.respond(HttpStatusCode.Accepted, mapOf("pending" to true))
    }
}

/** Streams tied to the address that resolved them (Google's) cannot be fetched by the home worker. */
internal fun ipBound(url: String) = listOf("googlevideo.com", "blogger.com", "googleusercontent.com").any { url.contains(it) }
