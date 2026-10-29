package com.nelson.yomukai.data.download

import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.domain.model.VideoSource
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.ssl.SSLException

/**
 * Which episode servers the downloader can use, in what order, and how to explain a failure.
 * Pure, so it is unit tested; the network checks live in [DownloadManager].
 */
object EpisodeServers {
    /** Direct streams (HLS/MP4) over HTTPS; web embeds only play in a browser. */
    fun isDownloadable(server: VideoSource): Boolean =
        server.isDirect && runCatching { DownloadSecurity.requireSecureUrl(server.videoUrl) }.isSuccess

    /** Downloadable servers in the order they are tried: the one the user picked first, then the source's order. */
    fun candidates(servers: List<VideoSource>, preferred: String): List<VideoSource> {
        val usable = servers.filter(::isDownloadable)
        val (chosen, rest) = usable.partition { preferred.isNotBlank() && it.serverName.equals(preferred, ignoreCase = true) }
        return chosen + rest
    }

    /**
     * Servers that can only be opened in a browser. Extractors return a resolved stream next to its
     * original embed, so a server that has a downloadable entry is never listed here.
     */
    fun browserOnly(servers: List<VideoSource>): List<String> {
        val downloadable = servers.filter(::isDownloadable).map { it.serverName.lowercase() }.toSet()
        return servers.map { it.serverName }.distinct().filter { it.lowercase() !in downloadable }
    }

    /** Short, user-facing reason for one server's failure. */
    fun reason(error: Throwable): String = when (error) {
        is HttpException -> when (error.code) {
            401, 403 -> "el servidor bloquea la descarga (HTTP ${error.code})"
            404, 410 -> "el vídeo ya no está disponible (HTTP ${error.code})"
            else -> "error del servidor (HTTP ${error.code})"
        }
        is SocketTimeoutException -> "no responde (tiempo de espera agotado)"
        is SSLException -> "se cortó la conexión segura (TLS)"
        // Library messages can span several lines of internals; the first one is enough here.
        else -> error.message?.lineSequence()?.first()?.take(160) ?: error.javaClass.simpleName
    }

    /** Message stored on a failed episode download: every server tried and why it failed. */
    fun failureMessage(failures: List<Pair<String, String>>, browserOnly: List<String>): String {
        val browserNote = if (browserOnly.isEmpty()) "" else " ${browserOnly.joinToString(", ")} solo se puede ver en el navegador."
        if (failures.isEmpty()) return "Este episodio no tiene servidores descargables.$browserNote"
        val tried = failures.joinToString("; ") { (server, reason) -> "$server: $reason" }
        return "No se pudo descargar con ningún servidor. $tried.$browserNote Toca el episodio fallido para elegir otro servidor."
    }

    /** First variant of a master playlist, or null when [playlist] is already a media playlist. */
    fun hlsFirstVariant(playlist: String, playlistUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        val index = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (index < 0) return null
        return lines.drop(index + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(playlistUrl, it) }
    }

    /** First piece of media a download needs: the init segment (EXT-X-MAP) if any, else the first segment. */
    fun hlsFirstSegment(playlist: String, playlistUrl: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.toList()
        lines.firstOrNull { it.startsWith("#EXT-X-MAP") }
            ?.let { MAP_URI.find(it)?.groupValues?.get(1) }
            ?.let { return resolve(playlistUrl, it) }
        return lines.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }?.let { resolve(playlistUrl, it) }
    }

    private val MAP_URI = Regex("""URI="([^"]+)"""")

    private fun resolve(base: String, uri: String): String = URI(base).resolve(uri).toString()
}
