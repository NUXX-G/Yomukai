package com.nelson.yomukai.server

import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.ZoneOffset

class NightlyVoicesTest {
    private val s = Services(Config.fromEnv(mapOf("DATA_DIR" to Files.createTempDirectory("yknight").toString(), "BACKUPS_ENABLED" to "false")))
    private fun at(hour: Int) = LocalDateTime.of(2026, 11, 8, hour, 30).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun piecesGoToTheVoicesTheReaderUses() {
        val roles = JsonObject().apply { addProperty("narrator", "w:pocket-alba"); addProperty("female", "w:clone:3"); addProperty("male", "es-sergio") }
        val text = "La noche era fría.\n\n—No pienso volver —dijo ella—. Nunca más.\n\n—Quédate —pidió el rey."
        val plan = NightlyVoices(s).plan(text, roles, characters = true)
        assertEquals(listOf("La noche era fría.", "dijo ella", "pidió el rey."), plan["w:pocket-alba"])
        assertEquals(listOf("No pienso volver", ". Nunca más."), plan["w:clone:3"])
        // The man's lines use a light VPS voice: nothing for the home worker.
        assertEquals(setOf("w:pocket-alba", "w:clone:3"), plan.keys)
    }

    @Test
    fun onlyOnceAndOnlyAtNight() = runBlocking {
        var now = at(14)
        val nightly = NightlyVoices(s, ZoneOffset.UTC) { now }
        assertEquals(0, nightly.tick())
        now = at(4)
        assertEquals(0, nightly.tick()) // nobody has worker voices: nothing queued, but the night counts as done
    }
}
