package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.discovery.MatchConfidence
import com.nelson.yomukai.data.discovery.RelatedTitle
import com.nelson.yomukai.domain.model.MediaType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Bringing a library from elsewhere: AniList (public lists by username), MyAnimeList (with MAL_CLIENT_ID) and Mihon /
 * Tachiyomi backups (.tachibk). Every title goes into a queue and is looked for in the user's sources a little at a
 * time in the background (the same check as everywhere: only a confirmed same work is added). Found ones land in the
 * library with their status, and what was already read or watched is marked; the rest stay listed as not found.
 */
class Imports(
    private val db: Database,
    private val catalog: Catalog,
    private val http: ServerHttp,
    private val malClientId: String,
    private val prefsOf: suspend (User) -> UserPrefs,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()

    /** One title to bring: its names, kind, list status (Yomukai's), and how far the user got (chapter/episode). */
    data class Entry(val title: String, val names: List<String>, val type: MediaType, val status: String, val progress: Float, val cover: String = "")

    suspend fun enqueue(userId: Long, origin: String, entries: List<Entry>): Int {
        val now = clock()
        val kept = entries.filter { it.title.isNotBlank() }.distinctBy { it.title.lowercase() + "|" + it.type.isReadableAsImages }.take(MAX_ENTRIES)
        db.tx {
            // One user's queue never grows past the cap, however many imports they start.
            val pending = queryOne("SELECT COUNT(*) FROM imports WHERE user_id = ? AND state = 'pending'", userId) { getInt(1) } ?: 0
            require(pending + kept.size <= MAX_ENTRIES) { "Ya tienes una importación en marcha; espera a que termine" }
            kept.forEach { e ->
                update(
                    "INSERT INTO imports(user_id, origin, data, state, created_at, updated_at) VALUES(?,?,?,'pending',?,?)",
                    userId, origin, gson.toJson(e), now, now
                )
            }
        }
        return kept.size
    }

    suspend fun summary(userId: Long): Map<String, Any> = db.tx {
        val counts = query("SELECT state, COUNT(*) FROM imports WHERE user_id = ? GROUP BY state", userId) { getString(1) to getInt(2) }.toMap()
        val notFound = query("SELECT data FROM imports WHERE user_id = ? AND state = 'notfound' ORDER BY id DESC LIMIT 200", userId) {
            runCatching { gson.fromJson(getString(1), Entry::class.java).title }.getOrDefault("?")
        }
        mapOf("pending" to (counts["pending"] ?: 0), "done" to (counts["done"] ?: 0), "notFound" to (counts["notfound"] ?: 0), "notFoundTitles" to notFound)
    }

    suspend fun clear(userId: Long) = db.tx { update("DELETE FROM imports WHERE user_id = ? AND state != 'pending'", userId) }

    @Volatile
    private var lastUser = 0L

    /**
     * Works through the next pending title, taking users in turns (one big import never holds up anyone else's).
     * Suspended accounts and guests are skipped. Called every few seconds in the background.
     */
    suspend fun resolveNext(): Boolean {
        val pick = """SELECT i.id, i.user_id, i.data FROM imports i JOIN users u ON u.id = i.user_id
            WHERE i.state = 'pending' AND u.suspended_at IS NULL AND u.is_guest = 0 AND i.user_id > ? ORDER BY i.user_id, i.id LIMIT 1"""
        val row = db.tx {
            queryOne(pick, lastUser) { Triple(getLong(1), getLong(2), getString(3)) } ?: queryOne(pick, 0L) { Triple(getLong(1), getLong(2), getString(3)) }
        } ?: return false
        val (id, userId, data) = row
        lastUser = userId
        val user = db.userById(userId) ?: return false
        val entry = runCatching { gson.fromJson(data, Entry::class.java) }.getOrNull()
        val found = entry != null && runCatching { resolve(user, entry) }.getOrElse {
            log.debug("Importar {}: {}", entry.title, it.message)
            false
        }
        val state = if (found) "done" else "notfound"
        db.tx { update("UPDATE imports SET state = ?, updated_at = ? WHERE id = ?", state, clock(), id) }
        return true
    }

    private suspend fun resolve(user: User, e: Entry): Boolean {
        val related = RelatedTitle(e.title, e.names, e.cover, e.type)
        val found = withTimeoutOrNull(60_000) { catalog.locate(user, prefsOf(user), related).getOrNull() }.orEmpty()
        val best = found.firstOrNull { it.confidence == MatchConfidence.SAME_WORK.name } ?: return false
        val item = best.item
        val now = clock()
        db.putLibrary(
            user.id,
            LibraryRow(item.sourceId, item.id, item.title, item.cover, item.type, item.status, item.author, item.genres.joinToString("|"), now, e.status, emptyList(), now)
        )
        if (e.progress > 0f) {
            val chapters = catalog.chapterList(item.sourceId, item.id).getOrNull().orEmpty()
            val read = chapters.filter { it.chapterNumber > 0f && it.chapterNumber <= e.progress + 0.001f }
            if (read.isNotEmpty()) {
                db.putProgress(user.id, read.map { ProgressRow(item.sourceId, it.id, item.id, true, 0, 0, 1.0, now) })
            }
        }
        return true
    }

    // ---------- Readers of other services' lists ----------

    /** A user's public AniList lists (anime and manga). */
    suspend fun fromAniList(name: String): List<Entry> {
        require(Regex("[A-Za-z0-9_]{2,40}").matches(name)) { "Nombre de AniList no válido" }
        val query = "query(\$u:String,\$t:MediaType){MediaListCollection(userName:\$u,type:\$t){lists{entries{status progress " +
            "media{format countryOfOrigin title{romaji english native} synonyms coverImage{large}}}}}}"
        return listOf("ANIME", "MANGA").flatMap { type ->
            val body = JsonObject().apply {
                addProperty("query", query)
                add("variables", JsonObject().apply { addProperty("u", name); addProperty("t", type) })
            }
            val answer = runCatching { http.postJson(ANILIST, body.toString()) }.getOrElse { e ->
                throw IllegalArgumentException(
                    if ("404" in e.message.orEmpty()) "Ese usuario de AniList no existe o su lista es privada" else "AniList no responde ahora; prueba en un rato"
                )
            }
            val root = JsonParser.parseString(answer).asJsonObject
            root.getAsJsonArray("errors")?.let { errs -> error(errs.firstOrNull()?.asJsonObject?.get("message")?.asString ?: "AniList no respondió") }
            root.getAsJsonObject("data")?.getAsJsonObject("MediaListCollection")?.getAsJsonArray("lists").orEmptyArray()
                .flatMap { it.asJsonObject.getAsJsonArray("entries").orEmptyArray() }
                .mapNotNull { aniListEntry(it.asJsonObject, type == "ANIME") }
        }
    }

    /** A user's MyAnimeList lists, through MAL's API (needs MAL_CLIENT_ID). */
    suspend fun fromMal(name: String): List<Entry> {
        require(malClientId.isNotBlank()) { "Para importar de MyAnimeList el servidor necesita MAL_CLIENT_ID" }
        require(Regex("[A-Za-z0-9_-]{2,40}").matches(name)) { "Nombre de MyAnimeList no válido" }
        return listOf("animelist", "mangalist").flatMap { list ->
            val anime = list == "animelist"
            val url = "https://api.myanimelist.net/v2/users/$name/$list?fields=list_status,alternative_titles,media_type&limit=1000&nsfw=true"
            val root = JsonParser.parseString(http.getString(url, mapOf("X-MAL-CLIENT-ID" to malClientId), cloudflareFallback = false)).asJsonObject
            root.getAsJsonArray("data").orEmptyArray().mapNotNull { row ->
                val o = row.asJsonObject
                val node = o.getAsJsonObject("node") ?: return@mapNotNull null
                val ls = o.getAsJsonObject("list_status")
                val alt = node.getAsJsonObject("alternative_titles")
                val names = listOfNotNull(alt?.get("en")?.asString, alt?.get("ja")?.asString) + alt?.getAsJsonArray("synonyms").orEmptyArray().map { it.asString }
                val type = when {
                    anime -> MediaType.ANIME
                    node.get("media_type")?.asString in setOf("novel", "light_novel") -> MediaType.NOVEL
                    node.get("media_type")?.asString == "manhwa" -> MediaType.MANHWA
                    node.get("media_type")?.asString == "manhua" -> MediaType.MANHUA
                    else -> MediaType.MANGA
                }
                val progress = (ls?.get(if (anime) "num_episodes_watched" else "num_chapters_read")?.asFloat) ?: 0f
                Entry(node.get("title")?.asString ?: return@mapNotNull null, names.filter { it.isNotBlank() }, type, malStatus(ls?.get("status")?.asString), progress)
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Imports::class.java)
        private const val ANILIST = "https://graphql.anilist.co"
        const val MAX_ENTRIES = 3_000
        const val MAX_BACKUP_BYTES = 30L * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 64 * 1024 * 1024

        private fun JsonArray?.orEmptyArray(): List<com.google.gson.JsonElement> = this?.toList().orEmpty()

        fun aniListStatus(s: String?) = when (s) {
            "CURRENT", "REPEATING" -> "READING"; "PLANNING" -> "PLAN_TO_READ"; "COMPLETED" -> "COMPLETED"
            "PAUSED" -> "ON_HOLD"; "DROPPED" -> "DROPPED"; else -> "NONE"
        }

        fun malStatus(s: String?) = when (s) {
            "watching", "reading" -> "READING"; "plan_to_watch", "plan_to_read" -> "PLAN_TO_READ"; "completed" -> "COMPLETED"
            "on_hold" -> "ON_HOLD"; "dropped" -> "DROPPED"; else -> "NONE"
        }

        fun aniListEntry(o: JsonObject, anime: Boolean): Entry? {
            val media = o.getAsJsonObject("media") ?: return null
            val t = media.getAsJsonObject("title")
            fun s(name: String) = t?.get(name)?.takeIf { it.isJsonPrimitive }?.asString
            val title = s("romaji") ?: s("english") ?: return null
            val names = listOfNotNull(s("english"), s("native")) + media.getAsJsonArray("synonyms")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            val type = when {
                anime -> MediaType.ANIME
                media.get("format")?.asString == "NOVEL" -> MediaType.NOVEL
                media.get("countryOfOrigin")?.asString == "KR" -> MediaType.MANHWA
                media.get("countryOfOrigin")?.asString in setOf("CN", "TW") -> MediaType.MANHUA
                else -> MediaType.MANGA
            }
            val cover = media.getAsJsonObject("coverImage")?.get("large")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            return Entry(title, names.distinct(), type, aniListStatus(o.get("status")?.asString), o.get("progress")?.takeIf { it.isJsonPrimitive }?.asFloat ?: 0f, cover)
        }

        /**
         * A Mihon/Tachiyomi backup (.tachibk / .proto.gz: gzip + protobuf). Only what is needed is read: each manga's
         * title (field 3) and its chapters (field 16: read = 4, number = 9). Favorites only (field 100 = favorite).
         */
        fun fromMihon(input: InputStream): List<Entry> {
            // A small file can unpack into a huge one: past the cap it is refused, never cut and parsed.
            val bytes = GZIPInputStream(input).use { it.readNBytes(MAX_UNPACKED_BYTES + 1) }
            require(bytes.size <= MAX_UNPACKED_BYTES) { "Copia demasiado grande" }
            val out = mutableListOf<Entry>()
            Proto(bytes).fields { field, value ->
                if (field == 1 && value is ByteArray) {
                    var title: String? = null
                    var favorite = true
                    var lastRead = 0f
                    Proto(value).fields { f, v ->
                        when {
                            f == 3 && v is ByteArray -> title = String(v, Charsets.UTF_8)
                            f == 100 && v is Long -> favorite = v != 0L
                            f == 16 && v is ByteArray -> {
                                var read = false
                                var number = 0f
                                Proto(v).fields { cf, cv ->
                                    if (cf == 4 && cv is Long) read = cv != 0L
                                    if (cf == 9 && cv is Int) number = java.lang.Float.intBitsToFloat(cv)
                                }
                                if (read && number > lastRead) lastRead = number
                            }
                        }
                    }
                    title?.takeIf { favorite && it.isNotBlank() }?.let { out += Entry(it, emptyList(), MediaType.MANGA, "READING", lastRead) }
                }
            }
            return out
        }
    }

    /** The smallest protobuf reader: varints (Long), 32-bit (Int), 64-bit (Long) and length-delimited (ByteArray). */
    private class Proto(private val b: ByteArray) {
        private var i = 0
        private fun varint(): Long {
            var shift = 0
            var result = 0L
            while (i < b.size) {
                val byte = b[i++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint" }
            }
            error("Copia de seguridad cortada")
        }

        fun fields(each: (Int, Any) -> Unit) {
            while (i < b.size) {
                val key = varint()
                val field = (key ushr 3).toInt()
                when ((key and 7).toInt()) {
                    0 -> each(field, varint())
                    1 -> { require(i + 8 <= b.size); var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; each(field, v) }
                    2 -> { val len = varint().toInt(); require(len in 0..(b.size - i)) { "Copia no válida" }; each(field, b.copyOfRange(i, i + len)); i += len }
                    5 -> { require(i + 4 <= b.size); var v = 0; for (k in 0 until 4) v = v or ((b[i + k].toInt() and 0xFF) shl (8 * k)); i += 4; each(field, v) }
                    else -> error("Copia no válida")
                }
            }
        }
    }
}

/** Users (not guests): import a library from AniList, MyAnimeList or a Mihon/Tachiyomi backup, and follow it. */
fun Route.importRoutes(s: Services) {
    post("/import/{origin}") {
        if (call.user.isGuest) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Los invitados no pueden importar"))
        val origin = call.parameters["origin"].orEmpty()
        val entries = when (origin) {
            "anilist" -> s.imports.fromAniList(call.jsonBody(512).text("user", 40).trim())
            "mal" -> s.imports.fromMal(call.jsonBody(512).text("user", 40).trim())
            "mihon" -> {
                val bytes = call.receiveChannel().toInputStream().use { it.readNBytes(Imports.MAX_BACKUP_BYTES.toInt() + 1) }
                require(bytes.size <= Imports.MAX_BACKUP_BYTES) { "Copia demasiado grande" }
                runCatching { Imports.fromMihon(ByteArrayInputStream(bytes)) }.getOrElse { throw IllegalArgumentException("No parece una copia de Mihon/Tachiyomi (.tachibk)") }
            }
            else -> throw IllegalArgumentException("Origen desconocido")
        }
        require(entries.isNotEmpty()) { "No hay nada que importar en esa lista" }
        call.respond(mapOf("queued" to s.imports.enqueue(call.user.id, origin, entries)))
    }
    get("/import/status") { call.respond(s.imports.summary(call.user.id)) }
    delete("/import/status") {
        s.imports.clear(call.user.id)
        call.respond(mapOf("ok" to true))
    }
}
