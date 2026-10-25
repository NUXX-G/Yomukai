package com.nelson.yomukai.data.repository

import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.HistoryEntity
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.local.MediaCategoryEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.local.YomukaiDatabase
import com.nelson.yomukai.data.source.SourceManager
import com.nelson.yomukai.domain.model.MediaItem
import com.nelson.yomukai.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

fun MediaEntity.toDomain() = MediaItem(
    id = mediaId,
    title = title,
    coverUrl = coverUrl,
    type = MediaType.fromName(type),
    status = status,
    description = description,
    sourceId = sourceId,
    author = author,
    genres = genres.split('|').filter { it.isNotBlank() }
)

/**
 * Single source of truth for titles, chapters and reading progress. The UI observes Room;
 * refresh functions pull from the network and write into Room.
 */
class LibraryRepository(
    private val db: YomukaiDatabase,
    private val sources: SourceManager
) {
    private val mediaDao = db.mediaDao()
    private val chapterDao = db.chapterDao()
    private val historyDao = db.historyDao()
    private val categoryDao = db.categoryDao()

    fun observeMedia(sourceId: String, mediaId: String): Flow<MediaEntity?> = mediaDao.observe(sourceId, mediaId)

    fun observeChapters(sourceId: String, mediaId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeForMedia(sourceId, mediaId)

    fun observeLibrary(): Flow<List<MediaItem>> = mediaDao.observeLibrary().map { list -> list.map { it.toDomain() } }

    fun observeLibraryEntries(): Flow<List<MediaEntity>> = mediaDao.observeLibrary()

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeCategoryAssignments(): Flow<List<MediaCategoryEntity>> = categoryDao.observeAssignments()

    fun observeCategoriesFor(sourceId: String, mediaId: String): Flow<List<Long>> = categoryDao.observeForMedia(sourceId, mediaId)

    suspend fun createCategory(name: String): Long = categoryDao.insert(CategoryEntity(name = name.trim()))

    suspend fun renameCategory(id: Long, name: String) = categoryDao.rename(id, name.trim())

    suspend fun deleteCategory(id: Long) = categoryDao.delete(id)

    suspend fun setCategories(sourceId: String, mediaId: String, ids: Set<Long>) = categoryDao.setForMedia(sourceId, mediaId, ids)

    suspend fun setReadingStatus(sourceId: String, mediaId: String, status: ReadingStatus) =
        mediaDao.setReadingStatus(sourceId, mediaId, status.name)

    suspend fun setNotifyUpdates(sourceId: String, mediaId: String, notify: Boolean) =
        mediaDao.setNotifyUpdates(sourceId, mediaId, notify)

    suspend fun getTrackedLibrary(): List<MediaEntity> = mediaDao.getTrackedLibrary()

    fun observeHistory(): Flow<List<HistoryWithMedia>> = historyDao.observe()

    suspend fun getMedia(sourceId: String, mediaId: String) = mediaDao.get(sourceId, mediaId)

    suspend fun getChapters(sourceId: String, mediaId: String) = chapterDao.getForMedia(sourceId, mediaId)

    suspend fun getChapter(sourceId: String, chapterId: String) = chapterDao.get(sourceId, chapterId)

    /** Stores browse results so the details screen can render instantly. Keeps library flags and richer data. */
    suspend fun cacheMedia(item: MediaItem) {
        val old = mediaDao.get(item.sourceId, item.id)
        mediaDao.upsert(merge(old, item, lastFetched = old?.lastFetched ?: 0L))
    }

    suspend fun refreshDetails(sourceId: String, mediaId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val source = sources.get(sourceId) ?: return@withContext Result.failure(IllegalStateException("Fuente no disponible"))
        val old = mediaDao.get(sourceId, mediaId)
        val seed = old?.toDomain() ?: MediaItem(mediaId, "", "", source.supportedTypes.first(), "", "", sourceId)
        source.fetchMediaDetails(seed).map { fresh ->
            mediaDao.upsert(merge(mediaDao.get(sourceId, mediaId), fresh, lastFetched = System.currentTimeMillis()))
        }
    }

    suspend fun refreshChapters(sourceId: String, mediaId: String): Result<Int> = withContext(Dispatchers.IO) {
        val source = sources.get(sourceId) ?: return@withContext Result.failure(IllegalStateException("Fuente no disponible"))
        source.fetchChapterList(mediaId).map { chapters ->
            // Stable sort: chapters sharing a number keep the order the source returned.
            val ordered = chapters.withIndex()
                .sortedWith(compareBy({ it.value.chapterNumber }, { it.index }))
                .map { it.value }
            val entities = ordered.mapIndexed { index, c ->
                ChapterEntity(
                    sourceId = sourceId,
                    chapterId = c.id,
                    mediaId = mediaId,
                    title = c.title,
                    number = c.chapterNumber,
                    url = c.url,
                    dateUploaded = c.dateUploaded,
                    sortIndex = index
                )
            }
            chapterDao.replaceForMedia(sourceId, mediaId, entities)
            entities.size
        }
    }

    suspend fun setInLibrary(sourceId: String, mediaId: String, inLibrary: Boolean) {
        mediaDao.setInLibrary(sourceId, mediaId, inLibrary, if (inLibrary) System.currentTimeMillis() else 0L)
        val current = mediaDao.get(sourceId, mediaId)
        if (inLibrary && current?.readingStatus == ReadingStatus.NONE.name) {
            mediaDao.setReadingStatus(sourceId, mediaId, ReadingStatus.PLAN_TO_READ.name)
        }
    }

    suspend fun saveProgress(
        chapter: ChapterEntity,
        page: Int,
        totalPages: Int,
        progress: Float,
        markRead: Boolean
    ) {
        val now = System.currentTimeMillis()
        chapterDao.updateProgress(chapter.sourceId, chapter.chapterId, page, totalPages, progress, markRead, now)
        historyDao.upsert(HistoryEntity(chapter.sourceId, chapter.mediaId, chapter.chapterId, chapter.title, now))
    }

    suspend fun setChaptersRead(sourceId: String, chapterIds: List<String>, read: Boolean) {
        if (chapterIds.isNotEmpty()) chapterDao.setRead(sourceId, chapterIds, read)
    }

    suspend fun deleteHistory(sourceId: String, mediaId: String) = historyDao.delete(sourceId, mediaId)

    suspend fun clearHistory() = historyDao.clear()

    private fun merge(old: MediaEntity?, item: MediaItem, lastFetched: Long) = MediaEntity(
        sourceId = item.sourceId,
        mediaId = item.id,
        title = item.title.ifBlank { old?.title.orEmpty() },
        coverUrl = item.coverUrl.ifBlank { old?.coverUrl.orEmpty() },
        type = item.type.name,
        status = item.status.ifBlank { old?.status.orEmpty() },
        description = item.description.ifBlank { old?.description.orEmpty() },
        author = item.author.ifBlank { old?.author.orEmpty() },
        genres = item.genres.joinToString("|").ifBlank { old?.genres.orEmpty() },
        inLibrary = old?.inLibrary ?: false,
        dateAdded = old?.dateAdded ?: 0L,
        lastFetched = lastFetched,
        readingStatus = old?.readingStatus ?: ReadingStatus.NONE.name,
        notifyUpdates = old?.notifyUpdates ?: true
    )
}
