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
        runCatching { s.jellyfin.refresh() }
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
            val direct = s.catalog.rawVideos(sourceId, b.text("url", 2_000)).getOrNull().orEmpty().filter { it.isDirect }
            val preferred = b.get("preferredServer")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().take(40)
            val video = direct.getOrNull(preferServer(direct.map { it.serverName }, preferred))
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Sin vídeo directo"))
            call.respond(mapOf("url" to video.videoUrl, "headers" to video.headers.filterKeys { it in setOf("Referer", "User-Agent", "Origin") }))
        }
    }
}
