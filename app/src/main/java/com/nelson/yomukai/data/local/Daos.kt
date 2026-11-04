package com.nelson.yomukai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Ids per query, under the 999 parameters SQLite allows on Android 11 and older. */
const val MAX_QUERY_IDS = 500

@Dao
interface MediaDao {
    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND mediaId = :mediaId")
    fun observe(sourceId: String, mediaId: String): Flow<MediaEntity?>

    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun get(sourceId: String, mediaId: String): MediaEntity?

    @Query("SELECT * FROM media WHERE inLibrary = 1 ORDER BY dateAdded DESC")
    fun observeLibrary(): Flow<List<MediaEntity>>

    @Upsert
    suspend fun upsert(media: MediaEntity)

    @Query(
        "UPDATE media SET inLibrary = :inLibrary, dateAdded = :dateAdded, libraryUpdatedAt = :now, libraryDirty = 1 " +
            "WHERE sourceId = :sourceId AND mediaId = :mediaId"
    )
    suspend fun setInLibrary(sourceId: String, mediaId: String, inLibrary: Boolean, dateAdded: Long, now: Long)

    @Query("UPDATE media SET readingStatus = :status, libraryUpdatedAt = :now, libraryDirty = 1 WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun setReadingStatus(sourceId: String, mediaId: String, status: String, now: Long)

    /** The title's categories changed: its library state goes to the server again. */
    @Query("UPDATE media SET libraryUpdatedAt = :now, libraryDirty = 1 WHERE sourceId = :sourceId AND mediaId = :mediaId AND inLibrary = 1")
    suspend fun touchLibrary(sourceId: String, mediaId: String, now: Long)

    @Query("UPDATE media SET notifyUpdates = :notify WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun setNotifyUpdates(sourceId: String, mediaId: String, notify: Boolean)

    /** Library titles the background update check should look at. */
    @Query("SELECT * FROM media WHERE inLibrary = 1 AND notifyUpdates = 1 AND readingStatus NOT IN ('COMPLETED', 'DROPPED')")
    suspend fun getTrackedLibrary(): List<MediaEntity>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM media_categories")
    fun observeAssignments(): Flow<List<MediaCategoryEntity>>

    @Query("SELECT categoryId FROM media_categories WHERE sourceId = :sourceId AND mediaId = :mediaId")
    fun observeForMedia(sourceId: String, mediaId: String): Flow<List<Long>>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM media_categories WHERE categoryId = :categoryId")
    suspend fun membersOf(categoryId: Long): List<MediaCategoryEntity>

    @Query("DELETE FROM media_categories WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun clearForMedia(sourceId: String, mediaId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(links: List<MediaCategoryEntity>)

    @Transaction
    suspend fun setForMedia(sourceId: String, mediaId: String, categoryIds: Set<Long>) {
        clearForMedia(sourceId, mediaId)
        assign(categoryIds.map { MediaCategoryEntity(sourceId, mediaId, it) })
    }
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE sourceId = :sourceId AND mediaId = :mediaId ORDER BY sortIndex ASC")
    fun observeForMedia(sourceId: String, mediaId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE sourceId = :sourceId AND mediaId = :mediaId ORDER BY sortIndex ASC")
    suspend fun getForMedia(sourceId: String, mediaId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun get(sourceId: String, chapterId: String): ChapterEntity?

    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE sourceId = :sourceId AND chapterId IN (:chapterIds)")
    suspend fun deleteIds(sourceId: String, chapterIds: List<String>)

    /** Replaces the chapter list while keeping reading progress of chapters that still exist. */
    @Transaction
    suspend fun replaceForMedia(sourceId: String, mediaId: String, fresh: List<ChapterEntity>) {
        val existing = getForMedia(sourceId, mediaId).associateBy { it.chapterId }
        val pending = pendingFor(sourceId, mediaId).associateBy { it.chapterId }
        val merged = fresh.map { c ->
            existing[c.chapterId]?.let { old ->
                c.copy(
                    read = old.read,
                    lastPageRead = old.lastPageRead,
                    totalPages = old.totalPages,
                    progress = old.progress,
                    lastReadAt = old.lastReadAt,
                    progressUpdatedAt = old.progressUpdatedAt,
                    progressDirty = old.progressDirty
                )
            } ?: c
        }.map { c ->
            // Progress synced from another device before this phone knew the chapter.
            pending[c.chapterId]?.takeIf { it.updatedAt > c.progressUpdatedAt }?.let { p ->
                c.copy(read = p.read, lastPageRead = p.page, totalPages = p.total, progress = p.fraction, progressUpdatedAt = p.updatedAt, progressDirty = false)
            } ?: c
        }
        if (pending.isNotEmpty()) clearPending(sourceId, mediaId)
        upsertAll(merged)
        if (merged.isNotEmpty()) {
            // In small batches: SQLite on Android 11 and older accepts 999 parameters per query.
            val keep = merged.mapTo(HashSet()) { it.chapterId }
            (existing.keys - keep).chunked(MAX_QUERY_IDS).forEach { deleteIds(sourceId, it) }
        }
    }

    @Query(
        "UPDATE chapters SET lastPageRead = :page, totalPages = :total, progress = :progress, " +
            "read = CASE WHEN :read THEN 1 ELSE read END, lastReadAt = :now, progressUpdatedAt = :now, progressDirty = 1 " +
            "WHERE sourceId = :sourceId AND chapterId = :chapterId"
    )
    suspend fun updateProgress(
        sourceId: String,
        chapterId: String,
        page: Int,
        total: Int,
        progress: Float,
        read: Boolean,
        now: Long
    )

    @Query(
        "UPDATE chapters SET read = 1, progressUpdatedAt = :now, progressDirty = 1 " +
            "WHERE sourceId = :sourceId AND mediaId = :mediaId AND sortIndex < :sortIndex AND read = 0"
    )
    suspend fun markPreviousRead(sourceId: String, mediaId: String, sortIndex: Int, now: Long)

    @Query(
        "UPDATE chapters SET read = :read, lastPageRead = 0, progress = 0, progressUpdatedAt = :now, progressDirty = 1 " +
            "WHERE sourceId = :sourceId AND chapterId IN (:chapterIds)"
    )
    suspend fun setRead(sourceId: String, chapterIds: List<String>, read: Boolean, now: Long)

    @Query("SELECT * FROM pending_progress WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun pendingFor(sourceId: String, mediaId: String): List<PendingProgressEntity>

    @Query("DELETE FROM pending_progress WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun clearPending(sourceId: String, mediaId: String)
}

@Dao
interface HistoryDao {
    @Query(
        "SELECT h.sourceId, h.mediaId, h.chapterId, h.chapterTitle, h.readAt, m.title, m.coverUrl, m.type " +
            "FROM history h INNER JOIN media m ON m.sourceId = h.sourceId AND m.mediaId = h.mediaId " +
            "ORDER BY h.readAt DESC LIMIT :limit"
    )
    fun observe(limit: Int = 200): Flow<List<HistoryWithMedia>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun delete(sourceId: String, mediaId: String)

    @Query("SELECT * FROM history")
    suspend fun keys(): List<HistoryEntity>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE sourceId = :sourceId AND mediaId = :mediaId")
    fun observeForMedia(sourceId: String, mediaId: String): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun get(sourceId: String, chapterId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE status IN ('QUEUED', 'DOWNLOADING') ORDER BY createdAt ASC")
    suspend fun pending(): List<DownloadEntity>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(download: DownloadEntity): Long

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progress = :progress, total = :total, error = :error WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun updateState(sourceId: String, chapterId: String, status: DownloadStatus, progress: Int, total: Int, error: String?)

    @Query("UPDATE downloads SET videoServer = :server WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun setVideoServer(sourceId: String, chapterId: String, server: String)

    @Query("DELETE FROM downloads WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun delete(sourceId: String, chapterId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE sourceId = :sourceId AND mediaId = :mediaId ORDER BY createdAt DESC")
    fun observeForMedia(sourceId: String, mediaId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE sourceId = :sourceId AND chapterId = :chapterId AND position = :position")
    suspend fun find(sourceId: String, chapterId: String, position: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun byId(id: Long): BookmarkEntity?

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

/** What the server sync reads and writes; normal screens never use it. */
@Dao
interface SyncDao {
    // Library

    @Query("SELECT * FROM media WHERE libraryDirty = 1")
    suspend fun dirtyLibrary(): List<MediaEntity>

    @Query("UPDATE media SET libraryDirty = 0 WHERE sourceId = :sourceId AND mediaId = :mediaId AND libraryUpdatedAt = :at")
    suspend fun librarySent(sourceId: String, mediaId: String, at: Long)

    @Query("UPDATE media SET libraryDirty = 1 WHERE inLibrary = 1")
    suspend fun markLibraryForUpload()

    @Query("SELECT c.name FROM media_categories mc JOIN categories c ON c.id = mc.categoryId WHERE mc.sourceId = :sourceId AND mc.mediaId = :mediaId")
    suspend fun categoryNamesFor(sourceId: String, mediaId: String): List<String>

    @Query("SELECT * FROM media WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun media(sourceId: String, mediaId: String): MediaEntity?

    @Upsert
    suspend fun upsertMedia(media: MediaEntity)

    // Categories

    @Query("SELECT * FROM categories")
    suspend fun categories(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE dirty = 1")
    suspend fun dirtyCategories(): List<CategoryEntity>

    @Query("UPDATE categories SET dirty = 0 WHERE id = :id AND updatedAt = :at")
    suspend fun categorySent(id: Long, at: Long)

    @Query("UPDATE categories SET dirty = 1, updatedAt = CASE WHEN updatedAt = 0 THEN :now ELSE updatedAt END")
    suspend fun markCategoriesForUpload(now: Long)

    @Insert
    suspend fun insertCategory(category: CategoryEntity): Long

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM media_categories WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun clearCategoriesOf(sourceId: String, mediaId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(links: List<MediaCategoryEntity>)

    // Reading progress

    @Query("SELECT * FROM chapters WHERE progressDirty = 1 LIMIT :limit")
    suspend fun dirtyProgress(limit: Int): List<ChapterEntity>

    @Query("UPDATE chapters SET progressDirty = 0 WHERE sourceId = :sourceId AND chapterId = :chapterId AND progressUpdatedAt = :at")
    suspend fun progressSent(sourceId: String, chapterId: String, at: Long)

    @Query("UPDATE chapters SET progressDirty = 1 WHERE read = 1 OR lastPageRead > 0 OR progress > 0")
    suspend fun markProgressForUpload()

    @Query("SELECT * FROM chapters WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun chapter(sourceId: String, chapterId: String): ChapterEntity?

    @Query(
        "UPDATE chapters SET read = :read, lastPageRead = :page, totalPages = :total, progress = :fraction, " +
            "progressUpdatedAt = :at, progressDirty = 0 WHERE sourceId = :sourceId AND chapterId = :chapterId"
    )
    suspend fun applyProgress(sourceId: String, chapterId: String, read: Boolean, page: Int, total: Int, fraction: Float, at: Long)

    @Query("SELECT * FROM pending_progress WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun pending(sourceId: String, chapterId: String): PendingProgressEntity?

    @Upsert
    suspend fun upsertPending(row: PendingProgressEntity)

    // History

    @Query(
        "SELECT h.sourceId, h.mediaId, h.chapterId, h.chapterTitle, h.readAt, COALESCE(m.title, '') AS title, " +
            "COALESCE(m.coverUrl, '') AS coverUrl, COALESCE(m.type, 'MANGA') AS type " +
            "FROM history h LEFT JOIN media m ON m.sourceId = h.sourceId AND m.mediaId = h.mediaId WHERE h.dirty = 1"
    )
    suspend fun dirtyHistory(): List<HistoryWithMedia>

    @Query("UPDATE history SET dirty = 0 WHERE sourceId = :sourceId AND mediaId = :mediaId AND readAt = :at")
    suspend fun historySent(sourceId: String, mediaId: String, at: Long)

    @Query("UPDATE history SET dirty = 1")
    suspend fun markHistoryForUpload()

    @Query("SELECT * FROM history WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun history(sourceId: String, mediaId: String): HistoryEntity?

    @Upsert
    suspend fun upsertHistory(entry: HistoryEntity)

    @Query("DELETE FROM history WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun deleteHistory(sourceId: String, mediaId: String)

    // Bookmarks

    @Query("SELECT * FROM bookmarks WHERE dirty = 1")
    suspend fun dirtyBookmarks(): List<BookmarkEntity>

    @Query("UPDATE bookmarks SET dirty = 0 WHERE id = :id AND updatedAt = :at")
    suspend fun bookmarkSent(id: Long, at: Long)

    @Query("UPDATE bookmarks SET dirty = 1, updatedAt = CASE WHEN updatedAt = 0 THEN createdAt ELSE updatedAt END")
    suspend fun markBookmarksForUpload()

    @Query("SELECT * FROM bookmarks WHERE sourceId = :sourceId AND chapterId = :chapterId AND position = :position")
    suspend fun bookmark(sourceId: String, chapterId: String, position: Int): BookmarkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putBookmark(bookmark: BookmarkEntity): Long

    @Query("DELETE FROM bookmarks WHERE sourceId = :sourceId AND chapterId = :chapterId AND position = :position")
    suspend fun deleteBookmark(sourceId: String, chapterId: String, position: Int)

    // Deletions to send

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstone(row: SyncTombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addTombstones(rows: List<SyncTombstoneEntity>)

    @Query("SELECT * FROM sync_tombstones")
    suspend fun tombstones(): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND `key` = :key AND deletedAt = :at")
    suspend fun tombstoneSent(kind: String, key: String, at: Long)

    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND `key` = :key")
    suspend fun dropTombstone(kind: String, key: String)
}
