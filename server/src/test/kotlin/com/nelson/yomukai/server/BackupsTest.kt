package com.nelson.yomukai.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class BackupsTest {
    private val utc = ZoneOffset.UTC
    private fun tempDir(): File = Files.createTempDirectory("ykbak").toFile()
    private fun at(day: Int) = Instant.parse("2026-11-%02dT04:00:00Z".format(day))

    @Test
    fun namesRoundTrip() {
        val name = Backups.nameFor(at(5), utc)
        assertEquals("yomukai-20261105-040000.db.gz", name)
        assertEquals(at(5), Backups.timeOf(name, utc))
        assertNull(Backups.timeOf("otra-cosa.db.gz", utc))
    }

    @Test
    fun keepsFourteenDaysAndAlwaysTheNewest() {
        val names = (1..20).map { Backups.nameFor(at(it), utc) } + "notas.txt"
        val gone = Backups.expired(names, at(20), 14, utc)
        assertEquals((1..5).map { Backups.nameFor(at(it), utc) }.toSet(), gone.toSet())
        // Even when every copy is old, the newest one stays.
        val old = listOf(Backups.nameFor(at(1), utc), Backups.nameFor(at(2), utc))
        assertEquals(listOf(Backups.nameFor(at(1), utc)), Backups.expired(old, at(30), 14, utc))
    }

    @Test
    fun nextRunIsTodayOrTomorrow() {
        val morning = LocalDateTime.of(2026, 11, 5, 3, 0)
        assertEquals(LocalDateTime.of(2026, 11, 5, 4, 0), Backups.nextRun(morning, 4))
        assertEquals(LocalDateTime.of(2026, 11, 6, 4, 0), Backups.nextRun(LocalDateTime.of(2026, 11, 5, 4, 0), 4))
    }

    @Test
    fun backupAndRestoreRoundTrip() = runBlocking {
        val data = tempDir()
        val dbFile = File(data, "yomukai.db")
        val db = Database(dbFile)
        val user = db.createUser("ana", "h", admin = false, guest = false, expiresAt = null)
        db.putLibrary(user.id, LibraryRow("s", "m", "Antes", "", "MANGA", "", "", "", 1, "NONE"))
        val remote = File(data, "nas").apply { mkdirs() }
        val backups = Backups(dbFile, File(data, "backups"), 14, remote, utc)
        val file = backups.backup(db)
        assertTrue(file.name.endsWith(".db.gz"))
        assertTrue("copied to the NAS folder", File(remote, file.name).isFile)

        // Changes after the copy are undone by restoring it.
        db.putLibrary(user.id, LibraryRow("s", "m2", "Después", "", "MANGA", "", "", "", 2, "NONE"))
        db.close()
        val previous = Backups.restore(file, dbFile)
        assertNotNull(previous)
        assertTrue(previous!!.isFile)
        val restored = Database(dbFile)
        assertEquals(listOf("Antes"), restored.library(user.id).map { it.title })
    }

    @Test
    fun remoteCopyCatchesUpWhenTheFolderComesBack() = runBlocking {
        val data = tempDir()
        val dbFile = File(data, "yomukai.db")
        val db = Database(dbFile)
        val remote = File(data, "nas-apagado")
        val backups = Backups(dbFile, File(data, "backups"), 14, remote, utc)
        val first = backups.backup(db, at(3))
        assertFalse(remote.exists())
        remote.mkdirs()
        backups.copyToRemote(at(3))
        assertTrue(File(remote, first.name).isFile)
    }

    @Test
    fun refusesDamagedOrForeignBackups() {
        val data = tempDir()
        val dbFile = File(data, "yomukai.db")
        Database(dbFile).close()
        val junk = File(data, "basura.db").apply { writeText("esto no es SQLite") }
        try {
            Backups.restore(junk, dbFile)
            fail("should refuse")
        } catch (_: Exception) {
        }
        assertTrue("current database untouched", dbFile.isFile)
        assertFalse(File(data, "yomukai.db.restaurando").exists())
    }

    @Test
    fun lockKeepsRestoreAwayFromARunningServer() {
        val data = tempDir()
        val held = ServerLock.tryAcquire(data)
        assertNotNull(held)
        assertNull(ServerLock.tryAcquire(data))
        held!!.close()
        ServerLock.tryAcquire(data)!!.close()
    }
}
