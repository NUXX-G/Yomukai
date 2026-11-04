package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.utils.io.writeFully
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Images and video reach the browser through the server: the sites only serve them with their own
 * Referer, and browsers cannot set it. Every proxied link is signed with the server's key, so the
 * server never fetches an address it did not hand out itself.
 */
class MediaProxy(private val http: ServerHttp, secret: ByteArray, cacheDir: File, private val guestStreams: Int) {
    private val key = SecretKeySpec(secret, "HmacSHA256")
    private val gson = Gson()
    private val imageCache = ImageCache(File(cacheDir, "images"))
    private val openStreams = ConcurrentHashMap<Long, AtomicInteger>()

    private fun sign(vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
        parts.forEach { mac.update(it.toByteArray()); mac.update(0) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal()).take(22)
    }

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun encodeHeaders(headers: Map<String, String>): String =
        if (headers.isEmpty()) "" else Base64.getUrlEncoder().withoutPadding().encodeToString(gson.toJson(headers).toByteArray())

    private fun decodeHeaders(value: String?): Map<String, String> {
        if (value.isNullOrBlank()) return emptyMap()
        val json = String(Base64.getUrlDecoder().decode(value))
        return gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type)
    }

    /** Link to [url] as an image with [headers]; blank stays blank. */
    fun image(url: String, headers: Map<String, String>): String {
        if (url.isBlank() || !url.startsWith("http")) return url
        val h = encodeHeaders(headers)
        return "/api/img?u=${enc(url)}&h=$h&k=${sign("img", url, h)}"
    }

    /** Link to a video or playlist at [url] with [headers]. */
    fun stream(url: String, headers: Map<String, String>): String {
        val h = encodeHeaders(headers)
        return "/api/stream?u=${enc(url)}&h=$h&k=${sign("stream", url, h)}"
    }

    private fun verified(kind: String, call: ApplicationCall): Pair<String, Map<String, String>>? {
        val url = call.request.queryParameters["u"] ?: return null
        val h = call.request.queryParameters["h"].orEmpty()
        val k = call.request.queryParameters["k"] ?: return null
        if (!MessageDigest.isEqual(sign(kind, url, h).toByteArray(), k.toByteArray())) return null
        if (url.toHttpUrlOrNull() == null) return null
        return url to decodeHeaders(h)
    }

    suspend fun serveImage(call: ApplicationCall) {
        val (url, headers) = verified("img", call) ?: return call.respond(HttpStatusCode.Forbidden)
        val (bytes, type) = fetchImage(url, headers) ?: return call.respond(HttpStatusCode.BadGateway)
        // Proxied links never change for the same address: the browser may keep the image for a month.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000, immutable")
        // Whatever the site claims, only a raster image type goes out: a page or an SVG served from
        // this origin could run scripts in it. Browsers decode images by their bytes anyway.
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.respondBytes(bytes, ContentType.parse(safeImageType(type)))
    }

    /** An image's bytes and type, from the disk cache or the site. */
    suspend fun fetchImage(url: String, headers: Map<String, String>): Pair<ByteArray, String>? {
        val cached = imageCache.get(url)
        return cached ?: withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", headers["User-Agent"] ?: http.userAgentFor(url.toHttpUrlOrNull()?.host.orEmpty()))
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .apply { headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> header(k, v) } }
                .build()
            http.streamClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val type = response.header("Content-Type").orEmpty()
                val body = response.body?.let { readBounded(it, MAX_IMAGE_BYTES.toLong()) } ?: return@withContext null
                if (body.size > MAX_IMAGE_BYTES) return@withContext null
                body to type.ifBlank { "image/jpeg" }
            }
        }?.also { if (cached == null) imageCache.put(url, it.first, it.second) }
    }

    /** Counts a download as one of the user's open videos; false when a guest already has too many. */
    fun openStream(user: User): Boolean {
        val open = openStreams.getOrPut(user.id) { AtomicInteger() }
        if (user.isGuest && open.get() >= guestStreams) return false
        open.incrementAndGet()
        return true
    }

    fun closeStream(user: User) { openStreams[user.id]?.decrementAndGet() }

    /**
     * Streams a video with Range passed through, so seeking works. HLS playlists are rewritten so
     * every segment and key also comes through here.
     */
    suspend fun serveStream(call: ApplicationCall, user: User) {
        val (url, headers) = verified("stream", call) ?: return call.respond(HttpStatusCode.Forbidden)
        val open = openStreams.getOrPut(user.id) { AtomicInteger() }
        if (user.isGuest && open.get() >= guestStreams && !url.contains(".m3u8")) {
            return call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiados vídeos a la vez"))
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", headers["User-Agent"] ?: http.userAgentFor(url.toHttpUrlOrNull()?.host.orEmpty()))
            .apply {
                headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> header(k, v) }
                call.request.headers[HttpHeaders.Range]?.let { header(HttpHeaders.Range, it) }
            }
            .build()
        val response = withContext(Dispatchers.IO) { http.streamClient.newCall(request).execute() }
        response.use {
            val type = response.header("Content-Type").orEmpty()
            if (!response.isSuccessful) return call.respond(HttpStatusCode.fromValue(response.code))
            val playlist = "mpegurl" in type.lowercase() || url.substringBefore('?').endsWith(".m3u8") ||
                (type.startsWith("text/") && response.peekBody(8).string().startsWith("#EXTM3U"))
            if (playlist) {
                val text = response.body?.string().orEmpty()
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                return call.respondText(rewritePlaylist(text, response.request.url.toString(), headers), ContentType.parse("application/vnd.apple.mpegurl"))
            }
            open.incrementAndGet()
            try {
                // Safari plays a video only with a real video type and an exact length on every range.
                // Only video and audio types leave the server; anything else is labelled as video.
                val videoType = type.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
                    ?: if (url.substringBefore('?').endsWith(".ts")) "video/mp2t" else "video/mp4"
                val length = response.header(HttpHeaders.ContentLength)?.toLongOrNull()
                val body = response.body ?: return call.respond(HttpStatusCode.BadGateway)
                val extra = io.ktor.http.Headers.build {
                    append("Content-Security-Policy", "sandbox; default-src 'none'")
                    response.header(HttpHeaders.ContentRange)?.let { append(HttpHeaders.ContentRange, it) }
                    append(HttpHeaders.AcceptRanges, "bytes")
                    append(HttpHeaders.CacheControl, "private, max-age=3600")
                }
                call.respond(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                    override val contentType = ContentType.parse(videoType)
                    override val contentLength = length
                    override val status = HttpStatusCode.fromValue(response.code)
                    override val headers = extra
                    override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                        withContext(Dispatchers.IO) {
                            body.byteStream().use { input ->
                                val buffer = ByteArray(BUFFER)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    channel.writeFully(buffer, 0, read)
                                }
                            }
                        }
                        channel.flushAndClose()
                    }
                })
            } finally {
                open.decrementAndGet()
            }
        }
    }

    /** Every URI in an HLS playlist (segments, keys, sub-playlists) made absolute and proxied. */
    fun rewritePlaylist(text: String, base: String, headers: Map<String, String>): String {
        val baseUrl = base.toHttpUrlOrNull() ?: return text
        fun proxied(uri: String): String = baseUrl.resolve(uri.trim())?.toString()?.let { stream(it, headers) } ?: uri
        return text.lineSequence().joinToString("\n") { line ->
            when {
                line.isBlank() -> line
                line.startsWith("#") -> URI_ATTRIBUTE.replace(line) { m -> "URI=\"${proxied(m.groupValues[1])}\"" }
                else -> proxied(line)
            }
        }
    }

    companion object {
        private val RASTER = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/avif", "image/bmp")

        /** The site's type when it is a raster image; JPEG otherwise (never HTML, SVG or text). */
        fun safeImageType(type: String): String = type.substringBefore(';').trim().lowercase().takeIf { it in RASTER } ?: "image/jpeg"

        private const val MAX_IMAGE_BYTES = 20 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    }
}

