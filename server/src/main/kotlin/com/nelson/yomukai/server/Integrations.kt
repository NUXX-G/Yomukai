package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Jellyfin and Kavita/Komga on the home network (reached through Tailscale). The settings, API keys included, are kept
 * sealed with the server's secret and never go to any client: the admin panel only learns whether a key is set. The
 * home worker gets them inside its own jobs (it is the one that writes to the NAS).
 */
class Integrations(private val db: Database, secret: ByteArray) {
    private val box = SecretBox(secret, "integrations")
    private val gson = Gson()

    data class Config(
        val jellyfinUrl: String = "", val jellyfinKey: String = "",
        /** Library folders as the worker sees them. */
        val animePath: String = "", val seriesPath: String = "", val moviesPath: String = "",
        val comicsPath: String = "", val comicsServer: String = "", val comicsUrl: String = "", val comicsKey: String = ""
    ) {
        val jellyfinReady: Boolean get() = jellyfinUrl.isNotBlank() && jellyfinKey.isNotBlank()
    }

    suspend fun get(): Config = db.tx { queryOne("SELECT value FROM integrations WHERE name = 'main'") { getString(1) } }
        ?.let { box.open(it) }?.let { runCatching { gson.fromJson(it, Config::class.java) }.getOrNull() } ?: Config()

    /** What the admin panel may see: everything but the keys. */
    suspend fun publicView(): Map<String, Any?> = get().let { c ->
        mapOf(
            "jellyfinUrl" to c.jellyfinUrl, "jellyfinKeySet" to c.jellyfinKey.isNotBlank(), "animePath" to c.animePath, "seriesPath" to c.seriesPath,
            "moviesPath" to c.moviesPath, "comicsPath" to c.comicsPath, "comicsServer" to c.comicsServer, "comicsUrl" to c.comicsUrl,
            "comicsKeySet" to c.comicsKey.isNotBlank()
        )
    }

    /** Saves the panel's form; an empty key field keeps the key already stored. */
    suspend fun save(body: JsonObject) {
        val old = get()
        fun url(name: String, current: String): String {
            val v = body.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.trimEnd('/') ?: return current
            require(v.isEmpty() || Regex("^https?://[^\\s/]+(:\\d+)?(/[^\\s]*)?$").matches(v)) { "Dirección no válida: $name" }
            return v.take(300)
        }
        fun path(name: String, current: String): String {
            val v = body.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim() ?: return current
            require(v.isEmpty() || (v.startsWith("/") || Regex("^[A-Za-z]:[\\\\/].*").matches(v)) && !v.contains("..")) { "Ruta no válida: $name" }
            return v.take(400)
        }
        fun key(name: String, current: String) = body.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }?.take(200) ?: current

        // A stored key only ever goes to the address it was given for: changing the address forgets it unless a new key comes too.
        fun keyFor(keyField: String, oldUrl: String, newUrl: String, oldKey: String): String {
            val sentKey = body.get(keyField)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            return if (newUrl != oldUrl && sentKey.isEmpty()) "" else key(keyField, oldKey)
        }
        val jellyfinUrl = url("jellyfinUrl", old.jellyfinUrl)
        val comicsUrl = url("comicsUrl", old.comicsUrl)
        val server = body.get("comicsServer")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it in setOf("", "kavita", "komga") } ?: old.comicsServer
        val c = Config(
            jellyfinUrl, keyFor("jellyfinKey", old.jellyfinUrl, jellyfinUrl, old.jellyfinKey), path("animePath", old.animePath),
            path("seriesPath", old.seriesPath), path("moviesPath", old.moviesPath), path("comicsPath", old.comicsPath), server,
            comicsUrl, keyFor("comicsKey", old.comicsUrl, comicsUrl, old.comicsKey)
        )
        db.tx {
            update(
                "INSERT INTO integrations(name, value, updated_at) VALUES('main', ?, ?) ON CONFLICT(name) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                box.seal(gson.toJson(c)), System.currentTimeMillis()
            )
        }
    }

    /** The settings the worker needs inside a job (it is trusted with the keys: it runs at home). */
    suspend fun forWorker(): JsonObject = JsonParser.parseString(gson.toJson(get())).asJsonObject
}

