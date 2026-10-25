package com.nelson.yomukai.data.download

import android.content.Context
import android.util.Log
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.DownloadEntity
import com.nelson.yomukai.data.local.DownloadStatus
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.network.HttpException
import com.nelson.yomukai.data.network.NetworkHelper
import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.MangaSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Sequential, persistent download queue. State lives in Room, so the queue survives process
 * death: pending rows are picked up again on the next app start.
 */
class DownloadManager(
    context: Context,
    db: YomukaiDatabase,
    private val sources: SourceManager,
    private val network: NetworkHelper,
    scope: CoroutineScope
) {
    private val dao = db.downloadDao()
    private val root = File(context.filesDir, "downloads")
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) { workerLoop() }
    }

    fun observeAll(): Flow<List<DownloadEntity>> = dao.observeAll()

    fun observeForMedia(sourceId: String, mediaId: String) = dao.observeForMedia(sourceId, mediaId)

    suspend fun enqueue(media: MediaEntity, chapters: List<ChapterEntity>) {
        if (sources.get(media.sourceId) is AnimeSource) return // streams are not downloadable
        val isNovel = sources.get(media.sourceId) is NovelSource
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
                        status = DownloadStatus.QUEUED,
                        localPath = chapterDir(chapter.sourceId, chapter.mediaId, chapter.chapterId).absolutePath
                    )
                )
            }
        }
        wakeUp.trySend(Unit)
    }

    suspend fun retry(sourceId: String, chapterId: String) {
        dao.updateState(sourceId, chapterId, DownloadStatus.QUEUED, 0, 0, null)
        wakeUp.trySend(Unit)
    }

    /** Cancels a queued/running download or deletes a finished one, removing its files. */
    suspend fun delete(sourceId: String, chapterId: String) = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId) ?: return@withContext
        dao.delete(sourceId, chapterId)
        File(row.localPath).deleteRecursively()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dao.all().forEach { dao.delete(it.sourceId, it.chapterId) }
        root.deleteRecursively()
    }

    /** Offline page files for a completed manga download, or null when not downloaded. */
    suspend fun localPages(sourceId: String, chapterId: String): List<File>? = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId)?.takeIf { it.status == DownloadStatus.COMPLETED && !it.isNovel }
            ?: return@withContext null
        File(row.localPath).listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }
            ?.sortedBy { it.name }
            ?.takeIf { it.isNotEmpty() }
    }

    suspend fun localText(sourceId: String, chapterId: String): String? = withContext(Dispatchers.IO) {
        val row = dao.get(sourceId, chapterId)?.takeIf { it.status == DownloadStatus.COMPLETED && it.isNovel }
            ?: return@withContext null
        File(row.localPath, NOVEL_FILE).takeIf { it.exists() }?.readText()
    }

    fun storageBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private suspend fun workerLoop() {
        // Rows left in DOWNLOADING by a killed process are simply resumed.
        while (true) {
            val next = dao.pending().firstOrNull()
            if (next == null) {
                wakeUp.receive()
                continue
            }
            try {
                process(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Descarga fallida ${next.chapterTitle}", e)
                if (dao.get(next.sourceId, next.chapterId) != null) {
                    val message = if (e is HttpException) "Error HTTP ${e.code}" else e.message ?: "Error desconocido"
                    dao.updateState(next.sourceId, next.chapterId, DownloadStatus.FAILED, next.progress, next.total, message)
                }
            }
        }
    }

    private suspend fun process(download: DownloadEntity) {
        val source = sources.require(download.sourceId)
        val dir = File(download.localPath).apply { mkdirs() }
        dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, 0, 0, null)

        when (source) {
            is NovelSource -> {
                val text = source.fetchChapterText(download.chapterUrl).getOrThrow()
                File(dir, NOVEL_FILE).writeText(text)
                dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, 1, 1, null)
            }
            is MangaSource -> {
                val pages = source.fetchPageList(download.chapterUrl).getOrThrow()
                pages.forEachIndexed { index, url ->
                    // Stop if the user cancelled while we were downloading.
                    if (dao.get(download.sourceId, download.chapterId) == null) {
                        dir.deleteRecursively()
                        return
                    }
                    downloadPage(url, index, dir, source.imageHeaders)
                    dao.updateState(download.sourceId, download.chapterId, DownloadStatus.DOWNLOADING, index + 1, pages.size, null)
                }
                dao.updateState(download.sourceId, download.chapterId, DownloadStatus.COMPLETED, pages.size, pages.size, null)
            }
            else -> error("La fuente no admite descargas")
        }
    }

    private fun downloadPage(url: String, index: Int, dir: File, headers: Map<String, String>) {
        val prefix = "%04d".format(index)
        if (dir.listFiles()?.any { it.name.startsWith("$prefix.") && !it.name.endsWith(".tmp") } == true) return

        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        network.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw HttpException(response.code, url)
            val body = response.body ?: error("Respuesta vacía")
            val ext = when (body.contentType()?.subtype) {
                "png" -> "png"
                "webp" -> "webp"
                "gif" -> "gif"
                "avif" -> "avif"
                else -> "jpg"
            }
            val tmp = File(dir, "$prefix.tmp")
            tmp.outputStream().use { out -> body.byteStream().copyTo(out) }
            if (!tmp.renameTo(File(dir, "$prefix.$ext"))) error("No se pudo guardar la página")
        }
    }

    private fun chapterDir(sourceId: String, mediaId: String, chapterId: String) =
        File(root, "$sourceId/${hash(mediaId)}/${hash(chapterId)}")

    private fun hash(value: String): String =
        MessageDigest.getInstance("MD5").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "YomukaiDownloads"
        const val NOVEL_FILE = "chapter.txt"
    }
}
