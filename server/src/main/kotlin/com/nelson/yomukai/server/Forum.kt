package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.nelson.yomukai.data.social.ChatGuard
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

/**
 * The forum: one thread per work (found from its title page, "Foro" tab) and general categories. Posts are stored as
 * the text people wrote (limited); the web shows them with a tiny markdown reader that builds elements, never HTML,
 * so nothing written here can run in someone's browser. Spoilers are marked "up to episode/chapter N" and hidden from
 * whoever has not got there (the reader says where it is; hiding is a courtesy, not a secret). Admins moderate from
 * /api/admin/forum (every action lands in the audit log).
 */
class Forum(
    private val db: Database,
    private val events: Events,
    private val notifications: Notifications,
    dataDir: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val images = File(dataDir, "forum").apply { mkdirs() }

    data class Thread(
        val id: Long, val category: String, val workKey: String?, val title: String, val author: String, val createdAt: Long,
        val lastPostAt: Long, val posts: Int, val pinned: Boolean, val locked: Boolean
    )

    data class Post(
        val id: Long, val author: String, val authorAvatar: String?, val body: String, val spoilerUntil: Double?, val hidden: Boolean,
        val quoteId: Long?, val createdAt: Long, val editedAt: Long?, val reactions: Map<String, Int>, val mine: List<String>, val own: Boolean
    )

    private fun java.sql.ResultSet.thread() = Thread(
        getLong("id"), getString("category"), getString("work_key"), getString("title"), getString("author_name"), getLong("created_at"),
        getLong("last_post_at"), getInt("posts"), getInt("pinned") == 1, getInt("locked") == 1
    )

    private val threadSelect = """SELECT t.*, u.name AS author_name, (SELECT COUNT(*) FROM forum_posts p WHERE p.thread_id = t.id AND p.deleted = 0) AS posts
        FROM forum_threads t JOIN users u ON u.id = t.author_id"""

    suspend fun threads(category: String, page: Int): List<Thread> {
        require(category in CATEGORIES.keys) { "Categoría desconocida" }
        return db.tx {
            query(
                "$threadSelect WHERE t.category = ? AND t.deleted = 0 ORDER BY t.pinned DESC, t.last_post_at DESC LIMIT 30 OFFSET ?",
                category, (page.coerceIn(0, 1_000)) * 30
            ) { thread() }
        }
    }

    suspend fun thread(id: Long): Thread? = db.tx { queryOne("$threadSelect WHERE t.id = ? AND t.deleted = 0", id) { thread() } }

    /** The work's own thread, created the first time someone writes in it. */
    suspend fun workThread(workKey: String): Thread? = db.tx { queryOne("$threadSelect WHERE t.work_key = ? AND t.deleted = 0", workKey) { thread() } }

    /** Posts of a thread for [viewer]; spoilers past [upTo] (the viewer's furthest episode/chapter) come without text. */
    suspend fun posts(threadId: Long, viewer: User, upTo: Double?, page: Int): List<Post> {
        val rows = db.tx {
            query(
                """SELECT p.*, u.name AS author_name, u.avatar_at FROM forum_posts p JOIN users u ON u.id = p.author_id
                   WHERE p.thread_id = ? AND p.deleted = 0 ORDER BY p.id LIMIT 50 OFFSET ?""",
                threadId, page.coerceIn(0, 10_000) * 50
            ) {
                val spoiler = getDouble("spoiler_until").takeUnless { wasNull() }
                val hidden = spoiler != null && getLong("author_id") != viewer.id && (upTo ?: 0.0) < spoiler
                val name = getString("author_name")
                Post(
                    getLong("id"), name, getLong("avatar_at").takeIf { it > 0 }?.let { "/api/users/${java.net.URLEncoder.encode(name, Charsets.UTF_8)}/avatar?v=$it" },
                    if (hidden) "" else getString("body"), spoiler, hidden, getLong("quote_id").takeUnless { wasNull() },
                    getLong("created_at"), getLong("edited_at").takeUnless { wasNull() }, emptyMap(), emptyList(), getLong("author_id") == viewer.id
                )
            }
        }
        if (rows.isEmpty()) return rows
        val ids = rows.map { it.id }
        val marks = ids.joinToString(",") { "?" }
        val reactions = db.tx {
            query("SELECT post_id, emoji, user_id FROM forum_reactions WHERE post_id IN ($marks)", *ids.toTypedArray()) { Triple(getLong(1), getString(2), getLong(3)) }
        }
        return rows.map { p ->
            val mine = reactions.filter { it.first == p.id }
            p.copy(reactions = mine.groupingBy { it.second }.eachCount(), mine = mine.filter { it.third == viewer.id }.map { it.second })
        }
    }

    suspend fun newThread(user: User, category: String, title: String, body: String, workKey: String?, spoilerUntil: Double?): Long {
        checkCanWrite(user)
        require(category in CATEGORIES.keys) { "Categoría desconocida" }
        require((category == "work") == (workKey != null)) { "Los hilos de obra van ligados a su obra" }
        val now = clock()
        val cleanTitle = ChatGuard.clean(title).replace('\n', ' ').take(MAX_TITLE)
        require(cleanTitle.length >= 3) { "Pon un título" }
        val id = db.tx {
            if (workKey != null) queryOne("SELECT id FROM forum_threads WHERE work_key = ? AND deleted = 0", workKey) { getLong(1) }?.let { return@tx it }
            update(
                "INSERT INTO forum_threads(category, work_key, title, author_id, created_at, last_post_at) VALUES(?,?,?,?,?,?)",
                category, workKey, cleanTitle, user.id, now, now
            )
            lastId()
        }
        reply(user, id, body, spoilerUntil, null, newThread = true)
        if (category != "work") notifications.discord("Nuevo hilo en ${CATEGORIES[category]}: $cleanTitle (de ${user.name})")
        return id
    }

    suspend fun reply(user: User, threadId: Long, body: String, spoilerUntil: Double?, quoteId: Long?, newThread: Boolean = false): Long {
        checkCanWrite(user)
        val t = thread(threadId) ?: throw NoSuchElementException("Ese hilo no existe")
        require(!t.locked || user.isAdmin) { "El hilo está cerrado" }
        val text = cleanBody(body)
        require(text.isNotBlank()) { "Escribe algo" }
        require(spoilerUntil == null || spoilerUntil in 0.0..100_000.0) { "Número de spoiler no válido" }
        val recent = db.tx { queryOne("SELECT COUNT(*) FROM forum_posts WHERE author_id = ? AND created_at > ?", user.id, clock() - 60_000) { getInt(1) } } ?: 0
        require(recent < MAX_POSTS_PER_MINUTE) { "Más despacio: espera un minuto" }
        val now = clock()
        val quote = quoteId?.let { q -> db.tx { queryOne("SELECT author_id FROM forum_posts WHERE id = ? AND thread_id = ? AND deleted = 0", q, threadId) { getLong(1) } } }
        val id = db.tx {
            update(
                "INSERT INTO forum_posts(thread_id, author_id, body, spoiler_until, quote_id, created_at) VALUES(?,?,?,?,?,?)",
                threadId, user.id, text, spoilerUntil, quote?.let { quoteId }, now
            )
            val id = lastId()
            update("UPDATE forum_threads SET last_post_at = ? WHERE id = ?", now, threadId)
            id
        }
        if (!newThread && now - t.lastPostAt > NECRO_MS) events.record(user.id, "thread_revived", mapOf("thread" to threadId))
        if (spoilerUntil != null) events.record(user.id, "forum_spoiler", mapOf("post" to id))
        val link = "/foro/$threadId#p$id"
        // Who hears about it: whoever is quoted, whoever is mentioned, and others who wrote in the thread.
        val told = HashSet<Long>().apply { add(user.id) }
        quote?.takeIf { told.add(it) }?.let { notifications.send(it, "reply", "${user.name} te ha citado en «${t.title}»", link) }
        for (name in ChatGuard.mentions(text).take(MAX_MENTIONS)) {
            val target = db.tx { queryOne("SELECT id FROM users WHERE name = ? AND is_guest = 0", name) { getLong(1) } } ?: continue
            if (told.add(target)) notifications.send(target, "mention", "${user.name} te ha mencionado en «${t.title}»", link)
        }
        if (!newThread) {
            val writers = db.tx { query("SELECT DISTINCT author_id FROM forum_posts WHERE thread_id = ? AND deleted = 0", threadId) { getLong(1) } }
            writers.filter { told.add(it) }.forEach { notifications.send(it, "reply", "${user.name} ha respondido en «${t.title}»", link) }
        }
        return id
    }

    suspend fun edit(user: User, postId: Long, body: String) {
        val (owner, threadId) = db.tx {
            queryOne("SELECT author_id, thread_id FROM forum_posts WHERE id = ? AND deleted = 0", postId) { getLong(1) to getLong(2) }
        } ?: throw NoSuchElementException("No existe")
        require(owner == user.id || user.isAdmin) { "Solo puedes editar lo tuyo" }
        if (!user.isAdmin) {
            // The same rules as writing: not silenced, and not in a closed or deleted thread.
            checkCanWrite(user)
            val t = thread(threadId) ?: throw NoSuchElementException("Ese hilo no existe")
            require(!t.locked) { "El hilo está cerrado" }
        }
        val text = cleanBody(body)
        require(text.isNotBlank()) { "Escribe algo" }
        db.tx { update("UPDATE forum_posts SET body = ?, edited_at = ? WHERE id = ?", text, clock(), postId) }
    }

    suspend fun deletePost(user: User, postId: Long) {
        val owner = db.tx { queryOne("SELECT author_id FROM forum_posts WHERE id = ? AND deleted = 0", postId) { getLong(1) } } ?: throw NoSuchElementException("No existe")
        require(owner == user.id || user.isAdmin) { "Solo puedes borrar lo tuyo" }
        db.tx { update("UPDATE forum_posts SET deleted = 1 WHERE id = ?", postId) }
    }

    suspend fun react(user: User, postId: Long, emoji: String) {
        checkCanWrite(user)
        require(emoji in ChatGuard.REACTIONS) { "Reacción no válida" }
        db.tx {
            requireNotNull(queryOne("SELECT 1 FROM forum_posts WHERE id = ? AND deleted = 0", postId) { 1 }) { "No existe" }
            if (update("DELETE FROM forum_reactions WHERE post_id = ? AND user_id = ? AND emoji = ?", postId, user.id, emoji) == 0) {
                update("INSERT INTO forum_reactions(post_id, user_id, emoji) VALUES(?,?,?)", postId, user.id, emoji)
            }
        }
    }

    // ---------- Moderation (admin) ----------

    suspend fun moderateThread(id: Long, pinned: Boolean?, locked: Boolean?, deleted: Boolean?, title: String?) {
        db.tx {
            pinned?.let { update("UPDATE forum_threads SET pinned = ? WHERE id = ?", it, id) }
            locked?.let { update("UPDATE forum_threads SET locked = ? WHERE id = ?", it, id) }
            deleted?.let { update("UPDATE forum_threads SET deleted = ? WHERE id = ?", it, id) }
            title?.let { t -> ChatGuard.clean(t).replace('\n', ' ').take(MAX_TITLE).takeIf { it.length >= 3 }?.let { update("UPDATE forum_threads SET title = ? WHERE id = ?", it, id) } }
        }
    }

    /** Silences [name] (forum and room chat) for [minutes]; 0 lifts it. */
    suspend fun mute(name: String, minutes: Int, reason: String) {
        require(minutes in 0..(60 * 24 * 90)) { "Entre 0 minutos y 90 días" }
        val id = db.tx { queryOne("SELECT id FROM users WHERE name = ?", name.take(60)) { getLong(1) } } ?: throw NoSuchElementException("No existe")
        db.tx { update("UPDATE users SET muted_until = ?, muted_reason = ? WHERE id = ?", if (minutes == 0) 0L else clock() + minutes * 60_000L, reason.take(200), id) }
    }

    suspend fun mutedUntil(userId: Long): Long = db.tx { queryOne("SELECT muted_until FROM users WHERE id = ?", userId) { getLong(1) } } ?: 0

    private suspend fun checkCanWrite(user: User) {
        require(!user.isGuest) { "Los invitados no pueden escribir en el foro" }
        val until = mutedUntil(user.id)
        val local = java.time.Instant.ofEpochMilli(until).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        require(until <= clock()) { "Estás silenciado hasta el ${local.toString().replace('T', ' ').take(16)}" }
    }

    // ---------- Images ----------

    /** An image for a post: re-drawn as JPEG (1200 px at most) and served from here, never hot-linked. */
    suspend fun saveImage(user: User, bytes: ByteArray): Long {
        checkCanWrite(user)
        val today = db.tx { queryOne("SELECT COUNT(*) FROM forum_images WHERE user_id = ? AND created_at > ?", user.id, clock() - 86_400_000L) { getInt(1) } } ?: 0
        require(today < MAX_IMAGES_PER_DAY) { "Ya has subido muchas imágenes hoy" }
        val jpeg = requireNotNull(ImageShrinker.reencode(bytes, 1200, aspectOf(bytes))) { "No es una imagen válida" }
        val id = db.tx {
            update("INSERT INTO forum_images(user_id, created_at) VALUES(?,?)", user.id, clock())
            lastId()
        }
        File(images, "$id.jpg").writeBytes(jpeg)
        return id
    }

    fun imageFile(id: Long): File? = File(images, "$id.jpg").takeIf { it.isFile }

    /** Keeps the picture's own shape (no crop): width/height read from its header. */
    private fun aspectOf(bytes: ByteArray): Double = runCatching {
        javax.imageio.ImageIO.createImageInputStream(bytes.inputStream()).use { input ->
            val reader = javax.imageio.ImageIO.getImageReaders(input).next()
            try {
                reader.input = input
                reader.getWidth(0).toDouble() / reader.getHeight(0)
            } finally {
                reader.dispose()
            }
        }
    }.getOrDefault(1.0).coerceIn(0.05, 20.0)

    private fun cleanBody(body: String): String =
        body.filter { it == '\n' || !it.isISOControl() }.trim().replace(Regex("\n{4,}"), "\n\n\n").take(MAX_BODY)

    companion object {
        val CATEGORIES = linkedMapOf("work" to "Obras", "recomendaciones" to "Recomendaciones", "offtopic" to "Off-topic", "quejas" to "Quejas al admin")
        const val MAX_TITLE = 120
        const val MAX_BODY = 5_000
        const val MAX_POSTS_PER_MINUTE = 6
        const val MAX_MENTIONS = 5
        const val MAX_IMAGES_PER_DAY = 30
        const val MAX_IMAGE_BYTES = 6 * 1024 * 1024
        const val NECRO_MS = 90L * 86_400_000L
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.forumOn(s: Services): Boolean {
    if (s.features.isOn("forum")) return true
    respond(HttpStatusCode.NotFound, mapOf("error" to "El foro está desactivado"))
    return false
}

private fun io.ktor.server.application.ApplicationCall.idParam(): Long =
    parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Identificador no válido")

private fun JsonObject.num(name: String): Double? = get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

fun Route.forumRoutes(s: Services) {
    get("/forum/categories") { if (call.forumOn(s)) call.respond(Forum.CATEGORIES) }
    get("/forum") {
        if (!call.forumOn(s)) return@get
        val p = call.request.queryParameters
        call.respond(s.forum.threads(p["c"].orEmpty(), p["page"]?.toIntOrNull() ?: 0))
    }
    // The thread of a work (null until someone writes in it).
    get("/forum/work") {
        if (!call.forumOn(s)) return@get
        val p = call.request.queryParameters
        val key = s.works.keyOf(p["s"].orEmpty().take(60), p["id"].orEmpty().take(2_000)) ?: return@get call.respond(mapOf("thread" to null))
        call.respond(mapOf("thread" to s.forum.workThread(key), "workKey" to key))
    }
    get("/forum/{id}") {
        if (!call.forumOn(s)) return@get
        val id = call.idParam()
        val t = s.forum.thread(id) ?: throw NoSuchElementException("Ese hilo no existe")
        val p = call.request.queryParameters
        call.respond(mapOf("thread" to t, "posts" to s.forum.posts(id, call.user, p["upTo"]?.toDoubleOrNull(), p["page"]?.toIntOrNull() ?: 0)))
    }
    forumWriteRoutes(s)
}

private fun Route.forumWriteRoutes(s: Services) {
    post("/forum") {
        if (!call.forumOn(s)) return@post
        val b = call.jsonBody(16_384)
        val category = b.text("category", 30)
        // A work's thread is asked by source page; the server finds the work (it must already be known).
        val workKey = if (category == "work") s.works.keyOf(b.text("sourceId", 60), b.text("mediaId", 2_000)) ?: throw IllegalArgumentException("Abre antes la ficha de la obra") else null
        call.respond(mapOf("id" to s.forum.newThread(call.user, category, b.text("title", 300), b.text("body", Forum.MAX_BODY + 100), workKey, b.num("spoilerUntil"))))
    }
    post("/forum/{id}/posts") {
        if (!call.forumOn(s)) return@post
        val id = call.idParam()
        val b = call.jsonBody(16_384)
        call.respond(mapOf("id" to s.forum.reply(call.user, id, b.text("body", Forum.MAX_BODY + 100), b.num("spoilerUntil"), b.num("quoteId")?.toLong())))
    }
    put("/forum/posts/{id}") {
        if (!call.forumOn(s)) return@put
        s.forum.edit(call.user, call.idParam(), call.jsonBody(16_384).text("body", Forum.MAX_BODY + 100))
        call.respond(mapOf("ok" to true))
    }
    delete("/forum/posts/{id}") {
        if (!call.forumOn(s)) return@delete
        s.forum.deletePost(call.user, call.idParam())
        call.respond(mapOf("ok" to true))
    }
    post("/forum/posts/{id}/react") {
        if (!call.forumOn(s)) return@post
        s.forum.react(call.user, call.idParam(), call.jsonBody(256).text("emoji", 16))
        call.respond(mapOf("ok" to true))
    }
    post("/forum/images") {
        if (!call.forumOn(s)) return@post
        val length = call.request.contentLength()
        require(length == null || length <= Forum.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 6 MB)" }
        val bytes = call.receiveChannel().readRemaining(Forum.MAX_IMAGE_BYTES + 1L).readByteArray()
        require(bytes.size <= Forum.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 6 MB)" }
        call.respond(mapOf("id" to s.forum.saveImage(call.user, bytes)))
    }
    get("/forum/images/{id}") {
        val file = call.parameters["id"]?.toLongOrNull()?.let { s.forum.imageFile(it) } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe"))
        call.response.header(HttpHeaders.CacheControl, "private, max-age=2592000, immutable")
        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
    }
}

/** Moderation, under /api/admin (admins only; every call is in the audit log). */
fun Route.forumAdminRoutes(s: Services) {
    put("/forum/threads/{id}") {
        val b = call.jsonBody(1_024)
        fun flag(n: String) = b.get(n)?.takeIf { it.isJsonPrimitive }?.asBoolean
        s.forum.moderateThread(call.idParam(), flag("pinned"), flag("locked"), flag("deleted"), b.get("title")?.asString)
        call.respond(mapOf("ok" to true))
    }
    post("/mute") {
        val b = call.jsonBody(1_024)
        s.forum.mute(b.text("name", 60), b.get("minutes")?.asInt ?: 0, b.text("reason", 200))
        call.respond(mapOf("ok" to true))
    }
}
