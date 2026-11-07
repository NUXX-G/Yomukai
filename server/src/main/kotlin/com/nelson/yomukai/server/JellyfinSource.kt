package com.nelson.yomukai.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The home Jellyfin as one more source of the unified work model: its series and films show with the same design,
 * a work found there gets the "en tu NAS" badge and its episodes open from there first (better quality, no dead
 * servers). It only exists on the server, only while Jellyfin is set up and answering: if it goes away, so does the
 * source, without errors anywhere.
 *
 * Nothing about Jellyfin reaches the clients: covers and video come through /api/jellyfin/… on the VPS, which adds the
 * API key in a header. Video is direct play by default; lower qualities ask Jellyfin to transcode only then (the home
 * upload is limited).
 */
class JellyfinSource(private val api: JellyfinApi) : AnimeSource {
    override val id = ID
    override val name = "Jellyfin (tu NAS)"
    override val baseUrl = api.base
    override val language = "MULTI"
    override val supportedTypes = setOf(MediaType.ANIME)
    override val supportsLatestPaging = true
    override val imageHeaders: Map<String, String> = emptyMap()

    private val fields = "Overview,Genres,ProviderIds,ProductionYear,Status,OriginalTitle"

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        val start = (page - 1).coerceAtLeast(0) * PAGE
        items("/Items?Recursive=true&IncludeItemTypes=Series,Movie&SortBy=DateCreated&SortOrder=Descending&StartIndex=$start&Limit=$PAGE&Fields=$fields")
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        val q = java.net.URLEncoder.encode(query.take(100), Charsets.UTF_8)
        items("/Items?Recursive=true&IncludeItemTypes=Series,Movie&SearchTerm=$q&Limit=$PAGE&Fields=$fields")
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        items("/Items?Ids=${safeId(media.id)}&Fields=$fields").firstOrNull() ?: error("No está en tu Jellyfin")
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        val id = safeId(mediaId)
        val item = api.json("/Items?Ids=$id&Fields=$fields").getAsJsonArray("Items")?.firstOrNull()?.asJsonObject ?: error("No está en tu Jellyfin")
        if (item.str("Type") == "Movie") {
            return@runCatching listOf(Chapter(id, id, item.str("Name").ifBlank { "Película" }, 1f, "$URL_PREFIX$id", item.str("PremiereDate").take(10)))
        }
        val eps = api.json("/Shows/$id/Episodes?Fields=Overview").getAsJsonArray("Items")?.map { it.asJsonObject }.orEmpty()
        episodes(id, eps)
    }

    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        val id = safeId(episodeUrl.removePrefix(URL_PREFIX))
        QUALITIES.map { (label, q) -> VideoSource(label, "/api/jellyfin/stream/$id?q=$q", "Jellyfin", isDirect = true) }
    }

    private suspend fun items(path: String): List<MediaItem> =
        api.json(path).getAsJsonArray("Items")?.map { it.asJsonObject }.orEmpty().mapNotNull(::toItem)

    private fun toItem(o: JsonObject): MediaItem? {
        val id = o.str("Id").takeIf { ID_RE.matches(it) } ?: return null
        val year = o.get("ProductionYear")?.takeIf { it.isJsonPrimitive }?.asInt
        return MediaItem(
            id = id,
            title = o.str("Name").ifBlank { return null },
            coverUrl = "/api/jellyfin/image/$id",
            type = MediaType.ANIME,
            status = when (o.str("Status")) { "Continuing" -> "En emisión"; "Ended" -> "Finalizado"; else -> "" },
            description = o.str("Overview").take(4_000),
            sourceId = ID,
            genres = o.getAsJsonArray("Genres")?.mapNotNull { g -> g.takeIf { it.isJsonPrimitive }?.asString }.orEmpty().take(12),
            altTitles = listOfNotNull(o.str("OriginalTitle").takeIf { it.isNotBlank() }, year?.let { "${o.str("Name")} ($it)" })
        )
    }

    companion object {
        const val ID = "jellyfin"
        const val URL_PREFIX = "jellyfin:"
        private const val PAGE = 24
        private val ID_RE = Regex("^[0-9a-fA-F-]{32,36}$")

        /** Labels for the server picker; q = bitrate in kbit/s, or "orig" for direct play. */
        val QUALITIES = listOf("Original (directo)" to "orig", "Alta · 8 Mbps" to "8000", "Media · 4 Mbps" to "4000", "Baja · 1,5 Mbps" to "1500")

        fun safeId(raw: String): String = raw.trim().takeIf { ID_RE.matches(it) }?.replace("-", "") ?: throw IllegalArgumentException("Id de Jellyfin no válido")

        /**
         * A series' episodes as one numbered list: with a single season (specials aside) the episode numbers as they
         * are; with several, numbered on across seasons (S2E1 after the last of S1) so the work model can merge them
         * with the streaming sources, which number that way. Specials (season 0) go last, unnumbered.
         */
        fun episodes(seriesId: String, eps: List<JsonObject>): List<Chapter> {
            fun season(o: JsonObject) = o.get("ParentIndexNumber")?.takeIf { it.isJsonPrimitive }?.asInt ?: 1
            fun index(o: JsonObject) = o.get("IndexNumber")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            val regular = eps.filter { season(it) > 0 && ID_RE.matches(it.str("Id")) }.sortedWith(compareBy({ season(it) }, { index(it) }))
            val seasons = regular.map(::season).distinct().size
            val numbered = regular.mapIndexed { i, o ->
                val n = if (seasons <= 1) index(o).takeIf { it > 0 } ?: (i + 1) else i + 1
                val label = if (seasons <= 1) "Episodio $n" else "T${season(o)} · Ep. ${index(o)}"
                val name = o.str("Name").takeIf { it.isNotBlank() && !it.startsWith("Episode ") && !it.startsWith("Episodio ") }
                Chapter(o.str("Id").replace("-", ""), seriesId, if (name != null) "$label: $name" else label, n.toFloat(), URL_PREFIX + o.str("Id").replace("-", ""), o.str("PremiereDate").take(10))
            }
            val specials = eps.filter { season(it) == 0 && ID_RE.matches(it.str("Id")) }.map { o ->
                Chapter(o.str("Id").replace("-", ""), seriesId, "Especial: ${o.str("Name")}", 0f, URL_PREFIX + o.str("Id").replace("-", ""), o.str("PremiereDate").take(10))
            }
            return (numbered + specials).reversed()
        }

        private fun JsonObject.str(name: String) = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}

