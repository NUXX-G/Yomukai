package com.nelson.yomukai.data.sync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.domain.model.Chapter
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import com.nelson.yomukai.domain.model.VideoSource
import java.net.URLEncoder

/**
 * A source only the connected server has (its home Jellyfin): everything goes through the server's own API with the
 * session, so the phone never needs Tailscale or the NAS's address, and Jellyfin's key never leaves the server.
 */
class ServerHostedSource(
    private val sync: ServerSync,
    override val id: String,
    override val name: String,
    override val supportedTypes: Set<MediaType>
) : AnimeSource {
    override val baseUrl: String get() = sync.serverUrl.orEmpty()
    override val language = "MULTI"
    override val supportsLatestPaging = true
    override val imageHeaders: Map<String, String> get() = emptyMap()

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /** This server's own paths ("/api/…") as full addresses; anything else as it came. */
    private fun absolute(url: String) = if (url.startsWith("/")) baseUrl.trimEnd('/') + url else url

    private suspend fun get(path: String): JsonElement = sync.api("GET", path) ?: error("Sin conexión con tu servidor")

    private fun JsonObject.s(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.list(k: String) = getAsJsonArray(k)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    private fun media(o: JsonObject) = MediaItem(
        id = o.s("id"), title = o.s("title"), coverUrl = absolute(o.s("cover")),
        type = runCatching { MediaType.valueOf(o.s("type")) }.getOrDefault(MediaType.ANIME),
        status = o.s("status"), description = o.s("description"), sourceId = id,
        author = o.s("author"), genres = o.list("genres"), altTitles = o.list("altTitles")
    )

    private fun mediaList(e: JsonElement): List<MediaItem> =
        e.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { x -> x.isJsonObject }?.asJsonObject?.let(::media) }.orEmpty()

    override suspend fun fetchLatestUpdates(page: Int): Result<List<MediaItem>> = runCatching {
        mediaList(get("/api/sources/${enc(id)}/latest?page=$page"))
    }

    override suspend fun searchMedia(query: String): Result<List<MediaItem>> = runCatching {
        mediaList(get("/api/sources/${enc(id)}/search?q=${enc(query)}"))
    }

    override suspend fun fetchMediaDetails(media: MediaItem): Result<MediaItem> = runCatching {
        media(get("/api/media?s=${enc(id)}&id=${enc(media.id)}").asJsonObject)
    }

    override suspend fun fetchChapterList(mediaId: String): Result<List<Chapter>> = runCatching {
        get("/api/chapters?s=${enc(id)}&id=${enc(mediaId)}").asJsonObject.getAsJsonArray("chapters")
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.map { c -> Chapter(c.s("id"), mediaId, c.s("title"), c.get("number")?.asFloat ?: 0f, c.s("url"), c.s("date")) }
            .orEmpty()
    }

    /** Streams of the server (/api/jellyfin/stream/…): the player adds the session only for this server's address. */
    override suspend fun fetchVideoSources(episodeUrl: String): Result<List<VideoSource>> = runCatching {
        get("/api/videos?s=${enc(id)}&c=${enc(episodeUrl)}").asJsonArray.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.map { v ->
            VideoSource(v.s("quality"), absolute(v.s("url")), v.s("server").ifBlank { name }, isDirect = v.get("direct")?.asBoolean == true)
        }
    }

    companion object {
        /** Sources that exist only on the server, offered in the app while it is connected. */
        val HOSTED = setOf("jellyfin")
    }
}
