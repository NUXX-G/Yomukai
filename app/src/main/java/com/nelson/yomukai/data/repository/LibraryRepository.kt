package com.nelson.yomukai.data.repository

import com.nelson.yomukai.data.local.BookmarkEntity
import com.nelson.yomukai.data.local.CategoryEntity
import com.nelson.yomukai.data.local.ChapterEntity
import com.nelson.yomukai.data.local.HistoryEntity
import com.nelson.yomukai.data.local.HistoryWithMedia
import com.nelson.yomukai.data.local.MAX_QUERY_IDS
import com.nelson.yomukai.data.local.MediaCategoryEntity
import com.nelson.yomukai.data.local.MediaEntity
import com.nelson.yomukai.data.local.ReadingStatus
import com.nelson.yomukai.data.local.SyncTombstoneEntity
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
    genres = genres.split('|').filter { it.isNotBlank() },
    altTitles = altTitles.split('|').filter { it.isNotBlank() }
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
    private val bookmarkDao = db.bookmarkDao()
    private val syncDao = db.syncDao()

    /** Called after a change that the server sync should send; set by the sync code. */
    @Volatile var onLocalChange: () -> Unit = {}

    private fun changed() = onLocalChange()

    fun observeMedia(sourceId: String, mediaId: String): Flow<MediaEntity?> = mediaDao.observe(sourceId, mediaId)

    fun observeChapters(sourceId: String, mediaId: String): Flow<List<ChapterEntity>> =
        chapterDao.observeForMedia(sourceId, mediaId)

    fun observeLibrary(): Flow<List<MediaItem>> = mediaDao.observeLibrary().map { list -> list.map { it.toDomain() } }

    fun observeLibraryEntries(): Flow<List<MediaEntity>> = mediaDao.observeLibrary()

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()

    fun observeCategoryAssignments(): Flow<List<MediaCategoryEntity>> = categoryDao.observeAssignments()

    fun observeCategoriesFor(sourceId: String, mediaId: String): Flow<List<Long>> = categoryDao.observeForMedia(sourceId, mediaId)

    suspend fun createCategory(name: String): Long {
        val now = System.currentTimeMillis()
        syncDao.dropTombstone(TOMBSTONE_CATEGORY, name.trim())
        return categoryDao.insert(CategoryEntity(name = name.trim(), updatedAt = now, dirty = true)).also { changed() }
    }

    /** Synced by name: the old name is deleted, the new one created, and its titles change with it. */
    suspend fun renameCategory(id: Long, name: String) {
        val old = syncDao.categories().firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        if (old.name == name.trim()) return
        syncDao.updateCategory(old.copy(name = name.trim(), updatedAt = now, dirty = true))
        syncDao.addTombstone(SyncTombstoneEntity(TOMBSTONE_CATEGORY, old.name, now))
        touchMembers(id, now)
        changed()
    }

    suspend fun deleteCategory(id: Long) {
        val old = syncDao.categories().firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        touchMembers(id, now)
        categoryDao.delete(id)
        syncDao.addTombstone(SyncTombstoneEntity(TOMBSTONE_CATEGORY, old.name, now))
        changed()
    }

    private suspend fun touchMembers(categoryId: Long, now: Long) {
        db.categoryDao().membersOf(categoryId).forEach { mediaDao.touchLibrary(it.sourceId, it.mediaId, now) }
    }

    suspend fun setCategories(sourceId: String, mediaId: String, ids: Set<Long>) {
        categoryDao.setForMedia(sourceId, mediaId, ids)
        mediaDao.touchLibrary(sourceId, mediaId, System.currentTimeMillis())
        changed()
    }

    suspend fun setReadingStatus(sourceId: String, mediaId: String, status: ReadingStatus) {
        mediaDao.setReadingStatus(sourceId, mediaId, status.name, System.currentTimeMillis())
        changed()
    }

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
        source.fetchMediaDetails(seed).mapCatching { fresh ->
            mediaDao.upsert(merge(mediaDao.get(sourceId, mediaId), fresh, lastFetched = System.currentTimeMillis()))
        }
    }

    /** What each title's chapter list looked like when last read: lists hide empty titles and put well-kept ones first. */
    val chapterHealth = com.nelson.yomukai.data.metadata.ChapterHealthRegistry()

    suspend fun refreshChapters(sourceId: String, mediaId: String): Result<Int> = withContext(Dispatchers.IO) {
        val source = sources.get(sourceId) ?: return@withContext Result.failure(IllegalStateException("Fuente no disponible"))
        source.fetchChapterList(mediaId).also { chapterHealth.record(sourceId, mediaId, it) }.mapCatching { chapters ->
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
        val now = System.currentTimeMillis()
        mediaDao.setInLibrary(sourceId, mediaId, inLibrary, if (inLibrary) now else 0L, now)
        val current = mediaDao.get(sourceId, mediaId)
        if (inLibrary && current?.readingStatus == ReadingStatus.NONE.name) {
            mediaDao.setReadingStatus(sourceId, mediaId, ReadingStatus.PLAN_TO_READ.name, now)
        }
        changed()
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
        // Finishing a chapter means the earlier ones were read too. A chapter already read changes
        // nothing, so chapters the user unmarked by hand stay unmarked when it is reopened.
        if (markRead && !chapter.read) chapterDao.markPreviousRead(chapter.sourceId, chapter.mediaId, chapter.sortIndex, now)
        historyDao.upsert(HistoryEntity(chapter.sourceId, chapter.mediaId, chapter.chapterId, chapter.title, now, dirty = true))
        syncDao.dropTombstone(TOMBSTONE_HISTORY, historyKey(chapter.sourceId, chapter.mediaId))
        changed()
    }

    suspend fun setChaptersRead(sourceId: String, chapterIds: List<String>, read: Boolean) {
        val now = System.currentTimeMillis()
        chapterIds.chunked(MAX_QUERY_IDS).forEach { chapterDao.setRead(sourceId, it, read, now) }
        changed()
    }

    fun observeBookmarks(sourceId: String, mediaId: String): Flow<List<BookmarkEntity>> =
        bookmarkDao.observeForMedia(sourceId, mediaId)

    /** Adds the bookmark, or removes it when that exact spot is already bookmarked. Returns true when added. */
    suspend fun toggleBookmark(chapter: ChapterEntity, position: Int, label: String = ""): Boolean {
        val existing = bookmarkDao.find(chapter.sourceId, chapter.chapterId, position)
        val now = System.currentTimeMillis()
        if (existing != null) {
            bookmarkDao.delete(existing.id)
            syncDao.addTombstone(SyncTombstoneEntity(TOMBSTONE_BOOKMARK, bookmarkKey(existing), now))
            changed()
            return false
        }
        syncDao.dropTombstone(TOMBSTONE_BOOKMARK, bookmarkKey(chapter.sourceId, chapter.mediaId, chapter.chapterId, position))
        bookmarkDao.insert(BookmarkEntity(0, chapter.sourceId, chapter.mediaId, chapter.chapterId, chapter.title, position, label, now, updatedAt = now, dirty = true))
        changed()
        return true
    }

    suspend fun deleteBookmark(id: Long) {
        val existing = syncDao.dirtyBookmarks().firstOrNull { it.id == id } ?: db.bookmarkDao().byId(id)
        bookmarkDao.delete(id)
        if (existing != null) syncDao.addTombstone(SyncTombstoneEntity(TOMBSTONE_BOOKMARK, bookmarkKey(existing), System.currentTimeMillis()))
        changed()
    }

    suspend fun deleteHistory(sourceId: String, mediaId: String) {
        historyDao.delete(sourceId, mediaId)
        syncDao.addTombstone(SyncTombstoneEntity(TOMBSTONE_HISTORY, historyKey(sourceId, mediaId), System.currentTimeMillis()))
        changed()
    }

    suspend fun clearHistory() {
        val now = System.currentTimeMillis()
        val keys = db.historyDao().keys()
        historyDao.clear()
        syncDao.addTombstones(keys.map { SyncTombstoneEntity(TOMBSTONE_HISTORY, historyKey(it.sourceId, it.mediaId), now) })
        changed()
    }

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
        notifyUpdates = old?.notifyUpdates ?: true,
        altTitles = item.altTitles.joinToString("|") { it.replace('|', ' ') }.ifBlank { old?.altTitles.orEmpty() },
        libraryUpdatedAt = old?.libraryUpdatedAt ?: 0L,
        libraryDirty = old?.libraryDirty ?: false
    )
}

const val TOMBSTONE_HISTORY = "history"
const val TOMBSTONE_BOOKMARK = "bookmark"
const val TOMBSTONE_CATEGORY = "category"

/** Keys of deleted rows waiting to be sent; the parts are joined with a line break. */
fun historyKey(sourceId: String, mediaId: String) = "$sourceId\n$mediaId"
fun bookmarkKey(sourceId: String, mediaId: String, chapterId: String, position: Int) = "$sourceId\n$mediaId\n$chapterId\n$position"
fun bookmarkKey(b: BookmarkEntity) = bookmarkKey(b.sourceId, b.mediaId, b.chapterId, b.position)
