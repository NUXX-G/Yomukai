package com.nelson.yomukai.data.download

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.prefs.AppPreferences
import com.nelson.yomukai.data.prefs.AppSettings
import com.nelson.yomukai.data.prefs.ComicFormat
import com.nelson.yomukai.data.prefs.NovelFormat
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** An episode that can be played without network. [url] is a file URI, or the HLS url keyed in the Media3 cache. */
data class LocalVideo(val url: String, val isHls: Boolean, val referer: String)

/**
 * Persistent download queue for chapter images, novel text and anime episodes.
 *
 * - State lives in Room, so the queue survives process death; rows left in DOWNLOADING are resumed.
 * - Up to `maxConcurrentDownloads` run at once and nothing starts on a metered connection when
 *   "Wi-Fi only" is on. All traffic goes through the app's OkHttp client (DoH / proxy / Tor).
 * - Security rules live in [DownloadSecurity]: HTTPS only, content-type and size checks, files only
 *   in app-private storage under hashed names, and a completeness check before a row is COMPLETED.
 * - Interrupted pages are skipped on retry and MP4 files resume with HTTP Range; HLS goes through
 *   Media3's DownloadManager/DownloadService.
 */
@OptIn(UnstableApi::class)
class DownloadManager(
    private val context: Context,
    db: YomukaiDatabase,
    private val sources: SourceManager,
    private val network: NetworkHelper,
    private val scope: CoroutineScope,
    private val preferences: AppPreferences,
    private val videoOffline: VideoOffline
) {
    private val dao = db.downloadDao()
    private val mediaDao = db.mediaDao()
    private val chapterDao = db.chapterDao()
    private val exporter = PublicExporter(context)
    private val root = File(context.filesDir, "downloads")
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val running = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch(Dispatchers.IO) { workerLoop() }
    }

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    fun observeForMedia(sourceId: String, mediaId: String) = dao.observeForMedia(sourceId, mediaId)

    suspend fun enqueue(media: MediaEntity, chapters: List<ChapterEntity>) {
        val source = sources.get(media.sourceId)
        val isNovel = source is NovelSource
        val isVideo = source is AnimeSource
        chapters.forEach { chapter ->
            val existing = dao.get(chapter.sourceId, chapter.chapterId)
            if (existing?.status == DownloadStatus.FAILED) {
                dao.updateState(chapter.sourceId, chapter.chapterId, DownloadStatus.QUEUED, 0, 0, null)
            } else if (existing == null) {
                dao.insert(
                    DownloadEntity(
                        sourceId = chapter.sourceId,
                        chapterId = chapter.chapterId,
                        mediaId = chapter.mediaId,
                        mediaTitle = media.title,
                        chapterTitle = chapter.title,
                        chapterUrl = chapter.url,
                        isNovel = isNovel,
                        isVideo = isVideo,
                        status = DownloadStatus.QUEUED,
                        localPath = chapterDir(chapter.sourceId, chapter.mediaId, chapter.chapterId).absolutePath
                    )
                )
            }
        }
        wakeUp.trySend(Unit)
    }

    /** Requeues a download; for an episode, [server] (when given) is tried before the others. */
    suspend fun retry(sourceId: String, chapterId: String, server: String? = null) {
        if (server != null) dao.setVideoServer(sourceId, chapterId, server)
        dao.updateState(sourceId, chapterId, DownloadStatus.QUEUED, 0, 0, null)
        wakeUp.trySend(Unit)
    }

    /** Cancels a queued/running download or deletes a finished one, removing its files. */
    suspend fun delete(sourceId: String, chapterId: String) = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId) ?: return@withContext
        dao.delete(sourceId, chapterId)
        removeFiles(row)
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.all().forEach { dao.delete(it.sourceId, it.chapterId) }
        videoOffline.downloadManager.removeAllDownloads()
        // The Media3 cache is cleaned by removeAllDownloads; everything else is plain files.
        root.listFiles()?.filter { it.name != "hls_cache" }?.forEach { it.deleteRecursively() }
    }

    private fun removeFiles(row: DownloadEntity) {
        if (row.isHls && row.videoUrl.isNotBlank()) videoOffline.downloadManager.removeDownload(row.videoUrl)
        val dir = File(row.localPath)
        // Rows are trusted app data, but never delete outside our own directory.
        if (DownloadSecurity.isInside(root, dir) && dir != root) dir.deleteRecursively()
    }

    /** Offline page files for a completed manga download, or null when not downloaded. */
    suspend fun localPages(sourceId: String, chapterId: String): List<File>? = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId)
            ?.takeIf { it.status == DownloadStatus.COMPLETED && !it.isNovel && !it.isVideo }
            ?: return@withContext null
        val dir = File(row.localPath).takeIf { DownloadSecurity.isInside(root, it) } ?: return@withContext null
        dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun localText(sourceId: String, chapterId: String): String? = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId)?.takeIf { it.status == DownloadStatus.COMPLETED && it.isNovel }
            ?: return@withContext null
        val file = File(row.localPath, NOVEL_FILE).takeIf { DownloadSecurity.isInside(root, it) && it.exists() }
        file?.readText()
    }

    /** The downloaded copy of an episode, or null when it has to be streamed. */
    suspend fun localVideo(sourceId: String, chapterId: String): LocalVideo? = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId)?.takeIf { it.status == DownloadStatus.COMPLETED && it.isVideo }
            ?: return@withContext null
        if (row.isHls) return@withContext LocalVideo(row.videoUrl, true, row.videoReferer)
        val file = File(row.localPath, VIDEO_FILE).takeIf { DownloadSecurity.isInside(root, it) && it.exists() }
            ?: return@withContext null
        LocalVideo(Uri.fromFile(file).toString(), false, "")
    }

    fun storageBytes(): Long =
        root.walkTopDown().filter { it.isFile }.sumOf { it.length() } // includes the HLS cache under downloads/

    // --- worker -----------------------------------------------------------------------------

    private fun networkAllowed(): Boolean {
        if (!preferences.app.value.downloadsWifiOnly) return true
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private suspend fun workerLoop() {
        while (true) {
            val limit = preferences.app.value.maxConcurrentDownloads.coerceIn(1, MAX_CONCURRENT)
            val next = if (running.size < limit && networkAllowed()) {
                dao.pending().firstOrNull { key(it) !in running }
            } else null
            if (next == null) {
                // Idle, saturated or waiting for Wi-Fi: wake on new work or re-check shortly.
                withTimeoutOrNull(POLL_MS) { wakeUp.receive() }
                continue
            }
            running += key(next)
            scope.launch(Dispatchers.IO) {
                try {
                    run(next)
                } finally {
                    running -= key(next)
                    wakeUp.trySend(Unit)
                }
            }
        }
    }

    private fun key(d: DownloadEntity) = "${d.sourceId}|${d.chapterId}"

    private suspend fun run(download: DownloadEntity) {
        try {
            process(download)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Descarga fallida ${download.chapterTitle}", e)
            if (dao.get(download.sourceId, download.chapterId) != null) {
                // Paused by "Wi-Fi only": back to the queue, it resumes from its partial file on Wi-Fi.
                if (!networkAllowed()) {
                    dao.updateState(download.sourceId, download.chapterId, DownloadStatus.QUEUED, download.progress, download.total, null)
                    return
                }
                val message = when (e) {
                    is HttpException -> "Error HTTP ${e.code}"
                    else -> e.message ?: "Error desconocido"
                }
                dao.updateState(download.sourceId, download.chapterId, DownloadStatus.FAILED, download.progress, download.total, message)
            }
        }
    }

    private suspend fun process(download: DownloadEntity) {
        val source = sources.require(download.sourceId)
        val dir = File(download.localPath)
        require(DownloadSecurity.isInside(root, dir) && dir != root) { "Ruta de descarga no válida" }
        dir.mkdirs()
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, download.progress, download.total, null)

        when (source) {
            is NovelSource -> downloadNovel(download, source, dir)
            is MangaSource -> downloadImages(download, source, dir)
            is AnimeSource -> downloadEpisode(download, source, dir)
            else -> error("La fuente no admite descargas")
        }
    }

    private suspend fun downloadNovel(download: DownloadEntity, source: NovelSource, dir: File) {
        val text = source.fetchChapterText(download.chapterUrl).getOrThrow()
        val bytes = text.toByteArray(Charsets.UTF_8)
        DownloadSecurity.checkDeclaredSize(bytes.size.toLong(), DownloadSecurity.MAX_TEXT_BYTES)
        check(bytes.isNotEmpty()) { "El capítulo está vacío" }
        val tmp = File(dir, "$NOVEL_FILE.tmp")
        tmp.writeBytes(bytes)
        check(tmp.renameTo(File(dir, NOVEL_FILE))) { "No se pudo guardar el capítulo" }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, 1, 1, null)
        export(download)
    }

    private suspend fun downloadImages(download: DownloadEntity, source: MangaSource, dir: File) {
        val pages = source.fetchPageList(download.chapterUrl).getOrThrow()
        check(pages.isNotEmpty()) { "El capítulo no tiene páginas" }
        val next = AtomicInteger(0)
        val finished = AtomicInteger(0)
        coroutineScope {
            repeat(PARALLEL_PAGES) {
                launch(Dispatchers.IO) {
                    while (true) {
                        val index = next.getAndIncrement()
                        if (index >= pages.size) break
                        // Stop if the user cancelled while we were downloading.
                        if (dao.get(download.sourceId, download.chapterId) == null) return@launch
                        while (!networkAllowed()) {
                            delay(POLL_MS)
                            if (dao.get(download.sourceId, download.chapterId) == null) return@launch
                        }
                        downloadPage(pages[index], index, dir, source.imageHeaders)
                        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, finished.incrementAndGet(), pages.size, null)
                    }
                }
            }
        }
        if (dao.get(download.sourceId, download.chapterId) == null) {
            dir.deleteRecursively()
            return
        }
        // Completeness: every page must be on disk before the chapter counts as downloaded.
        val onDisk = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.size ?: 0
        check(onDisk >= pages.size) { "Descarga incompleta ($onDisk de ${pages.size} páginas)" }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, pages.size, pages.size, null)
        export(download)
    }

    private fun downloadPage(url: String, index: Int, dir: File, headers: Map<String, String>) {
        val prefix = "%04d".format(index)
        if (dir.listFiles()?.any { it.name.startsWith("$prefix.") && !it.name.endsWith(".tmp") } == true) return

        val request = Request.Builder().url(DownloadSecurity.requireSecureUrl(url))
            .apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val body = response.body ?: error("Respuesta vacía")
            val contentType = response.header("Content-Type")
            if (!DownloadSecurity.isImageType(contentType)) {
                throw IOException("La página ${index + 1} no es una imagen ($contentType)")
            }
            val declared = body.contentLength()
            DownloadSecurity.checkDeclaredSize(declared, DownloadSecurity.MAX_IMAGE_BYTES)
            val tmp = File(dir, "$prefix.tmp")
            val written = copyLimited(body.byteStream(), tmp, DownloadSecurity.MAX_IMAGE_BYTES)
            if (!DownloadSecurity.isComplete(declared, written)) {
                tmp.delete()
                throw IOException("La página ${index + 1} se descargó incompleta")
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete()
                throw IOException("La página ${index + 1} no es una imagen válida")
            }
            val name = "$prefix.${DownloadSecurity.imageExtension(contentType)}"
            if (!tmp.renameTo(File(dir, DownloadSecurity.sanitizeFileName(name)))) error("No se pudo guardar la página")
        }
    }

    // --- anime ------------------------------------------------------------------------------

    /**
     * Streams are extracted right here, just before downloading, because their URLs carry tokens that
     * expire. Every downloadable server is tried in turn (the user's pick first); each one is checked
     * with a small request before committing to it, and the failure of each is reported.
     */
    private suspend fun downloadEpisode(download: DownloadEntity, source: AnimeSource, dir: File) {
        val servers = source.fetchVideoSources(download.chapterUrl).getOrThrow()
        // Two rounds: a failure is often momentary, and the next try of a server may well work.
        val candidates = EpisodeServers.candidates(servers, download.videoServer).let { it + it }
        val browserOnly = EpisodeServers.browserOnly(servers)
        val failures = mutableListOf<Pair<String, String>>()
        for (server in candidates) {
            if (dao.get(download.sourceId, download.chapterId) == null) return
            val url = server.videoUrl
            val hls = url.contains("m3u8", ignoreCase = true)
            try {
                if (hls) probeHls(url, server.headers)
                dao.upsert(
                    download.copy(
                        isVideo = true, isHls = hls, videoUrl = url, videoReferer = server.headers["Referer"].orEmpty(),
                        videoServer = server.serverName, status = DownloadStatus.DOWNLOADING
                    )
                )
                if (hls) downloadHls(download, url, server.headers) else downloadMp4(download, url, server.headers, dir, server.serverName)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!networkAllowed()) throw e // not the server's fault: wait for Wi-Fi instead of trying the next one
                Log.w(TAG, "Servidor ${server.serverName} no se pudo descargar: $url", e)
                if (failures.none { it.first == server.serverName }) failures += server.serverName to EpisodeServers.reason(e)
                if (hls) runCatching { videoOffline.downloadManager.removeDownload(url) }
            }
        }
        throw IOException(EpisodeServers.failureMessage(failures, browserOnly))
    }

    /** Fetches the playlist (and the variant) and the first piece of media, so a blocked server fails in seconds. */
    private fun probeHls(url: String, headers: Map<String, String>) {
        var playlistUrl = url
        var playlist = fetchText(playlistUrl, headers)
        if (!playlist.trimStart().startsWith("#EXTM3U")) throw IOException("la lista de reproducción no es válida")
        EpisodeServers.hlsFirstVariant(playlist, playlistUrl)?.let { variant ->
            playlistUrl = variant
            playlist = fetchText(variant, headers)
        }
        val first = EpisodeServers.hlsFirstSegment(playlist, playlistUrl) ?: throw IOException("la lista de reproducción está vacía")
        val request = Request.Builder().url(DownloadSecurity.requireSecureUrl(first))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=0-1023")
            .build()
        network.videoClient.newCall(request).execute().use { if (!it.isSuccessful) throw HttpException(it.code, first) }
    }

    private fun fetchText(url: String, headers: Map<String, String>): String {
        val request = Request.Builder().url(DownloadSecurity.requireSecureUrl(url))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()
        return network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val body = response.body ?: throw IOException("Respuesta vacía")
            DownloadSecurity.checkDeclaredSize(body.contentLength(), DownloadSecurity.MAX_TEXT_BYTES)
            body.string()
        }
    }

    private suspend fun downloadHls(download: DownloadEntity, url: String, headers: Map<String, String>) {
        val manager = videoOffline.downloadManager
        videoOffline.registerHeaders(url, headers)
        val request = DownloadRequest.Builder(url, Uri.parse(url)).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        try {
            DownloadService.sendAddDownload(context, VideoDownloadService::class.java, request, /* foreground = */ true)
        } catch (e: Exception) {
            // Starting a foreground service from the background can be refused; the manager works without it.
            manager.addDownload(request)
        }
        manager.resumeDownloads()
        while (true) {
            delay(1_000)
            if (dao.get(download.sourceId, download.chapterId) == null) {
                manager.removeDownload(url)
                return
            }
            if (networkAllowed()) manager.resumeDownloads() else manager.pauseDownloads()
            val current = manager.downloadIndex.getDownload(url)
            when (current?.state) {
                Download.STATE_COMPLETED -> break
                Download.STATE_FAILED -> throw IOException("la descarga se interrumpió en el servidor")
                else -> {
                    val percent = current?.percentDownloaded?.takeIf { it >= 0 }?.toInt() ?: 0
                    dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, percent, 100, null)
                }
            }
        }
        val finished = manager.downloadIndex.getDownload(url)
        check(finished != null && finished.percentDownloaded >= 99.9f && finished.bytesDownloaded > 0) { "Descarga HLS incompleta" }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, 100, 100, null)
    }

    /** Single-file download that resumes from its `.part` file with an HTTP Range request. */
    private suspend fun downloadMp4(download: DownloadEntity, url: String, headers: Map<String, String>, dir: File, server: String) {
        // One partial file per server: each serves its own encode, so bytes must never be mixed.
        val part = File(dir, DownloadSecurity.sanitizeFileName("$VIDEO_FILE.$server.part"))
        val finalFile = File(dir, VIDEO_FILE)
        val chunked = try {
            downloadInChunks(download, url, headers, part)
        } catch (e: HttpException) {
            // Some hosts refuse several connections at once: start over with a single one.
            part.delete()
            File(part.path + ".chunks").delete()
            false
        }
        if (chunked) return
        val resumeFrom = part.takeIf { it.exists() }?.length() ?: 0L

        val request = Request.Builder().url(DownloadSecurity.requireSecureUrl(url))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .apply { if ("Referer" !in headers) header("Referer", url) }
            .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
            .build()
        network.videoClient.newCall(request).execute().use { response ->
            if (response.code == 416) { // our partial file does not match the server: start over
                part.delete()
                throw IOException("Reanudación rechazada, se reintentará desde cero")
            }
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val body = response.body ?: error("Respuesta vacía")
            val contentType = response.header("Content-Type")
            if (!DownloadSecurity.isVideoType(contentType)) throw IOException("El servidor no devolvió un vídeo ($contentType)")

            val resumed = response.code == 206
            val offset = if (resumed) resumeFrom else 0L
            val total = if (resumed) {
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
            } else body.contentLength()
            DownloadSecurity.checkDeclaredSize(total, DownloadSecurity.MAX_VIDEO_BYTES)

            var lastPercent = -1
            var lastCheck = System.currentTimeMillis()
            java.io.RandomAccessFile(part, "rw").use { out ->
                if (resumed) out.seek(offset) else out.setLength(0)
                val buffer = ByteArray(BUFFER_BYTES)
                var written = offset
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        written += read
                        if (written > DownloadSecurity.MAX_VIDEO_BYTES) throw IOException("El vídeo supera el tamaño máximo")
                        // Works without a declared size too: cancelling and "Wi-Fi only" apply mid-file.
                        val now = System.currentTimeMillis()
                        if (now - lastCheck >= CHECK_MS) {
                            lastCheck = now
                            if (dao.get(download.sourceId, download.chapterId) == null) {
                                part.delete()
                                return
                            }
                            if (!networkAllowed()) throw IOException("Esperando Wi-Fi")
                        }
                        if (total > 0) {
                            val percent = (written * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, percent, 100, null)
                            }
                        }
                    }
                }
            }
            val size = part.length()
            if (!DownloadSecurity.isComplete(total, size)) throw IOException("Descarga incompleta ($size de $total bytes)")
            val header = ByteArray(16).also { buf -> part.inputStream().use { it.read(buf) } }
            if (!DownloadSecurity.looksLikeMp4(header)) {
                part.delete()
                throw IOException("El archivo descargado no es un vídeo MP4 válido")
            }
        }
        check(part.renameTo(finalFile)) { "No se pudo guardar el vídeo" }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, 100, 100, null)
        export(download)
    }

    /**
     * Files of 32 MB or more are fetched in chunks over several connections, which is what speeds up
     * hosts that throttle each connection. Returns false when the server does not allow it, and the
     * regular single download takes over.
     */
    private suspend fun downloadInChunks(download: DownloadEntity, url: String, headers: Map<String, String>, part: File): Boolean {
        fun request(from: Long, to: Long) = Request.Builder().url(DownloadSecurity.requireSecureUrl(url))
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .apply { if ("Referer" !in headers) header("Referer", url) }
            .header("Range", "bytes=$from-$to")
            .build()

        val total = network.videoClient.newCall(request(0, 0)).execute().use { response ->
            if (response.code != 206 || !DownloadSecurity.isVideoType(response.header("Content-Type"))) return false
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: return false
        }
        if (total < MIN_CHUNKED_BYTES) return false
        DownloadSecurity.checkDeclaredSize(total, DownloadSecurity.MAX_VIDEO_BYTES)

        val progress = File(part.path + ".chunks")
        val done = ConcurrentHashMap.newKeySet<Int>()
        if (part.length() == total && progress.exists()) progress.readLines().mapNotNullTo(done) { it.toIntOrNull() }
        else progress.delete()
        java.io.RandomAccessFile(part, "rw").use { it.setLength(total) }

        val chunks = ((total - 1) / CHUNK_BYTES + 1).toInt()
        val next = AtomicInteger(0)
        val finished = AtomicInteger(done.size)
        val stopped = AtomicBoolean(false)
        var lastPercent = -1
        coroutineScope {
            repeat(PARALLEL_CHUNKS) {
                launch(Dispatchers.IO) {
                    java.io.RandomAccessFile(part, "rw").use { out ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (!stopped.get()) {
                            val index = next.getAndIncrement()
                            if (index >= chunks) break
                            if (index in done) continue
                            if (dao.get(download.sourceId, download.chapterId) == null) {
                                stopped.set(true)
                                break
                            }
                            if (!networkAllowed()) throw IOException("Esperando Wi-Fi")
                            val from = index * CHUNK_BYTES
                            val to = minOf(from + CHUNK_BYTES, total) - 1
                            var attempt = 0
                            while (true) {
                                try {
                                    network.videoClient.newCall(request(from, to)).execute().use { response ->
                                        if (response.code != 206) throw HttpException(response.code, url)
                                        val input = response.body?.byteStream() ?: throw IOException("Respuesta vacía")
                                        var position = from
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            synchronized(out) {
                                                out.seek(position)
                                                out.write(buffer, 0, read)
                                            }
                                            position += read
                                        }
                                        if (position != to + 1) throw IOException("Bloque incompleto")
                                    }
                                    break
                                } catch (e: IOException) {
                                    if (++attempt >= CHUNK_ATTEMPTS) throw e
                                    delay(500L * attempt)
                                }
                            }
                            synchronized(progress) { progress.appendText("$index\n") }
                            done += index
                            val percent = finished.incrementAndGet() * 100 / chunks
                            if (percent != lastPercent) {
                                lastPercent = percent
                                dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, percent, 100, null)
                            }
                        }
                    }
                }
            }
        }
        if (stopped.get()) {
            part.delete()
            progress.delete()
            return true
        }
        progress.delete()
        val header = ByteArray(16).also { buf -> part.inputStream().use { it.read(buf) } }
        if (!DownloadSecurity.looksLikeMp4(header)) {
            part.delete()
            throw IOException("El archivo descargado no es un vídeo MP4 válido")
        }
        check(part.renameTo(File(part.parentFile, VIDEO_FILE))) { "No se pudo guardar el vídeo" }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, 100, 100, null)
        export(download)
        return true
    }

    // --- shared storage -----------------------------------------------------------------------

    /** Copies everything already downloaded; returns how many chapters it went through. */
    suspend fun exportAll(): Int = withContext(Dispatchers.IO) {
        val done = dao.all().filter { it.status == DownloadStatus.COMPLETED }
        done.forEach { export(it, force = true) }
        done.size
    }

    /** Never fails the download: the private copy is what the app reads. */
    private suspend fun export(download: DownloadEntity, force: Boolean = false) {
        val settings = preferences.app.value
        if (!settings.exportDownloads && !force) return
        runCatching { exportChapter(download, settings) }
            .onFailure { Log.w(TAG, "No se pudo copiar ${download.chapterTitle} a Descargas", it) }
    }

    private suspend fun exportChapter(download: DownloadEntity, settings: AppSettings) {
        val row = dao.get(download.sourceId, download.chapterId) ?: download
        val media = mediaDao.get(row.sourceId, row.mediaId)
        val chapter = chapterDao.get(row.sourceId, row.chapterId)
        val type = MediaType.fromName(media?.type)
        val title = media?.title ?: row.mediaTitle
        val series = DownloadSecurity.sanitizeFileName(ExportNaming.seriesName(title), "Sin título")
        val number = ExportNaming.number(chapter?.number ?: 0f)
        val base = when {
            row.isVideo -> "Anime/$series"
            row.isNovel -> "Novelas/$series"
            else -> "${DownloadSecurity.sanitizeFileName(type.displayName)}/$series"
        }
        val dir = File(row.localPath)
        exportSeriesInfo(base, title, media, row)
        when {
            row.isHls -> return // lives in the player's cache as loose segments, not as a video file
            row.isVideo -> {
                val season = ExportNaming.season(title)
                val name = DownloadSecurity.sanitizeFileName("$series - S%02dE$number.mp4".format(season))
                exporter.save("$base/Temporada $season", name, "video/mp4") { out ->
                    File(dir, VIDEO_FILE).inputStream().use { it.copyTo(out) }
                }
            }
            row.isNovel -> {
                val text = File(dir, NOVEL_FILE).readText()
                val name = DownloadSecurity.sanitizeFileName("$series - Capítulo $number")
                when (settings.novelFormat) {
                    NovelFormat.TXT -> exporter.save(base, "$name.txt", "text/plain") { it.write(text.toByteArray()) }
                    NovelFormat.EPUB -> exporter.save(base, "$name.epub", PublicExporter.BINARY) { out ->
                        ComicArchive.epub(out, "$title - ${row.chapterTitle}", media?.author.orEmpty(), row.chapterTitle, text)
                    }
                }
            }
            else -> {
                val pages = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedBy { it.name }.orEmpty()
                val name = DownloadSecurity.sanitizeFileName("$series - Capítulo $number")
                when (settings.comicFormat) {
                    ComicFormat.CBZ -> exporter.save(base, "$name.cbz", PublicExporter.BINARY) { out ->
                        ComicArchive.cbz(out, pages, ComicArchive.comicInfo(title, row.chapterTitle, number, media, pages.size))
                    }
                    ComicFormat.IMAGES -> pages.forEach { page ->
                        exporter.save("$base/$name", page.name, "image/*") { out -> page.inputStream().use { it.copyTo(out) } }
                    }
                }
            }
        }
    }

    /** `info.nfo` and the cover next to the chapters: what media libraries such as Kodi or Jellyfin read. */
    private fun exportSeriesInfo(base: String, title: String, media: MediaEntity?, row: DownloadEntity) {
        exporter.save(base, "tvshow.nfo", PublicExporter.BINARY) { it.write(ComicArchive.seriesInfo(title, media, row).toByteArray()) }
        val cover = media?.coverUrl?.takeIf { it.startsWith("https://") } ?: return
        runCatching {
            exporter.save(base, "poster.jpg", "image/jpeg") { out ->
                val headers = sources.get(row.sourceId)?.imageHeaders.orEmpty()
                val request = Request.Builder().url(cover).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
                network.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw HttpException(response.code, cover)
                    response.body?.byteStream()?.copyTo(out)
                }
            }
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    /** Copies at most [limit] bytes; throws instead of silently truncating a runaway response. */
    private fun copyLimited(input: java.io.InputStream, target: File, limit: Long): Long {
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) {
                    target.delete()
                    throw IOException("El archivo supera el tamaño máximo permitido")
                }
                out.write(buffer, 0, read)
            }
        }
        return total
    }

    private fun chapterDir(sourceId: String, mediaId: String, chapterId: String) =
        File(root, "${DownloadSecurity.sanitizeFileName(sourceId)}/${hash(mediaId)}/${hash(chapterId)}")

    private fun hash(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "YomukaiDownloads"
        const val NOVEL_FILE = "chapter.txt"
        const val VIDEO_FILE = "video.mp4"
        const val POLL_MS = 5_000L
        const val CHECK_MS = 1_000L
        const val PARALLEL_PAGES = 6
        const val PARALLEL_CHUNKS = 4
        const val CHUNK_ATTEMPTS = 4
        const val CHUNK_BYTES = 4L * 1024 * 1024
        const val MIN_CHUNKED_BYTES = 32L * 1024 * 1024
        const val BUFFER_BYTES = 256 * 1024
        const val MAX_CONCURRENT = 4
    }
}
