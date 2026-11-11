package com.nelson.yomukai.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nelson.yomukai.data.image.HighResCovers
import com.nelson.yomukai.data.image.ImageShrinker
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
class MediaProxy(private val http: ServerHttp, secret: ByteArray, cacheDir: File, private val guestStreams: Int, imageCacheBytes: Long = 2L * 1024 * 1024 * 1024) {
    private val key = SecretKeySpec(secret, "HmacSHA256")
    private val gson = Gson()
    private val log = org.slf4j.LoggerFactory.getLogger("YomukaiMedia")
    private val imageCache = ImageCache(File(cacheDir, "images"), imageCacheBytes)
    private val openStreams = ConcurrentHashMap<Long, AtomicInteger>()

    /** Told every byte of video sent to a viewer (the monthly traffic meter). */
    @Volatile var onStreamBytes: (Long) -> Unit = {}
    private val imageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Images being fetched right now: a second request for one waits for the same download. */
    private val imagesInFlight = ConcurrentHashMap<String, Deferred<Pair<ByteArray, String>?>>()

    /** Downloads ahead of the reader, shared by everyone, so they never crowd out what is on screen. */
    private val warmLimit = Semaphore(WARM_PARALLEL)

    /** Shrinking is CPU work: a couple at a time. */
    private val shrinkLimit = Semaphore(SHRINK_PARALLEL)
    private val shrinkQueued = java.util.concurrent.atomic.AtomicInteger()

    /** However many people ask, at most this many images come down from the sites at once (2 GB VPS). */
    private val downloadLimit = Semaphore(DOWNLOAD_PARALLEL)

    /** Widths readers asked for, and when: pages are prepared ahead at those. */
    private val recentWidths = ConcurrentHashMap<Int, Long>()
    private val warmQueued = AtomicInteger()

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

    /** The page that shows a site's own player full screen ([embedPage]); null for anything that is not https. */
    fun embed(url: String): String? =
        url.takeIf { it.startsWith("https://") }?.let { "$EMBED_PATH?u=${enc(it)}&k=${sign("embed", it)}" }

    fun embedSigned(url: String, key: String): Boolean = java.security.MessageDigest.isEqual(sign("embed", url).toByteArray(), key.toByteArray())

    fun image(url: String, headers: Map<String, String>): String {
        if (url.isBlank() || !url.startsWith("http")) return url
        val h = encodeHeaders(headers)
        return "/api/img?u=${enc(url)}&h=$h&k=${sign("img", url, h)}"
    }

    /**
     * A cover at the best resolution the site has: thumbnails are swapped for their original ([HighResCovers]) and the
     * browser asks for its own width (big screens and TVs get more), the server shrinking and caching. If the
     * original cannot be fetched, the thumbnail it came from is served instead ("o").
     */
    fun cover(url: String, headers: Map<String, String>): String {
        if (url.isBlank() || !url.startsWith("http")) return url
        val hi = HighResCovers.upgrade(url)
        if (hi == url) return image(url, headers) + "&w=$COVER_WIDTH"
        val h = encodeHeaders(headers)
        return "/api/img?u=${enc(hi)}&o=${enc(url)}&h=$h&k=${sign("img", hi, h, url)}&w=$COVER_WIDTH"
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
        val fallback = call.request.queryParameters["o"]
        val expected = if (fallback != null) sign(kind, url, h, fallback) else sign(kind, url, h)
        if (!MessageDigest.isEqual(expected.toByteArray(), k.toByteArray())) return null
        if (fallback != null && fallback.toHttpUrlOrNull() == null) return null
        if (url.toHttpUrlOrNull() == null) return null
        return url to decodeHeaders(h)
    }