/** The few Jellyfin API calls Yomukai uses (X-Emby-Token header). Never throws for "unreachable": returns null/false. */
class JellyfinClient(
    private val base: String,
    private val key: String,
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
) {
    private fun request(path: String) = Request.Builder().url(base.trimEnd('/') + path).header("X-Emby-Token", key).header("Accept", "application/json")

    /** Server name and version, or null when it cannot be reached or the key is wrong. */
    fun info(): Pair<String, String>? = runCatching {
        client.newCall(request("/System/Info").build()).execute().use { r ->
            if (!r.isSuccessful) return null
            val o = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            o.get("ServerName")?.asString.orEmpty() to o.get("Version")?.asString.orEmpty()
        }
    }.getOrNull()

    /** Tells Jellyfin to look only at [path] (a folder or file just written), instead of rescanning the library. */
    fun scanPath(path: String): Boolean = runCatching {
        val body = JsonObject().apply {
            add("Updates", JsonArray().apply { add(JsonObject().apply { addProperty("Path", path); addProperty("UpdateType", "Created") }) })
        }
        client.newCall(request("/Library/Media/Updated").post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)
}

fun Route.integrationAdminRoutes(s: Services) {
    get("/integrations") { call.respond(s.integrations.publicView()) }
    put("/integrations") {
        s.integrations.save(call.jsonBody(8_192))
        call.respond(s.integrations.publicView())
    }
    // Checked from the VPS itself (it must be on the tailnet to reach Jellyfin).
    post("/integrations/jellyfin/test") {
        val c = s.integrations.get()
        if (!c.jellyfinReady) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Falta la dirección o la clave de Jellyfin"))
        val info = JellyfinClient(c.jellyfinUrl, c.jellyfinKey).info()
            ?: return@post call.respond(mapOf("ok" to false, "error" to "No responde: ¿está el VPS en tu tailnet y la clave es correcta?"))
        call.respond(mapOf("ok" to true, "server" to info.first, "version" to info.second))
    }
}

/** The worker asks for a fresh direct video when it starts a download (addresses expire while jobs wait). */
fun Route.workerMediaRoutes(s: Services) {
    route("/worker/videos") {
        install(requireWorker(s.workers, s.workerLimiter))
        post {
            val b = call.jsonBody(8_192)
            val sourceId = b.text("sourceId", 60)
            require(s.sources.get(sourceId) != null) { "Fuente desconocida" }
            val video = s.catalog.rawVideos(sourceId, b.text("url", 2_000)).getOrNull()?.firstOrNull { it.isDirect }
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sin vídeo directo"))
            call.respond(mapOf("url" to video.videoUrl, "headers" to video.headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
        }
    }
}

/** Admin only: download episodes to Jellyfin, or export chapters to CBZ for Kavita/Komga (both done by the worker). */
private suspend fun io.ktor.server.application.ApplicationCall.selection(): Triple<String, String, List<String>> {
    val b = jsonBody(32_000)
    val ids = b.getAsJsonArray("chapterIds")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.take(2_000) }?.take(500).orEmpty()
    require(ids.isNotEmpty()) { "Elige al menos un episodio o capítulo" }
    return Triple(b.text("sourceId", 60), b.text("mediaId", 2_000), ids)
}

fun Route.mediaLibraryAdminRoutes(s: Services) {
    jellyfinDownloadRoute(s)
    cbzExportRoute(s)
}

private fun Route.jellyfinDownloadRoute(s: Services) {
    post("/jellyfin/download") {
        require(s.features.isOn("jellyfin")) { "Jellyfin está desactivado" }
        val (sourceId, mediaId, ids) = call.selection()
        val cfg = s.integrations.get()
        require(cfg.animePath.isNotBlank()) { "Configura antes la carpeta de anime en Integraciones" }
        val work = runCatching { s.works.resolve(sourceId, mediaId) }.getOrNull()
        val item = s.catalog.media(sourceId, mediaId).getOrThrow()
        val chapters = s.catalog.chapterList(sourceId, mediaId).getOrThrow().filter { it.id in ids || it.url in ids }
        val config = s.integrations.forWorker()
        chapters.forEach { ch ->
            val payload = JsonObject().apply {
                addProperty("sourceId", sourceId); addProperty("episodeUrl", ch.url); addProperty("title", work?.title ?: item.title)
                addProperty("number", ch.chapterNumber); addProperty("season", 1); addProperty("episodeTitle", ch.title)
                addProperty("synopsis", (work?.synopsis ?: item.description).take(2_000)); addProperty("cover", work?.cover ?: item.coverUrl)
                work?.anilistId?.let { addProperty("anilistId", it) }; work?.malId?.let { addProperty("malId", it) }
                add("genres", JsonArray().apply { (work?.genres ?: item.genres).take(10).forEach(::add) })
                add("config", config)
            }
            s.jobs.enqueue(JobQueue.Request(JobTypes.JELLYFIN_DOWNLOAD, payload, JobPriority.BACKGROUND, dedupKey = "jf:$sourceId:${ch.url}", ttlMs = 30L * 86_400_000, maxAttempts = 5))
        }
        call.respond(mapOf("queued" to chapters.size))
    }
}

private fun Route.cbzExportRoute(s: Services) {
    post("/cbz/export") {
        require(s.features.isOn("jellyfin")) { "Desactivado" }
        val (sourceId, mediaId, ids) = call.selection()
        val cfg = s.integrations.get()
        require(cfg.comicsPath.isNotBlank()) { "Configura antes la carpeta de cómics en Integraciones" }
        val item = s.catalog.media(sourceId, mediaId).getOrThrow()
        val work = runCatching { s.works.resolve(sourceId, mediaId) }.getOrNull()
        val chapters = s.catalog.chapterList(sourceId, mediaId).getOrThrow().filter { it.id in ids || it.url in ids }
        val config = s.integrations.forWorker()
        var queued = 0
        for (ch in chapters) {
            val (pages, headers) = s.catalog.rawPages(sourceId, ch.url).getOrNull() ?: continue
            val payload = JsonObject().apply {
                addProperty("series", work?.title ?: item.title); addProperty("number", ch.chapterNumber); addProperty("title", ch.title)
                addProperty("summary", item.description.take(2_000)); addProperty("type", item.type.name)
                add("authors", JsonArray().apply { item.author.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach(::add) })
                add("genres", JsonArray().apply { (work?.genres ?: item.genres).take(10).forEach(::add) })
                add("pages", JsonArray().apply { pages.forEach(::add) })
                add("headers", Gson().toJsonTree(headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
                work?.anilistId?.let { addProperty("anilistId", it) }; work?.malId?.let { addProperty("malId", it) }
                add("config", config)
            }
            s.jobs.enqueue(JobQueue.Request(JobTypes.CBZ_EXPORT, payload, JobPriority.BACKGROUND, dedupKey = "cbz:$sourceId:${ch.url}", ttlMs = 30L * 86_400_000))
            queued++
        }
        call.respond(mapOf("queued" to queued))
    }
}
