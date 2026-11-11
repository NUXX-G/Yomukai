package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.learning.AnkiExport
import com.nelson.yomukai.data.learning.CardState
import com.nelson.yomukai.data.learning.Comprehension
import com.nelson.yomukai.data.learning.Fsrs
import com.nelson.yomukai.data.learning.Grade
import com.nelson.yomukai.data.ocr.BoxKind
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.security.MessageDigest

/**
 * OCR of comic pages, shared: whoever reads a page first (the phone with ML Kit, or the home worker with Tesseract)
 * leaves its text boxes here and nobody repeats the work. Only validated, bounded data is kept: fractions of the page,
 * short texts and the box kind. Pages are found by the server itself, never from an address a user sends.
 */
class OcrStore(private val s: Services) {
    private fun key(sourceId: String, chapterId: String, page: Int) =
        "ocr:" + MessageDigest.getInstance("SHA-256").digest("$sourceId|$chapterId|$page".toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun get(sourceId: String, chapterId: String, page: Int): JsonObject? =
        s.jobs.results.get(key(sourceId, chapterId, page))?.first?.let { runCatching { JsonParser.parseString(it.readText()).asJsonObject }.getOrNull() }

    /** Stores [body] after checking it: at most 300 boxes, coordinates 0..1, texts up to 2000 characters. */
    suspend fun put(sourceId: String, chapterId: String, page: Int, body: JsonObject, engine: String) {
        val clean = sanitize(body, engine)
        val bytes = clean.toString().toByteArray()
        s.jobs.results.put(key(sourceId, chapterId, page), bytes.inputStream(), "application/json", MAX_BYTES)
    }

    /** Asks the home worker to read a chapter's pages (those not read yet). The page addresses come from the source. */
    suspend fun request(user: User, sourceId: String, chapterId: String, lang: String): Int {
        require(!user.isGuest) { "Los invitados no pueden pedir OCR" }
        if (!s.features.isOn("worker_jobs") || JobTypes.OCR !in s.workers.availability().engines) return 0
        val (pages, headers) = s.catalog.rawPages(sourceId, chapterId).getOrThrow()
        var queued = 0
        pages.take(MAX_PAGES).forEachIndexed { i, url ->
            if (get(sourceId, chapterId, i) != null) return@forEachIndexed
            val payload = JsonObject().apply {
                addProperty("url", url); addProperty("lang", lang.take(5)); addProperty("sourceId", sourceId); addProperty("chapterId", chapterId); addProperty("page", i)
                add("headers", Gson().toJsonTree(headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
            }
            s.jobs.enqueue(JobQueue.Request(JobTypes.OCR, payload, JobPriority.NEXT, dedupKey = key(sourceId, chapterId, i), requestedBy = user.id))
            queued++
        }
        return queued
    }

    companion object {
        const val MAX_BYTES = 256L * 1024
        const val MAX_PAGES = 300
        private const val MAX_BOXES = 300

        fun sanitize(body: JsonObject, engine: String): JsonObject {
            val boxes = JsonArray()
            body.getAsJsonArray("boxes")?.take(MAX_BOXES)?.forEach { e ->
                val o = e as? JsonObject ?: return@forEach
                fun frac(n: String) = o.get(n)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
                val x = frac("x") ?: return@forEach
                val y = frac("y") ?: return@forEach
                val w = frac("w") ?: return@forEach
                val h = frac("h") ?: return@forEach
                val text = o.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.filter { it == '\n' || !it.isISOControl() }?.trim()?.take(2_000) ?: return@forEach
                if (text.isEmpty()) return@forEach
                val kind = o.get("kind")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { k -> BoxKind.entries.any { it.name == k } } ?: BoxKind.BUBBLE.name
                boxes.add(JsonObject().apply {
                    addProperty("x", x); addProperty("y", y); addProperty("w", w); addProperty("h", h); addProperty("text", text); addProperty("kind", kind)
                })
            }
            return JsonObject().apply {
                add("boxes", boxes)
                addProperty("engine", engine.take(30))
                addProperty("language", body.get("language")?.takeIf { it.isJsonPrimitive }?.asString?.take(5).orEmpty())
            }
        }
    }
}

/**
 * Language learning (off unless the user turns it on): saved words with their sentence and work, FSRS reviews, the
 * daily session, comprehension and an Anki export. Per user; nothing is shared unless they share a deck later.
 */
class Words(private val db: Database, private val events: Events, private val clock: () -> Long = System::currentTimeMillis) {
    private val gson = Gson()

    data class Word(val id: Long, val lang: String, val word: String, val meaning: String, val context: String, val source: String, val card: CardState, val level: String)

    suspend fun save(user: User, body: JsonObject): Long {
        require(!user.isGuest) { "Los invitados no guardan palabras" }
        val word = body.text("word", 80).lowercase().trim()
        require(word.isNotEmpty()) { "Falta la palabra" }
        val lang = body.text("lang", 5).lowercase().ifBlank { "en" }
        val count = db.tx { queryOne("SELECT COUNT(*) FROM words WHERE user_id = ?", user.id) { getInt(1) } } ?: 0
        require(count < MAX_WORDS) { "Ya tienes $MAX_WORDS palabras guardadas" }
        val id = db.tx {
            queryOne("SELECT id FROM words WHERE user_id = ? AND lang = ? AND word = ?", user.id, lang, word) { getLong(1) }?.let { return@tx -it }
            update(
                "INSERT INTO words(user_id, lang, word, meaning, context, source, card, created_at) VALUES(?,?,?,?,?,?,?,?)",
                user.id, lang, word, body.text("meaning", 500), body.text("context", 500), body.text("source", 200), gson.toJson(CardState(due = clock())), clock()
            )
            lastId()
        }
        if (id > 0) events.record(user.id, "word_saved", mapOf("lang" to lang))
        return kotlin.math.abs(id)
    }

    suspend fun list(user: User, dueOnly: Boolean, lang: String?): List<Word> = db.tx {
        query(
            "SELECT * FROM words WHERE user_id = ? AND (? IS NULL OR lang = ?) ORDER BY created_at DESC LIMIT 5000",
            user.id, lang, lang
        ) { row() }
    }.let { all -> if (dueOnly) all.filter { it.card.due <= clock() }.sortedBy { it.card.due }.take(DAILY_SESSION) else all }

    private fun java.sql.ResultSet.row(): Word {
        val card = runCatching { gson.fromJson(getString("card"), CardState::class.java) }.getOrDefault(CardState())
        return Word(getLong("id"), getString("lang"), getString("word"), getString("meaning"), getString("context"), getString("source"), card, Comprehension.level(card).name)
    }

    suspend fun review(user: User, id: Long, grade: Grade): Word {
        val word = db.tx { queryOne("SELECT * FROM words WHERE id = ? AND user_id = ?", id, user.id) { row() } } ?: throw NoSuchElementException("No existe")
        val next = Fsrs.review(word.card, grade, clock())
        db.tx { update("UPDATE words SET card = ? WHERE id = ? AND user_id = ?", gson.toJson(next), id, user.id) }
        events.record(user.id, "word_reviewed", mapOf("grade" to grade.name))
        return word.copy(card = next, level = Comprehension.level(next).name)
    }

    suspend fun delete(user: User, id: Long) = db.tx { update("DELETE FROM words WHERE id = ? AND user_id = ?", id, user.id) }

    suspend fun anki(user: User): String = AnkiExport.tsv(list(user, false, null).map { AnkiExport.Entry(it.word, it.meaning, it.context, it.source) })

    data class Deck(val id: Long, val owner: String, val mine: Boolean, val name: String, val lang: String, val size: Int, val createdAt: Long)
    private data class DeckWord(val word: String = "", val meaning: String = "", val context: String = "")

    /** Shares a snapshot of the user's [lang] words (up to [MAX_DECK]) for others on this server to copy. */
    suspend fun publishDeck(user: User, name: String, lang: String): Long {
        require(!user.isGuest) { "Los invitados no comparten mazos" }
        val clean = name.filter { !it.isISOControl() }.trim().take(80)
        require(clean.isNotEmpty()) { "Falta el nombre" }
        val words = list(user, false, lang.take(5).lowercase()).take(MAX_DECK).map { DeckWord(it.word, it.meaning, it.context) }
        require(words.isNotEmpty()) { "No tienes palabras guardadas en ese idioma" }
        val mine = db.tx { queryOne("SELECT COUNT(*) FROM decks WHERE user_id = ?", user.id) { getInt(1) } } ?: 0
        require(mine < MAX_DECKS_PER_USER) { "Ya compartes $MAX_DECKS_PER_USER mazos" }
        return db.tx {
            update(
                "INSERT INTO decks(user_id, name, lang, words, size, created_at) VALUES(?,?,?,?,?,?)",
                user.id, clean, lang.take(5).lowercase(), gson.toJson(words), words.size, clock()
            )
            lastId()
        }
    }

    suspend fun decks(viewer: User): List<Deck> = db.tx {
        query(
            """SELECT d.id, u.name, d.user_id, d.name, d.lang, d.size, d.created_at FROM decks d JOIN users u ON u.id = d.user_id
               WHERE u.hidden = 0 OR d.user_id = ? ORDER BY d.created_at DESC LIMIT 200""",
            viewer.id
        ) { Deck(getLong(1), getString(2), getLong(3) == viewer.id, getString(4), getString(5), getInt(6), getLong(7)) }
    }

    /** Copies a shared deck's words into the user's own (those already saved stay as they are); how many were new. */
    suspend fun importDeck(user: User, id: Long): Int {
        require(!user.isGuest) { "Los invitados no guardan palabras" }
        val (lang, json) = db.tx {
            queryOne(
                "SELECT d.lang, d.words FROM decks d JOIN users u ON u.id = d.user_id WHERE d.id = ? AND (u.hidden = 0 OR d.user_id = ?)",
                id, user.id
            ) { getString(1) to getString(2) }
        }
            ?: throw NoSuchElementException("Ese mazo ya no existe")
        val words = runCatching { gson.fromJson(json, Array<DeckWord>::class.java).toList() }.getOrDefault(emptyList())
        var added = 0
        for (w in words) {
            val body = JsonObject().apply { addProperty("word", w.word); addProperty("lang", lang); addProperty("meaning", w.meaning); addProperty("context", w.context) }
            val before = db.tx { queryOne("SELECT 1 FROM words WHERE user_id = ? AND lang = ? AND word = ?", user.id, lang, w.word.lowercase().trim()) { 1 } }
            if (before == null && runCatching { save(user, body) }.isSuccess) added++
        }
        return added
    }

    suspend fun deleteDeck(user: User, id: Long) = db.tx { update("DELETE FROM decks WHERE id = ? AND (user_id = ? OR ?)", id, user.id, user.isAdmin) }

    /** This week's reviews per person on the server (from Monday, UTC), most first; people who hide their profile are left out. */
    suspend fun ranking(now: Long = clock()): List<Map<String, Any>> {
        val monday = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        return db.tx {
            query(
                """SELECT u.name, COUNT(*) AS n FROM events e JOIN users u ON u.id = e.user_id
                   WHERE e.kind = 'word_reviewed' AND e.created_at >= ? AND u.hidden = 0 AND u.is_guest = 0
                   GROUP BY e.user_id ORDER BY n DESC LIMIT 20""",
                monday
            ) { mapOf("name" to getString(1), "reviews" to getInt(2)) }
        }
    }

    /** Known/learning per word, for marking text discreetly and the comprehension percentage (computed by the client). */
    suspend fun levels(user: User, lang: String): Map<String, String> = list(user, false, lang).associate { it.word to it.level }

    companion object {
        const val MAX_WORDS = 20_000
        const val DAILY_SESSION = 50
        const val MAX_DECK = 500
        const val MAX_DECKS_PER_USER = 20
    }
}

fun Route.learningRoutes(s: Services) {
    suspend fun io.ktor.server.application.ApplicationCall.on(flag: String): Boolean {
        if (s.features.isOn(flag)) return true
        respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        return false
    }
    fun io.ktor.server.application.ApplicationCall.page(): Int =
        request.queryParameters["p"]?.toIntOrNull()?.takeIf { it in 0..OcrStore.MAX_PAGES } ?: throw IllegalArgumentException("Página no válida")
    fun io.ktor.server.application.ApplicationCall.qp(name: String, max: Int) =
        request.queryParameters[name]?.take(max)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Falta $name")

    get("/ocr") {
        if (!call.on("ocr")) return@get
        val r = s.ocr.get(call.qp("s", 60), call.qp("c", 2_000), call.page()) ?: return@get call.respond(HttpStatusCode.NoContent)
        call.respond(r)
    }
    // The phone's own OCR (ML Kit) for a page, shared with everyone else who reads it.
    put("/ocr") {
        if (!call.on("ocr")) return@put
        require(!call.user.isGuest) { "Los invitados no suben OCR" }
        val sourceId = call.qp("s", 60)
        require(s.sources.get(sourceId) != null) { "Fuente desconocida" }
        s.ocr.put(sourceId, call.qp("c", 2_000), call.page(), call.jsonBody(200_000), "device")
        call.respond(mapOf("ok" to true))
    }
    post("/ocr/request") {
        if (!call.on("ocr")) return@post
        val b = call.jsonBody(4_096)
        call.respond(mapOf("queued" to s.ocr.request(call.user, b.text("sourceId", 60), b.text("chapterId", 2_000), b.text("lang", 5))))
    }

    wordRoutes(s)
    dictionaryRoutes(s)
}

private fun Route.wordRoutes(s: Services) {
    suspend fun io.ktor.server.application.ApplicationCall.on(flag: String): Boolean {
        if (s.features.isOn(flag)) return true
        respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        return false
    }
    fun io.ktor.server.application.ApplicationCall.qp(name: String, max: Int) =
        request.queryParameters[name]?.take(max)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Falta $name")

    get("/words") { if (call.on("learning")) call.respond(s.words.list(call.user, false, call.request.queryParameters["lang"]?.take(5))) }
    get("/words/due") { if (call.on("learning")) call.respond(s.words.list(call.user, true, call.request.queryParameters["lang"]?.take(5))) }
    get("/words/levels") { if (call.on("learning")) call.respond(s.words.levels(call.user, call.qp("lang", 5))) }
    post("/words") { if (call.on("learning")) call.respond(mapOf("id" to s.words.save(call.user, call.jsonBody(4_096)))) }
    post("/words/{id}/review") {
        if (!call.on("learning")) return@post
        val grade = call.jsonBody(256).text("grade", 10).uppercase().let { g -> Grade.entries.firstOrNull { it.name == g } } ?: throw IllegalArgumentException("Nota no válida")
        call.respond(s.words.review(call.user, call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("No válido"), grade))
    }
    delete("/words/{id}") {
        s.words.delete(call.user, call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("No válido"))
        call.respond(mapOf("ok" to true))
    }
    get("/words/anki") {
        if (!call.on("learning")) return@get
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"yomukai-palabras.txt\"")
        call.respondText(s.words.anki(call.user), ContentType.Text.Plain)
    }
    deckRoutes(s)
}

/** Shared decks and the weekly ranking of reviews. */
private fun Route.deckRoutes(s: Services) {
    suspend fun io.ktor.server.application.ApplicationCall.on(flag: String): Boolean {
        if (s.features.isOn(flag)) return true
        respond(HttpStatusCode.NotFound, mapOf("error" to "Desactivado por el administrador"))
        return false
    }
    fun io.ktor.server.application.ApplicationCall.id(): Long = parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("No válido")

    get("/words/decks") { if (call.on("learning")) call.respond(s.words.decks(call.user)) }
    post("/words/decks") {
        if (!call.on("learning")) return@post
        val body = call.jsonBody(1_024)
        call.respond(mapOf("id" to s.words.publishDeck(call.user, body.text("name", 80), body.text("lang", 5))))
    }
    post("/words/decks/{id}/import") {
        if (!call.on("learning")) return@post
        call.respond(mapOf("added" to s.words.importDeck(call.user, call.id())))
    }
    delete("/words/decks/{id}") {
        s.words.deleteDeck(call.user, call.id())
        call.respond(mapOf("ok" to true))
    }
    get("/words/ranking") { if (call.on("learning")) call.respond(s.words.ranking()) }
}

/** Subtitles and translations made by the home worker (whisper.cpp, LibreTranslate), cached and shared. */
class WorkerLanguage(private val s: Services) {
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** "ready" with the VTT, "pending" while the worker transcribes, or "unavailable" (no worker with whisper). */
    suspend fun subtitles(user: User, sourceId: String, episode: String, lang: String): Pair<String, String?> {
        val key = "subs:" + hash("$sourceId|$episode|$lang")
        s.jobs.results.get(key)?.let { (file, _) -> return "ready" to file.readText() }
        if (user.isGuest || !s.features.isOn("worker_jobs") || JobTypes.WHISPER !in s.workers.availability().engines) return "unavailable" to null
        val video = s.catalog.rawVideos(sourceId, episode).getOrNull()?.firstOrNull { it.isDirect && !ipBound(it.videoUrl) } ?: return "unavailable" to null
        val payload = JsonObject().apply {
            addProperty("url", video.videoUrl); addProperty("lang", lang.take(2))
            add("headers", Gson().toJsonTree(video.headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
        }
        s.jobs.enqueue(JobQueue.Request(JobTypes.WHISPER, payload, JobPriority.INTERACTIVE, dedupKey = key, requestedBy = user.id, ttlMs = 6 * 3_600_000L))
        return "pending" to null
    }

    /** Translations of [texts] (in order), waiting up to [waitMs] for the worker; null when it cannot. */

    /** Translations of [texts] in the same places (blank texts stay blank); [TranslationPending] while the worker works. */
    suspend fun translate(user: User, texts: List<String>, from: String, to: String, waitMs: Long = 20_000): List<String>? {
        require(!user.isGuest) { "Los invitados no pueden traducir" }
        if (!s.features.isOn("worker_jobs") || JobTypes.TRANSLATE !in s.workers.availability().engines) return null
        val all = texts.take(MAX_TEXTS).map { it.replace(Regex("\\s+"), " ").trim().take(2_000) }
        val clean = all.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return all
        // The worker translates the non-blank ones; each answer goes back to the place its text had.
        fun placed(done: List<String>): List<String> {
            var next = 0
            return all.map { if (it.isEmpty()) "" else done.getOrNull(next++).orEmpty() }
        }
        val key = "tr:" + hash("$from|$to|" + clean.joinToString("\u0000"))
        s.jobs.results.get(key)?.let { (file, _) -> return placed(parse(file.readText())) }
        val payload = JsonObject().apply {
            add("texts", JsonArray().also { a -> clean.forEach(a::add) }); addProperty("from", from.take(5)); addProperty("to", to.take(5))
        }
        val job = s.jobs.enqueue(JobQueue.Request(JobTypes.TRANSLATE, payload, JobPriority.INTERACTIVE, dedupKey = key, requestedBy = user.id, ttlMs = 10 * 60_000L))
        kotlinx.coroutines.withTimeoutOrNull(waitMs) {
            while (true) {
                val j = s.jobs.get(job.id) ?: break
                if (j.status != com.nelson.yomukai.server.JobStatus.QUEUED && j.status != com.nelson.yomukai.server.JobStatus.RUNNING) break
                kotlinx.coroutines.delay(300)
            }
        }
        return s.jobs.results.get(key)?.first?.readText()?.let { placed(parse(it)) } ?: throw TranslationPending()
    }

    private fun parse(json: String): List<String> =
        runCatching { JsonParser.parseString(json).asJsonObject.getAsJsonArray("translations").map { it.asString } }.getOrDefault(emptyList())
}

/** The translation was queued but is not ready yet (the worker is slow); it keeps going and is asked for again. */
class TranslationPending : RuntimeException("Traduciendo en el servidor de casa")

private const val MAX_TEXTS = 200

fun Route.workerLanguageRoutes(s: Services) {
    get("/subtitles") {
        val p = call.request.queryParameters
        val (status, vtt) = s.language.subtitles(call.user, p["s"].orEmpty().take(60), p["c"].orEmpty().take(2_000), p["lang"]?.take(2) ?: "auto")
        when (status) {
            "ready" -> call.respondText(vtt.orEmpty(), ContentType.parse("text/vtt"))
            "pending" -> call.respond(HttpStatusCode.Accepted, mapOf("pending" to true))
            else -> call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/translate") {
        val b = call.jsonBody(64_000)
        val texts = b.getAsJsonArray("texts")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }.orEmpty()
        var via = "worker"
        val result = try {
            s.language.translate(call.user, texts, b.text("from", 5).ifBlank { "auto" }, b.text("to", 5).ifBlank { "es" })
        } catch (_: TranslationPending) {
            // The home worker is on it (a slow computer): the reader asks again in a moment.
            return@post call.respond(HttpStatusCode.Accepted, mapOf("pending" to true))
        }
            // Worker off: short selections can still go to the free online translator (F6.3, switch online_translate).
            ?: (if (!call.user.isGuest && s.features.isOn("online_translate")) s.onlineTranslator.translate(call.user.id, texts, b.text("from", 5), b.text("to", 5).ifBlank { "es" }) else null)
                ?.also { via = "online" }
            ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "La traducción necesita el servidor de casa encendido"))
        call.respond(mapOf("translations" to result, "via" to via))
    }
}