    suspend fun serveImage(call: ApplicationCall) {
        val (url, headers) = verified("img", call) ?: return call.respond(HttpStatusCode.Forbidden)
        // "w": the reader's width in pixels. Pages much wider than it come shrunk (and stay cached so).
        val width = ImageShrinker.bucket(call.request.queryParameters["w"])

        // V1: a site that takes too long does not hold the card: the download goes on (and is kept for next time), the
        // answer moves to the thumbnail it came from.
        suspend fun get(u: String) = withTimeoutOrNull(IMAGE_WAIT_MS) { if (width != null) fetchShrunk(u, headers, width) else fetchImage(u, headers) }
        // A cover upgraded to its original falls back to the thumbnail it came from (already checked by the signature).
        val (bytes, type) = get(url) ?: call.request.queryParameters["o"]?.let { get(it) }
            ?: return call.respond(HttpStatusCode.BadGateway)
        // Proxied links never change for the same address: the browser may keep the image for a month.
        call.response.header(HttpHeaders.CacheControl, "public, max-age=2592000, immutable")
        // Whatever the site claims, only a raster image type goes out: a page or an SVG served from
        // this origin could run scripts in it. Browsers decode images by their bytes anyway.
        call.response.header("Content-Security-Policy", "sandbox; default-src 'none'")
        call.respondBytes(bytes, ContentType.parse(safeImageType(type)))
    }

    /** Whether [url] is already on disk at [width] (null: the original), so the worker is not asked for it again. */
    fun hasImage(url: String, width: Int? = null): Boolean = imageCache.has(if (width == null) url else "$url#w=$width")

    /**
     * Stores what the home worker made (F2.2): the original of [url] and its [widths] (null = no smaller than the
     * original, like [shrinkNow] decides). Only real raster images are kept: the bytes come from scraped sites.
     */
    fun storeFromWorker(url: String, original: ByteArray, type: String, widths: Map<Int, ByteArray?>): Boolean {
        if (sniffImage(original) == null) return false
        imageCache.put(url, original, safeImageType(type))
        widths.forEach { (w, bytes) ->
            when {
                bytes == null -> imageCache.put("$url#w=$w", ByteArray(0), SAME_AS_ORIGINAL)
                sniffImage(bytes) == "image/jpeg" -> imageCache.put("$url#w=$w", bytes, "image/jpeg")
            }
        }
        return true
    }

