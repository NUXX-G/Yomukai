package com.nelson.yomukai.server

import com.nelson.yomukai.domain.model.VideoSource
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.IOException
import java.io.OutputStream
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Chapters and episodes as files for the user's own device. The file is put together while it is being
 * sent, so nothing stays on the server: comics as CBZ, novels as EPUB, episodes as MP4 (or TS when the
 * site only offers HLS).
 */
class Downloader(private val catalog: Catalog, private val proxy: MediaProxy, private val http: ServerHttp) {
    private val active = ConcurrentHashMap<Long, AtomicInteger>()
    /** Shared by every download: page and segment requests to the sites at the same time. */
    private val fetches = Semaphore(MAX_PARALLEL_FETCHES)

    suspend fun serve(call: ApplicationCall, user: User, sourceId: String, mediaId: String, chapterIds: List<String>, nameHint: String? = null) {
        val kind = catalog.kindOf(sourceId) ?: return call.respond(HttpStatusCode.NotFound, mapOf("error" to "Fuente desconocida"))
        val wanted = chapterIds.toSet()
        val list = catalog.chapters(sourceId, mediaId, null).getOrElse {
            return call.respond(HttpStatusCode.BadGateway, mapOf("error" to (it.message ?: "No se pudo leer la lista de capítulos")))
        }.chapters
        // Only chapters of this title: the addresses come from the site's own list, never from the request.
        val chapters = list.filter { it.id in wanted }.sortedBy { it.index }
        if (chapters.isEmpty()) return call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No hay capítulos que descargar"))
        val max = when {
            kind == "anime" -> 1
            user.isGuest -> GUEST_MAX_CHAPTERS
            else -> MAX_CHAPTERS
        }
        if (chapters.size > max) {
            return call.respond(HttpStatusCode.BadRequest, mapOf("error" to if (kind == "anime") "Los episodios se descargan de uno en uno" else "Como mucho $max capítulos por descarga"))
        }
        val counter = active.getOrPut(user.id) { AtomicInteger() }
        val limit = if (user.isGuest) 1 else 3
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet()
            return call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Espera a que termine la descarga que ya está en marcha"))
        }
        try {
            val media = catalog.media(sourceId, mediaId).getOrNull()
            // The name only names the file; the page sends it for sources whose details page has no title.
            val title = media?.title?.takeIf { it.isNotBlank() } ?: nameHint?.trim()?.take(150)?.takeIf { it.isNotEmpty() } ?: "Yomukai"
            when (kind) {
                "manga" -> comics(call, sourceId, title, chapters)
                "novel" -> novel(call, sourceId, title, media?.coverUrl.orEmpty(), media?.author.orEmpty(), chapters)
                else -> episode(call, user, sourceId, title, chapters.single())
            }
        } finally {
            counter.decrementAndGet()
        }
    }

    // Comics

    private suspend fun comics(call: ApplicationCall, sourceId: String, title: String, chapters: List<ChapterDto>) {
        // The first chapter's page list is read before answering, so an error can still be told properly.
        val first = catalog.rawPages(sourceId, chapters.first().url).getOrElse {
            return call.respond(HttpStatusCode.BadGateway, mapOf("error" to (it.message ?: "No se pudieron leer las páginas")))
        }
        if (chapters.size == 1) {
            attachment(call, "$title - ${chapterName(chapters.single())}.cbz")
            call.respondOutputStream(ContentType.parse("application/vnd.comicbook+zip")) {
                val problems = ZipOutputStream(this).use { zip -> writeCbz(zip, title, chapters.single(), first) }
                if (problems.isNotEmpty()) log.warn("Descarga incompleta de {}: {}", title, problems)
            }
            return
        }
        attachment(call, "$title.zip")
        call.respondOutputStream(ContentType.Application.Zip) {
            ZipOutputStream(this).use { zip ->
                // Pages are already compressed images: the outer zip only packs them.
                zip.setLevel(java.util.zip.Deflater.BEST_SPEED)
                val problems = mutableListOf<String>()
                chapters.forEachIndexed { i, chapter ->
                    val pages = if (i == 0) Result.success(first) else catalog.rawPages(sourceId, chapter.url)
                    pages.onFailure { problems += "${chapterName(chapter)}: ${it.message}" }.onSuccess { list ->
                        // Each chapter becomes its own CBZ inside the zip, as comic readers expect.
                        // Written page by page straight into the outer zip, so the download never sits idle.
                        withContext(Dispatchers.IO) { zip.putNextEntry(ZipEntry("${safeName(title)} - ${safeName(chapterName(chapter))}.cbz")) }
                        val inner = ZipOutputStream(object : java.io.FilterOutputStream(zip) {
                            override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                            override fun close() = flush()
                        })
                        problems += writeCbz(inner, title, chapter, list)
                        withContext(Dispatchers.IO) { inner.finish(); zip.closeEntry() }
                    }
                }
                if (problems.isNotEmpty()) deflated(zip, "FALTA ALGO.txt", problems.joinToString("\n").toByteArray())
            }
        }
    }

    /** Writes one chapter's pages in order, a few fetched ahead. Returns what could not be fetched. */
    private suspend fun writeCbz(zip: ZipOutputStream, title: String, chapter: ChapterDto, pages: Pair<List<String>, Map<String, String>>): List<String> {
        val (urls, headers) = pages
        val missing = mutableListOf<String>()
        val digits = urls.size.toString().length.coerceAtLeast(3)
        var total = 0L
        coroutineScope {
            val pending = ArrayDeque<Deferred<Pair<ByteArray, String>?>>()
            var next = 0
            fun launchNext() {
                if (next >= urls.size) return
                val url = urls[next++]
                pending += async { fetches.withPermit { retry { proxy.fetchImage(url, headers) } } }
            }
            repeat(PAGES_AHEAD) { launchNext() }
            var index = 0
            while (pending.isNotEmpty()) {
                val image = pending.removeFirst().await()
                launchNext()
                index++
                if (image == null) { missing += "${chapterName(chapter)}: página $index"; continue }
                total += image.first.size
                if (total > MAX_CHAPTER_BYTES) throw IOException("El capítulo es demasiado grande")
                val ext = when (MediaProxy.safeImageType(image.second)) {
                    "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/avif" -> "avif"; else -> "jpg"
                }
                withContext(Dispatchers.IO) { stored(zip, index.toString().padStart(digits, '0') + ".$ext", image.first) }
            }
        }
        val info = """<?xml version="1.0" encoding="utf-8"?>
            |<ComicInfo xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
            |  <Series>${xml(title)}</Series>
            |  <Title>${xml(chapter.name ?: chapter.label)}</Title>
            |  <Number>${chapter.number.toString().removeSuffix(".0")}</Number>
            |  <PageCount>${urls.size}</PageCount>
            |</ComicInfo>""".trimMargin()
        withContext(Dispatchers.IO) { deflated(zip, "ComicInfo.xml", info.toByteArray()) }
        return missing
    }

    // Novels

    private suspend fun novel(call: ApplicationCall, sourceId: String, title: String, coverUrl: String, author: String, chapters: List<ChapterDto>) {
        val firstText = catalog.text(sourceId, chapters.first().url).getOrElse {
            return call.respond(HttpStatusCode.BadGateway, mapOf("error" to (it.message ?: "No se pudo leer el capítulo")))
        }
        val name = if (chapters.size == 1) "$title - ${chapterName(chapters.single())}" else "$title (${chapterName(chapters.first())} - ${chapterName(chapters.last())})"
        val language = if (catalog.languageOf(sourceId) == "EN") "en" else "es"
        attachment(call, "$name.epub")
        call.respondOutputStream(ContentType.parse("application/epub+zip")) {
            ZipOutputStream(this).use { zip ->
                withContext(Dispatchers.IO) {
                    // The EPUB rules: "mimetype" first and uncompressed.
                    stored(zip, "mimetype", "application/epub+zip".toByteArray())
                    deflated(zip, "META-INF/container.xml", CONTAINER.toByteArray())
                }
                val cover = coverUrl.takeIf { it.startsWith("http") }?.let { proxy.fetchImage(it, catalog.imageHeadersOf(sourceId)) }
                val coverExt = cover?.let { if (MediaProxy.safeImageType(it.second) == "image/png") "png" else "jpg" }
                val written = mutableListOf<Pair<String, String>>()
                chapters.forEachIndexed { i, chapter ->
                    val text = if (i == 0) Result.success(firstText) else catalog.text(sourceId, chapter.url)
                    val heading = chapterName(chapter) + (chapter.name?.let { " · $it" } ?: "")
                    val body = text.fold(
                        { t -> t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { "<p>${xml(it)}</p>" } },
                        { "<p>No se pudo descargar este capítulo: ${xml(it.message ?: "error")}</p>" }
                    )
                    val file = "c%04d.xhtml".format(i + 1)
                    written += file to heading
                    withContext(Dispatchers.IO) { deflated(zip, "OEBPS/$file", xhtml(heading, "<h1>${xml(heading)}</h1>\n$body", language).toByteArray()) }
                }
                withContext(Dispatchers.IO) {
                    if (cover != null) stored(zip, "OEBPS/cover.$coverExt", cover.first)
                    deflated(zip, "OEBPS/nav.xhtml", nav(written, language).toByteArray())
                    deflated(zip, "OEBPS/toc.ncx", ncx(name, written).toByteArray())
                    deflated(zip, "OEBPS/content.opf", opf(name, author, language, written, coverExt).toByteArray())
                }
            }
        }
    }

    private fun xhtml(title: String, body: String, language: String) = """<?xml version="1.0" encoding="utf-8"?>
        |<!DOCTYPE html>
        |<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="$language" lang="$language">
        |<head><meta charset="utf-8"/><title>${xml(title)}</title>
        |<style>body{font-family:serif;line-height:1.6;margin:0 5%}h1{font-size:1.3em;margin:1em 0}p{margin:0 0 .9em;text-align:justify}</style></head>
        |<body>
        |$body
        |</body>
        |</html>""".trimMargin()

    private fun nav(chapters: List<Pair<String, String>>, language: String) = xhtml(
        "Índice",
        "<nav epub:type=\"toc\" id=\"toc\"><h1>Índice</h1><ol>\n" +
            chapters.joinToString("\n") { (file, heading) -> "<li><a href=\"$file\">${xml(heading)}</a></li>" } + "\n</ol></nav>",
        language
    )

    private fun ncx(title: String, chapters: List<Pair<String, String>>) = """<?xml version="1.0" encoding="utf-8"?>
        |<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
        |<head><meta name="dtb:uid" content="yomukai"/></head>
        |<docTitle><text>${xml(title)}</text></docTitle>
        |<navMap>
        |${chapters.mapIndexed { i, (file, heading) -> "<navPoint id=\"n${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${xml(heading)}</text></navLabel><content src=\"$file\"/></navPoint>" }.joinToString("\n")}
        |</navMap>
        |</ncx>""".trimMargin()

    private fun opf(title: String, author: String, language: String, chapters: List<Pair<String, String>>, coverExt: String?): String {
        val modified = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
        val coverItem = coverExt?.let { "<item id=\"cover\" href=\"cover.$it\" media-type=\"image/${if (it == "png") "png" else "jpeg"}\" properties=\"cover-image\"/>" }.orEmpty()
        return """<?xml version="1.0" encoding="utf-8"?>
            |<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
            |<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            |<dc:identifier id="uid">urn:uuid:${UUID.randomUUID()}</dc:identifier>
            |<dc:title>${xml(title)}</dc:title>
            |${if (author.isNotBlank()) "<dc:creator>${xml(author)}</dc:creator>" else ""}
            |<dc:language>$language</dc:language>
            |<meta property="dcterms:modified">$modified</meta>
            |${if (coverExt != null) "<meta name=\"cover\" content=\"cover\"/>" else ""}
            |</metadata>
            |<manifest>
            |<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
            |<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
            |$coverItem
            |${chapters.mapIndexed { i, (file, _) -> "<item id=\"c${i + 1}\" href=\"$file\" media-type=\"application/xhtml+xml\"/>" }.joinToString("\n")}
            |</manifest>
            |<spine toc="ncx">
            |${chapters.indices.joinToString("\n") { "<itemref idref=\"c${it + 1}\"/>" }}
            |</spine>
            |</package>""".trimMargin()
    }

    // Episodes

    private suspend fun episode(call: ApplicationCall, user: User, sourceId: String, title: String, episode: ChapterDto) {
        val servers = catalog.rawVideos(sourceId, episode.url).getOrElse {
            return call.respond(HttpStatusCode.BadGateway, mapOf("error" to (it.message ?: "No se pudieron leer los servidores")))
        }.filter { it.isDirect }
        // Plain MP4 first: one file, the right length, and it plays everywhere.
        val ordered = servers.filterNot { isHls(it) } + servers.filter { isHls(it) }
        if (ordered.isEmpty()) return call.respond(HttpStatusCode.Conflict, mapOf("error" to "Este episodio solo se puede ver en la web del servidor; no se puede descargar"))
        if (!proxy.openStream(user)) return call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Demasiados vídeos a la vez"))
        try {
            val name = "$title - ${chapterName(episode)}"
            val committed = java.util.concurrent.atomic.AtomicBoolean(false)
            for (server in ordered) {
                val done = runCatching { if (isHls(server)) hls(call, server, name, committed) else mp4(call, server, name, committed) }
                    .onFailure { log.info("Descarga de {} por {} falló: {}", name, server.serverName, it.message) }
                    .getOrDefault(false)
                // Once the file started, another server cannot take over: the browser reports it as failed.
                if (done || committed.get()) return
            }
            call.respond(HttpStatusCode.BadGateway, mapOf("error" to "Ningún servidor dejó descargar el episodio. Prueba más tarde."))
        } finally {
            proxy.closeStream(user)
        }
    }

    private fun isHls(v: VideoSource) = v.videoUrl.contains("m3u8") || v.quality.contains("HLS", ignoreCase = true)

    private fun request(url: String, headers: Map<String, String>) = Request.Builder().url(url)
        .header("User-Agent", headers["User-Agent"] ?: http.userAgentFor(url.toHttpUrlOrNull()?.host.orEmpty()))
        .apply { headers.filterKeys { it != "User-Agent" }.forEach { (k, v) -> header(k, v) } }
        .build()

    /**
     * Sends an MP4. Video sites hand each connection out slowly, so when the server accepts ranges the
     * file is fetched in pieces over several connections at once and sent in order. False when this
     * server does not give a video.
     */
    private suspend fun mp4(call: ApplicationCall, server: VideoSource, name: String, committed: java.util.concurrent.atomic.AtomicBoolean): Boolean {
        val probe = request(server.videoUrl, server.headers).newBuilder().header(HttpHeaders.Range, "bytes=0-0").build()
        val (rangeOk, length, finalUrl) = withContext(Dispatchers.IO) {
            http.streamClient.newCall(probe).execute().use { r ->
                val type = r.header("Content-Type").orEmpty().lowercase()
                if (!r.isSuccessful || type.startsWith("text/") || "json" in type) return@withContext Triple(false, -1L, "")
                val total = r.header(HttpHeaders.ContentRange)?.substringAfter('/')?.toLongOrNull()
                    ?: r.header(HttpHeaders.ContentLength)?.toLongOrNull()?.takeIf { r.code == 200 } ?: -1L
                Triple(r.code == 206 && total > 0, total, r.request.url.toString())
            }
        }
        if (length == -1L && !rangeOk && finalUrl.isEmpty()) return false
        if (!rangeOk) {
            val response = withContext(Dispatchers.IO) { http.streamClient.newCall(request(server.videoUrl, server.headers)).execute() }
            response.use {
                val type = response.header("Content-Type").orEmpty().lowercase()
                if (!response.isSuccessful || type.startsWith("text/") || "json" in type) return false
                val body = response.body ?: return false
                attachment(call, "$name.mp4")
                committed.set(true)
                call.respondOutputStream(ContentType.parse("video/mp4"), contentLength = response.header(HttpHeaders.ContentLength)?.toLongOrNull()) {
                    withContext(Dispatchers.IO) { body.byteStream().use { it.copyTo(this@respondOutputStream, BUFFER) } }
                }
            }
            return true
        }
        if (length > MAX_VIDEO_BYTES) throw IOException("El vídeo es demasiado grande")
        attachment(call, "$name.mp4")
        committed.set(true)
        call.respondOutputStream(ContentType.parse("video/mp4"), contentLength = length) {
            coroutineScope {
                val pieces = (0 until length step PIECE_BYTES).map { start -> start to minOf(length, start + PIECE_BYTES) - 1 }
                val pending = ArrayDeque<kotlinx.coroutines.channels.Channel<ByteArray>>()
                var next = 0
                fun launchNext() {
                    if (next >= pieces.size) return
                    val (from, to) = pieces[next++]
                    // Each piece hands its bytes over as they arrive: the piece being sent is never waited
                    // for as a whole (the connection would sit idle and be closed), the next ones fill up meanwhile.
                    val out = kotlinx.coroutines.channels.Channel<ByteArray>((PIECE_BYTES / BUFFER).toInt() + 1)
                    pending += out
                    launch(Dispatchers.IO) {
                        var got = 0L
                        var failures = 0
                        fetches.withPermit {
                            while (from + got <= to) {
                                val ok = runCatching {
                                    val req = request(finalUrl, server.headers).newBuilder().header(HttpHeaders.Range, "bytes=${from + got}-$to").build()
                                    http.streamClient.newCall(req).execute().use { r ->
                                        if (r.code != 206) throw IOException("HTTP ${r.code}")
                                        r.body!!.byteStream().use { input ->
                                            val buffer = ByteArray(BUFFER)
                                            // Never more than the piece, whatever the site sends.
                                            while (from + got <= to) {
                                                val read = input.read(buffer, 0, minOf(BUFFER.toLong(), to - from - got + 1).toInt())
                                                if (read < 0) break
                                                out.send(buffer.copyOf(read))
                                                got += read
                                            }
                                        }
                                    }
                                }.isSuccess
                                // A connection that drops is picked up where it stopped, a few times.
                                if (!ok && ++failures > 3) { out.close(IOException("Falta un trozo del vídeo")); return@withPermit }
                                if (!ok) kotlinx.coroutines.delay(500L * failures)
                            }
                            out.close()
                        }
                    }
                }
                repeat(PIECES_AHEAD) { launchNext() }
                while (pending.isNotEmpty()) {
                    for (chunk in pending.removeFirst()) withContext(Dispatchers.IO) { write(chunk) }
                    launchNext()
                }
            }
        }
        return true
    }

    /** Joins an HLS stream's segments (decrypting AES-128 ones) into one file. */
    private suspend fun hls(call: ApplicationCall, server: VideoSource, name: String, committed: java.util.concurrent.atomic.AtomicBoolean): Boolean {
        val headers = server.headers
        var playlistUrl = server.videoUrl
        var text = fetchText(playlistUrl, headers) ?: return false
        if ("#EXT-X-STREAM-INF" in text) {
            // A list of qualities: the best one.
            val lines = text.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .maxByOrNull { Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0 }
                ?.let { i -> lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } } ?: return false
            playlistUrl = resolve(playlistUrl, best) ?: return false
            text = fetchText(playlistUrl, headers) ?: return false
        }
        val plan = HlsPlan.parse(text, playlistUrl) ?: return false
        val keys = ConcurrentHashMap<String, ByteArray>()
        attachment(call, name + if (plan.init != null) ".mp4" else ".ts")
        committed.set(true)
        call.respondOutputStream(ContentType.parse(if (plan.init != null) "video/mp4" else "video/mp2t")) {
            plan.init?.let { init -> fetchBytes(init, headers)?.let { withContext(Dispatchers.IO) { write(it) } } ?: throw IOException("Sin cabecera de vídeo") }
            var total = 0L
            coroutineScope {
                val pending = ArrayDeque<Deferred<ByteArray>>()
                var next = 0
                fun launchNext() {
                    if (next >= plan.segments.size) return
                    val segment = plan.segments[next++]
                    pending += async {
                        val raw = fetches.withPermit { retry { fetchBytes(segment.url, headers) } } ?: throw IOException("Falta un trozo del vídeo")
                        val key = segment.keyUrl?.let { url -> keys[url] ?: (fetchBytes(url, headers)?.also { keys[url] = it } ?: throw IOException("Sin clave del vídeo")) }
                        if (key == null) raw else decrypt(raw, key, segment.iv)
                    }
                }
                repeat(SEGMENTS_AHEAD) { launchNext() }
                while (pending.isNotEmpty()) {
                    val bytes = pending.removeFirst().await()
                    launchNext()
                    total += bytes.size
                    if (total > MAX_VIDEO_BYTES) throw IOException("El vídeo es demasiado grande")
                    withContext(Dispatchers.IO) { write(bytes) }
                }
            }
        }
        return true
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private suspend fun fetchText(url: String, headers: Map<String, String>): String? = withContext(Dispatchers.IO) {
        http.streamClient.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.let { readBounded(it, MAX_PLAYLIST_CHARS.toLong()) }?.toString(Charsets.UTF_8)?.takeIf { it.trimStart().startsWith("#EXTM3U") }
        }
    }

    private suspend fun fetchBytes(url: String, headers: Map<String, String>): ByteArray? = withContext(Dispatchers.IO) {
        http.streamClient.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) null else r.body?.let { readBounded(it, MAX_SEGMENT_BYTES.toLong()) }
        }
    }

    private suspend fun <T> retry(block: suspend () -> T?): T? {
        repeat(3) { attempt ->
            runCatching { block() }.getOrNull()?.let { return it }
            if (attempt < 2) kotlinx.coroutines.delay(500L * (attempt + 1))
        }
        return null
    }

    // Zip helpers

    private fun stored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        zip.putNextEntry(ZipEntry(name).apply {
            method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = bytes.size.toLong(); this.crc = crc.value
        })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun deflated(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun attachment(call: ApplicationCall, fileName: String) {
        val name = safeName(fileName)
        val ascii = name.map { if (it.code in 32..126 && it != '"' && it != '\\') it else '_' }.joinToString("")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded")
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger("YomukaiDownloads")
        const val MAX_CHAPTERS = 100
        const val GUEST_MAX_CHAPTERS = 3
        private const val MAX_PARALLEL_FETCHES = 32
        private const val PAGES_AHEAD = 8
        private const val SEGMENTS_AHEAD = 8
        private const val PIECE_BYTES = 4L * 1024 * 1024
        private const val PIECES_AHEAD = 10
        private const val BUFFER = 64 * 1024
        private const val MAX_CHAPTER_BYTES = 400L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 6L * 1024 * 1024 * 1024
        private const val MAX_SEGMENT_BYTES = 64 * 1024 * 1024
        private const val MAX_PLAYLIST_CHARS = 4 * 1024 * 1024

        private val CONTAINER = """<?xml version="1.0" encoding="utf-8"?>
            |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            |<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            |</container>""".trimMargin()

        fun chapterName(c: ChapterDto) = c.label.ifBlank { c.title }

        /** A name every file system accepts. */
        fun safeName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(150).ifEmpty { "Yomukai" }

        /** Text for XML, without the control characters XML forbids. */
        fun xml(text: String): String = text.filter { it == '\n' || it == '\t' || it.code >= 0x20 }
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

        fun resolve(base: String, uri: String): String? = base.toHttpUrlOrNull()?.resolve(uri.trim())?.toString()
    }
}