/** The JSON calls the source needs, with the key in a header. Its own HTTP client: the tailnet is private on purpose. */
class JellyfinApi(val base: String, private val key: String) {
    val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun request(path: String): Request = Request.Builder().url(base.trimEnd('/') + path).header("X-Emby-Token", key).build()

    suspend fun json(path: String): JsonObject = withContext(Dispatchers.IO) {
        client.newCall(request(path).newBuilder().header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) error("Jellyfin respondió ${r.code}")
            val body = r.body?.string().orEmpty()
            require(body.length < MAX_JSON) { "Respuesta de Jellyfin demasiado grande" }
            JsonParser.parseString(body).asJsonObject
        }
    }

    suspend fun reachable(): Boolean = runCatching { json("/System/Info").has("Version") }.getOrDefault(false)

    /** Address of one video: direct play, or transcoded to MP4 at [kbps] (only when asked: it costs the home CPU). */
    fun videoPath(id: String, quality: String): String {
        val kbps = quality.toIntOrNull()?.coerceIn(500, 40_000)
        return if (kbps == null) "/Videos/$id/stream?static=true&MediaSourceId=$id"
        else "/Videos/$id/stream.mp4?MediaSourceId=$id&VideoCodec=h264&AudioCodec=aac&VideoBitrate=${kbps * 1000}&AudioBitrate=160000&MaxWidth=1920&DeviceId=yomukai"
    }

    companion object { const val MAX_JSON = 8 * 1024 * 1024 }
}