    /** The image type by its first bytes (JPEG, PNG, GIF, WebP, AVIF); null for anything else. */
    private fun sniffImage(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size > 8 && b[0] == 0x89.toByte() && String(b, 1, 3, Charsets.US_ASCII) == "PNG" -> "image/png"
        b.size > 6 && String(b, 0, 4, Charsets.US_ASCII) == "GIF8" -> "image/gif"
        b.size > 12 && String(b, 0, 4, Charsets.US_ASCII) == "RIFF" && String(b, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        b.size > 12 && String(b, 4, 4, Charsets.US_ASCII) == "ftyp" -> "image/avif"
        else -> null
    }

    /** The image no wider than [width] (see [ImageShrinker]); the original when shrinking gains nothing. */
    private suspend fun fetchShrunk(url: String, headers: Map<String, String>, width: Int): Pair<ByteArray, String>? {
        // Covers (240-480) are not reading widths: only those are prepared ahead for readers.
        if (width >= READER_MIN_WIDTH) recentWidths[width] = System.currentTimeMillis()
        val key = "$url#w=$width"
        imageCache.get(key)?.let { (bytes, type) -> return if (type == SAME_AS_ORIGINAL) fetchImage(url, headers) else bytes to type }
        val mine = imageScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { shrinkNow(url, headers, width, key) }
        val running = imagesInFlight.putIfAbsent(key, mine)
        if (running != null) { mine.cancel(); return running.await() }
        // Removed when the work really ends, not when this caller stops waiting (the 10 s limit): a slow image is
        // never downloaded twice because someone gave up on it.
        mine.invokeOnCompletion { imagesInFlight.remove(key, mine) }
        return mine.await()
    }

    /**
     * V1: the download happens in parallel, outside the shrinking queue (it used to hold the only turn while a slow
     * site answered, so a Home page of covers came one by one, up to a minute). When the CPU queue is busy, the
     * original goes out now and the smaller copy is made in the background for the next time.
     */
    private suspend fun shrinkNow(url: String, headers: Map<String, String>, width: Int, key: String): Pair<ByteArray, String>? {
        val original = fetchImage(url, headers) ?: return null
        if (!shrinkLimit.tryAcquire()) {
            if (shrinkQueued.incrementAndGet() > SHRINK_MAX_QUEUED) shrinkQueued.decrementAndGet()
            else imageScope.launch {
                try {
                    shrinkLimit.withPermit { if (!imageCache.has(key)) shrinkAndKeep(original, width, key) }
                } finally {
                    shrinkQueued.decrementAndGet()
                }
            }
            return original
        }
        val small = try { shrinkAndKeep(original, width, key) } finally { shrinkLimit.release() }
        return if (small != null) small to "image/jpeg" else original
    }

    /** Either way the answer is kept: the same image at the same width is never worked on twice. */
    private suspend fun shrinkAndKeep(original: Pair<ByteArray, String>, width: Int, key: String): ByteArray? {
        val small = withContext(Dispatchers.Default) { runCatching { ImageShrinker.shrink(original.first, original.second.lowercase(), width) }.getOrNull() }
        if (small != null) imageCache.put(key, small, "image/jpeg") else imageCache.put(key, ByteArray(0), SAME_AS_ORIGINAL)
        return small
    }

    /**
     * Starts downloading a chapter's pages before the browser asks for them, a few at a time, so they
     * come from this server's disk (or a download already halfway) instead of the far-away site.
     */
    fun warm(urls: List<String>, headers: Map<String, String>) {
        urls.take(WARM_MAX_PAGES).forEach { url ->
            if (imageCache.has(url) || imagesInFlight.containsKey(url)) return@forEach
            // A bounded queue: past it, pages are simply fetched when the reader asks for them.
            if (warmQueued.incrementAndGet() > WARM_MAX_QUEUED) { warmQueued.decrementAndGet(); return }
            imageScope.launch {
                try {
                    warmLimit.withPermit {
                        runCatching { fetchImage(url, headers) }
                        // Also ready at the widths readers asked for lately, so nobody waits for the shrinking.
                        val now = System.currentTimeMillis()
                        recentWidths.filterValues { now - it < RECENT_WIDTH_MS }.keys.sortedDescending().take(2).forEach { w ->
                            runCatching { fetchShrunk(url, headers, w) }
                        }
                    }
                } finally {
                    warmQueued.decrementAndGet()
                }
            }
        }
    }

    /** An image's bytes and type, from the disk cache or the site; one download however many ask at once. */
    suspend fun fetchImage(url: String, headers: Map<String, String>): Pair<ByteArray, String>? {
        imageCache.get(url)?.let { return it }
        val mine = imageScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { download(url, headers) }
        val running = imagesInFlight.putIfAbsent(url, mine)
        if (running != null) { mine.cancel(); return running.await() }
        mine.invokeOnCompletion { imagesInFlight.remove(url, mine) }
        return mine.await()
    }

    private suspend fun download(url: String, headers: Map<String, String>): Pair<ByteArray, String>? = downloadLimit.withPermit {
        val cached = imageCache.get(url)
        // A total deadline: a site that trickles bytes never keeps its turn (and the shared download) forever.
        cached ?: withTimeoutOrNull(DOWNLOAD_DEADLINE_MS) { fetchFromSite(url, headers) }
    }

    private suspend fun fetchFromSite(url: String, headers: Map<String, String>): Pair<ByteArray, String>? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", headers["User-Agent"] ?: http.userAgentFor(url.toHttpUrlOrNull()?.host.orEmpty()))
                .header("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                .apply { headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> header(k, v) } }
                .build()
            // Some CDNs refuse OkHttp over HTTP/2 (403) but serve the same image over HTTP/1.1.
            val first = http.streamClient.newCall(request).await()
            val response = if (first.code == 403) { first.close(); http.http1Client.newCall(request).await() } else first
            response.use { response ->
                if (!response.isSuccessful) {
                    log.info("Imagen {} respondió {}", url.toHttpUrlOrNull()?.host, response.code)
                    return@withContext null
                }
                val type = response.header("Content-Type").orEmpty()
                val body = response.body?.let { readBounded(it, MAX_IMAGE_BYTES.toLong()) } ?: return@withContext null
                if (body.size > MAX_IMAGE_BYTES) return@withContext null
                body to type.ifBlank { "image/jpeg" }
            }
        }?.also { imageCache.put(url, it.first, it.second) }

    /**
     * F3: whether a direct stream really answers (its first byte, with the headers it needs), before it is offered.
     * Some CDN nodes refuse links their own page just gave (LuluStream: 403 now and then): those are not offered.
     */
    suspend fun answers(url: String, headers: Map<String, String>): Boolean = withTimeoutOrNull(STREAM_CHECK_MS) {
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url)
                .header("User-Agent", headers["User-Agent"] ?: http.userAgentFor(url.toHttpUrlOrNull()?.host.orEmpty()))
                .apply { headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> header(k, v) } }
                .header(HttpHeaders.Range, "bytes=0-0")
                .build()
            runCatching { http.streamClient.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }
    } ?: false

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
            if (!response.isSuccessful) {
                log.info("Vídeo {} respondió {} (cabeceras: {})", url.toHttpUrlOrNull()?.host, response.code, headers.keys)
                return call.respond(HttpStatusCode.fromValue(response.code))
            }
            val playlist = "mpegurl" in type.lowercase() || url.substringBefore('?').endsWith(".m3u8") ||
                (type.startsWith("text/") && response.peekBody(8).string().startsWith("#EXTM3U"))
            if (playlist) {
                val text = response.body?.string().orEmpty()
                onStreamBytes(text.length.toLong())
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                return call.respondText(rewritePlaylist(text, response.request.url.toString(), headers), ContentType.parse("application/vnd.apple.mpegurl"))
            }
            sendVideo(call, open, response, url)
        }
    }

    /**
     * A video from a host only the server may reach (the home Jellyfin through Tailscale), with [client] and the
     * request already built (its key in a header, never in the link). Playlists are not relayed: progressive only.
     */
    suspend fun pipeVideo(call: ApplicationCall, user: User, client: okhttp3.OkHttpClient, request: Request) {
        val open = openStreams.getOrPut(user.id) { AtomicInteger() }
        if (user.isGuest) return call.respond(HttpStatusCode.Forbidden)
        val ranged = request.newBuilder().apply { call.request.headers[HttpHeaders.Range]?.let { header(HttpHeaders.Range, it) } }.build()
        val response = withContext(Dispatchers.IO) { client.newCall(ranged).execute() }
        if (!response.isSuccessful) {
            response.close()
            return call.respond(HttpStatusCode.fromValue(response.code))
        }
        // The body is closed once written: an engine may write it after respond() has returned.
        try {
            sendVideo(call, open, response, request.url.toString(), closeAfter = true)
        } catch (e: Throwable) {
            response.close()
            throw e
        }
    }

    private suspend fun sendVideo(call: ApplicationCall, open: AtomicInteger, response: okhttp3.Response, url: String, closeAfter: Boolean = false) {
            val type = response.header("Content-Type").orEmpty()
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
                        try {
                            withContext(Dispatchers.IO) {
                                body.byteStream().use { input ->
                                    val buffer = ByteArray(BUFFER)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        channel.writeFully(buffer, 0, read)
                                        onStreamBytes(read.toLong())
                                    }
                                }
                            }
                        } finally {
                            if (closeAfter) response.close()
                        }
                        channel.flushAndClose()
                    }
                })
            } finally {
                open.decrementAndGet()
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
        private const val COVER_WIDTH = 720
        private const val READER_MIN_WIDTH = 720
        private const val SAME_AS_ORIGINAL = "x-yomukai/original"
        private const val WARM_PARALLEL = 12
        private const val WARM_MAX_PAGES = 80
        private const val SHRINK_PARALLEL = 2
        private const val SHRINK_MAX_QUEUED = 64
        private const val DOWNLOAD_PARALLEL = 24
        private const val DOWNLOAD_DEADLINE_MS = 45_000L
        private const val STREAM_CHECK_MS = 6_000L
        private const val IMAGE_WAIT_MS = 10_000L
        private const val WARM_MAX_QUEUED = 240
        private const val RECENT_WIDTH_MS = 30 * 60_000L
        private const val BUFFER = 64 * 1024
        private val URI_ATTRIBUTE = Regex("""URI="([^"]+)"""")
    }
}

/**
 * Images kept on disk, a few GB at most, the oldest dropped first. Covers are asked for by
 * everyone and pages often by friends reading the same series, so most hits never touch the site.
 */
internal class ImageCache(private val dir: File, private val maxBytes: Long = 2L * 1024 * 1024 * 1024) {
    init { dir.mkdirs() }

    @Volatile private var approxSize = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(url: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(2) + "/" + hash)
    }

    fun has(url: String): Boolean = fileFor(url).exists()

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
