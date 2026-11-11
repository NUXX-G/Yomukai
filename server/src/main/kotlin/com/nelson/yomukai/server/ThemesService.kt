package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.image.ImageShrinker
import com.nelson.yomukai.data.theme.Contrast
import com.nelson.yomukai.data.theme.ThemeRules
import com.nelson.yomukai.data.theme.ThemeSpec
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
 * Themes: the built-in ones (shared/resources/design/themes.json), people's own (private or published to the group's
 * gallery) and which one each person uses (so the web and the app look the same). Only validated tokens are stored;
 * a background is an image uploaded here and re-drawn by ImageShrinker.
 */
class ThemesService(private val db: Database, private val events: Events, dataDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val backgrounds = File(dataDir, "themes").apply { mkdirs() }

    /** Default colours (tokens.json) for whatever a theme leaves out, and for the contrast check. */
    val base: Map<String, String> = JsonParser.parseString(resource("/design/tokens.json")).asJsonObject
        .getAsJsonObject("color").entrySet().associate { it.key to it.value.asString }

    val builtIn: List<Pair<String, ThemeSpec>> = JsonParser.parseString(resource("/design/themes.json")).asJsonObject
        .getAsJsonArray("themes").map { it.asJsonObject.get("id").asString to ThemeRules.fromJson(it.asJsonObject) }

    private fun resource(path: String) = javaClass.getResourceAsStream(path)!!.bufferedReader().readText()

    data class Stored(val id: Long, val owner: String, val spec: ThemeSpec, val published: Boolean, val uses: Int, val mine: Boolean)

    suspend fun gallery(viewer: User): Map<String, Any?> {
        val rows = db.tx {
            query(
                """SELECT t.*, u.name AS owner_name, (SELECT COUNT(*) FROM theme_uses x WHERE x.theme_id = t.id AND x.user_id <> t.owner_id) AS uses
                   FROM themes t JOIN users u ON u.id = t.owner_id WHERE t.published = 1 OR t.owner_id = ? ORDER BY uses DESC, t.created_at DESC LIMIT 200""",
                viewer.id
            ) { stored(viewer) }
        }
        return mapOf(
            "builtIn" to builtIn.map { (id, spec) -> mapOf("id" to id, "theme" to ThemeRules.toJson(spec)) },
            "gallery" to rows.map { mapOf("id" to it.id, "owner" to it.owner, "theme" to ThemeRules.toJson(it.spec), "published" to it.published, "uses" to it.uses, "mine" to it.mine) },
            "current" to current(viewer.id)?.let { ThemeRules.toJson(it) }
        )
    }

    private fun java.sql.ResultSet.stored(viewer: User) = Stored(
        getLong("id"), getString("owner_name"), ThemeRules.parse(getString("json")).getOrElse { ThemeSpec("Tema roto", emptyMap()) },
        getInt("published") == 1, getInt("uses"), getLong("owner_id") == viewer.id
    )

    /** Validates [json]; returns the theme and its contrast warnings (saving never fails for warnings, only errors). */
    fun check(json: JsonObject): Pair<ThemeSpec, List<Contrast.Issue>> {
        val spec = ThemeRules.fromJson(json)
        return spec to Contrast.issues(spec, base)
    }

    suspend fun save(user: User, id: Long?, json: JsonObject, published: Boolean): Long {
        require(!user.isGuest) { "Los invitados no pueden crear temas" }
        val (spec, _) = check(json)
        spec.background?.let { b -> require(ownsBackground(user.id, b)) { "Ese fondo no es tuyo" } }
        val text = ThemeRules.toJson(spec).toString()
        return db.tx {
            if (id != null) {
                require(update("UPDATE themes SET json = ?, published = ?, updated_at = ? WHERE id = ? AND owner_id = ?", text, published, clock(), id, user.id) == 1) {
                    "Solo puedes cambiar tus temas"
                }
                id
            } else {
                val count = queryOne("SELECT COUNT(*) FROM themes WHERE owner_id = ?", user.id) { getInt(1) } ?: 0
                require(count < MAX_THEMES) { "Ya tienes $MAX_THEMES temas" }
                update("INSERT INTO themes(owner_id, json, published, created_at, updated_at) VALUES(?,?,?,?,?)", user.id, text, published, clock(), clock())
                lastId()
            }
        }
    }

    suspend fun delete(user: User, id: Long) {
        db.tx { require(update("DELETE FROM themes WHERE id = ? AND owner_id = ?", id, user.id) == 1) { "Solo puedes borrar tus temas" } }
    }

    /** Uses a built-in theme (by id), a gallery theme (published or one's own) or none (null = the default). */
    suspend fun use(user: User, builtInId: String?, themeId: Long?) {
        val spec: ThemeSpec? = when {
            themeId != null -> {
                val (owner, json) = db.tx {
                    queryOne("SELECT owner_id, json FROM themes WHERE id = ? AND (published = 1 OR owner_id = ?)", themeId, user.id) { getLong(1) to getString(2) }
                } ?: throw NoSuchElementException("Ese tema no existe")
                if (owner != user.id) {
                    val first = db.tx { update("INSERT OR IGNORE INTO theme_uses(theme_id, user_id) VALUES(?,?)", themeId, user.id) } > 0
                    if (first) events.record(owner, "theme_used", mapOf("theme" to themeId, "by" to user.name))
                }
                ThemeRules.parse(json).getOrThrow()
            }
            builtInId != null -> builtIn.firstOrNull { it.first == builtInId }?.second ?: throw NoSuchElementException("Ese tema no existe")
            else -> null
        }
        db.tx { update("UPDATE users SET theme = ? WHERE id = ?", spec?.let { ThemeRules.toJson(it).toString() }, user.id) }
    }

    suspend fun current(userId: Long): ThemeSpec? =
        db.tx { queryOne("SELECT theme FROM users WHERE id = ?", userId) { getString(1) } }?.let { ThemeRules.parse(it).getOrNull() }

    // ---------- Backgrounds ----------

    suspend fun saveBackground(user: User, bytes: ByteArray): Long {
        require(!user.isGuest) { "Los invitados no pueden subir fondos" }
        val count = db.tx { queryOne("SELECT COUNT(*) FROM theme_backgrounds WHERE owner_id = ?", user.id) { getInt(1) } } ?: 0
        require(count < MAX_BACKGROUNDS) { "Ya tienes $MAX_BACKGROUNDS fondos" }
        val jpeg = requireNotNull(ImageShrinker.reencode(bytes, 1920, 16.0 / 9)) { "No es una imagen válida" }
        val id = db.tx {
            update("INSERT INTO theme_backgrounds(owner_id, created_at) VALUES(?,?)", user.id, clock())
            lastId()
        }
        File(backgrounds, "$id.jpg").writeBytes(jpeg)
        return id
    }

    private suspend fun ownsBackground(userId: Long, id: Long) =
        db.tx { queryOne("SELECT 1 FROM theme_backgrounds WHERE id = ? AND owner_id = ?", id, userId) { 1 } } != null

    fun backgroundFile(id: Long): File? = File(backgrounds, "$id.jpg").takeIf { it.isFile }

    /**
     * A background is private until it is part of a published theme: its owner sees it, and so does whoever has it
     * in their current theme. (Theme JSON is written by [ThemeRules.toJson], where "background" is always followed by
     * another field, so the pattern cannot match a longer id.)
     */
    suspend fun canSeeBackground(viewer: User, id: Long): Boolean {
        val pattern = "%\"background\":$id,%"
        return db.tx {
            queryOne("SELECT 1 FROM theme_backgrounds WHERE id = ? AND owner_id = ?", id, viewer.id) { 1 } != null ||
                queryOne("SELECT 1 FROM themes WHERE published = 1 AND json LIKE ?", pattern) { 1 } != null ||
                queryOne("SELECT 1 FROM users WHERE id = ? AND theme LIKE ?", viewer.id, pattern) { 1 } != null
        }
    }

    companion object {
        const val MAX_THEMES = 30
        const val MAX_BACKGROUNDS = 10
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.themesOn(s: Services): Boolean {
    if (s.features.isOn("themes")) return true
    respond(HttpStatusCode.NotFound, mapOf("error" to "Los temas están desactivados"))
    return false
}

private fun io.ktor.server.application.ApplicationCall.themeId(): Long =
    parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Tema no válido")

fun Route.themeRoutes(s: Services) {
    get("/themes") { if (call.themesOn(s)) call.respond(s.themes.gallery(call.user)) }
    get("/themes/current") { call.respond(mapOf("theme" to s.themes.current(call.user.id)?.let { ThemeRules.toJson(it) })) }
    // Checks a theme without saving it (the editor's contrast warnings).
    post("/themes/check") {
        val (spec, issues) = s.themes.check(call.jsonBody(8_192))
        call.respond(mapOf("theme" to ThemeRules.toJson(spec), "issues" to issues))
    }
    post("/themes") {
        if (!call.themesOn(s)) return@post
        val b = call.jsonBody(8_192)
        call.respond(mapOf("id" to s.themes.save(call.user, null, b.getAsJsonObject("theme") ?: throw IllegalArgumentException("Falta el tema"), b.get("published")?.asBoolean == true)))
    }
    put("/themes/{id}") {
        if (!call.themesOn(s)) return@put
        val b = call.jsonBody(8_192)
        s.themes.save(call.user, call.themeId(), b.getAsJsonObject("theme") ?: throw IllegalArgumentException("Falta el tema"), b.get("published")?.asBoolean == true)
        call.respond(mapOf("ok" to true))
    }
    delete("/themes/{id}") {
        s.themes.delete(call.user, call.themeId())
        call.respond(mapOf("ok" to true))
    }
    put("/themes/use") {
        if (!call.themesOn(s)) return@put
        val b = call.jsonBody(1_024)
        s.themes.use(call.user, b.get("builtIn")?.takeIf { it.isJsonPrimitive }?.asString, b.get("id")?.takeIf { it.isJsonPrimitive }?.asLong)
        call.respond(mapOf("theme" to s.themes.current(call.user.id)?.let { ThemeRules.toJson(it) }))
    }
    post("/themes/backgrounds") {
        if (!call.themesOn(s)) return@post
        val length = call.request.contentLength()
        require(length == null || length <= ThemesService.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 8 MB)" }
        val bytes = call.receiveChannel().readRemaining(ThemesService.MAX_IMAGE_BYTES + 1L).readByteArray()
        require(bytes.size <= ThemesService.MAX_IMAGE_BYTES) { "La imagen pesa demasiado (máximo 8 MB)" }
        call.respond(mapOf("id" to s.themes.saveBackground(call.user, bytes)))
    }
    get("/themes/backgrounds/{id}") {
        if (!call.themesOn(s)) return@get
        val id = call.themeId()
        val file = s.themes.backgroundFile(id)?.takeIf { s.themes.canSeeBackground(call.user, id) }
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "No existe"))
        call.response.header(HttpHeaders.CacheControl, "private, max-age=2592000, immutable")
        call.respondBytes(file.readBytes(), ContentType.Image.JPEG)
    }
}
