package com.nelson.yomukai.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Cached metadata for any title the user has opened; [inLibrary] marks favourites. */
@Entity(
    tableName = "media",
    primaryKeys = ["sourceId", "mediaId"],
    indices = [Index("inLibrary", "dateAdded")]
)
data class MediaEntity(
    val sourceId: String,
    val mediaId: String,
    val title: String,
    val coverUrl: String,
    val type: String,
    val status: String,
    val description: String,
    val author: String,
    val genres: String,
    val inLibrary: Boolean = false,
    val dateAdded: Long = 0L,
    val lastFetched: Long = 0L,
    /** [ReadingStatus] name; NONE until the user picks one. */
    @ColumnInfo(defaultValue = "NONE") val readingStatus: String = ReadingStatus.NONE.name,
    /** Whether the background update check should notify about new chapters of this title. */
    @ColumnInfo(defaultValue = "1") val notifyUpdates: Boolean = true
)

enum class ReadingStatus(val displayName: String) {
    NONE("Sin estado"),
    READING("Leyendo"),
    PLAN_TO_READ("Pendiente"),
    COMPLETED("Completado"),
    ON_HOLD("En pausa"),
    DROPPED("Abandonado");

    companion object {
        fun parse(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** User-defined library category / tag. */
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "media_categories",
    primaryKeys = ["sourceId", "mediaId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("categoryId")]
)
data class MediaCategoryEntity(
    val sourceId: String,
    val mediaId: String,
    val categoryId: Long
)

/**
 * Chapter cache plus per-chapter reading progress. [sortIndex] is the ascending reading
 * order (0 = first chapter) so "next/previous" is a simple ±1.
 */
@Entity(
    tableName = "chapters",
    primaryKeys = ["sourceId", "chapterId"],
    indices = [Index("sourceId", "mediaId", "sortIndex")]
)
data class ChapterEntity(
    val sourceId: String,
    val chapterId: String,
    val mediaId: String,
    val title: String,
    val number: Float,
    val url: String,
    val dateUploaded: String,
    val sortIndex: Int,
    val read: Boolean = false,
    /** Manga: last page index viewed. */
    val lastPageRead: Int = 0,
    val totalPages: Int = 0,
    /** Novel: scroll position as a fraction 0..1. */
    val progress: Float = 0f,
    val lastReadAt: Long = 0L
)

/** One row per title: the most recently read chapter. */
@Entity(
    tableName = "history",
    primaryKeys = ["sourceId", "mediaId"],
    indices = [Index("readAt")]
)
data class HistoryEntity(
    val sourceId: String,
    val mediaId: String,
    val chapterId: String,
    val chapterTitle: String,
    val readAt: Long
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

@Entity(
    tableName = "downloads",
    primaryKeys = ["sourceId", "chapterId"],
    indices = [Index("sourceId", "mediaId"), Index("status", "createdAt")]
)
data class DownloadEntity(
    val sourceId: String,
    val chapterId: String,
    val mediaId: String,
    val mediaTitle: String,
    val chapterTitle: String,
    val chapterUrl: String,
    val isNovel: Boolean,
    val status: DownloadStatus,
    val progress: Int = 0,
    val total: Int = 0,
    val localPath: String = "",
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    /** Anime episode: a video file (MP4) or a Media3 HLS download instead of images/text. */
    @ColumnInfo(defaultValue = "0") val isVideo: Boolean = false,
    @ColumnInfo(defaultValue = "0") val isHls: Boolean = false,
    /** Stream resolved when the download started; HLS downloads are keyed by this url. */
    @ColumnInfo(defaultValue = "''") val videoUrl: String = "",
    @ColumnInfo(defaultValue = "''") val videoReferer: String = ""
)

/** History row joined with its media for display. */
data class HistoryWithMedia(
    val sourceId: String,
    val mediaId: String,
    val chapterId: String,
    val chapterTitle: String,
    val readAt: Long,
    val title: String,
    val coverUrl: String,
    val type: String
)

/** A saved spot in a chapter: page index (images) or paragraph index (novels). */
@Entity(
    tableName = "bookmarks",
    indices = [Index("sourceId", "mediaId"), Index("sourceId", "chapterId", "position", unique = true)]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val mediaId: String,
    val chapterId: String,
    val chapterTitle: String,
    val position: Int,
    /** Short preview text for novels, empty for pages. */
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
