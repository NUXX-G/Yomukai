package com.nelson.yomukai.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager

class MigrationsTest {
    private fun tempFile() = File(Files.createTempDirectory("ykmig").toFile(), "t.db")
    private fun open(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    /** A database as the very first server left it: no schema_version and none of the columns added later. */
    private fun legacyDatabase(file: File) = open(file).use { c ->
        c.exec("""CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            pass_hash TEXT, is_admin INTEGER NOT NULL DEFAULT 0, is_guest INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL, expires_at INTEGER)""")
        c.exec("CREATE TABLE sessions(token TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created_at INTEGER NOT NULL, last_seen INTEGER NOT NULL)")
        c.exec("""CREATE TABLE library(user_id INTEGER NOT NULL, source_id TEXT NOT NULL, media_id TEXT NOT NULL, title TEXT NOT NULL,
            cover TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL DEFAULT '', author TEXT NOT NULL DEFAULT '',
            genres TEXT NOT NULL DEFAULT '', added_at INTEGER NOT NULL, reading_status TEXT NOT NULL DEFAULT 'NONE',
            PRIMARY KEY(user_id, source_id, media_id))""")
        c.exec("""CREATE TABLE history(user_id INTEGER NOT NULL, source_id TEXT NOT NULL, media_id TEXT NOT NULL, chapter_id TEXT NOT NULL,
            chapter_title TEXT NOT NULL, title TEXT NOT NULL, cover TEXT NOT NULL, type TEXT NOT NULL, read_at INTEGER NOT NULL,
            PRIMARY KEY(user_id, source_id, media_id))""")
        c.exec("CREATE TABLE settings(user_id INTEGER PRIMARY KEY, json TEXT NOT NULL)")
        c.exec("INSERT INTO users(name, pass_hash, is_admin, created_at) VALUES('admin', 'h', 1, 1)")
        c.exec("INSERT INTO sessions VALUES('tok', 1, 1, 1)")
        c.exec("INSERT INTO library(user_id, source_id, media_id, title, cover, type, added_at, reading_status) VALUES(1, 's', 'm', 'Obra', '', 'MANGA', 42, 'PLAN')")
        c.exec("INSERT INTO history VALUES(1, 's', 'm', 'c1', 'Cap. 1', 'Obra', '', 'MANGA', 77)")
        c.exec("INSERT INTO settings VALUES(1, '{\"a\":1}')")
    }

    private fun Connection.allColumns(): Map<String, Set<String>> =
        listOf("users", "sessions", "library", "progress", "history", "settings", "categories", "bookmarks", "counters", "schema_version")
            .associateWith { columnsOf(it) }

    @Test
    fun legacyDatabaseUpgradesWithoutLosingData() = runBlocking {
        val file = tempFile()
        legacyDatabase(file)
        val db = Database(file)
        assertEquals(Migrations.LATEST, db.schemaVersion())
        val user = db.userBySession("tok")!!
        assertEquals("admin", user.name)
        val library = db.library(user.id).single()
        assertEquals("Obra", library.title)
        assertEquals("old web name is migrated", "PLAN_TO_READ", library.readingStatus)
        assertEquals("updated_at filled from added_at", 42L, library.updatedAt)
        assertEquals(77L, db.history(user.id).single().updatedAt)
        assertEquals("{\"a\":1}", db.settings(user.id))
        // A first sync sees the old rows.
        assertEquals(1, db.sync(user.id, 0, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), null).library.size)
    }

    @Test
    fun upgradedAndFreshDatabasesHaveTheSameSchema() {
        val legacy = tempFile().also(::legacyDatabase)
        Database(legacy)
        val fresh = tempFile()
        Database(fresh)
        assertEquals(open(fresh).use { it.allColumns() }, open(legacy).use { it.allColumns() })
    }

    @Test
    fun runningAgainAppliesNothing() {
        val file = tempFile()
        Database(file)
        open(file).use { c ->
            assertTrue(Migrations.run(c).isEmpty())
            // Idempotent even if the version table were lost: every step can meet its own work already done.
            c.exec("DELETE FROM schema_version")
            assertEquals((1..Migrations.LATEST).toList(), Migrations.run(c))
        }
        Database(file)
    }

    @Test
    fun aFailedStepRollsBackAndIsNotRecorded() {
        val file = tempFile()
        open(file).use { c ->
            val steps = listOf(
                Migration(1, "uno") { exec("CREATE TABLE a(x INTEGER)") },
                Migration(2, "rota") { exec("CREATE TABLE b(x INTEGER)"); exec("ESTO NO ES SQL") }
            )
            try {
                Migrations.run(c, steps)
                fail("should throw")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("2"))
            }
            assertEquals(1, Migrations.currentVersion(c))
            assertFalse("step 2 was rolled back", "b" in tables(c))
            assertTrue("a" in tables(c))
        }
    }

    @Test
    fun refusesADatabaseFromANewerVersion() {
        val file = tempFile()
        Database(file)
        open(file).use { it.exec("INSERT INTO schema_version VALUES(${Migrations.LATEST + 1}, 'del futuro', 0)") }
        try {
            Database(file)
            fail("should refuse")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("más nueva"))
        }
    }

    @Test
    fun versionsMustBeContiguous() {
        open(tempFile()).use { c ->
            try {
                Migrations.run(c, listOf(Migration(2, "sin la 1") {}))
                fail("should refuse a gap")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** F1.6: name keys changed (seasons, «×», anime remakes); only the provisional groups that change are redone. */
    @Test
    fun workKeysByNameAreRedoneOnlyWhereTheRulesChanged() {
        open(tempFile()).use { c ->
            Migrations.run(c, Migrations.ALL.take(21))
            fun add(id: String, type: String, title: String, key: String) = c.prepareStatement(
                "INSERT INTO index_items(source_id, media_id, lang, type, title, work_key, updated_at, seen_at) VALUES('src', ?, 'es', ?, ?, ?, 1, 1)"
            ).use { it.setString(1, id); it.setString(2, type); it.setString(3, title); it.setString(4, key); it.executeUpdate() }
            add("season", "ANIME", "Ao Ashi 2nd Season", "t:ANIME|ao ashi 2nd season")
            add("remake", "ANIME", "Ranma1/2 (2024)", "t:ANIME|ranma1 2")
            add("manhwa", "MANHWA", "Regicide (2026)", "t:images|regicide")
            add("plain", "ANIME", "Frieren", "t:ANIME|frieren")
            add("sure", "ANIME", "Ao Ashi 2nd Season", "al:12345")
            Migrations.run(c)
            val keys = c.createStatement().use { s ->
                s.executeQuery("SELECT media_id, work_key FROM index_items").use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getString(2)) } }
            }
            assertEquals(null, keys["season"])
            assertEquals(null, keys["remake"])
            assertEquals("a manhwa with a site's year keeps its key", "t:images|regicide", keys["manhwa"])
            assertEquals("t:ANIME|frieren", keys["plain"])
            assertEquals("confirmed works are never touched", "al:12345", keys["sure"])
        }
    }

    private fun tables(c: Connection) = c.createStatement().use { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
    }
}
