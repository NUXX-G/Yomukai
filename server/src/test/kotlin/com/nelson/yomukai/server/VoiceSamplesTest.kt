package com.nelson.yomukai.server

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class VoiceSamplesTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("ykvoice").toString(), "BACKUPS_ENABLED" to "false")))
    private val admin = runBlocking { s.db.createUser("ana", "h", admin = true, guest = false, expiresAt = null) }
    private val leo = runBlocking { s.db.createUser("leo", "h", admin = false, guest = false, expiresAt = null) }

    /** A silent 16-bit mono PCM WAV of [seconds] at 16 kHz. */
    private fun wav(seconds: Double): ByteArray {
        val rate = 16_000
        val data = (seconds * rate).toInt() * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
        }.array()
    }

    @Test
    fun wavLength() {
        assertEquals(5.0, VoiceSamples.wavSeconds(wav(5.0))!!, 0.001)
        assertNull(VoiceSamples.wavSeconds("not a wav at all, just text...............".toByteArray()))
    }

    @Test
    fun onlyAdminsWithALicenceAndShortSamples() = runBlocking {
        fun fails(block: suspend () -> Unit) = assertTrue(runCatching { runBlocking { block() } }.isFailure)
        fails { s.voiceSamples.add(leo, wav(5.0), "Narradora", "es", "f", "CC0", "") }
        fails { s.voiceSamples.add(admin, wav(5.0), "Narradora", "es", "f", "", "") }
        fails { s.voiceSamples.add(admin, wav(45.0), "Narradora", "es", "f", "CC0", "") }
        fails { s.voiceSamples.add(admin, wav(5.0), "Narradora", "es", "x", "CC0", "") }
        val id = s.voiceSamples.add(admin, wav(6.0), "Narradora", "es", "f", "Grabada por nosotros", "")
        val listed = s.voiceSamples.list().single()
        assertEquals(listOf("Narradora", "es", "f", "Grabada por nosotros"), listOf(listed.name, listed.lang, listed.gender, listed.license))
        assertTrue(s.voiceSamples.file(id)!!.length() > 0)
        s.voiceSamples.delete(admin, id)
        assertTrue(s.voiceSamples.list().isEmpty())
        assertNull(s.voiceSamples.file(id))
    }
}