/** An HLS media playlist reduced to what a download needs. */
data class HlsPlan(val init: String?, val segments: List<Segment>) {
    data class Segment(val url: String, val keyUrl: String?, val iv: ByteArray)

    companion object {
        /** Null for what cannot be joined into a file (byte ranges, sample encryption, DRM). */
        fun parse(text: String, base: String): HlsPlan? {
            var keyUrl: String? = null
            var explicitIv: ByteArray? = null
            var init: String? = null
            var sequence = 0L
            val segments = mutableListOf<Segment>()
            for (raw in text.lines()) {
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                    line.startsWith("#EXT-X-BYTERANGE") -> return null
                    line.startsWith("#EXT-X-MAP") -> {
                        if ("BYTERANGE" in line) return null
                        init = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { Downloader.resolve(base, it) }
                    }
                    line.startsWith("#EXT-X-KEY") -> {
                        val method = Regex("METHOD=([A-Z0-9-]+)").find(line)?.groupValues?.get(1)
                        when (method) {
                            "NONE" -> { keyUrl = null; explicitIv = null }
                            "AES-128" -> {
                                keyUrl = Regex("URI=\"([^\"]+)\"").find(line)?.groupValues?.get(1)?.let { Downloader.resolve(base, it) } ?: return null
                                explicitIv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)?.let { hex ->
                                    hex.padStart(32, '0').takeLast(32).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                }
                            }
                            else -> return null
                        }
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        val url = Downloader.resolve(base, line) ?: return null
                        // Without an IV in the playlist, AES-128 uses the segment's sequence number.
                        val iv = explicitIv ?: ByteArray(16).also { b -> var n = sequence; for (i in 15 downTo 8) { b[i] = (n and 0xff).toByte(); n = n shr 8 } }
                        segments += Segment(url, keyUrl, iv)
                        sequence++
                    }
                }
            }
            return if (segments.isEmpty()) null else HlsPlan(init, segments)
        }
    }
}

/**
 * A response body, or null when it is bigger than [max]: the declared length is checked first and the
 * reading stops as soon as the limit is passed, so a huge or endless answer never fills the memory.
 */
fun readBounded(body: okhttp3.ResponseBody, max: Long): ByteArray? {
    if (body.contentLength() > max) return null
    val out = java.io.ByteArrayOutputStream(body.contentLength().takeIf { it in 1..max }?.toInt() ?: 64 * 1024)
    body.byteStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > max) return null
            out.write(buffer, 0, read)
        }
    }
    return out.toByteArray()
}
