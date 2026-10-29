package com.nelson.yomukai.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nelson.yomukai.data.local.YomukaiDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Upgrades a database created by v1.0.0 (Room schema 2) with real rows to the current schema (6). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        YomukaiDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migratesFromV1_0_0_keepingData() {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO media (sourceId, mediaId, title, coverUrl, type, status, description, author, genres, " +
                    "inLibrary, dateAdded, lastFetched, readingStatus, notifyUpdates) " +
                    "VALUES ('mangadex','m1','Titulo','','MANGA','','','','',1,1,1,'READING',1)"
            )
            execSQL(
                "INSERT INTO chapters (sourceId, chapterId, mediaId, title, number, url, dateUploaded, sortIndex, " +
                    "read, lastPageRead, totalPages, progress, lastReadAt) VALUES ('mangadex','c1','m1','Cap 1',1.0,'u','',0,1,3,10,0.5,5)"
            )
            execSQL("INSERT INTO history (sourceId, mediaId, chapterId, chapterTitle, readAt) VALUES ('mangadex','m1','c1','Cap 1',9)")
            execSQL(
                "INSERT INTO downloads (sourceId, chapterId, mediaId, mediaTitle, chapterTitle, chapterUrl, isNovel, status, " +
                    "progress, total, localPath, error, createdAt) VALUES ('mangadex','c1','m1','Titulo','Cap 1','u',0,'COMPLETED',10,10,'/x',NULL,1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB, 6, true,
            YomukaiDatabase.MIGRATION_1_2, YomukaiDatabase.MIGRATION_2_3,
            YomukaiDatabase.MIGRATION_3_4, YomukaiDatabase.MIGRATION_4_5, YomukaiDatabase.MIGRATION_5_6
        )
        db.query("SELECT title, inLibrary FROM media WHERE mediaId='m1'").use {
            it.moveToFirst(); assertEquals("Titulo", it.getString(0)); assertEquals(1, it.getInt(1))
        }
        db.query("SELECT lastPageRead, read FROM chapters WHERE chapterId='c1'").use {
            it.moveToFirst(); assertEquals(3, it.getInt(0)); assertEquals(1, it.getInt(1))
        }
        db.query("SELECT isVideo, isHls, videoUrl, videoServer FROM downloads WHERE chapterId='c1'").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0)); assertEquals(0, it.getInt(1)); assertEquals("", it.getString(2)); assertEquals("", it.getString(3))
        }
        db.query("SELECT COUNT(*) FROM bookmarks").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