/**
 * Keeps the Jellyfin source in the catalogue only while it is set up, switched on and answering (checked every few
 * minutes), so nothing shows when the home server is off.
 */
class JellyfinLink(private val integrations: Integrations, private val features: FeatureFlags, private val sources: com.nelson.yomukai.data.source.SourceManager) {
    @Volatile
    var api: JellyfinApi? = null
        private set

    suspend fun refresh() {
        val c = integrations.get()
        val candidate = if (c.jellyfinReady && features.isOn("jellyfin")) (api?.takeIf { it.base == c.jellyfinUrl } ?: JellyfinApi(c.jellyfinUrl, c.jellyfinKey)) else null
        val live = candidate?.takeIf { it.reachable() }
        api = live
        sources.setExtra(if (live != null) listOf(JellyfinSource(live)) else emptyList())
    }
}

/**
 * The home NAS is for the group, never for guests: any request a guest makes that names the Jellyfin source (as "s",
 * in a /sources/jellyfin/… path or under /jellyfin/…) is refused here, whatever the route. Installed after the
 * session check, so the user is known.
 */
val GuestsOffNas = io.ktor.server.application.createRouteScopedPlugin("GuestsOffNas") {
    onCall { call ->
        val user = call.attributes.getOrNull(UserKey) ?: return@onCall
        if (!user.isGuest) return@onCall
        val path = call.request.path()
        val asked = call.request.queryParameters.getAll("s").orEmpty() + call.request.queryParameters.getAll("sourceId").orEmpty()
        if (JellyfinSource.ID in asked || path.contains("/sources/" + JellyfinSource.ID) || path.startsWith("/api/jellyfin/")) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "No disponible para invitados"))
        }
    }
}

/** Covers and video from the home Jellyfin, for signed-in users (not guests), through the VPS. */
fun Route.jellyfinMediaRoutes(s: Services) {
    get("/jellyfin/image/{id}") {
        if (call.user.isGuest) return@get call.respond(HttpStatusCode.Forbidden)
        val api = s.jellyfin.api ?: return@get call.respond(HttpStatusCode.NotFound)
        val id = runCatching { JellyfinSource.safeId(call.parameters["id"].orEmpty()) }.getOrElse { return@get call.respond(HttpStatusCode.BadRequest) }
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                api.client.newCall(api.request("/Items/$id/Images/Primary?maxWidth=600&quality=85")).execute().use { r ->
                    if (r.isSuccessful) r.body?.bytes()?.takeIf { it.size < 8 * 1024 * 1024 } else null
                }
            }.getOrNull()
        } ?: return@get call.respond(HttpStatusCode.NotFound)
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.respondBytes(bytes, ContentType.Image.JPEG)
    }
    get("/jellyfin/stream/{id}") {
        if (call.user.isGuest) return@get call.respond(HttpStatusCode.Forbidden)
        val api = s.jellyfin.api ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Tu Jellyfin no responde ahora mismo"))
        if (!call.user.isAdmin && s.bandwidth.blocked(s.features.isOn("bandwidth_cap"))) {
            return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "Se ha llegado al tope mensual de vídeo por el servidor"))
        }
        val id = runCatching { JellyfinSource.safeId(call.parameters["id"].orEmpty()) }.getOrElse { return@get call.respond(HttpStatusCode.BadRequest) }
        val q = call.request.queryParameters["q"].orEmpty().take(8)
        val request = api.request(api.videoPath(id, q))
        require(request.url.host == api.base.toHttpUrl().host) { "Dirección no válida" }
        s.proxy.pipeVideo(call, call.user, api.client, request)
    }
}
