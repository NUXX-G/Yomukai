package com.nelson.yomukai.server

import com.nelson.yomukai.data.source.AnimeSource
import com.nelson.yomukai.data.source.NovelSource
import com.nelson.yomukai.data.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Gets ahead of the user (F2.2), on the VPS because it is only light network work (no images resized, nothing heavy):
 *  - the chapter or episode after the one being read/watched: its pages (whose images the proxy then warms), its text,
 *    or its video servers near the end of an episode;
 *  - the chapter lists of what people are following, refreshed every half hour, so a work's page opens at once.
 * Two at a time at most, each with a time limit; anything that fails is simply not ready, as before.
 */
class Prefetcher(
    private val catalog: Catalog,
    private val db: Database,
    private val sources: SourceManager,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val log = LoggerFactory.getLogger("Prefetcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limit = Semaphore(PARALLEL)
    private val done = ConcurrentHashMap<String, Long>()

    /** The chapter after [chapterId] of [mediaId], by number as the lists show it; null when it is the last one. */
    suspend fun nextOf(sourceId: String, mediaId: String, chapterId: String): com.nelson.yomukai.domain.model.Chapter? {
        val list = catalog.chapterList(sourceId, mediaId).getOrNull() ?: return null
        val ordered = list.withIndex().sortedWith(compareBy({ it.value.chapterNumber }, { it.index })).map { it.value }
        val at = ordered.indexOfFirst { it.id == chapterId }
        return if (at < 0) null else ordered.getOrNull(at + 1)
    }

    /**
     * Someone saved progress in [chapterId]: its next one gets ready. For anime only near the end ([fraction] ≥ 0.75),
     * since video links expire in minutes; manga and novels at once (pages and text keep an hour).
     */
    fun afterProgress(sourceId: String, mediaId: String, chapterId: String, fraction: Double) {
        val source = sources.get(sourceId) ?: return
        if (source is AnimeSource && fraction < ANIME_FROM) return
        val key = "$sourceId|$chapterId"
        val now = clock()
        if ((done[key] ?: 0L) > now - AGAIN_MS) return
        done[key] = now
        if (done.size > MAX_REMEMBERED) done.entries.removeIf { it.value < now - AGAIN_MS }
        scope.launch {
            limit.withPermit {
                withTimeoutOrNull(STEP_MS) {
                    val next = nextOf(sourceId, mediaId, chapterId) ?: return@withTimeoutOrNull
                    when (source) {
                        is AnimeSource -> catalog.videos(sourceId, next.url)
                        is NovelSource -> catalog.text(sourceId, next.url)
                        else -> catalog.pages(sourceId, next.url)
                    }
                }
            }
        }
    }

    /** The works people follow now: reading/watching in a library, or opened in the last two weeks. */
    suspend fun followed(max: Int = LIBRARY_PER_ROUND): List<Pair<String, String>> {
        val since = clock() - RECENT_MS
        return db.tx {
            query(
                """SELECT source_id, media_id FROM (
                     SELECT source_id, media_id, updated_at AS at FROM library WHERE deleted = 0 AND reading_status IN ('READING', 'WATCHING')
                     UNION ALL SELECT source_id, media_id, read_at AS at FROM history WHERE deleted = 0 AND read_at > ?
                   ) GROUP BY source_id, media_id ORDER BY MAX(at) DESC LIMIT ?""",
                since, max
            ) { getString(1) to getString(2) }
        }.filter { (s, _) -> sources.get(s) != null }
    }

    /** One round over [followed]: lists not in memory are fetched again, one after another, gently. */
    suspend fun refreshLibraries(): Int {
        var fetched = 0
        for ((s, m) in followed()) {
            if (catalog.hasCachedChapters(s, m)) continue
            limit.withPermit { withTimeoutOrNull(STEP_MS) { catalog.chapterList(s, m) } }
            fetched++
            delay(PAUSE_MS)
        }
        if (fetched > 0) log.debug("Listas de capítulos al día: {}", fetched)
        return fetched
    }

    companion object {
        private const val PARALLEL = 2
        private const val STEP_MS = 60_000L
        private const val AGAIN_MS = 30 * 60_000L
        private const val MAX_REMEMBERED = 5_000
        const val ANIME_FROM = 0.75
        private const val RECENT_MS = 14 * 86_400_000L
        private const val LIBRARY_PER_ROUND = 60
        private const val PAUSE_MS = 2_000L
        const val ROUND_MS = 30 * 60_000L
    }
}