/**
 * Images kept on disk, a few GB at most, the oldest dropped first. Covers are asked for by
 * everyone and pages often by friends reading the same series, so most hits never touch the site.
 */
private class ImageCache(private val dir: File, private val maxBytes: Long = 2L * 1024 * 1024 * 1024) {
    init { dir.mkdirs() }

    @Volatile private var approxSize = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(2) + "/" + hash)
    }

    fun get(url: String): Pair<ByteArray, String>? {
        val file = fileFor(url)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val split = bytes.indexOf('\n'.code.toByte()).takeIf { it in 1..100 } ?: return null
        file.setLastModified(System.currentTimeMillis())
        return bytes.copyOfRange(split + 1, bytes.size) to String(bytes, 0, split)
    }

    fun put(url: String, bytes: ByteArray, type: String) {
        val file = fileFor(url)
        file.parentFile.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { it.write(type.take(100).toByteArray()); it.write('\n'.code); it.write(bytes) }
        tmp.renameTo(file)
        approxSize += file.length()
        if (approxSize > maxBytes) trim()
    }

    @Synchronized
    private fun trim() {
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toMutableList()
        var size = files.sumOf { it.length() }
        val target = maxBytes * 8 / 10
        while (size > target && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            size -= oldest.length()
            oldest.delete()
        }
        approxSize = size
    }
}
