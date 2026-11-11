package com.nelson.yomukai.data.sync

import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.domain.model.AudioTrack
import com.nelson.yomukai.domain.model.VideoSource
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * An episode's servers with a server connected (F3, O2): the server resolves them (its resolvers, its checks of each
 * stream and the order of the version the user chose), and the phone plays the stream itself, straight from the
 * host, so the video never goes through the VPS; the server's own relay stays as the fallback for each stream.
 * Without a server, or when it has nothing that plays, the source is scraped here as before.
 */
object ServerVideos {
    suspend fun fetch(sync: ServerSync, source: AnimeSource, episodeUrl: String): Result<List<VideoSource>> {
        if (sync.serverUrl != null && source !is ServerHostedSource) {
            val fromServer = runCatching { fromServer(sync, source, episodeUrl) }.getOrNull()
            if (fromServer != null && fromServer.any { it.isDirect }) return Result.success(fromServer)
        }
        return source.fetchVideoSources(episodeUrl)
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    private suspend fun fromServer(sync: ServerSync, source: AnimeSource, episodeUrl: String): List<VideoSource>? {
        val base = sync.serverUrl?.trimEnd('/') ?: return null
        val answer = sync.api("GET", "/api/videos?s=${enc(source.id)}&c=${enc(episodeUrl)}")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return answer.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.flatMap { v -> expand(v, base, source.name) }
    }

    /** The direct stream (with its headers) first, the server's relay of it as the fallback; embeds as they are. */
    private fun expand(v: JsonObject, base: String, sourceName: String): List<VideoSource> {
        fun s(k: String) = v.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val url = s("url")
        val name = s("server").ifBlank { sourceName }
        val audio = AudioTrack.fromName(s("audio"))
        val direct = v.get("direct")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        if (!direct) return listOf(VideoSource(s("quality"), url, name, isDirect = false, audio = audio))
        val relayed = VideoSource(s("quality"), if (url.startsWith("/")) base + url else url, "$name · " + com.nelson.yomukai.i18n.tr("vía servidor"), isDirect = true, audio = audio)
        val raw = rawStream(url) ?: return listOf(relayed)
        return listOf(VideoSource(s("quality"), raw.first, name, isDirect = true, headers = raw.second, audio = audio), relayed)
    }

    /** The stream and headers inside the server's signed relay link (`/api/stream?u=…&h=…`); null for anything else. */
    fun rawStream(link: String): Pair<String, Map<String, String>>? {
        if (!link.startsWith("/api/stream?")) return null
        val params = link.substringAfter('?').split('&').mapNotNull { p ->
            p.substringBefore('=').takeIf { it.isNotBlank() }?.let { it to URLDecoder.decode(p.substringAfter('=', ""), Charsets.UTF_8) }
        }.toMap()
        val url = params["u"]?.takeIf { it.startsWith("http") } ?: return null
        val headers = params["h"]?.takeIf { it.isNotBlank() }?.let { h ->
            runCatching {
                val json = String(Base64.getUrlDecoder().decode(h))
                com.google.gson.Gson().fromJson<Map<String, String>>(json, object : TypeToken<Map<String, String>>() {}.type)
            }.getOrNull()
        }.orEmpty()
        return url to headers
    }
}
