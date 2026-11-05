package com.nelson.yomukai.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset

class BandwidthTest {
    private val db = Database(File(Files.createTempDirectory("ykbw").toFile(), "t.db"))
    private var now = Instant.parse("2026-11-20T10:00:00Z").toEpochMilli()

    @Test
    fun levels() {
        assertEquals(Bandwidth.Level.OK, Bandwidth.levelFor(10, 0))
        assertEquals(Bandwidth.Level.OK, Bandwidth.levelFor(79, 100))
        assertEquals(Bandwidth.Level.WARNING, Bandwidth.levelFor(80, 100))
        assertEquals(Bandwidth.Level.OVER, Bandwidth.levelFor(100, 100))
    }

    @Test
    fun countsPerMonthAndBlocksOnlyWhenEnforced() = runBlocking {
        val meter = Bandwidth(db, capBytes = 1_000, notifier = Notifier(null, null), zone = ZoneOffset.UTC) { now }
        meter.record(500)
        meter.flush()
        assertEquals(500L, meter.used())
        meter.record(600)
        assertTrue(meter.blocked(enforce = true))
        assertFalse(meter.blocked(enforce = false))
        meter.flush()
        // A new month starts from zero; the old one stays recorded.
        now = Instant.parse("2026-12-01T00:00:01Z").toEpochMilli()
        assertEquals(0L, meter.used())
        assertFalse(meter.blocked(enforce = true))
        val nov = db.tx { queryOne("SELECT bytes FROM bandwidth WHERE month = '2026-11'") { getLong(1) } }
        assertEquals(1_100L, nov)
    }

    @Test
    fun notifierDoesNotRepeatItself() {
        val n = Notifier(null, null, quietMs = 1_000)
        assertTrue(n.shouldSend("x", 0))
        assertFalse(n.shouldSend("x", 500))
        assertTrue(n.shouldSend("y", 500))
        assertTrue(n.shouldSend("x", 1_500))
        assertEquals("Trafico al 80 %", Notifier.asciiHeader("Tráfico al 80 %"))
    }
}
