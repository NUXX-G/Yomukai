package com.nelson.yomukai.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter
    fun fromStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toStatus(value: String): DownloadStatus =
        DownloadStatus.entries.firstOrNull { it.name == value } ?: DownloadStatus.FAILED
}

@Database(
    entities = [
        MediaEntity::class,
        ChapterEntity::class,
        HistoryEntity::class,
        DownloadEntity::class,
        CategoryEntity::class,
        MediaCategoryEntity::class,
        BookmarkEntity::class
    ],
    version = 6,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class YomukaiDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun historyDao(): HistoryDao
    abstract fun downloadDao(): DownloadDao
    abstract fun categoryDao(): CategoryDao
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        /** v2: reading status + update notifications per title, user categories. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE media ADD COLUMN readingStatus TEXT NOT NULL DEFAULT 'NONE'")
                db.execSQL("ALTER TABLE media ADD COLUMN notifyUpdates INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS categories (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, sortOrder INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS media_categories (" +
                        "sourceId TEXT NOT NULL, mediaId TEXT NOT NULL, categoryId INTEGER NOT NULL, " +
                        "PRIMARY KEY(sourceId, mediaId, categoryId), " +
                        "FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_categories_categoryId ON media_categories(categoryId)")
                // Existing library titles start as "Leyendo".
                db.execSQL("UPDATE media SET readingStatus = 'READING' WHERE inLibrary = 1")
            }
        }

        /** v3: indices for the library list, ordered chapter lists, history and the download queue. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_media_inLibrary_dateAdded ON media(inLibrary, dateAdded)")
                db.execSQL("DROP INDEX IF EXISTS index_chapters_sourceId_mediaId")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_chapters_sourceId_mediaId_sortIndex " +
                        "ON chapters(sourceId, mediaId, sortIndex)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_history_readAt ON history(readAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_downloads_status_createdAt ON downloads(status, createdAt)")
            }
        }

        /** v4: reader bookmarks. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bookmarks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId TEXT NOT NULL, mediaId TEXT NOT NULL, " +
                        "chapterId TEXT NOT NULL, chapterTitle TEXT NOT NULL, position INTEGER NOT NULL, " +
                        "label TEXT NOT NULL, createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bookmarks_sourceId_mediaId ON bookmarks(sourceId, mediaId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_sourceId_chapterId_position " +
                        "ON bookmarks(sourceId, chapterId, position)"
                )
            }
        }

        /** v5: anime episode downloads (video file or HLS) in the persistent queue. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN isVideo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN isHls INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoReferer TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6: the episode server chosen for (or used by) a video download. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN videoServer TEXT NOT NULL DEFAULT ''")
            }
        }

        fun build(context: Context): YomukaiDatabase =
            Room.databaseBuilder(context, YomukaiDatabase::class.java, "yomukai.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
