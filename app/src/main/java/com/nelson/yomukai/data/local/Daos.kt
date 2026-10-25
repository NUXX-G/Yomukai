package com.nelson.yomukai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Query("UPDATE media SET inLibrary = :inLibrary, dateAdded = :dateAdded WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun setInLibrary(sourceId: String, mediaId: String, inLibrary: Boolean, dateAdded: Long)

    @Query("UPDATE media SET readingStatus = :status WHERE sourceId = :sourceId AND mediaId = :mediaId")
    suspend fun setReadingStatus(sourceId: String, mediaId: String, status: String)

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

    @Query("DELETE FROM chapters WHERE sourceId = :sourceId AND mediaId = :mediaId AND chapterId NOT IN (:keepIds)")
    suspend fun deleteMissing(sourceId: String, mediaId: String, keepIds: List<String>)

    /** Replaces the chapter list while keeping reading progress of chapters that still exist. */
    @Transaction
    suspend fun replaceForMedia(sourceId: String, mediaId: String, fresh: List<ChapterEntity>) {
        val existing = getForMedia(sourceId, mediaId).associateBy { it.chapterId }
        val merged = fresh.map { c ->
            existing[c.chapterId]?.let { old ->
                c.copy(
                    read = old.read,
                    lastPageRead = old.lastPageRead,
                    totalPages = old.totalPages,
                    progress = old.progress,
                    lastReadAt = old.lastReadAt
                )
            } ?: c
        }
        upsertAll(merged)
        if (merged.isNotEmpty()) deleteMissing(sourceId, mediaId, merged.map { it.chapterId })
    }

    @Query(
        "UPDATE chapters SET lastPageRead = :page, totalPages = :total, progress = :progress, " +
            "read = CASE WHEN :read THEN 1 ELSE read END, lastReadAt = :now " +
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

    @Query("UPDATE chapters SET read = :read, lastPageRead = 0, progress = 0 WHERE sourceId = :sourceId AND chapterId IN (:chapterIds)")
    suspend fun setRead(sourceId: String, chapterIds: List<String>, read: Boolean)
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

    @Query("DELETE FROM downloads WHERE sourceId = :sourceId AND chapterId = :chapterId")
    suspend fun delete(sourceId: String, chapterId: String)
}
